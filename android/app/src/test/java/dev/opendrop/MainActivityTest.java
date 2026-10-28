package dev.opendrop;

import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class MainActivityTest {
    @Before public void disableNetworking() {
        DropService.setEnabled(RuntimeEnvironment.getApplication(), false);
    }

    @Test public void invalidAddressStaysOpenAndValidAddressLaunchesPicker() {
        try (ActivityController<MainActivity> screen = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = screen.get();
            Button manual = activity.findViewById(R.id.manual);
            manual.setEnabled(true);
            manual.performClick();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            TextInputLayout field = findField(dialog.getWindow().getDecorView());
            assertNotNull(field);
            TextInputEditText address = (TextInputEditText) field.getEditText();
            address.setText("999.1.1.1:70000");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(field.getError());
            address.setText("192.168.1.20:45455");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertFalse(dialog.isShowing());
            Intent picker = org.robolectric.Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.getAction());
            assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        }
    }

    @Test @Config(qualifiers = "night")
    public void nightThemeInflatesMaterialControlsAndDeviceCards() {
        try (ActivityController<MainActivity> screen = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = screen.get();
            assertTrue(activity.findViewById(R.id.choose) instanceof MaterialButton);
            activity.peersChanged(Arrays.asList(new NetworkEngine.Peer("pc", "My computer", "desktop", "192.168.1.20", 45455)));
            android.widget.LinearLayout devices = activity.findViewById(R.id.devices);
            assertTrue(devices.getChildAt(0) instanceof MaterialCardView);
            assertTrue(devices.getChildAt(0).getContentDescription().toString().contains("My computer"));
        }
    }

    @Test public void transferProgressSwitchesBetweenUnknownAndKnownSizes() {
        try (ActivityController<MainActivity> screen = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = screen.get();
            com.google.android.material.progressindicator.LinearProgressIndicator indicator = activity.findViewById(R.id.progress);
            activity.progress("Preparing", 0, 0);
            assertTrue(indicator.isIndeterminate());
            activity.progress("Sending", 5, 10);
            assertFalse(indicator.isIndeterminate());
            assertEquals(500, indicator.getProgress());
            activity.finished("Sent", true);
            assertEquals(android.view.View.GONE, indicator.getVisibility());
        }
    }

    @Test public void recreatingScreenPreservesSharedFilesWithoutDuplicatingThem() {
        Intent share = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
                .setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://documents/example"));
        try (ActivityController<MainActivity> screen = Robolectric.buildActivity(MainActivity.class, share).setup()) {
            screen.recreate();
            List<Uri> pending = ReflectionHelpers.getField(screen.get(), "pending");
            assertEquals(Arrays.asList(Uri.parse("content://documents/example")), pending);
            TextView selection = screen.get().findViewById(R.id.selection);
            assertTrue(selection.getText().toString().contains("1 file(s) selected"));
        }
    }

    private TextInputLayout findField(android.view.View view) {
        if (view instanceof TextInputLayout) return (TextInputLayout) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextInputLayout field = findField(group.getChildAt(i));
                if (field != null) return field;
            }
        }
        return null;
    }
}
