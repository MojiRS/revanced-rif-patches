package app.revanced.extension.rif;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import java.util.Iterator;
import java.util.List;

import r4.c;

/**
 * Free only (Disable ads). rif's own ad switches ("Ads & pro features" ADS_ENABLED, "Hide ads
 * (reddit premium)" GOLD_BLOCK_ADS) mean nothing while our Block ads is on, and ADS_ENABLED is
 * also rif's whole pro flag, so while Block ads is on we hide both rows and keep it true.
 * Everything here does nothing when Block ads is off.
 *
 * Names are free's (R8-renamed androidx members via stubs): findPreference {@code b0}, setVisible
 * {@code C0}, setSummary {@code y0}, isChecked {@code I0}, setChecked {@code J0}.
 */
public final class AdSettings {

    private static final String TAG = "RifAdSettings";
    private static final String ADS_ENABLED = "ADS_ENABLED";
    private static final String GOLD_BLOCK_ADS = "GOLD_BLOCK_ADS";
    private static final String MODERATOR_ENABLED = "MODERATOR_ENABLED";

    private AdSettings() {}

    /**
     * Start of RedditIsFunApplication.onCreate, before rif first loads its pro flag from the
     * stored ADS_ENABLED: write it true so pro features and the settings screens agree from launch.
     */
    public static void keepProEnabled(Context context) {
        try {
            Settings.init(context);
            if (!Settings.blockAds()) return;
            SharedPreferences p = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
            if (!p.getBoolean(ADS_ENABLED, true)) {
                p.edit().putBoolean(ADS_ENABLED, true).apply();
                Log.w(TAG, "ADS_ENABLED was off: set on (Block ads keeps pro features)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "keepProEnabled failed", t);
        }
    }

    /**
     * Hook in GeneralSettingsFragment's post-inflate method; {@code fragment} is that fragment.
     * Hides both ad rows (setVisible, not removal: Moderator features and Hide ads depend on
     * ADS_ENABLED by key and a missing key crashes the screen), makes sure ADS_ENABLED is on, and
     * drops Moderator features' "Requires ads enabled" summary.
     */
    public static void hideAdRows(Object fragment) {
        try {
            if (!Settings.blockAds()) return;
            androidx.preference.i f = (androidx.preference.i) fragment;
            Preference ads = f.b0(ADS_ENABLED);
            if (ads instanceof TwoStatePreference) {
                TwoStatePreference toggle = (TwoStatePreference) ads;
                if (!toggle.I0()) toggle.J0(true);
            }
            if (ads != null) ads.C0(false);
            Preference gold = f.b0(GOLD_BLOCK_ADS);
            if (gold != null) gold.C0(false);
            Preference moderator = f.b0(MODERATOR_ENABLED);
            if (moderator != null) moderator.y0(null);
        } catch (Throwable t) {
            Log.w(TAG, "hideAdRows failed", t);
        }
    }

    /**
     * Settings search: drops the two ad rows from the results (the search reads each screen's XML,
     * so rows hidden at runtime would still be listed). {@code results} is the list of r4.c.
     */
    public static void filterSearch(List<?> results) {
        try {
            if (results == null || !Settings.blockAds()) return;
            Iterator<?> it = results.iterator();
            while (it.hasNext()) {
                Object o = it.next();
                if (!(o instanceof c)) continue;
                String key = ((c) o).a();
                if (ADS_ENABLED.equals(key) || GOLD_BLOCK_ADS.equals(key)) it.remove();
            }
        } catch (Throwable t) {
            Log.w(TAG, "filterSearch failed", t);
        }
    }
}
