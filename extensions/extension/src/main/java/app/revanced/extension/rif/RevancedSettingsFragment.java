package app.revanced.extension.rif;

import android.content.Context;

import com.andrewshu.android.reddit.settings.RifBaseSettingsFragment;

/**
 * The "ReVanced" settings screen. It extends rif's own base settings fragment
 * (whose class name R8 keeps), so it gets rif's native preference styling and
 * XML-loading for free. We only override the obfuscated E4(), which returns the
 * preference-XML resource id; we look ours up by name at runtime so the extension
 * never has to reference a patch-generated R constant or any renamed androidx class.
 *
 * The actual preferences are added to res/xml/revanced_preferences.xml by each
 * patch's resource patch, so this screen is a shared, extensible framework.
 */
public class RevancedSettingsFragment extends RifBaseSettingsFragment {

    @Override
    protected int E4() {
        try {
            Context ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return ctx.getResources()
                    .getIdentifier("revanced_preferences", "xml", ctx.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }
}
