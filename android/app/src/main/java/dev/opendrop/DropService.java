package dev.opendrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Owns networking independently of any visible Activity. All listener callbacks run on the main thread. */
public class DropService extends Service implements NetworkEngine.Listener {
    static final String ACTION_START = "dev.opendrop.START";
    static final String ACTION_STOP = "dev.opendrop.STOP";
    static final String ACTION_SEND = "dev.opendrop.SEND_FILES";
    static final String ACTION_ACCEPT = "dev.opendrop.ACCEPT";
    static final String ACTION_DECLINE = "dev.opendrop.DECLINE";
    static final String EXTRA_OFFER = "offer";
    static final int RUNNING_NOTIFICATION = 1;
    static final int OFFER_NOTIFICATION = 2;
    private static final String RUNNING_CHANNEL = "local-network";
    private static final String OFFER_CHANNEL = "incoming-files";
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<NetworkEngine.Listener> listeners = new ArrayList<>();
    private NotificationManager notifications;
    private NetworkEngine engine;
    private PowerManager.WakeLock wakeLock;
    private final Runnable renewWakeLock = new Runnable() {
        @Override public void run() {
            if (engine == null) return;
            // Discovery needs CPU time with the screen off. Bound each lease and release it on Stop.
            wakeLock.acquire(15 * 60 * 1000L);
            main.postDelayed(this, 10 * 60 * 1000L);
        }
    };
    private List<NetworkEngine.Peer> peers = new ArrayList<>();
    private String deviceName = "", addresses = "", message = "Visible to devices on your local network";
    private int port;
    private boolean transferring;
    private long done, total, lastNotification;
    private PendingOffer pending;

    private static final class PendingOffer {
        final String token = UUID.randomUUID().toString();
        final String sender;
        final List<Wire.FileInfo> files;
        final CompletableFuture<Boolean> decision;
        PendingOffer(String sender, List<Wire.FileInfo> files, CompletableFuture<Boolean> decision) {
            this.sender = sender;
            this.files = new ArrayList<>(files);
            this.decision = decision;
        }
    }

