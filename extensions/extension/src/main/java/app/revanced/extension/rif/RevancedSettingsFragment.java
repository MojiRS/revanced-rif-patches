package app.revanced.extension.rif;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.Preference;

import com.andrewshu.android.reddit.settings.RifBaseSettingsFragment;

/**
 * The "rif patches" settings screen. It extends rif's own base settings fragment
 * (whose class name R8 keeps), so it gets rif's native preference styling and
 * XML-loading for free. We only override the obfuscated abstract method that returns
 * the preference-XML resource id; we look ours up by name at runtime so the extension
 * never has to reference a patch-generated R constant or any renamed androidx class.
 *
 * That method is obfuscated differently in each rif build, so both are implemented
 * (the other is just an unused extra method in the build that doesn't declare it):
 *   - E4()  free rif is fun
 *   - t4()  rif is fun golden platinum
 *
 * The actual preferences are added to res/xml/revanced_preferences.xml by each
 * patch's resource patch, so this screen is a shared, extensible framework.
 *
 * Live greying that one preference `dependency` key can't express (several keys, "any of")
 * is done here, see {@link #applyGreying}.
 */
public class RevancedSettingsFragment extends RifBaseSettingsFragment {

    private static final String TAG = "RifSettings";

    // Held here: SharedPreferences keeps its listeners only weakly, so this lives as long as the screen.
    private SharedPreferences.OnSharedPreferenceChangeListener greyListener;
    private SharedPreferences listened;

    @Override
    protected int E4() {
        return preferencesXmlId();
    }

    @Override
    protected int t4() {
        return preferencesXmlId();
    }

    /**
     * rif's base fragment calls this protected hook from onViewCreated, once the preferences
     * are inflated. Each build has only its own (free N4, Platinum C4); the other is an unused
     * extra method there. Not calling super: it only disables ad preferences by key, and ours
     * has none (and calling the other build's name would not resolve).
     */
    @Override
    protected void N4() {
        startGreying(false);
    }

    @Override
    protected void C4() {
        startGreying(true);
    }

    private void startGreying(boolean platinum) {
        try {
            applyGreying(platinum);
            SharedPreferences p = Settings.sharedPreferences();
            if (p == null) return;
            if (listened != null && greyListener != null) listened.unregisterOnSharedPreferenceChangeListener(greyListener);
            greyListener = (prefs, key) -> {
                try {
                    if (key == null || isGreyingKey(key)) applyGreying(platinum);
                    // Free only (Platinum has no Block ads): rif's pro flag goes back on with it.
                    if (!platinum && Settings.KEY_BLOCK_ADS.equals(key)) AdSettings.blockAdsSwitchedOn(prefs);
                } catch (Throwable t) {
                    Log.w(TAG, "greying failed", t);
                }
            };
            listened = p;
            p.registerOnSharedPreferenceChangeListener(greyListener);
        } catch (Throwable t) {
            Log.w(TAG, "greying setup failed", t);
        }
    }

    private static boolean isGreyingKey(String key) {
        return Settings.KEY_INLINE_IMAGES.equals(key) || Settings.KEY_INLINE_VIDEOS.equals(key)
                || Settings.KEY_INLINE_TABLES.equals(key);
    }

    /**
     * Inline media greying across several keys, using the same getters the code runs on (so in
     * tables-only mode images and videos count as off):
     * long press (its delay slider follows it by its own dependency) needs images, videos or
     * tables; the size settings (their sliders follow their limit boxes) need images or videos.
     * A preference that isn't on the screen (tables-only mode drops some) is skipped.
     */
    private void applyGreying(boolean platinum) {
        boolean images = Settings.inlineImages();
        boolean videos = Settings.inlineVideos();
        boolean tables = Settings.inlineTables();
        boolean media = images || videos;
        setEnabled(platinum, Settings.KEY_INLINE_IMAGES_LONG_PRESS_SELECT, media || tables);
        setEnabled(platinum, Settings.KEY_INLINE_IMAGES_SCALE, media);
        setEnabled(platinum, Settings.KEY_INLINE_IMAGES_LIMIT_WIDTH, media);
        setEnabled(platinum, Settings.KEY_INLINE_IMAGES_LIMIT_HEIGHT, media);
    }

    private void setEnabled(boolean platinum, String key, boolean enabled) {
        Preference pref;
        try {
            pref = platinum ? A4(key) : L4(key);
        } catch (Throwable absent) { // rif's lookup throws NullPointerException for a missing key
            return;
        }
        if (pref == null) return;
        if (platinum) pref.k0(enabled);
        else pref.m0(enabled);
    }

    private static int preferencesXmlId() {
        try {
            Context ctx = Settings.context();
            if (ctx == null) return 0;
            return ctx.getResources()
                    .getIdentifier("revanced_preferences", "xml", ctx.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }
}
