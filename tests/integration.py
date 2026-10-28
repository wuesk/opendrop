"""Real TCP/UDP interoperability checks. Run outside network-restricted sandboxes."""
import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import unittest

BINARY = str(Path(sys.argv.pop(1)).resolve())


def frame(obj):
    raw = json.dumps(obj, ensure_ascii=False).encode()
    return struct.pack('!I', len(raw)) + raw


def read_exact(sock, size):
    result = b''
    while len(result) < size:
        data = sock.recv(size - len(result))
        if not data:
            raise EOFError('Connection closed')
        result += data
    return result


def read_frame(sock):
    size = struct.unpack('!I', read_exact(sock, 4))[0]
    if not 0 < size <= 262144:
        raise ValueError('Invalid header length')
    return json.loads(read_exact(sock, size))


def offer(files):
    return {'protocol': 'opendrop', 'version': 1, 'type': 'offer',
            'sender': 'Python reference peer', 'files': files}


class Process:
    def __init__(self, directory, *args):
        env = dict(os.environ, XDG_CONFIG_HOME=str(Path(directory) / 'config'))
        self.process = subprocess.Popen(
            [BINARY, '--headless', '--port', '0', '--receive-dir', str(directory),
             '--id', 'integration-peer', *args], stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding='utf-8', env=env)
        self.events = queue.Queue()

        def pump():
            for line in self.process.stdout:
                self.events.put(json.loads(line))
        threading.Thread(target=pump, daemon=True).start()
        self.ready = self.wait('ready')

    def wait(self, event, timeout=15):
        while True:
            try:
                value = self.events.get(timeout=timeout)
            except queue.Empty:
                raise AssertionError(f'No {event} event; exit={self.process.poll()}')
            if value['event'] == event:
                return value

    def connect(self):
        return socket.create_connection(('127.0.0.1', self.ready['port']), timeout=5)

    def close(self):
        self.process.terminate()
        try:
            self.process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.communicate()


class Integration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
        except PermissionError as error:
            raise unittest.SkipTest(f'TCP/UDP blocked by sandbox: {error}')

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='opendrop-test-')
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def receiver(self, *args, discovery=False):
        process = Process(self.directory, *([] if discovery else ['--no-discovery']), *args)
        self.addCleanup(process.close)
        return process

    def test_reference_peer_sends_fragmented_batch(self):
        receiver = self.receiver('--auto-accept')
        files = [('żółw 🐢.bin', bytes(range(256)) * 8192), ('empty.txt', b''), ('żółw 🐢.bin', b'duplicate')]
        with receiver.connect() as sock:
            raw = frame(offer([{'name': name, 'size': len(data)} for name, data in files]))
            for byte in raw:
                sock.sendall(bytes([byte]))
            self.assertTrue(read_frame(sock)['ok'])
            for name, data in files:
                sock.sendall(data + hashlib.sha256(data).digest())
                self.assertTrue(read_frame(sock)['ok'])
        self.assertTrue(receiver.wait('finished')['ok'])
        self.assertEqual((self.directory / files[0][0]).read_bytes(), files[0][1])
        self.assertEqual((self.directory / 'empty.txt').read_bytes(), b'')
        self.assertEqual((self.directory / 'żółw 🐢 (1).bin').read_bytes(), b'duplicate')

    def test_desktop_sender_to_reference_receiver(self):
        source = self.directory / 'source.bin'
        data = bytes(range(256)) * 8192
        source.write_bytes(data)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            listener.settimeout(10)
            sender = Process(self.directory, '--no-discovery', '--quit-after-transfer',
                             '--send', f'127.0.0.1:{listener.getsockname()[1]}', str(source))
            self.addCleanup(sender.close)
            with listener.accept()[0] as sock:
                sock.settimeout(10)
                metadata = read_frame(sock)
                self.assertEqual(metadata['files'], [{'name': 'source.bin', 'size': len(data)}])
                sock.sendall(frame({'ok': True}))
                self.assertEqual(read_exact(sock, len(data)), data)
                self.assertEqual(read_exact(sock, 32), hashlib.sha256(data).digest())
                sock.sendall(frame({'ok': True}))
            self.assertTrue(sender.wait('finished')['ok'])

    def test_desktop_to_desktop(self):
        receiver = self.receiver('--auto-accept')
        source = self.directory / 'original.dat'
        source.write_bytes(os.urandom(1024 * 1024))
        sender = Process(self.directory / 'sender', '--no-discovery', '--quit-after-transfer',
                         '--send', f"127.0.0.1:{receiver.ready['port']}", str(source))
        self.addCleanup(sender.close)
        self.assertTrue(sender.wait('finished')['ok'])
        self.assertTrue(receiver.wait('finished')['ok'])
        self.assertEqual(source.read_bytes(), (self.directory / 'original (1).dat').read_bytes())

    def test_decline(self):
        receiver = self.receiver('--reject')
        with receiver.connect() as sock:
            sock.sendall(frame(offer([{'name': 'nope.txt', 'size': 5}])))
            self.assertFalse(read_frame(sock)['ok'])
        self.assertFalse((self.directory / 'nope.txt').exists())

    def test_invalid_metadata(self):
        receiver = self.receiver('--auto-accept')
        for name, size in [('../escape', 1), ('a/b', 1), ('x', -1), ('x', 0.5), ('x', '2'), ('x', 2**50)]:
            with self.subTest(name=name, size=size), receiver.connect() as sock:
                sock.sendall(frame(offer([{'name': name, 'size': size}])))
                self.assertFalse(read_frame(sock)['ok'])
            self.assertFalse(receiver.wait('finished')['ok'])

    def test_checksum_failure_and_disconnect(self):
        receiver = self.receiver('--auto-accept')
        with receiver.connect() as sock:
            sock.sendall(frame(offer([{'name': 'corrupt', 'size': 3}])))
            self.assertTrue(read_frame(sock)['ok'])
            sock.sendall(b'abc' + b'\0' * 32)
            self.assertFalse(read_frame(sock)['ok'])
        self.assertFalse(receiver.wait('finished')['ok'])
        self.assertFalse((self.directory / 'corrupt').exists())
        with receiver.connect() as sock:
            sock.sendall(frame(offer([{'name': 'partial', 'size': 100}])))
            self.assertTrue(read_frame(sock)['ok'])
            sock.sendall(b'part')
        self.assertFalse(receiver.wait('finished')['ok'])
        self.assertFalse((self.directory / 'partial').exists())

    def test_discovery(self):
        receiver = self.receiver('--auto-accept', discovery=True)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            hello = {'protocol': 'opendrop', 'version': 1, 'type': 'hello',
                     'id': 'reference-phone', 'name': 'Test Android', 'kind': 'android', 'port': 45455}
            udp.sendto(json.dumps(hello).encode(), ('127.0.0.1', 45454))
            for _ in range(20):
                peers = receiver.wait('peers')['devices']
                target = next((peer for peer in peers if peer['id'] == 'reference-phone'), None)
                if target is not None:
                    self.assertEqual(target['address'], '127.0.0.1')
                    break
            else:
                self.fail('Reference phone was not discovered')


if __name__ == '__main__':
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Integration))
    if result.testsRun == 0 and result.skipped:
        sys.exit(77)
    sys.exit(0 if result.wasSuccessful() else 1)
