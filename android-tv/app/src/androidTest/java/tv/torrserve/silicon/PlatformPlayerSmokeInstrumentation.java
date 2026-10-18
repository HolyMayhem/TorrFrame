package tv.torrserve.silicon;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Platform-only smoke runner: AndroidX Test depends on unminified Kotlin classes in the debug APK. */
public final class PlatformPlayerSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        File fixture = new File(getTargetContext().getCacheDir(), "player-release-smoke.mkv");
        String target = getTargetContext().getPackageName();
        boolean passed = false;
        try {
            try (InputStream input = getContext().getAssets().open("player-multitrack.mkv");
                 FileOutputStream output = new FileOutputStream(fixture)) {
                byte[] bytes = new byte[8192]; int length;
                while ((length = input.read(bytes)) != -1) output.write(bytes, 0, length);
            }
            activity = startActivitySync(new Intent().setClassName(target,
                "tv.torrserve.silicon.features.player.PlayerActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("stream", Uri.fromFile(fixture).toString()).putExtra("identity", "release-smoke")
                .putExtra("title", "Release playback smoke"));
            String session = "";
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                    getUiAutomation().executeShellCommand("dumpsys media_session"))) {
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int length;
                    while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                    session = new String(output.toByteArray(), StandardCharsets.UTF_8);
                }
                if (session.contains("Release playback smoke") && (session.contains("state=3,") || session.contains("state=PLAYING(3)"))) break;
                Thread.sleep(250);
            }
            if (activity.isFinishing() || !session.contains("Release playback smoke") || !(session.contains("state=3,") || session.contains("state=PLAYING(3)")))
                throw new AssertionError("No playing session: " + session);
            Thread.sleep(1500);
            passed = true;
            result.putString("stream", "\nOK (1 test)\nMinified playback session is playing.\n");
        } catch (Throwable error) {
            result.putString("stream", "\nFAILED\n" + android.util.Log.getStackTraceString(error));
        } finally {
            try { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); } catch (RuntimeException ignored) { }
            Activity opened = activity;
            if (opened != null) runOnMainSync(opened::finish);
            getTargetContext().stopService(new Intent().setClassName(target,
                "tv.torrserve.silicon.features.player.PlaybackService"));
            fixture.delete();
        }
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
