package app.revanced.extension.rif;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Reads the ReVanced settings the patches expose in rif's settings UI.
 *
 * The checkboxes live in a "ReVanced" preference screen and write booleans to
 * rif's default SharedPreferences ("<package>_preferences"). We read them here.
 * A Context is obtained via ActivityThread reflection so this works on any thread
 * (e.g. the comment-render worker) without a hooked Context.
 */
public final class Settings {

    public static final String KEY_BLOCK_ADS = "BLOCK_ADS";
    public static final String KEY_INLINE_IMAGES = "INLINE_IMAGES";
    public static final String KEY_INLINE_IMAGES_SCALE = "INLINE_IMAGES_SCALE";

    private static SharedPreferences prefs;
    private static Context appContext;

    private Settings() {}

    /**
     * Captures the application Context. Called from a hook injected into rif's
     * Application.onCreate, so we never depend on (hidden-API-restricted)
     * ActivityThread reflection to read preferences.
     */
    public static void init(Context context) {
        try {
            if (context != null && appContext == null) {
                appContext = context.getApplicationContext();
                prefs = null; // re-resolve against the real context
            }
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs() {
        if (prefs == null) {
            try {
                Context ctx = appContext;
                if (ctx == null) {
                    // Fallback if init() hasn't run yet.
                    ctx = (Context) Class.forName("android.app.ActivityThread")
                            .getMethod("currentApplication").invoke(null);
                }
                if (ctx != null) {
                    // rif overrides the preference name to "settings" in its base
                    // settings fragment (RifBaseSettingsFragment.s4 ->
                    // setSharedPreferencesName("settings")), so our checkboxes — and
                    // these reads — must use that file, not the androidx default.
                    prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
                }
            } catch (Throwable ignored) {
            }
        }
        return prefs;
    }

    private static boolean get(String key, boolean def) {
        try {
            SharedPreferences p = prefs();
            return p == null ? def : p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean blockAds() {
        return get(KEY_BLOCK_ADS, true);
    }

    public static boolean inlineImages() {
        return get(KEY_INLINE_IMAGES, true);
    }

    public static boolean scaleInlineImages() {
        return get(KEY_INLINE_IMAGES_SCALE, true);
    }
}
