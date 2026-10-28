package dev.opendrop;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class NetworkEngine implements AutoCloseable {
    interface Listener {
        void peersChanged(List<Peer> peers);
        void offer(String sender, List<Wire.FileInfo> files, CompletableFuture<Boolean> decision);
        void progress(String message, long done, long total);
        void finished(String message, boolean success);
        void ready(String name, int port, String addresses);
        void warning(String message);
        default void stopped() { }
    }
    static final class Peer {
        final String id, name, kind, address;
        final int port;
        final long seen;
        Peer(String id, String name, String kind, String address, int port) {
            this.id = id; this.name = name; this.kind = kind;
            this.address = address; this.port = port; seen = android.os.SystemClock.elapsedRealtime();
        }
    }
    private static final class Source {
        final Uri uri;
        final String name;
        final long size;
        final File temporary;
        Source(Uri uri, String name, long size, File temporary) {
            this.uri = uri; this.name = name; this.size = size; this.temporary = temporary;
        }
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService schedule = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Map<Socket, Long> deadlines = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Boolean>> decisions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean receiving = new AtomicBoolean(), sending = new AtomicBoolean();
    private volatile boolean closed;
    private volatile ServerSocket server;
    private volatile MulticastSocket discovery;
    private WifiManager.MulticastLock multicastLock;
    final String name;
    private final String id;

    NetworkEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        String model = Build.MODEL.trim();
        name = model.isEmpty() ? "Android phone" : model.substring(0, Math.min(80, model.length()));
        android.content.SharedPreferences prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE);
        id = prefs.getString("id", UUID.randomUUID().toString());
        prefs.edit().putString("id", id).apply();
    }

    private void post(Runnable task) { if (!closed) main.post(() -> { if (!closed) task.run(); }); }

    void start() {
        // Java SO_TIMEOUT only covers reads. Closing idle sockets also bounds blocked writes.
        schedule.scheduleWithFixedDelay(() -> {
            long now = android.os.SystemClock.elapsedRealtime();
            deadlines.forEach((socket, deadline) -> {
                if (now > deadline) try { socket.close(); } catch (IOException ignored) { }
            });
        }, 1, 1, TimeUnit.SECONDS);
        io.execute(() -> {
            try {
                ServerSocket listenerSocket = new ServerSocket();
                server = listenerSocket;
                listenerSocket.setReuseAddress(true);
                try { listenerSocket.bind(new InetSocketAddress(Wire.TRANSFER_PORT)); }
                catch (IOException e) { listenerSocket.bind(new InetSocketAddress(0)); }
                if (closed) { listenerSocket.close(); return; }
                String addresses = localAddresses();
                post(() -> listener.ready(name, listenerSocket.getLocalPort(), addresses));
                startDiscovery();
                while (!closed) {
                    Socket socket = listenerSocket.accept();
                    socket.setSoTimeout(30000);
                    if (!receiving.compareAndSet(false, true)) {
                        try (Socket busy = socket) {
                            Wire.write(new DataOutputStream(busy.getOutputStream()),
                                    Wire.object("ok", false, "error", "Device is busy"));
                        }
                        continue;
                    }
                    connections.add(socket);
                    touch(socket, 30000);
                    io.execute(() -> receive(socket));
                }
            } catch (Exception e) {
                if (!closed) post(() -> listener.warning("Cannot receive files: " + message(e)));
            }
        });
    }

    private void startDiscovery() {
        if (closed) return;
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("opendrop-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                if (closed) { multicastLock.release(); return; }
            }
            MulticastSocket socket = new MulticastSocket(null);
            discovery = socket;
            if (closed) { socket.close(); return; }
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(Wire.DISCOVERY_PORT));
            socket.setBroadcast(true);
            socket.setTimeToLive(1);
            socket.setSoTimeout(1000);
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback() || !iface.supportsMulticast()) continue;
                try { socket.joinGroup(new InetSocketAddress(Wire.GROUP, Wire.DISCOVERY_PORT), iface); }
                catch (IOException ignored) { /* Broadcast is also available. */ }
            }
            schedule.scheduleWithFixedDelay(this::announce, 0, 3, TimeUnit.SECONDS);
            io.execute(() -> {
                byte[] buffer = new byte[8192];
                while (!closed) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        JSONObject o = new JSONObject(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
                        String peerId = o.optString("id"), peerName = o.optString("name"), kind = o.optString("kind");
                        int port = o.optInt("port");
                        if (!"opendrop".equals(o.optString("protocol")) || o.optInt("version") != 1 ||
                                !"hello".equals(o.optString("type")) || peerId.isEmpty() || peerId.equals(id) ||
                                peerId.length() > 64 || peerName.isEmpty() || peerName.length() > 80 ||
                                !(kind.equals("desktop") || kind.equals("android")) || port < 1 || port > 65535 ||
                                !(packet.getAddress() instanceof Inet4Address)) continue;
                        if (!peers.containsKey(peerId) && peers.size() >= 128) continue;
                        Peer previous = peers.put(peerId, new Peer(peerId, peerName, kind, packet.getAddress().getHostAddress(), port));
                        if (previous == null || !previous.name.equals(peerName) || !previous.kind.equals(kind) ||
                                !previous.address.equals(packet.getAddress().getHostAddress()) || previous.port != port)
                            publishPeers();
                    } catch (SocketTimeoutException ignored) {
                    } catch (Exception ignored) {
                        if (socket.isClosed()) break;
                    }
                }
            });
        } catch (Exception e) {
            post(() -> listener.warning("Discovery unavailable. Use Connect by address. " + message(e)));
        }
    }

    private void announce() {
        if (closed || discovery == null || discovery.isClosed()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean removed = peers.values().removeIf(p -> now - p.seen > 12000);
        if (removed) publishPeers();
        try {
            byte[] bytes = Wire.object("protocol", "opendrop", "version", 1, "type", "hello", "id", id,
                    "name", name, "kind", "android", "port", server.getLocalPort()).toString().getBytes(StandardCharsets.UTF_8);
            sendAnnouncement(bytes, InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InterfaceAddress address : iface.getInterfaceAddresses())
                    if (address.getBroadcast() != null) sendAnnouncement(bytes, address.getBroadcast());
                if (iface.supportsMulticast()) {
                    try {
                        discovery.setNetworkInterface(iface);
                        sendAnnouncement(bytes, InetAddress.getByName(Wire.GROUP));
                    } catch (IOException ignored) { }
                }
            }
        } catch (Exception ignored) { /* Retry on next heartbeat after network changes. */ }
    }

    private void sendAnnouncement(byte[] bytes, InetAddress address) {
        try { discovery.send(new DatagramPacket(bytes, bytes.length, address, Wire.DISCOVERY_PORT)); }
        catch (IOException ignored) { }
    }

    private void publishPeers() {
        List<Peer> devices = new ArrayList<>(peers.values());
        devices.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
        post(() -> listener.peersChanged(devices));
    }

    private void receive(Socket socket) {
        Uri pending = null;
        CompletableFuture<Boolean> decision = null;
        try (Socket connection = socket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(connection.getInputStream(), Wire.CHUNK));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream(), Wire.CHUNK))) {
            try {
                JSONObject offer = Wire.read(input);
                List<Wire.FileInfo> files = Wire.parseOffer(offer);
                long total = 0;
                for (Wire.FileInfo f : files) total += f.size;
                decision = new CompletableFuture<>();
                touch(socket, 65000);
                decisions.add(decision);
                CompletableFuture<Boolean> answer = decision;
                post(() -> listener.offer(offer.optString("sender"), files, answer));
                boolean accepted;
                try { accepted = decision.get(60, TimeUnit.SECONDS); }
                finally { decision.complete(false); decisions.remove(decision); }
                if (!accepted) throw new IOException("Transfer declined");
                touch(socket, 30000);
                if (new android.os.StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes() < total)
                    throw new IOException("Not enough free space");
                Wire.write(output, Wire.object("ok", true));
                byte[] buffer = new byte[Wire.CHUNK];
                long done = 0, lastUpdate = 0;
                for (Wire.FileInfo file : files) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, file.name);
                    String extension = MimeTypeMap.getFileExtensionFromUrl(Uri.encode(file.name)).toLowerCase(Locale.ROOT);
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    values.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Open Drop");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    pending = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (pending == null) throw new IOException("Could not create destination file");
                    MessageDigest hash = MessageDigest.getInstance("SHA-256");
                    try (OutputStream destination = context.getContentResolver().openOutputStream(pending, "w")) {
                        if (destination == null) throw new IOException("Could not open destination file");
                        long left = file.size;
                        while (left > 0) {
                            int n = input.read(buffer, 0, (int) Math.min(buffer.length, left));
                            if (n < 0) throw new IOException("Sender disconnected; incomplete file discarded");
                            destination.write(buffer, 0, n);
                            touch(socket, 30000);
                            hash.update(buffer, 0, n);
                            left -= n;
                            done += n;
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (now - lastUpdate > 80 || left == 0) {
                                reportProgress("Receiving " + file.name, done, total);
                                lastUpdate = now;
                            }
                        }
                        byte[] expected = new byte[32];
                        input.readFully(expected);
                        if (!MessageDigest.isEqual(expected, hash.digest()))
                            throw new IOException("Checksum mismatch; incomplete file discarded");
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    if (context.getContentResolver().update(pending, values, null, null) != 1)
                        throw new IOException("Could not publish downloaded file");
                    pending = null;
                    Wire.write(output, Wire.object("ok", true));
                }
                post(() -> listener.finished("Received " + files.size() + " file(s) in Downloads/Open Drop", true));
            } catch (Exception e) {
                try { Wire.write(output, Wire.object("ok", false, "error", message(e))); }
                catch (Exception ignored) { }
                post(() -> listener.finished(message(e), false));
            }
        } catch (Exception e) { post(() -> listener.finished(message(e), false)); }
        finally {
            if (pending != null) {
                try { context.getContentResolver().delete(pending, null, null); }
                catch (Exception ignored) { }
            }
            if (decision != null) { decision.complete(false); decisions.remove(decision); }
            connections.remove(socket);
            deadlines.remove(socket);
            receiving.set(false);
        }
    }

    void send(Peer peer, List<Uri> uris) {
        if (!sending.compareAndSet(false, true)) {
            post(() -> listener.warning("Another outgoing transfer is running")); return;
        }
        io.execute(() -> {
            List<Source> sources = new ArrayList<>();
            Socket socket = new Socket();
            connections.add(socket);
            try {
                if (uris.isEmpty() || uris.size() > Wire.MAX_FILES) throw new IOException("Choose between 1 and 500 files");
                reportProgress("Preparing files…", 0, 0);
                long total = 0;
                JSONArray files = new JSONArray();
                for (Uri uri : uris) {
                    Source source = prepare(uri);
                    sources.add(source);
                    if (source.size > Wire.MAX_BYTES - total) throw new IOException("Transfer is too large");
                    total += source.size;
                    files.put(Wire.object("name", source.name, "size", source.size));
                }
                try (Socket connection = socket) {
                    touch(socket, 10000);
                    connection.connect(new InetSocketAddress(peer.address, peer.port), 10000);
                    touch(socket, 65000);
                    connection.setSoTimeout(65000);
                    DataInputStream input = new DataInputStream(new BufferedInputStream(connection.getInputStream(), Wire.CHUNK));
                    DataOutputStream output = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream(), Wire.CHUNK));
                    Wire.write(output, Wire.object("protocol", "opendrop", "version", 1, "type", "offer", "sender", name, "files", files));
                    reportProgress("Waiting for " + peer.name + " to accept…", 0, total);
                    Wire.requireOk(Wire.read(input));
                    touch(socket, 30000);
                    connection.setSoTimeout(30000);
                    byte[] buffer = new byte[Wire.CHUNK];
                    long done = 0, lastUpdate = 0;
                    for (Source source : sources) {
                        MessageDigest hash = MessageDigest.getInstance("SHA-256");
                        try (InputStream file = context.getContentResolver().openInputStream(source.uri)) {
                            if (file == null) throw new IOException("Could not read " + source.name);
                            long left = source.size;
                            while (left > 0) {
                                if (closed) throw new IOException("Transfer cancelled");
                                int n = file.read(buffer, 0, (int) Math.min(left, buffer.length));
                                if (n < 0) throw new IOException("Source file changed or could not be read");
                                output.write(buffer, 0, n);
                                touch(socket, 30000);
                                hash.update(buffer, 0, n);
                                left -= n;
                                done += n;
                                long now = android.os.SystemClock.elapsedRealtime();
                                if (now - lastUpdate > 80 || left == 0) {
                                    reportProgress("Sending " + source.name, done, total);
                                    lastUpdate = now;
                                }
                            }
                        }
                        output.write(hash.digest());
                        output.flush();
                        Wire.requireOk(Wire.read(input));
                    }
                }
                post(() -> listener.finished("Sent " + sources.size() + " file(s) to " + peer.name, true));
            } catch (Exception e) { post(() -> listener.finished(message(e), false)); }
            finally {
                try { socket.close(); } catch (IOException ignored) { }
                connections.remove(socket);
                deadlines.remove(socket);
                for (Source source : sources) if (source.temporary != null) source.temporary.delete();
                sending.set(false);
            }
        });
    }

    private Source prepare(Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = null;
        long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null || !Wire.validName(name)) throw new IOException("Unsupported filename");
        if (size > Wire.MAX_BYTES) throw new IOException("File is too large");
        if (size >= 0) return new Source(uri, name, size, null);
        File temporary = File.createTempFile("opendrop-", ".part", context.getCacheDir());
        try (InputStream input = resolver.openInputStream(uri); OutputStream output = new FileOutputStream(temporary)) {
            if (input == null) throw new IOException("Could not read file");
            byte[] buffer = new byte[Wire.CHUNK];
            size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (closed) throw new IOException("Transfer cancelled");
                size += count;
                if (size > Wire.MAX_BYTES) throw new IOException("File is too large");
                output.write(buffer, 0, count);
            }
        } catch (Exception e) {
            temporary.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(message(e), e);
        }
        return new Source(Uri.fromFile(temporary), name, size, temporary);
    }

    private void reportProgress(String message, long done, long total) {
        post(() -> listener.progress(message, done, total));
    }

    private void touch(Socket socket, long milliseconds) {
        deadlines.put(socket, android.os.SystemClock.elapsedRealtime() + milliseconds);
    }

    private String localAddresses() throws IOException {
        List<String> addresses = new ArrayList<>();
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!iface.isUp() || iface.isLoopback()) continue;
            for (InetAddress address : Collections.list(iface.getInetAddresses()))
                if (address instanceof Inet4Address) addresses.add(address.getHostAddress());
        }
        return String.join(" · ", addresses);
    }

    static String message(Exception e) {
        if (e instanceof java.util.concurrent.TimeoutException) return "Transfer timed out";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override public void close() {
        closed = true;
        for (CompletableFuture<Boolean> decision : decisions) decision.complete(false);
        for (Socket socket : connections) try { socket.close(); } catch (IOException ignored) { }
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        if (discovery != null) discovery.close();
        schedule.shutdownNow();
        io.shutdownNow();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        main.removeCallbacksAndMessages(null);
    }
}
