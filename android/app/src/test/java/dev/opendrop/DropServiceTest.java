package dev.opendrop;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Button;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class DropServiceTest {
    public static class TestService extends DropService {
        FakeEngine fake;
        int created;
        @Override NetworkEngine createEngine() {
            created++;
            fake = new FakeEngine(this, this);
            return fake;
        }
    }

    static final class FakeEngine extends NetworkEngine {
        int starts, closes;
        List<Uri> sent;
        private final Listener listener;
        FakeEngine(Context context, Listener listener) { super(context, listener); this.listener = listener; }
        @Override void start() {
            starts++;
            listener.ready("Test phone", 45455, "192.168.0.58");
        }
        @Override void send(Peer peer, List<Uri> uris) { sent = new ArrayList<>(uris); }
        @Override public void close() { closes++; super.close(); }
    }

    static final class Screen implements NetworkEngine.Listener {
        String name, message;
        int offers;
        boolean stopped;
        List<NetworkEngine.Peer> peers;
        @Override public void peersChanged(List<NetworkEngine.Peer> peers) { this.peers = peers; }
        @Override public void offer(String sender, List<Wire.FileInfo> files, CompletableFuture<Boolean> answer) { offers++; }
        @Override public void progress(String message, long done, long total) { this.message = message; }
        @Override public void finished(String message, boolean success) { this.message = message; }
        @Override public void ready(String name, int port, String addresses) { this.name = name; }
        @Override public void warning(String message) { this.message = message; }
        @Override public void stopped() { stopped = true; }
    }

    private Application app;
    private TestService service;
    private ServiceController<TestService> controller;
    private NotificationManager notifications;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        if (Build.VERSION.SDK_INT >= 33) Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        DropService.setEnabled(app, true);
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        notifications = app.getSystemService(NotificationManager.class);
        service.onStartCommand(new Intent(app, DropService.class).setAction(DropService.ACTION_START), 0, 1);
    }

    @After public void cleanup() { controller.destroy(); }

    private List<Wire.FileInfo> files() { return Arrays.asList(new Wire.FileInfo("example.txt", 12)); }
    private Notification offerNotification() { return Shadows.shadowOf(notifications).getNotification(DropService.OFFER_NOTIFICATION); }
    private Intent action(Notification notification, int index) { return Shadows.shadowOf(notification.actions[index].actionIntent).getSavedIntent(); }

    @Test public void startIsIdempotentAndNotificationHasStop() {
        service.onStartCommand(new Intent(app, DropService.class).setAction(DropService.ACTION_START), 0, 2);
        assertEquals(1, service.created);
        assertEquals(1, service.fake.starts);
        Notification notification = Shadows.shadowOf(service).getLastForegroundNotification();
        assertNotNull(notification);
        assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertEquals("Stop", notification.actions[0].title.toString());
        assertEquals(DropService.ACTION_STOP, action(notification, 0).getAction());
        PowerManager.WakeLock wakeLock = ReflectionHelpers.getField(service, "wakeLock");
        assertTrue(wakeLock.isHeld());
    }

    @Test public void screenDetachKeepsDiscoveryAndReattachRestoresState() {
        Screen first = new Screen();
        service.attach(first);
        service.detach(first);
        service.peersChanged(Arrays.asList(new NetworkEngine.Peer("desktop", "Computer", "desktop", "192.168.0.138", 45455)));
        service.progress("Sending example.txt", 6, 12);
        assertEquals(0, service.fake.closes);
        Screen reopened = new Screen();
        service.attach(reopened);
        assertEquals("Test phone", reopened.name);
        assertEquals("Computer", reopened.peers.get(0).name);
        assertEquals("Sending example.txt", reopened.message);
        assertEquals(1, service.created);
    }

    @Test public void closingActivityDoesNotStopNetworkOrDeclineOffer() {
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
                new ComponentName(app, DropService.class), service.onBind(new Intent()));
        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create().start().resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        service.offer("Computer", files(), decision);
        activity.pause().stop().destroy();
        assertEquals(0, service.fake.closes);
        assertFalse(decision.isDone());
        assertNotNull(offerNotification());
    }

    @Test public void notificationAcceptsInBackgroundAndStaleActionCannotAcceptNextOffer() {
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        service.offer("Computer", files(), first);
        assertFalse(first.isDone());
        Intent receive = action(offerNotification(), 0);
        service.onStartCommand(receive, 0, 2);
        assertTrue(first.join());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(offerNotification());
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        service.offer("Other computer", files(), second);
        service.onStartCommand(receive, 0, 3);
        assertFalse(second.isDone());
        service.onStartCommand(action(offerNotification(), 1), 0, 4);
        assertFalse(second.join());
        assertEquals(0, service.fake.closes);
    }

    @Test public void expiredOfferNotificationIsRemovedAndReopenCannotAcceptIt() {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        service.offer("Computer", files(), decision);
        decision.complete(false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(offerNotification());
        Screen reopened = new Screen();
        service.attach(reopened);
        assertEquals(0, reopened.offers);
    }

    @Test public void stopClosesSocketsRejectsPendingAndPersistsDisabledState() {
        Screen screen = new Screen();
        service.attach(screen);
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        service.offer("Computer", files(), decision);
        Intent stop = action(Shadows.shadowOf(service).getLastForegroundNotification(), 0);
        service.onStartCommand(stop, 0, 2);
        assertEquals(1, service.fake.closes);
        assertFalse(decision.join());
        assertTrue(screen.stopped);
        assertFalse(DropService.isEnabled(app));
        PowerManager.WakeLock wakeLock = ReflectionHelpers.getField(service, "wakeLock");
        assertFalse(wakeLock.isHeld());
        assertNull(offerNotification());
        assertNull(Shadows.shadowOf(notifications).getNotification(DropService.RUNNING_NOTIFICATION));
        service.onStartCommand(null, 0, 3);
        assertEquals(1, service.created);
    }

    @Test public void openingStoppedAppRequiresExplicitStart() {
        service.stopNetworking();
        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create().start().resume();
        Button toggle = ReflectionHelpers.getField(activity.get(), "toggle");
        assertEquals("Start Open Drop", toggle.getText().toString());
        assertFalse(DropService.isEnabled(app));
        activity.pause().stop().destroy();
        assertEquals(1, service.fake.closes);
    }

    @Test public void sendIntentGivesServiceUriGrantsIndependentOfScreen() {
        List<Uri> uris = Arrays.asList(Uri.parse("content://documents/one"), Uri.parse("content://documents/two"));
        DropService.sendFiles(app, new NetworkEngine.Peer("pc", "Computer", "desktop", "192.168.0.138", 45455), uris);
        Intent send = Shadows.shadowOf(app).getNextStartedService();
        assertEquals(DropService.ACTION_SEND, send.getAction());
        assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals(2, send.getClipData().getItemCount());
        assertEquals(uris.get(1), send.getClipData().getItemAt(1).getUri());
        service.onStartCommand(send, 0, 2);
        assertEquals(uris, service.fake.sent);
    }
}
