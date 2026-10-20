package tv.torrframe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.Locale;

/** Platform-only identity and startup check runnable against the optimized APK. */
public final class BrandingSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            check("tv.torrframe".equals(target.getPackageName()), "Wrong APK installation ID");
            ApplicationInfo app = target.getApplicationInfo();
            for (String language : new String[]{"en", "ru"}) {
                Configuration config = new Configuration(target.getResources().getConfiguration());
                config.setLocale(Locale.forLanguageTag(language));
                Context localized = target.createConfigurationContext(config);
                check("TorrFrame".contentEquals(localized.getText(app.labelRes)), "Wrong " + language + " app label");
            }
            Intent launch = target.getPackageManager().getLeanbackLaunchIntentForPackage(target.getPackageName());
            check(launch != null, "Missing Android TV launcher entry");
            check(new ComponentName("tv.torrframe", "tv.torrframe.MainActivity").equals(launch.getComponent()),
                "Wrong launcher component");
            ActivityInfo activityInfo = target.getPackageManager().getActivityInfo(launch.getComponent(), 0);
            check(activityInfo.targetActivity == null, "Launcher still uses an alias");
            check("TorrFrame".contentEquals(activityInfo.loadLabel(target.getPackageManager())), "Wrong launcher label");
            ProviderInfo updates = target.getPackageManager().resolveContentProvider("tv.torrframe.updates", 0);
            check(updates != null && "tv.torrframe".equals(updates.packageName), "Wrong update provider authority");
            Activity activity = startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            check("tv.torrframe.MainActivity".equals(activity.getClass().getName()), "Wrong activity");
            SystemClock.sleep(8_000);
            check(!activity.isFinishing() && !activity.isDestroyed(), "Activity closed during startup");
            result.putString("stream", "PASS: tv.torrframe APK, RU/EN TorrFrame labels, direct TV launcher, update provider, optimized startup.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
