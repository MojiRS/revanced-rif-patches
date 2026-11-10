package app.revanced.extension.rif;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;

/**
 * The "post as GIF (no sound)" row on the Submit screen's media tab (added to submit_fragment.xml
 * by the Reddit image hosting patch): a LinearLayout around one rif-styled switch. Hidden until a
 * video is chosen; photos and GIFs hide it again. The state (video chosen, switch on) survives
 * rotation through the view state. {@link ImageHosting#fixParams} reads {@link #asGif} to post the
 * video as a "videogif".
 */
public class PostAsGifView extends LinearLayout {

    private static final String TAG = "RifImageHosting";

    /** True while this row is on screen, a video is chosen and the switch is on. */
    static volatile boolean asGif;

    private static WeakReference<PostAsGifView> current = new WeakReference<>(null);

    private boolean video;
    private CompoundButton toggle;

    public PostAsGifView(Context context) {
        super(context);
    }

    public PostAsGifView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PostAsGifView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Hook (the Submit screen's upload/resize branch, main thread): the chosen file is a video or not. */
    static void mediaChosen(boolean isVideo) {
        PostAsGifView view = current.get();
        if (view != null) view.setVideo(isVideo);
    }

    private void setVideo(boolean isVideo) {
        video = isVideo;
        update();
    }

    private void update() {
        try {
            setVisibility(video ? View.VISIBLE : View.GONE);
            asGif = video && toggle != null && toggle.isChecked() && isAttachedToWindow();
        } catch (Throwable t) {
            Log.w(TAG, "post-as-GIF row update failed", t);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        try {
            View child = getChildCount() > 0 ? getChildAt(0) : null;
            if (child instanceof CompoundButton) {
                toggle = (CompoundButton) child;
                toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        update();
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "post-as-GIF row setup failed", t);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        current = new WeakReference<>(this);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (current.get() == this) {
            current = new WeakReference<>(null);
            asGif = false;
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        SavedState state = new SavedState(super.onSaveInstanceState());
        state.video = video;
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        SavedState saved = (SavedState) state;
        super.onRestoreInstanceState(saved.getSuperState());
        video = saved.video;
        update();
    }

    private static final class SavedState extends BaseSavedState {
        boolean video;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            video = in.readInt() != 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(video ? 1 : 0);
        }

        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
}
