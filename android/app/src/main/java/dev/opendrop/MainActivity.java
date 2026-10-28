package dev.opendrop;

import android.Manifest;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class MainActivity extends AppCompatActivity implements NetworkEngine.Listener {
    private static final int PICK_FILES = 1;
    private static final int NOTIFICATIONS = 2;
    private DropService service;
    private boolean bound, resumed;
    private Button choose, manual, toggle;
    private AlertDialog offerDialog;
    private TextView identity, status, selection;
    private LinearLayout devices;
    private LinearProgressIndicator progress;
    private final List<Uri> pending = new ArrayList<>();
    private NetworkEngine.Peer pickerTarget;
    private DragAndDropPermissions dragPermission;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!bound || !(binder instanceof DropService.LocalBinder)) return;
            service = ((DropService.LocalBinder) binder).service();
            choose.setEnabled(true);
            manual.setEnabled(true);
            toggle.setEnabled(true);
            toggle.setText("Stop Open Drop");
            if (resumed) service.attach(MainActivity.this);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            status.setText("Reconnecting to background service…");
        }
    };

    @Override public void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(light);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightNavigationBars(light);
        identity = findViewById(R.id.identity);
        devices = findViewById(R.id.devices);
        selection = findViewById(R.id.selection);
        choose = findViewById(R.id.choose);
        manual = findViewById(R.id.manual);
        toggle = findViewById(R.id.toggle);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        choose.setOnClickListener(v -> chooseFiles(null));
        manual.setOnClickListener(v -> manualConnect());
        toggle.setOnClickListener(v -> {
            if (service != null && DropService.isEnabled(this)) service.stopNetworking();
            else {
                DropService.setEnabled(this, true);
                requestNotifications();
                startAndBind();
            }
        });
        peersChanged(new ArrayList<>());
        if (state != null) {
            ArrayList<Uri> restored = state.getParcelableArrayList("pendingFiles");
            if (restored != null) pending.addAll(restored);
            pickerTarget = peerFromState(state);
            updateSelection();
        } else readSharedFiles(getIntent());
        requestNotifications();
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("notificationPermissionAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationPermissionAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == NOTIFICATIONS && (grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED))
            warning("Enable notifications in Android settings to receive files and use Stop from the notification. You can also stop Open Drop here.");
    }

    private void startAndBind() {
        try {
            startForegroundService(new Intent(this, DropService.class).setAction(DropService.ACTION_START));
            if (!bound) bound = bindService(new Intent(this, DropService.class), connection, 0);
            if (!bound) warning("Could not connect to background service. Tap Start to retry.");
        } catch (RuntimeException e) {
            stopped();
            warning("Could not start background service: " + NetworkEngine.message(e));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (DropService.isEnabled(this)) startAndBind();
        else stopped();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (service != null) service.attach(this);
    }

    @Override protected void onPause() {
        resumed = false;
        if (service != null) service.detach(this);
        dismissOffer();
        super.onPause();
    }

    @Override protected void onStop() {
        disconnectService();
        super.onStop();
    }

    private void disconnectService() {
        if (service != null) service.detach(this);
        if (bound) { unbindService(connection); bound = false; }
        service = null;
    }

    private TextView label(String text, int size) {
        TextView view = new MaterialTextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface));
        return view;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void peersChanged(List<NetworkEngine.Peer> peers) {
        devices.removeAllViews();
        if (peers.isEmpty()) {
            TextView empty = label("Looking for your devices…\n\nLaunch Open Drop on your computer and connect both devices to the same local network.", 16);
            empty.setPadding(0, dp(32), 0, dp(32));
            devices.addView(empty);
        }
        for (NetworkEngine.Peer peer : peers) {
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(20));
            card.setCardElevation(0);
            card.setStrokeWidth(0);
            card.setCardBackgroundColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorSurfaceContainer));
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(20), dp(20), dp(20));
            ImageView icon = new ImageView(this);
            icon.setImageResource(peer.kind.equals("android") ? R.drawable.ic_phone : R.drawable.ic_computer);
            icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(card,
                    androidx.appcompat.R.attr.colorPrimary)));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout description = new LinearLayout(this);
            description.setOrientation(LinearLayout.VERTICAL);
            description.setPadding(dp(16), 0, 0, 0);
            TextView name = label(peer.name, 18);
            name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            description.addView(name);
            TextView detail = label((peer.kind.equals("android") ? "Android phone" : "Computer") + " · " + peer.address, 14);
            detail.setTextColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            description.addView(detail);
            row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(row);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, dp(12), 0, 0);
            devices.addView(card, params);
            card.setClickable(true);
            card.setFocusable(true);
            card.setContentDescription("Send files to " + peer.name + ", " + peer.address);
            card.setOnClickListener(v -> {
                if (pending.isEmpty()) chooseFiles(peer);
                else sendPending(peer);
            });
            card.setOnDragListener((v, event) -> {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return event.getClipDescription() != null &&
                            (event.getClipDescription().hasMimeType("text/uri-list") ||
                             event.getClipDescription().getMimeTypeCount() > 0);
                    case DragEvent.ACTION_DRAG_ENTERED: card.setAlpha(0.5f); return true;
                    case DragEvent.ACTION_DRAG_EXITED:
                    case DragEvent.ACTION_DRAG_ENDED: card.setAlpha(1); return true;
                    case DragEvent.ACTION_DROP:
                        if (dragPermission != null) dragPermission.release();
                        dragPermission = requestDragAndDropPermissions(event);
                        pending.clear();
                        ClipData clip = event.getClipData();
                        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
                            Uri uri = clip.getItemAt(i).getUri();
                            if (uri != null && "content".equals(uri.getScheme())) pending.add(uri);
                        }
                        if (!pending.isEmpty()) sendPending(peer);
                        card.setAlpha(1);
                        return true;
                    default: return true;
                }
            });
        }
    }

    private void chooseFiles(NetworkEngine.Peer peer) {
        pickerTarget = peer;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_FILES);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES || result != RESULT_OK || data == null) return;
        pending.clear();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) pending.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) pending.add(data.getData());
        updateSelection();
        if (pickerTarget != null && !pending.isEmpty()) sendPending(pickerTarget);
        pickerTarget = null;
    }

    @SuppressWarnings("deprecation")
    private void readSharedFiles(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) pending.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) pending.addAll(uris);
        }
        pending.removeIf(uri -> uri == null || !"content".equals(uri.getScheme()));
        updateSelection();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            pending.clear();
            readSharedFiles(intent);
        }
    }

    private void updateSelection() {
        selection.setText(pending.isEmpty() ? getString(R.string.selection_hint) :
                pending.size() + " file(s) selected. Tap a device to send.");
    }

    private void sendPending(NetworkEngine.Peer peer) {
        if (service == null || !DropService.isEnabled(this)) {
            warning("Tap Start Open Drop before sending files.");
            return;
        }
        DropService.sendFiles(this, peer, new ArrayList<>(pending));
        pending.clear();
        updateSelection();
    }

    private void manualConnect() {
        TextInputLayout field = new TextInputLayout(this);
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setHint(getString(R.string.address_label));
        field.setHelperText(getString(R.string.address_help));
        TextInputEditText address = new TextInputEditText(field.getContext());
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.addView(address, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(24), dp(8), dp(24), 0);
        container.addView(field, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Connect to a device")
                .setView(container).setNegativeButton("Cancel", null).setPositiveButton("Connect", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = address.getText() == null ? "" : address.getText().toString().trim();
            try {
                String[] parts = value.split(":", -1);
                if (parts.length > 2 || !parts[0].matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IllegalArgumentException();
                for (String octet : parts[0].split("\\.")) if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException();
                int port = parts.length == 2 ? Integer.parseInt(parts[1]) : Wire.TRANSFER_PORT;
                if (port < 1 || port > 65535) throw new IllegalArgumentException();
                NetworkEngine.Peer peer = new NetworkEngine.Peer("manual", value, "desktop", parts[0], port);
                dialog.dismiss();
                if (pending.isEmpty()) chooseFiles(peer); else sendPending(peer);
            } catch (IllegalArgumentException e) {
                field.setError(getString(R.string.invalid_address));
                address.requestFocus();
            }
        });
    }

    @Override public void offer(String sender, List<Wire.FileInfo> files, CompletableFuture<Boolean> decision) {
        if (!resumed || decision.isDone()) return;
        dismissOffer();
        long total = 0;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            total += files.get(i).size;
            if (i < 8) names.append("\n").append(files.get(i).name);
        }
        if (files.size() > 8) names.append("\n…");
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Receive files?")
                .setMessage(sender + " wants to send " + files.size() + " file(s) (" + sizeText(total) + ").\n" + names)
                .setNegativeButton("Decline", (d, which) -> decision.complete(false))
                .setPositiveButton("Receive", (d, which) -> decision.complete(true))
                .setOnCancelListener(d -> decision.complete(false)).create();
        offerDialog = dialog;
        decision.whenComplete((accepted, error) -> runOnUiThread(dialog::dismiss));
        if (!decision.isDone() && !isFinishing()) dialog.show();
    }

    private void dismissOffer() {
        if (offerDialog != null) { offerDialog.dismiss(); offerDialog = null; }
    }

    @Override public void progress(String message, long done, long total) {
        status.setText(message + (total > 0 ? " · " + sizeText(done) + " / " + sizeText(total) : ""));
        if (progress.isIndeterminate() != (total == 0)) {
            progress.setVisibility(View.GONE);
            progress.setIndeterminate(total == 0);
        }
        progress.setVisibility(View.VISIBLE);
        if (total > 0) progress.setProgress((int) (done * 1000 / total));
    }

    @Override public void finished(String message, boolean success) {
        progress.setVisibility(View.GONE);
        status.setText((success ? "✓ " : "Transfer stopped: ") + message);
        if (dragPermission != null) { dragPermission.release(); dragPermission = null; }
    }

    @Override public void warning(String message) { status.setText(message); }

    @Override public void stopped() {
        disconnectService();
        dismissOffer();
        progress.setVisibility(View.GONE);
        choose.setEnabled(false);
        manual.setEnabled(false);
        toggle.setEnabled(true);
        toggle.setText("Start Open Drop");
        devices.removeAllViews();
        devices.addView(label("Open Drop is stopped. Tap Start Open Drop to be discoverable again.", 16));
        identity.setText("This phone · Stopped");
        status.setText("Stopped · No discovery or file transfers running");
        if (dragPermission != null) { dragPermission.release(); dragPermission = null; }
    }

    @Override public void ready(String name, int port, String addresses) {
        identity.setText("This phone · " + name + "\n" + addresses + ":" + port);
    }

    private static String sizeText(long bytes) {
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
        return String.format(Locale.getDefault(), index == 0 ? "%.0f %s" : "%.1f %s", value, units[index]);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putParcelableArrayList("pendingFiles", new ArrayList<>(pending));
        if (pickerTarget != null) {
            state.putString("targetName", pickerTarget.name);
            state.putString("targetAddress", pickerTarget.address);
            state.putInt("targetPort", pickerTarget.port);
        }
        super.onSaveInstanceState(state);
    }

    private NetworkEngine.Peer peerFromState(Bundle state) {
        String address = state.getString("targetAddress");
        return address == null ? null : new NetworkEngine.Peer("restored",
                state.getString("targetName"), "desktop", address, state.getInt("targetPort"));
    }

    @Override protected void onDestroy() {
        disconnectService();
        dismissOffer();
        if (dragPermission != null) dragPermission.release();
        super.onDestroy();
    }
}
