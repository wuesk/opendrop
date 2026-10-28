# Open Drop protocol v1

Both applications implement this protocol directly. No server or internet connection is used at runtime.

## Discovery

Every three seconds, send a UTF-8 JSON datagram to UDP port **45454**, using IPv4 interface broadcasts and multicast group **239.255.77.77** (TTL 1). Announcements are at most 8 KiB. Devices expire after 12 seconds without a heartbeat.

```json
{"protocol":"opendrop","version":1,"type":"hello","id":"persistent-uuid","name":"My phone","kind":"android","port":45455}
```

`kind` is `android` or `desktop`. Use the datagram's source IPv4 address, never an advertised address. Ignore your own ID, unsupported versions, invalid ports, and malformed announcements. Device IDs are at most 64 characters; names are 1–80 characters. Retain at most 128 peers.

## Transfer

TCP normally listens on **45455**; if occupied, choose a free port and advertise it. All JSON messages use a four-byte **unsigned big-endian byte length**, followed by UTF-8 JSON. Length must be 1–262144 bytes.

1. Sender connects and sends an offer:

   ```json
   {"protocol":"opendrop","version":1,"type":"offer","sender":"My computer","files":[{"name":"photo.jpg","size":12345},{"name":"empty.txt","size":0}]}
   ```

2. Receiver validates metadata and asks the user once for the batch. Reply with `{"ok":true}` to accept or `{"ok":false,"error":"Transfer declined"}` to reject. Do not send file contents before acceptance.
3. For each file, sender streams **exactly `size` bytes**, then **32 raw bytes of SHA-256** over that file's contents. Empty files still have a digest. No separators or file headers appear between these bytes.
4. Receiver verifies the hash, commits the file, and replies with a framed `{"ok":true}`. Sender waits for this acknowledgement before starting the next file. Failure replies contain `ok:false` and an error; close the connection and stop the batch.
5. After the last acknowledgement, close the connection.

Allow 60 seconds for receive confirmation and 30 seconds of transfer inactivity. Senders wait 65 seconds for the initial decision. One incoming and one outgoing batch may run on a device simultaneously. Files stream in 64 KiB chunks. Maximum: 500 files and 1 TiB per batch; sizes must be nonnegative JSON integers. Filenames must be basenames, 1–240 UTF-8 bytes, with no slashes, backslashes, control characters, `.` or `..`.

Never overwrite an existing download intentionally. Use a numbered filename on desktop and MediaStore's unique download destination on Android. Discard an incomplete or corrupt current file; already completed files in a failed batch remain saved.

## Scope

This first version uses plaintext TCP and unauthenticated discovery with explicit receive confirmation. Use it on a trusted local network. SHA-256 detects incomplete or corrupt content; it does not authenticate a sender. Encryption, pairing, resumable transfers, folders, and IPv6 are outside v1.

Android 0.2.0 runs discovery and transfers in a `connectedDevice` foreground service. Switching apps does not close the transfer engine. The service's ongoing notification has a Stop action, which closes listeners, rejects pending offers, and cancels transfers. A background offer uses Receive/Decline notification actions within the same 60-second decision window; returning to the screen restores any pending confirmation. This changes the Android lifecycle only; the wire protocol remains v1 and is compatible with the existing desktop app.