    public final class LocalBinder extends Binder {
        DropService service() { return DropService.this; }
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences("background", MODE_PRIVATE).getBoolean("enabled", true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("background", MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply();
    }

    static void sendFiles(Context context, NetworkEngine.Peer peer, List<Uri> uris) {
        if (uris.isEmpty()) return;
        Intent intent = new Intent(context, DropService.class).setAction(ACTION_SEND)
                .putExtra("peerId", peer.id).putExtra("peerName", peer.name).putExtra("peerKind", peer.kind)
                .putExtra("peerAddress", peer.address).putExtra("peerPort", peer.port)
                .putParcelableArrayListExtra("files", new ArrayList<>(uris));
        // Give the started service its own URI grants, so closing the picker/Activity does not revoke access.
        ClipData clip = ClipData.newRawUri("Files to send", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        intent.setClipData(clip);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        NotificationChannel running = new NotificationChannel(RUNNING_CHANNEL, "Background discovery", NotificationManager.IMPORTANCE_LOW);
        running.setDescription("Open Drop is available on your local network. Use Stop to turn it off.");
        notifications.createNotificationChannel(running);
        notifications.createNotificationChannel(new NotificationChannel(OFFER_CHANNEL, "Incoming files", NotificationManager.IMPORTANCE_DEFAULT));
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":lan");
        wakeLock.setReferenceCounted(false);
    }

    // A factory keeps service lifecycle tests independent of real sockets and Wi-Fi hardware.
    NetworkEngine createEngine() { return new NetworkEngine(this, this); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopNetworking();
            return START_NOT_STICKY;
        }
        if (ACTION_ACCEPT.equals(action) || ACTION_DECLINE.equals(action)) {
            if (pending != null && pending.token.equals(intent.getStringExtra(EXTRA_OFFER)))
                pending.decision.complete(ACTION_ACCEPT.equals(action));
            if (engine == null) stopSelf();
            return engine == null ? START_NOT_STICKY : START_STICKY;
        }
        if (!isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (engine == null) {
            startForeground(RUNNING_NOTIFICATION, runningNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            engine = createEngine();
            message = "Visible to devices on your local network";
            renewWakeLock.run();
            engine.start();
        }
        if (ACTION_SEND.equals(action)) {
            @SuppressWarnings("deprecation")
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra("files");
            if (uris != null) engine.send(new NetworkEngine.Peer(intent.getStringExtra("peerId"),
                    intent.getStringExtra("peerName"), intent.getStringExtra("peerKind"),
                    intent.getStringExtra("peerAddress"), intent.getIntExtra("peerPort", Wire.TRANSFER_PORT)), uris);
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    void attach(NetworkEngine.Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        if (engine == null) {
            if (!isEnabled(this)) listener.stopped();
            else listener.warning("Starting background discovery…");
            return;
        }
        if (port != 0) listener.ready(deviceName, port, addresses);
        listener.peersChanged(new ArrayList<>(peers));
        if (transferring) listener.progress(message, done, total);
        else listener.warning(message);
        if (pending != null && !pending.decision.isDone()) listener.offer(pending.sender, pending.files, pending.decision);
    }

    void detach(NetworkEngine.Listener listener) { listeners.remove(listener); }

    private PendingIntent openApp() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent serviceAction(String action, String token) {
        Intent intent = new Intent(this, DropService.class).setAction(action);
        if (token != null) intent.setData(Uri.parse("opendrop://offer/" + token + "/" + action)).putExtra(EXTRA_OFFER, token);
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification runningNotification() {
        Notification.Builder builder = new Notification.Builder(this, RUNNING_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drop).setContentTitle("Open Drop is running")
                .setContentText(message).setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Stop", serviceAction(ACTION_STOP, null)).build());
        if (transferring) builder.setProgress(1000, total > 0 ? (int) (done * 1000 / total) : 0, total == 0);
        return builder.build();
    }

    private void updateNotification(boolean force) {
        if (engine == null) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        long now = SystemClock.elapsedRealtime();
        if (force || now - lastNotification >= 1000) {
            notifications.notify(RUNNING_NOTIFICATION, runningNotification());
            lastNotification = now;
        }
    }

    @Override public void peersChanged(List<NetworkEngine.Peer> devices) {
        if (engine == null) return;
        peers = new ArrayList<>(devices);
        for (NetworkEngine.Listener listener : new ArrayList<>(listeners)) listener.peersChanged(new ArrayList<>(peers));
    }

    @Override public void ready(String name, int receivePort, String localAddresses) {
        if (engine == null) return;
        deviceName = name;
        port = receivePort;
        addresses = localAddresses;
        for (NetworkEngine.Listener listener : new ArrayList<>(listeners)) listener.ready(name, receivePort, localAddresses);
    }

    @Override public void offer(String sender, List<Wire.FileInfo> files, CompletableFuture<Boolean> decision) {
        if (engine == null) { decision.complete(false); return; }
        PendingOffer offer = new PendingOffer(sender, files, decision);
        pending = offer;
        decision.whenComplete((accepted, error) -> main.post(() -> {
            if (pending == offer) {
                pending = null;
                notifications.cancel(OFFER_NOTIFICATION);
            }
        }));
        Notification notification = new Notification.Builder(this, OFFER_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drop).setContentTitle(sender + " wants to send files")
                .setContentText(files.size() + " file(s) · " + files.get(0).name)
                .setContentIntent(openApp()).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_MESSAGE).setTimeoutAfter(60000)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Receive", serviceAction(ACTION_ACCEPT, offer.token)).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Decline", serviceAction(ACTION_DECLINE, offer.token)).build())
                .setDeleteIntent(serviceAction(ACTION_DECLINE, offer.token)).build();
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            notifications.notify(OFFER_NOTIFICATION, notification);
        if (listeners.isEmpty() && !notifications.areNotificationsEnabled()) decision.complete(false);
        else for (NetworkEngine.Listener listener : new ArrayList<>(listeners)) listener.offer(sender, files, decision);
    }

    @Override public void progress(String text, long transferred, long bytes) {
        if (engine == null) return;
        message = text;
        done = transferred;
        total = bytes;
        transferring = true;
        updateNotification(false);
        for (NetworkEngine.Listener listener : new ArrayList<>(listeners)) listener.progress(text, transferred, bytes);
    }

    @Override public void finished(String text, boolean success) {
        if (engine == null) return;
        transferring = false;
        message = (success ? "✓ " : "Transfer stopped: ") + text;
        updateNotification(true);
        for (NetworkEngine.Listener listener : new ArrayList<>(listeners)) listener.finished(text, success);
    }

    @Override public void warning(String text) {
        if (engine == null) return;
        message = text;
        updateNotification(true);
        for (NetworkEngine.Listener listener : new ArrayList<>(listeners)) listener.warning(text);
    }

    private void closeEngine() {
        if (pending != null) { pending.decision.complete(false); pending = null; }
        if (engine != null) {
            NetworkEngine closing = engine;
            engine = null;
            closing.close();
        }
        notifications.cancel(OFFER_NOTIFICATION);
        main.removeCallbacks(renewWakeLock);
        if (wakeLock.isHeld()) wakeLock.release();
        peers.clear();
        port = 0;
        transferring = false;
    }

    void stopNetworking() {
        setEnabled(this, false);
        closeEngine();
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifications.cancel(RUNNING_NOTIFICATION);
        List<NetworkEngine.Listener> oldListeners = new ArrayList<>(listeners);
        listeners.clear();
        for (NetworkEngine.Listener listener : oldListeners) listener.stopped();
        stopSelf();
    }

    @Override public void onDestroy() {
        closeEngine();
        listeners.clear();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
