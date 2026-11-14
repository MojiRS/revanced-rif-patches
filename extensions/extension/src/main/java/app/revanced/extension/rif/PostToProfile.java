package app.revanced.extension.rif;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import java.lang.ref.WeakReference;

/**
 * "Post to profile": a MY PROFILE button on rif's submit page, on the same line as PICK
 * SUBREDDIT. It fills {@code u_<account>} into the subreddit field exactly like a picker choice
 * does, so the rules and flair refresh as they do for any subreddit. The button itself is added
 * to the layouts by the patch (a copy of the pick button's look); this class wires it.
 *
 * Two hooks: {@link #install} at the end of the submit fragment's onCreateView, and
 * {@link #accountChanged} at the start of the method that writes the "posting as" line (called
 * from onCreateView and again when the account changes).
 */
public final class PostToProfile {

    private static final String TAG = "RifPostToProfile";

    /** The button of the submit page that is showing, for account changes. */
    private static WeakReference<View> current = new WeakReference<>(null);

    private PostToProfile() {}

    /**
     * Stub the patch rewrites to return the active account's name (rif's settings
     * {@code q0()}), null when logged out. The fragment is rif's submit fragment, passed as an
     * Object so this compiles without rif's classes.
     */
    public static String accountName(Object fragment) {
        return null;
    }

    /**
     * Stub the patch rewrites to call the submit fragment's "choose this subreddit" method (the
     * one the subreddit picker's result uses), made public by the patch.
     */
    public static void fill(Object fragment, String subreddit) {
    }

    /** End of the submit fragment's onCreateView: {@code root} is the page, {@code fragment} rif's submit fragment. */
    public static void install(final View root, final Object fragment) {
        try {
            if (root == null || fragment == null) return;
            Context context = root.getContext();
            int id = root.getResources().getIdentifier(
                    "rif_my_profile_button", "id", context.getPackageName());
            if (id == 0) return;
            final View button = root.findViewById(id);
            if (button == null) return;
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        String name = accountName(fragment);
                        if (name == null || name.trim().isEmpty()) {
                            v.setVisibility(View.GONE);
                            return;
                        }
                        InputMethodManager imm = (InputMethodManager)
                                v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                        fill(fragment, "u_" + name.trim());
                    } catch (Throwable t) {
                        Log.w(TAG, "fill failed", t);
                    }
                }
            });
            current = new WeakReference<>(button);
            update(button, fragment);
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
        }
    }

    /** Start of the "posting as" writer: shows or hides the button for the account now active. */
    public static void accountChanged(Object fragment) {
        try {
            View button = current.get();
            if (button != null && fragment != null) update(button, fragment);
        } catch (Throwable t) {
            Log.w(TAG, "accountChanged failed", t);
        }
    }

    private static void update(View button, Object fragment) {
        String name = accountName(fragment);
        boolean loggedIn = name != null && !name.trim().isEmpty();
        button.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
    }
}
