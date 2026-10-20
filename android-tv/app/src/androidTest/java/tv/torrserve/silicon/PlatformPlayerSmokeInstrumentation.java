package tv.torrserve.silicon;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Platform-only smoke runner: AndroidX Test depends on unminified Kotlin classes in the debug APK. */
public final class PlatformPlayerSmokeInstrumentation extends Instrumentation {
    private boolean phoneWindow;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments); phoneWindow = "true".equals(arguments.getString("phoneWindow")); start();
    }
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
            android.media.MediaMetadataRetriever reference = new android.media.MediaMetadataRetriever();
            int expectedLit;
            try {
                reference.setDataSource(fixture.getAbsolutePath());
                android.graphics.Bitmap frame = reference.getFrameAtTime(1000000);
                if (frame == null) throw new AssertionError("Fixture has no decodable reference frame");
                expectedLit = litPixels(frame); frame.recycle();
            } finally { reference.release(); }
            Bundle fixtureInfo = new Bundle(); fixtureInfo.putString("stream", "Fixture reference lit samples: " + expectedLit + "\n"); sendStatus(1, fixtureInfo);
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
            if (expectedLit > 0) await(() -> {
                android.graphics.Bitmap screen = getUiAutomation().takeScreenshot();
                if (screen == null) return false;
                int lit = litPixels(screen); screen.recycle(); return lit > 100;
            }, "Rendered reference video");
            getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
            MediaSessionManager manager = getTargetContext().getSystemService(MediaSessionManager.class);
            MediaController controller = manager.getActiveSessions(null).stream()
                .filter(c -> target.equals(c.getPackageName())).findFirst().orElseThrow(
                    () -> new AssertionError("Application media session is missing"));
            Thread.sleep(5600);
            if (node(getUiAutomation().getRootInActiveWindow(), "Пауза", false) != null ||
                node(getUiAutomation().getRootInActiveWindow(), "Pause", false) != null)
                throw new AssertionError("Playing controls did not hide after inactivity");
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            await(() -> node(getUiAutomation().getRootInActiveWindow(), "Пауза", false) != null ||
                node(getUiAutomation().getRootInActiveWindow(), "Pause", false) != null, "Reveal hidden controls");
            if (controller.getPlaybackState().getState() != PlaybackState.STATE_PLAYING)
                throw new AssertionError("Revealing controls paused playback");
            sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
            await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED, "Remote pause");
            controller.getTransportControls().seekTo(10000);
            await(() -> controller.getPlaybackState().getPosition() >= 9500, "Seek to 10 seconds");
            sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            await(() -> controller.getPlaybackState().getPosition() >= 19500, "Remote fast-forward");
            sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_REWIND);
            await(() -> controller.getPlaybackState().getPosition() < 15000, "Remote rewind");
            click("Аудиодорожки", "Audio tracks");
            clickContaining("Russian");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            click("Субтитры", "Subtitles");
            clickContaining("English", "английский", "Английский");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            long pausedAt = controller.getPlaybackState().getPosition();
            sendKeyDownUpSync(KeyEvent.KEYCODE_HOME);
            Thread.sleep(1200);
            Intent returning = new Intent().setClassName(target, "tv.torrserve.silicon.features.player.PlayerActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("stream", Uri.fromFile(fixture).toString()).putExtra("identity", "release-smoke")
                .putExtra("title", "Release playback smoke");
            runOnMainSync(() -> getTargetContext().startActivity(returning));
            await(() -> node(getUiAutomation().getRootInActiveWindow(), "Release playback smoke", false) != null,
                "Return from Home");
            if (controller.getPlaybackState().getState() != PlaybackState.STATE_PAUSED ||
                Math.abs(controller.getPlaybackState().getPosition() - pausedAt) > 1000)
                throw new AssertionError("Background return changed paused playback or its position");
            sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY);
            await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING, "Remote resume");
            if (phoneWindow) {
                controller.getTransportControls().pause();
                await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED, "Phone handoff");
                Bundle ready = new Bundle(); ready.putString("stream", "READY_FOR_PHONE\n"); sendStatus(1, ready);
                Thread.sleep(30000); // Bounded window for a separate LAN client; UI/main thread stays active.
            }
            passed = true;
            result.putString("stream", "\nOK (1 test)\nMinified playback: start, pause, seek, D-pad/media keys, audio/subtitle menus, Home/return and resume passed.\n");
        } catch (Throwable error) {
            result.putString("stream", "\nFAILED\n" + android.util.Log.getStackTraceString(error));
        } finally {
            try { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); } catch (RuntimeException ignored) { }
            Activity opened = activity;
            if (opened != null) runOnMainSync(opened::finish);
            getTargetContext().stopService(new Intent().setClassName(target,
                "tv.torrserve.silicon.features.player.PlaybackService"));
            fixture.delete();
            getUiAutomation().dropShellPermissionIdentity();
        }
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private interface Condition { boolean ready() throws Exception; }
    private int litPixels(android.graphics.Bitmap image) {
        int lit = 0;
        for (int y = image.getHeight()/4; y < image.getHeight()/2; y += 8)
            for (int x = image.getWidth()/4; x < image.getWidth()*3/4; x += 8) {
                int p = image.getPixel(x,y);
                if (Math.max(android.graphics.Color.red(p),Math.max(android.graphics.Color.green(p),android.graphics.Color.blue(p))) > 8) lit++;
            }
        return lit;
    }
    private void await(Condition condition, String description) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            if (condition.ready()) return;
            Thread.sleep(150);
        }
        throw new AssertionError(description + " timed out");
    }
    private AccessibilityNodeInfo node(AccessibilityNodeInfo root, String label, boolean contains) {
        if (root == null) return null;
        String text = String.valueOf(root.getText());
        String description = String.valueOf(root.getContentDescription());
        if (contains ? text.contains(label) || description.contains(label) : text.equals(label) || description.equals(label)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = node(root.getChild(i), label, contains);
            if (found != null) return found;
        }
        return null;
    }
    private void click(String... labels) throws Exception { clickNode(false, labels); }
    private void clickContaining(String... labels) throws Exception { clickNode(true, labels); }
    private void clickNode(boolean contains, String... labels) throws Exception {
        await(() -> {
            for (String label : labels) {
                AccessibilityNodeInfo found = node(getUiAutomation().getRootInActiveWindow(), label, contains);
                while (found != null) {
                    if (found.isClickable()) return found.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    found = found.getParent();
                }
            }
            return false;
        }, "Control " + String.join("/", labels));
        waitForIdleSync();
    }
}
