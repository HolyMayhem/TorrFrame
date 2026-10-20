package tv.torrframe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Tests the real minified APK through Android APIs, without app-internal Kotlin symbols. */
public final class DeveloperUnlockSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String... labels) {
        if (node == null) return null;
        for (String label : labels) {
            if (label.contentEquals(node.getText() == null ? "" : node.getText()) ||
                label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = find(node.getChild(i), labels);
            if (result != null) return result;
        }
        return null;
    }
    private AccessibilityNodeInfo node(String... labels) { return find(getUiAutomation().getRootInActiveWindow(), labels); }
    private AccessibilityNodeInfo waitFor(String... labels) {
        long deadline = SystemClock.elapsedRealtime() + 18000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo result = node(labels);
            if (result != null) return result;
            SystemClock.sleep(100);
        }
        throw new AssertionError("UI missing: " + java.util.Arrays.toString(labels));
    }
    private boolean scroll(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (scroll(node.getChild(i))) return true;
        return false;
    }
    private AccessibilityNodeInfo inList(String... labels) {
        for (int i = 0; i < 12; i++) {
            AccessibilityNodeInfo result = node(labels);
            if (result != null) return result;
            if (!scroll(getUiAutomation().getRootInActiveWindow())) break;
            SystemClock.sleep(200);
        }
        return node(labels);
    }
    private void click(String... labels) {
        long deadline = SystemClock.elapsedRealtime() + 18000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (activate(node(labels))) { SystemClock.sleep(500); return; }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Cannot click " + labels[0]);
    }
    private boolean activate(AccessibilityNodeInfo node) {
        while (node != null && !node.isClickable()) node = node.getParent();
        return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    private void back() { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); SystemClock.sleep(600); }
    private void status(String text) { Bundle b = new Bundle(); b.putString("stream", text + "\n"); sendStatus(1, b); }
    private android.graphics.Rect gearBounds() {
        AccessibilityNodeInfo gear = node("Settings", "Настройки");
        while (gear != null && !gear.isClickable()) gear = gear.getParent();
        require(gear != null, "Gear must remain available while held");
        android.graphics.Rect result = new android.graphics.Rect(); gear.getBoundsInScreen(result); return result;
    }
    private android.graphics.Bitmap gearImage(android.graphics.Rect bounds) {
        android.graphics.Bitmap full = getUiAutomation().takeScreenshot();
        require(full != null, "No screenshot for pressed feedback check");
        android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width(), bounds.height());
        full.recycle();
        return crop;
    }
    private void holdGear() {
        long down = SystemClock.uptimeMillis();
        sendKeySync(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        SystemClock.sleep(5300);
        sendKeySync(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
    }

    @Override public void onStart() {
        Bundle result = new Bundle(); Activity activity = null;
        android.content.SharedPreferences access = getTargetContext().getSharedPreferences("developer-access", Context.MODE_PRIVATE);
        boolean originallyPresent = access.contains("unlocked");
        boolean originallyUnlocked = access.getBoolean("unlocked", false);
        boolean passed = false;
        try {
            Context target = getTargetContext();
            activity = startActivitySync(new Intent().setClassName(target.getPackageName(), "tv.torrframe.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Wait out the real cold-start clip and initialization.
            SystemClock.sleep(10000);
            click("Settings", "Настройки");
            waitFor("TorrServer");
            if (originallyUnlocked) {
                back(); holdGear();
                waitFor("Developer mode disabled", "Режим разработчика отключён");
                require(!access.getBoolean("unlocked", true), "Mode did not disable");
                click("Settings", "Настройки"); waitFor("TorrServer");
            }
            require(inList("Developer menu", "Меню разработчика") == null, "Developer category leaked before unlock");
            back();
            android.graphics.Rect resting = gearBounds();
            android.graphics.Bitmap beforePress = gearImage(resting);
            long down = SystemClock.uptimeMillis();
            sendKeySync(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            SystemClock.sleep(1000);
            android.graphics.Bitmap duringPress = gearImage(resting);
            int changed = 0;
            for (int y = 0; y < resting.height(); y++) for (int x = 0; x < resting.width(); x++) {
                int a = beforePress.getPixel(x, y), b = duringPress.getPixel(x, y);
                if (Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) > 12 ||
                    Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) > 12 ||
                    Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) > 12) changed++;
            }
            beforePress.recycle(); duringPress.recycle();
            require(changed > 100, "Gear has no visible pressed feedback; changed pixels: " + changed);
            status("R8: pressed gear changed " + changed + " actual rendered pixels");
            require(!access.getBoolean("unlocked", false), "One second unexpectedly unlocked menu");
            sendKeySync(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            waitFor("TorrServer");
            require(inList("Developer menu", "Меню разработчика") == null, "Short OK unlocked menu");
            back();
            down = SystemClock.uptimeMillis();
            sendKeySync(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            SystemClock.sleep(4100);
            require(!access.getBoolean("unlocked", false), "Menu unlocked before five seconds");
            require(node("Appearance", "Оформление") == null, "Held OK opened settings before release");
            SystemClock.sleep(1200);
            sendKeySync(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            long deadline = SystemClock.elapsedRealtime() + 3000;
            while (!access.getBoolean("unlocked", false) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            require(access.getBoolean("unlocked", false), "Five-second hold did not persist unlock");
            AccessibilityNodeInfo notice = waitFor("Developer mode enabled", "Режим разработчика активирован");
            require(!notice.isFocusable() && notice.getChildCount() == 0, "Notice must be plain non-focusable text");
            require(node("Appearance", "Оформление") == null, "Long release also opened settings");
            status("R8: hidden initially, short OK preserved, unlock only after 5 seconds, no extra click");
            click("Settings", "Настройки");
            AccessibilityNodeInfo developer = inList("Developer menu", "Меню разработчика");
            require(activate(developer), "Developer category unavailable");
            waitFor("Diagnostic panel", "Диагностическая панель");
            require(inList("Replay startup with splash", "Повторить запуск с заставкой") != null, "Normal preview unavailable in R8");
            require(inList("Replay startup · slow loading", "Повторить запуск · медленная загрузка") != null, "Slow preview unavailable in R8");
            status("R8: existing developer controls, diagnostics and both startup previews are present");
            back(); back();
            holdGear();
            waitFor("Developer mode disabled", "Режим разработчика отключён");
            require(!access.getBoolean("unlocked", true), "Second hold did not disable mode");
            click("Settings", "Настройки"); waitFor("TorrServer");
            require(inList("Developer menu", "Меню разработчика") == null, "Developer category remained after disable");
            back();
            status("R8: held gear visibly shrinks, notices are plain text, second hold disables and hides the category");
            result.putString("stream", "\nOK (1 test)\n"); passed = true;
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error));
        } finally {
            // Restore only the access bit owned by this test, leaving the user's other data alone.
            android.content.SharedPreferences.Editor edit = access.edit();
            if (originallyPresent) edit.putBoolean("unlocked", originallyUnlocked); else edit.remove("unlocked");
            edit.commit();
            try { sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); } catch (RuntimeException ignored) { }
        }
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
