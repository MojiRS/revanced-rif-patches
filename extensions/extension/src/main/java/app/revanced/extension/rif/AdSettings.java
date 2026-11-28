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
 * {@code C0}, isChecked {@code I0}, setChecked {@code J0}.
 */
public final class AdSettings {

    private static final String TAG = "RifAdSettings";
    private static final String ADS_ENABLED = "ADS_ENABLED";
    private static final String GOLD_BLOCK_ADS = "GOLD_BLOCK_ADS";
    private static final String REVOKE_ADS_GDPR_CONSENT = "REVOKE_ADS_GDPR_CONSENT";

    private AdSettings() {}

    /**
     * Start of RedditIsFunApplication.onCreate, before rif first loads its pro flag from the
     * stored ADS_ENABLED: write it true so pro features and the settings screens agree from launch.
     */
    public static void keepProEnabled(Context context) {
        try {
            Settings.init(context);
            if (!Settings.blockAds()) return;
            setProEnabled(context.getSharedPreferences("settings", Context.MODE_PRIVATE));
        } catch (Throwable t) {
            Log.w(TAG, "keepProEnabled failed", t);
        }
    }

    /**
     * Block ads was just switched on in our settings screen: store ADS_ENABLED true now, so pro
     * features (and the other screens' pro rows) are back without waiting for a restart or a visit
     * to General settings. rif reloads its in-memory pro flag from the stored value when its
     * settings activity pauses.
     */
    static void blockAdsSwitchedOn(SharedPreferences p) {
        try {
            if (p != null && Settings.blockAds()) setProEnabled(p);
        } catch (Throwable t) {
            Log.w(TAG, "blockAdsSwitchedOn failed", t);
        }
    }

    /** Writes ADS_ENABLED true unless it's stored true (a never-written key is not trusted to read as true everywhere). */
    private static void setProEnabled(SharedPreferences p) {
        if (!p.getBoolean(ADS_ENABLED, false)) p.edit().putBoolean(ADS_ENABLED, true).apply();
    }

    /**
     * Hook in GeneralSettingsFragment's post-inflate method; {@code fragment} is that fragment.
     * Hides both ad rows (setVisible, not removal: Moderator features and Hide ads depend on
     * ADS_ENABLED by key and a missing key crashes the screen) and makes sure ADS_ENABLED is on.
     * (Moderator features' "Requires ads enabled" summary is emptied in strings.xml by the patch.)
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
        } catch (Throwable t) {
            Log.w(TAG, "hideAdRows failed", t);
        }
    }

    /**
     * Hook at the start of SecurityPrivacySettingsFragment's onViewCreated (free); {@code fragment}
     * is that fragment. Hides "Revoke data consent for ads" while Block ads is on (nothing to
     * revoke: no ads load). setVisible, like the rows above.
     */
    public static void hideRevokeConsentRow(Object fragment) {
        try {
            if (!Settings.blockAds()) return;
            Preference revoke = ((androidx.preference.i) fragment).b0(REVOKE_ADS_GDPR_CONSENT);
            if (revoke != null) revoke.C0(false);
        } catch (Throwable t) {
            Log.w(TAG, "hideRevokeConsentRow failed", t);
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
                if (ADS_ENABLED.equals(key) || GOLD_BLOCK_ADS.equals(key) || REVOKE_ADS_GDPR_CONSENT.equals(key)) it.remove();
            }
        } catch (Throwable t) {
            Log.w(TAG, "filterSearch failed", t);
        }
    }
}
