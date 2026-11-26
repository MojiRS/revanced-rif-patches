package androidx.preference;

/**
 * COMPILE-ONLY stub of androidx's Preference as shipped in rif (class name kept), so the
 * settings fragment can grey preferences in and out. Packaged into ../../libs/rif-stubs.jar
 * (rebuild command in RifBaseSettingsFragment's header); rif provides the real class at runtime.
 *
 * setEnabled(boolean) is R8-renamed differently per build, so both names are declared:
 *   - m0  free
 *   - k0  Golden Platinum
 * Only call the one of the running build (RevancedSettingsFragment knows which from the hook
 * rif called); the other is a different method there.
 *
 * C0 (setVisible) and y0 (setSummary) are free's names only (AdSettings, free-only code).
 */
public class Preference {
    public void m0(boolean enabled) {
    }

    public void k0(boolean enabled) {
    }

    public final void C0(boolean visible) {
    }

    public void y0(CharSequence summary) {
    }
}
