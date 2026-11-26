package androidx.preference;

/**
 * COMPILE-ONLY stub of androidx's TwoStatePreference as shipped in rif free (class name kept;
 * CheckBoxPreference and SwitchPreference extend it). Free's names only: I0 = isChecked,
 * J0 = setChecked (persists and notifies dependents). Used by AdSettings.
 */
public abstract class TwoStatePreference extends Preference {
    public boolean I0() {
        return false;
    }

    public void J0(boolean checked) {
    }
}
