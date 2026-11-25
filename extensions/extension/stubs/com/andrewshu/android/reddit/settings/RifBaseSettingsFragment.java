package com.andrewshu.android.reddit.settings;

import androidx.preference.Preference;

/**
 * COMPILE-ONLY stub of rif's base settings fragment, packaged as ../libs/rif-stubs.jar
 * and wired in with compileOnly(), so it is never part of the extension .rve; rif
 * provides the real class at runtime. It deliberately extends Object so nothing here
 * references rif's R8-renamed androidx classes.
 *
 * Members that RevancedSettingsFragment uses are obfuscated differently per build of
 * rif 5.6.22, so the stub declares both:
 *   - E4() / t4(): the abstract "return the preference-XML resource id" method
 *     (free / Golden Platinum).
 *   - N4() / C4(): the protected hook rif calls from onViewCreated, after the preferences
 *     are inflated (free / Golden Platinum). Each build has only its own; the other name
 *     is an unused extra method there.
 *   - L4(key) / A4(key): findPreference(key), throwing NullPointerException when absent
 *     (free / Golden Platinum).
 *
 * The jar also holds the androidx Preference and SeekBarPreference stubs (stubs/androidx/preference/)
 * and the androidx Pair stub (stubs/androidx/core/util/c.java, used by ImageHosting).
 * Rebuild it after editing any of them (from extensions/extension/, JDK 17):
 *   javac --release 17 -cp "$ANDROID_HOME/platforms/android-34/android.jar" -d stubs-out \
 *       stubs/com/andrewshu/android/reddit/settings/RifBaseSettingsFragment.java \
 *       stubs/androidx/preference/Preference.java \
 *       stubs/androidx/preference/SeekBarPreference.java stubs/androidx/core/util/c.java
 *   jar cf libs/rif-stubs.jar -C stubs-out .
 */
public abstract class RifBaseSettingsFragment {
    protected abstract int E4();

    protected abstract int t4();

    protected void N4() {
    }

    protected void C4() {
    }

    protected final <T extends Preference> T L4(CharSequence key) {
        return null;
    }

    protected final <T extends Preference> T A4(CharSequence key) {
        return null;
    }
}
