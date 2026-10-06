package app.revanced.extension.rif;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Reads the ReVanced settings the patches expose in rif's settings UI.
 *
 * The checkboxes live in a "ReVanced" preference screen that inherits rif's base
 * settings fragment, so they write booleans to rif's "settings" SharedPreferences
 * file. The app Context comes from a hook in rif's Application.onCreate
 * ({@link #init}); reads are safe from any thread (e.g. the comment-render worker).
 */
public final class Settings {

    public static final String KEY_BLOCK_ADS = "BLOCK_ADS";
    public static final String KEY_INLINE_IMAGES = "INLINE_IMAGES";
    public static final String KEY_INLINE_IMAGES_SCALE = "INLINE_IMAGES_SCALE";
    public static final String KEY_INLINE_ALBUM_NAVIGATION = "INLINE_ALBUM_NAVIGATION";

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

    /**
     * The app Context captured by {@link #init}, falling back to (hidden-API)
     * ActivityThread reflection if init() somehow hasn't run. May return null.
     */
    static Context context() {
        Context ctx = appContext;
        if (ctx != null) return ctx;
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences prefs() {
        if (prefs == null) {
            try {
                Context ctx = context();
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

    public static boolean inlineAlbumNavigation() {
        return get(KEY_INLINE_ALBUM_NAVIGATION, true);
    }
}
