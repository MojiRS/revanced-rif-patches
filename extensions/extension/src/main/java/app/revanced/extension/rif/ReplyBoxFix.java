package app.revanced.extension.rif;

import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

/**
 * "Keep reply box above keyboard": rif's reply box is a floating dialog whose window is as
 * tall as its content. With the keyboard open, the dialog's views are never told about the
 * keyboard (the window's own insets stay empty), so the box, centred on the whole screen,
 * grows under the keyboard as you type.
 *
 * Fix: make the window full height (the layout rif inflated is already built for that: a
 * full-size root, the box centred in it, the box's contents in a ScrollView), then keep the
 * bottom padding of the dialog's content equal to how far the keyboard covers it. The box is
 * then centred in the space above the keyboard, its ScrollView is capped there, and the
 * edit text keeps the cursor in view by scrolling.
 *
 * Hooked right after rif's dialog base class sets the window's soft input mode in onStart,
 * which runs for every rif dialog, so only the reply box (the one with the
 * {@code scrollview_frame} view) is touched.
 */
public final class ReplyBoxFix {

    private static final String TAG = "RifReplyBoxFix";

    private ReplyBoxFix() {}

    public static void onDialogStart(Window window) {
        try {
            if (window == null) return;
            View decor = window.peekDecorView();
            if (decor == null) return;
            int id = decor.getResources().getIdentifier(
                    "scrollview_frame", "id", decor.getContext().getPackageName());
            if (id == 0 || decor.findViewById(id) == null) return;
            if (decor.getTag(id) != null) return; // onStart runs again after the app was in the background
            decor.setTag(id, Boolean.TRUE);

            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                // Keep the width rif has, so the box looks the same.
                window.setLayout(lp.width, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            final View content = decor.findViewById(android.R.id.content);
            if (content == null) return;
            final Rect visible = new Rect();
            final int[] loc = new int[2];
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                if (!content.isAttachedToWindow()) return;
                                content.getWindowVisibleDisplayFrame(visible);
                                content.getLocationOnScreen(loc);
                                int overlap = loc[1] + content.getHeight() - visible.bottom;
                                int pad = Math.max(0, overlap);
                                if (pad != content.getPaddingBottom()) {
                                    content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                                            content.getPaddingRight(), pad);
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "layout pass failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "onDialogStart failed", t);
        }
    }
}
