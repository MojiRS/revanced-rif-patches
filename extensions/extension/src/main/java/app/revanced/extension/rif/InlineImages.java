package app.revanced.extension.rif;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.util.LruCache;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inline comment images (static + animated GIFs).
 *
 * Two injection points:
 *  - {@link #embed(SpannableStringBuilder)} is called from rif's
 *    CommentThing.e(...) on a background thread, before the comment body is
 *    cached/shown. It fetches each direct-image link, scales it, and overlays an
 *    ImageSpan. Animated GIFs (API 28+) decode to an AnimatedImageDrawable but
 *    are not started yet (no host view exists here).
 *  - {@link #attach(TextView)} is called from rif's comment ViewHolder bind
 *    (n2.o.h, right after setText) on the main thread. It wires each animated
 *    drawable's callback to the TextView and starts it, so frames invalidate
 *    only that TextView. Recycling to a different comment stops the drawables
 *    that left; an in-place rebind (e.g. a vote) leaves running GIFs alone.
 *
 * Multi-image imgur albums get an {@link AlbumImageSpan}: a fixed-size box showing one
 * image, with ◀ ▶ buttons and an "n/x" badge. Taps reach it through rif's own link
 * movement method (which dispatches any ClickableSpan); the tap position comes from a
 * touch listener installed by attach().
 *
 * Images are decoded for the full comment width, but replies are indented (narrower), so
 * attach() also shrinks each image to its TextView's actual text width once laid out.
 */
public final class InlineImages {

    private InlineImages() {}

    // Downloaded bytes cache (~24 MB), keyed by URL.
    private static final LruCache<String, byte[]> BYTES = new LruCache<String, byte[]>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    // Per-TextView animation state: the animatables shown there, and the Drawable.Callback
    // wired to them. Drawable.setCallback() only keeps a WeakReference, so this map is
    // what keeps the callback alive. The callback holds the TextView weakly, so values
    // never pin their (weak) keys.
    private static final WeakHashMap<TextView, Bound> RUNNING = new WeakHashMap<>();

    private static final class Bound {
        final Drawable.Callback callback;
        List<Animatable> anims = Collections.emptyList();

        Bound(TextView tv) {
            callback = new ViewCallback(tv);
        }
    }

    // Resolved page-link -> image URL (or "" = no image found), to avoid re-scraping.
    private static final LruCache<String, String> RESOLVED = new LruCache<>(256);

    private static final String TAG = "RifInlineImages";
    // Browser-like UA; some CDNs reject unusual agents. Used for images and pages.
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 rif-inline-images";
    private static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;
    private static final int MAX_HTML_BYTES = 256 * 1024;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    // rif's own imgur API client ID (already shipped in the app and sent by its other imgur
    // requests). Used to list an album's images.
    private static final String IMGUR_AUTH = "Client-ID 4d7e2f74f1a519c";
    // imgur.com/a/<id> or imgur.com/gallery/<id> (plain ids only).
    private static final Pattern IMGUR_ALBUM = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?imgur\\.com/(a|gallery)/([A-Za-z0-9]+)/?(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    // Album link -> its image URLs (empty list = not listable; fall back to og:image).
    private static final LruCache<String, List<String>> ALBUMS = new LruCache<>(128);

    // Last touch position per TextView (recorded by TOUCH_LISTENER), so an album span's
    // onClick knows which part of the image was tapped. Values never reference keys.
    private static final WeakHashMap<View, float[]> LAST_TOUCH = new WeakHashMap<>();
    // A long press on an inline image selects the comment (the click a tap on its text
    // performs: rif's onListItemClick). It's timed by our own timer (ImageLongPress), so its
    // delay is configurable, rather than by the TextView's long click. Per TextView: the
    // pending timer; whether it fired during the current touch; and whether the TextView's
    // own long click fired (when our delay is the longer one).
    private static final WeakHashMap<View, Runnable> PENDING_LONG_PRESS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> LONG_PRESSED = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> VIEW_LONG_PRESSED = new WeakHashMap<>();
    // Records the touch position and runs the long-press timer. Never consumes events, so
    // rif's own touch handling (its link movement method) runs as before. One exception: the
    // lift that ends a timed long press is turned into a cancel, since rif's movement method
    // would otherwise treat it as a tap and open the image.
    private static final View.OnTouchListener TOUCH_LISTENER = (v, event) -> {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                LONG_PRESSED.remove(v);
                VIEW_LONG_PRESSED.remove(v);
                LAST_TOUCH.put(v, new float[]{event.getX(), event.getY()});
                cancelImageLongPress(v);
                if (v instanceof TextView) ImageLongPress.start((TextView) v);
                break;
            case MotionEvent.ACTION_MOVE:
                float[] down = LAST_TOUCH.get(v);
                int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                if (down == null || Math.abs(event.getX() - down[0]) > slop
                        || Math.abs(event.getY() - down[1]) > slop) {
                    cancelImageLongPress(v);
                }
                break;
            case MotionEvent.ACTION_UP:
                cancelImageLongPress(v);
                LAST_TOUCH.put(v, new float[]{event.getX(), event.getY()});
                if (LONG_PRESSED.remove(v) != null) event.setAction(MotionEvent.ACTION_CANCEL);
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelImageLongPress(v);
                break;
        }
        return false;
    };
    // The TextView's own long click, on an image: consumed, so it doesn't start text
    // selection (the comment is selected by our timer instead). TextView then discards the
    // touch's lift itself. Elsewhere in the text, the long press behaves as usual.
    private static final View.OnLongClickListener LONG_CLICK = v -> {
        try {
            if (!Settings.longPressImageSelectsComment() || !(v instanceof TextView)) return false;
            TextView tv = (TextView) v;
            if (!tv.isClickable() || !onImage(tv)) return false;
            VIEW_LONG_PRESSED.put(tv, Boolean.TRUE);
            // If our timer already fired, leave the lift to TextView (which now discards it):
            // cancelling it too would leave TextView waiting to discard the next tap's lift.
            LONG_PRESSED.remove(tv);
            clearSelection(tv);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "image long press failed", t);
            return false;
        }
    };
    // Re-fits images after each layout of the TextView, as the space it gets can change
    // (e.g. a recycled row bound to a reply at a different depth). fitImages() is a no-op
    // when nothing changed. Posted: the re-layout it may cause can't run mid-layout.
    private static final View.OnLayoutChangeListener FITTER =
            (v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                if (v instanceof TextView) v.post(() -> fitImages((TextView) v));
            };

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    // <meta property="og:image[:url|:secure_url]" content="..."> in either attr order.
    private static final Pattern OG_PROP_FIRST = Pattern.compile(
            "<meta[^>]+property=[\"']og:image(?::url|:secure_url)?[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_CONTENT_FIRST = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image(?::url|:secure_url)?[\"']",
            Pattern.CASE_INSENSITIVE);

    // ---- background: embed images into the comment spannable -------------------

    public static void embed(SpannableStringBuilder body) {
        try {
            if (body == null) return;
            if (!Settings.inlineImages()) return; // feature disabled in settings
            if (Looper.myLooper() == Looper.getMainLooper()) return; // never block UI

            URLSpan[] links = body.getSpans(0, body.length(), URLSpan.class);
            if (links == null || links.length == 0) return;

            // Process in descending start order so inserting an image line for one
            // link doesn't shift the positions of links we haven't handled yet.
            List<URLSpan> ordered = new ArrayList<>(Arrays.asList(links));
            Collections.sort(ordered,
                    (a, b) -> Integer.compare(body.getSpanStart(b), body.getSpanStart(a)));

            for (URLSpan link : ordered) {
                try {
                    String pageUrl = link.getURL();
                    // Video links (comment videos, v.redd.it, imgur gifv/mp4) are InlineVideos'.
                    if (InlineVideos.embed(body, link)) continue;
                    // imgur albums are listed via the API (so multi-image albums can be
                    // cycled inline). Direct image links are used as-is; other known media
                    // hosts (imgur pages, redgifs, reddit galleries, ...) are resolved to
                    // their image via the page's og:image tag. Anything else stays a link.
                    List<String> album = imgurAlbumImages(pageUrl);
                    String imageUrl = album != null ? album.get(0) : resolveImageUrl(pageUrl);
                    if (imageUrl == null) continue;
                    boolean multiImage = album != null && album.size() > 1;

                    int start = body.getSpanStart(link);
                    int end = body.getSpanEnd(link);
                    if (start < 0 || end < 0 || start >= end) continue;

                    byte[] data = fetch(imageUrl);
                    if (data == null) continue;

                    Drawable drawable = toDrawable(data);
                    if (drawable == null) {
                        Log.w(TAG, "image decode failed: " + imageUrl);
                        continue;
                    }

                    String linkText = body.subSequence(start, end).toString();
                    if (linkText.equals(pageUrl) || isHideableLinkText(linkText)) {
                        // Bare URL, or a Reddit-app media marker like "[gif]": replace
                        // the link text with the image inline (hide the text).
                        // An image that starts its own line (at the top of the comment, or
                        // after a newline) needs the padded span: a plain ALIGN_BASELINE
                        // ImageSpan alone on a line is drawn shifted up by the font descent,
                        // overlapping (clipping) the bottom of the previous line.
                        boolean leading = startsLine(body, start);
                        if (multiImage) {
                            setAlbumSpan(body, new AlbumImageSpan(drawable, album, link, leading), start, end);
                        } else {
                            body.setSpan(
                                    leading ? new LeadingSpacedImageSpan(drawable)
                                            : new FitImageSpan(drawable),
                                    start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                        }
                    } else {
                        // [text](url) link: keep the visible text and render the image
                        // on its own line just below it (U+FFFC = object replacement).
                        // LeadingSpacedImageSpan adds ~1/3 line of space above the
                        // image so it doesn't crowd the link text, matching the gap
                        // used for an image directly under a comment header.
                        body.insert(end, "\n￼");
                        if (multiImage) {
                            setAlbumSpan(body, new AlbumImageSpan(drawable, album, link, true), end + 1, end + 2);
                        } else {
                            ImageSpan image = new LeadingSpacedImageSpan(drawable);
                            body.setSpan(image, end + 1, end + 2, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                            // The image sits outside the link span here, so make it open the
                            // link itself (bare-link images already sit on the link span).
                            setImageClickSpan(body, image, link, end + 1, end + 2);
                        }
                    }
                } catch (Throwable ignored) {
                    // leave this link as a plain link
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- main thread: start/stop GIF animation for a bound TextView ------------

    // TextViews with a RETEXT watcher (keys weak).
    private static final WeakHashMap<TextView, Boolean> WATCHED = new WeakHashMap<>();

    /**
     * Re-runs attach() when rif sets a watched TextView's text outside the bind we hook
     * (e.g. it re-sets a text post's body once its rendering, with our images, completes).
     */
    private static final class Retext implements TextWatcher {
        private final WeakReference<TextView> view;

        Retext(TextView tv) {
            view = new WeakReference<>(tv);
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            TextView tv = view.get();
            if (tv != null) tv.post(() -> attach(tv));
        }
    }

    public static void attach(TextView tv) {
        try {
            if (tv == null) return;
            if (WATCHED.put(tv, Boolean.TRUE) == null) tv.addTextChangedListener(new Retext(tv));

            Bound bound = RUNNING.get(tv);
            if (bound == null) bound = new Bound(tv);
            List<Animatable> prev = bound.anims;

            // Animatables shown in this TextView's current text. Each one is (re)pointed
            // at this view's callback, which makes this view its owner. Ones that are
            // already animating are NOT restarted, so an in-place rebind of the same
            // comment (e.g. a vote, which re-binds the row to update the score) or a
            // rebind into a different holder (change animations) keeps the GIF playing.
            CharSequence cs = tv.getText();
            List<Animatable> current = new ArrayList<>();
            if (cs instanceof Spanned) {
                Spanned sp = (Spanned) cs;
                if (sp.getSpans(0, sp.length(), FitImageSpan.class).length > 0) {
                    // Image taps and long presses need the touch position (rif's body
                    // TextViews have no touch or long-click listeners of their own).
                    tv.setOnTouchListener(TOUCH_LISTENER);
                    tv.setOnLongClickListener(LONG_CLICK);
                    // Fit to the current width now (usually right, as rows are recycled
                    // between comments), and again whenever the width changes.
                    tv.removeOnLayoutChangeListener(FITTER);
                    tv.addOnLayoutChangeListener(FITTER);
                    fitImages(tv);
                }
                for (ImageSpan span : sp.getSpans(0, sp.length(), ImageSpan.class)) {
                    Drawable d = span.getDrawable();
                    if (!(d instanceof Animatable)) continue;
                    Animatable anim = (Animatable) d;
                    current.add(anim);
                    if (d.getCallback() != bound.callback) d.setCallback(bound.callback);
                    if (!anim.isRunning()) anim.start();
                }
            }

            // Stop animatables from the previous bind that left this view (a recycle to a
            // different comment) — but only ones this view still owns. If another view
            // has since claimed one (same comment bound elsewhere), leave it running.
            for (Animatable a : prev) {
                if (current.contains(a)) continue;
                try {
                    Drawable d = (Drawable) a;
                    if (d.getCallback() != bound.callback) continue;
                    a.stop();
                    d.setCallback(null);
                } catch (Throwable ignored) {
                }
            }

            if (current.isEmpty()) {
                RUNNING.remove(tv);
            } else {
                bound.anims = current;
                RUNNING.put(tv, bound);
            }
        } catch (Throwable ignored) {
        }
        // Video overlays: added for this text's videos, removed for ones that left.
        InlineVideos.attach(tv);
    }

    /** Drawable.Callback that invalidates a TextView it holds only weakly. */
    private static final class ViewCallback implements Drawable.Callback {
        private final WeakReference<TextView> view;

        ViewCallback(TextView tv) {
            view = new WeakReference<>(tv);
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            TextView tv = view.get();
            if (tv != null) tv.invalidate();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            TextView tv = view.get();
            if (tv != null) tv.postDelayed(what, Math.max(0, when - SystemClock.uptimeMillis()));
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            TextView tv = view.get();
            if (tv != null) tv.removeCallbacks(what);
        }
    }

    // ---- imgur albums -----------------------------------------------------------

    /**
     * The image URLs of an imgur album/gallery link, via the imgur API (rif's client ID),
     * or null if the link isn't one or the album can't be listed (callers then fall back to
     * the og:image cover). Network; background thread only.
     */
    private static List<String> imgurAlbumImages(String url) {
        if (url == null) return null;
        Matcher m = IMGUR_ALBUM.matcher(url);
        if (!m.matches()) return null;
        List<String> cached = ALBUMS.get(url);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String id = m.group(2);
        List<String> images = listImgurAlbum("https://api.imgur.com/3/album/" + id);
        if (images == null && "gallery".equalsIgnoreCase(m.group(1))) {
            images = listImgurAlbum("https://api.imgur.com/3/gallery/album/" + id);
        }
        ALBUMS.put(url, images == null ? Collections.<String>emptyList() : images);
        return images;
    }

    private static List<String> listImgurAlbum(String apiUrl) {
        try {
            String json = fetchText(apiUrl, IMGUR_AUTH, "application/json", MAX_JSON_BYTES);
            if (json == null) return null;
            JSONArray items = new JSONObject(json).getJSONObject("data").optJSONArray("images");
            if (items == null || items.length() == 0) return null;
            List<String> urls = new ArrayList<>(items.length());
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String id = item.optString("id", "");
                String link = item.optString("link", "");
                // Videos (mp4) can't be drawn in a span; imgur serves animated items as GIFs.
                if (item.optString("type", "").startsWith("video/") && !id.isEmpty()) {
                    link = "https://i.imgur.com/" + id + ".gif";
                }
                if (!link.isEmpty()) urls.add(link);
            }
            return urls.isEmpty() ? null : Collections.unmodifiableList(urls);
        } catch (Throwable t) {
            Log.w(TAG, "imgur album listing failed: " + apiUrl + " (" + t + ")");
            return null;
        }
    }

    /** Sets an album image span plus its tap handler over [start, end). */
    private static void setAlbumSpan(SpannableStringBuilder body, AlbumImageSpan album, int start, int end) {
        body.setSpan(album, start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        setImageClickSpan(body, album, album.link, start, end);
    }

    /** Sets a tap handler for the inline [image] (which opens [link]) over [start, end). */
    static void setImageClickSpan(SpannableStringBuilder body, ImageSpan image, URLSpan link,
                                          int start, int end) {
        // Top priority so rif's movement method (which takes the first ClickableSpan under
        // the tap) picks this over a link span covering the same text.
        body.setSpan(new ImageClickSpan(image, link), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_PRIORITY);
    }

    /**
     * Where the last tap on [tv] fell across the image drawn by [span] (0 = left edge,
     * 1 = right edge; [width] = its drawn width), or NaN if it wasn't on the image. rif's
     * movement method maps a tap to the nearest character offset and fires any span touching
     * it, so taps on text next to an image (e.g. the start of the following line) also land
     * on its click span; those must be ignored.
     */
    private static float tapPosition(TextView tv, Object span, int width) {
        float[] touch = LAST_TOUCH.get(tv);
        Layout layout = tv.getLayout();
        CharSequence text = tv.getText();
        if (touch == null || layout == null || width <= 0 || !(text instanceof Spanned)) return Float.NaN;
        int start = ((Spanned) text).getSpanStart(span);
        if (start < 0) return Float.NaN;
        int line = layout.getLineForOffset(start);
        float y = touch[1] - tv.getTotalPaddingTop() + tv.getScrollY();
        if (y < layout.getLineTop(line) || y > layout.getLineBottom(line)) return Float.NaN;
        float left = layout.getPrimaryHorizontal(start) + tv.getTotalPaddingLeft() - tv.getScrollX();
        float x = (touch[0] - left) / width;
        return (x < 0f || x > 1f) ? Float.NaN : x;
    }

    private static void cancelImageLongPress(View v) {
        Runnable pending = PENDING_LONG_PRESS.remove(v);
        if (pending != null) v.removeCallbacks(pending);
    }

    /** rif's movement method highlights a link/image's text range on touch-down. */
    private static void clearSelection(TextView tv) {
        CharSequence text = tv.getText();
        if (text instanceof Spannable) Selection.removeSelection((Spannable) text);
    }

    /** Timer for a long press on an inline image; holds its TextView weakly. */
    private static final class ImageLongPress implements Runnable {
        private final WeakReference<TextView> view;

        private ImageLongPress(TextView tv) {
            view = new WeakReference<>(tv);
        }

        /** Starts the timer if the touch that just went down on [tv] is on an image. */
        static void start(TextView tv) {
            try {
                if (!Settings.longPressImageSelectsComment() || !tv.isClickable() || !onImage(tv)) return;
                ImageLongPress timer = new ImageLongPress(tv);
                PENDING_LONG_PRESS.put(tv, timer);
                tv.postDelayed(timer, Settings.longPressImageDelayMs());
            } catch (Throwable t) {
                Log.w(TAG, "image long press failed", t);
            }
        }

        @Override
        public void run() {
            try {
                TextView tv = view.get();
                if (tv == null || PENDING_LONG_PRESS.get(tv) != this) return;
                PENDING_LONG_PRESS.remove(tv);
                // Stop the TextView's own (pending) long click; if it already fired, the
                // TextView discards the lift itself, so don't also cancel it.
                tv.cancelLongPress();
                if (VIEW_LONG_PRESSED.get(tv) == null) LONG_PRESSED.put(tv, Boolean.TRUE);
                clearSelection(tv);
                tv.performClick();
            } catch (Throwable t) {
                Log.w(TAG, "image long press failed", t);
            }
        }
    }

    /** True if the last touch on [tv] landed on one of its inline images. */
    private static boolean onImage(TextView tv) {
        CharSequence text = tv.getText();
        if (!(text instanceof Spanned)) return false;
        for (FitImageSpan span : ((Spanned) text).getSpans(0, text.length(), FitImageSpan.class)) {
            if (!Float.isNaN(tapPosition(tv, span, span.width()))) return true;
        }
        return false;
    }

    /**
     * Shrinks [tv]'s inline images to fit its text width (or grows them back toward their
     * decoded size, if it got wider), re-laying out the text if any changed.
     */
    private static void fitImages(TextView tv) {
        try {
            int avail = availableTextWidth(tv);
            CharSequence text = tv.getText();
            if (avail <= 0 || !(text instanceof Spannable)) return;
            Spannable sp = (Spannable) text;
            boolean changed = false;
            for (FitImageSpan span : sp.getSpans(0, sp.length(), FitImageSpan.class)) {
                if (!span.fitWidth(avail)) continue;
                changed = true;
                // A span change makes the TextView re-flow (and re-draw) that range.
                sp.setSpan(span, sp.getSpanStart(span), sp.getSpanEnd(span), sp.getSpanFlags(span));
            }
            if (changed) {
                tv.requestLayout();
                tv.invalidate();
            }
        } catch (Throwable t) {
            Log.w(TAG, "image fit failed", t);
        }
    }

    /**
     * The widest [tv]'s text can be laid out. rif's comment body is wrap_content (inside a
     * wrap_content frame), so its own width just follows its content; the limit comes from
     * the nearest ancestor with a fixed or match_parent width, minus the paddings and
     * margins in between. 0 if not laid out yet.
     */
    private static int availableTextWidth(TextView tv) {
        int insets = tv.getTotalPaddingLeft() + tv.getTotalPaddingRight();
        View v = tv;
        while (v.getLayoutParams() != null
                && v.getLayoutParams().width == ViewGroup.LayoutParams.WRAP_CONTENT
                && v.getParent() instanceof ViewGroup) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                insets += mlp.leftMargin + mlp.rightMargin;
            }
            ViewGroup parent = (ViewGroup) v.getParent();
            insets += parent.getPaddingLeft() + parent.getPaddingRight();
            v = parent;
        }
        return v.getWidth() <= 0 ? 0 : v.getWidth() - insets;
    }

    /**
     * Inline image that can shrink to fit its comment's width: images are decoded for a
     * top-level comment, but replies are indented. Starts at its drawable's decoded size.
     */
    static class FitImageSpan extends ImageSpan {
        final int natW;
        final int natH;

        FitImageSpan(Drawable d) {
            super(d, ImageSpan.ALIGN_BASELINE);
            Rect b = d.getBounds();
            natW = Math.max(1, b.width());
            natH = Math.max(1, b.height());
        }

        /** Drawn width. */
        int width() {
            return getDrawable().getBounds().right;
        }

        /** Size for at most [maxW] wide, keeping the aspect ratio. */
        final int[] fittedSize(int maxW) {
            int w = Math.min(natW, maxW);
            int h = w == natW ? natH : Math.max(1, Math.round(natH * (float) w / natW));
            return new int[]{w, h};
        }

        /** Fits the image within [maxW]; true if its size changed. */
        boolean fitWidth(int maxW) {
            int[] size = fittedSize(maxW);
            Drawable d = getDrawable();
            Rect b = d.getBounds();
            if (b.width() == size[0] && b.height() == size[1]) return false;
            d.setBounds(0, 0, size[0], size[1]);
            return true;
        }
    }

    /**
     * Inline image for a multi-image album: a fixed-size box (sized from the first image,
     * so cycling never re-flows the comment) showing the current image fitted inside,
     * with ◀ ▶ buttons and an "n/x" badge. Extends ImageSpan so attach() animates GIFs.
     * index/current/loading are touched on the main thread only.
     */
    private static final class AlbumImageSpan extends FitImageSpan {
        final List<String> urls;
        final URLSpan link;
        final boolean leading;
        // The box: natW x natH (the first image's decoded size), shrunk by fitWidth().
        int boxW;
        int boxH;
        Drawable current;
        // current's decoded (unfitted) size.
        int currentW;
        int currentH;
        int index;
        boolean loading;

        AlbumImageSpan(Drawable first, List<String> urls, URLSpan link, boolean leading) {
            super(first);
            this.boxW = natW;
            this.boxH = natH;
            this.currentW = natW;
            this.currentH = natH;
            this.urls = urls;
            this.link = link;
            this.leading = leading;
            this.current = first;
        }

        @Override
        int width() {
            return boxW;
        }

        @Override
        boolean fitWidth(int maxW) {
            int[] size = fittedSize(maxW);
            if (boxW == size[0] && boxH == size[1]) return false;
            boxW = size[0];
            boxH = size[1];
            fitIntoBox(current, currentW, currentH);
            return true;
        }

        @Override
        public Drawable getDrawable() {
            return current;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                // Same metrics as the single-image spans (incl. LeadingSpacedImageSpan's gap).
                int pad = 0;
                if (leading) {
                    Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
                    pad = Math.round((pfm.descent - pfm.ascent) / 3f);
                }
                fm.ascent = -boxH - pad;
                fm.top = fm.ascent;
                fm.descent = 0;
                fm.bottom = 0;
            }
            return boxW;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            // Placement identical to DynamicDrawableSpan (ALIGN_BASELINE) for a box this size.
            // Not using its cached drawable, since ours changes.
            int transY = bottom - boxH - paint.getFontMetricsInt().descent;
            canvas.save();
            canvas.translate(x, transY);
            current.draw(canvas);
            drawControls(canvas);
            canvas.restore();
        }

        private void drawControls(Canvas canvas) {
            Resources res = Resources.getSystem();
            float density = res.getDisplayMetrics().density;
            float margin = 6 * density;

            // "n/x" badge, top-right.
            String label = (index + 1) + "/" + urls.size();
            BADGE_TEXT.setTextSize(12 * density);
            float textW = BADGE_TEXT.measureText(label);
            float padX = 6 * density;
            float badgeH = 20 * density;
            RectF badge = new RectF(boxW - margin - textW - 2 * padX, margin, boxW - margin, margin + badgeH);
            canvas.drawRoundRect(badge, badgeH / 2, badgeH / 2, SCRIM);
            Paint.FontMetrics tfm = BADGE_TEXT.getFontMetrics();
            canvas.drawText(label, badge.left + padX,
                    badge.centerY() - (tfm.ascent + tfm.descent) / 2, BADGE_TEXT);

            // ◀ ▶ buttons, vertically centered on the left/right edges (skipped if tiny, or
            // if inline album navigation is turned off — the badge stays either way).
            if (!Settings.inlineAlbumNavigation()) return;
            float r = 16 * density;
            if (boxW < 6 * r || boxH < 3 * r) return;
            float cy = boxH / 2f;
            drawArrow(canvas, margin + r, cy, r, -1);
            drawArrow(canvas, boxW - margin - r, cy, r, +1);
        }

        private static void drawArrow(Canvas canvas, float cx, float cy, float r, int dir) {
            canvas.drawCircle(cx, cy, r, SCRIM);
            float s = r * 0.4f;
            Path chevron = new Path();
            chevron.moveTo(cx - dir * s * 0.5f, cy - s);
            chevron.lineTo(cx + dir * s * 0.5f, cy);
            chevron.lineTo(cx - dir * s * 0.5f, cy + s);
            ARROW.setStrokeWidth(r * 0.18f);
            canvas.drawPath(chevron, ARROW);
        }

        /** Shows the next (+1) / previous (-1) image, wrapping; loads it in the background. */
        void step(TextView tv, int direction) {
            if (loading) return;
            int count = urls.size();
            final int target = ((index + direction) % count + count) % count;
            final String url = urls.get(target);
            final WeakReference<TextView> view = new WeakReference<>(tv);
            loading = true;
            final String prefetchUrl = urls.get(((target + direction) % count + count) % count);
            new Thread(() -> {
                Drawable loaded = null;
                try {
                    byte[] data = fetch(url);
                    if (data != null) loaded = toDrawable(data);
                } catch (Throwable ignored) {
                }
                final Drawable result = loaded;
                MAIN.post(() -> {
                    loading = false;
                    if (result == null) {
                        Log.w(TAG, "album image failed to load: " + url);
                        return;
                    }
                    Rect natural = result.getBounds();
                    currentW = Math.max(1, natural.width());
                    currentH = Math.max(1, natural.height());
                    fitIntoBox(result, currentW, currentH);
                    current = result;
                    index = target;
                    TextView tvNow = view.get();
                    CharSequence text = tvNow == null ? null : tvNow.getText();
                    // Only touch the view if it still shows this span (rows get recycled);
                    // otherwise the next bind picks the new image up via getDrawable().
                    if (text instanceof Spannable && ((Spannable) text).getSpanStart(this) >= 0) {
                        // rif's body text is selectable, so TextView draws it through cached
                        // per-block display lists that a plain invalidate() reuses; our draw()
                        // wouldn't run again. Re-setting the span is a span change, which makes
                        // the TextView re-record that range with the new image.
                        Spannable sp = (Spannable) text;
                        sp.setSpan(this, sp.getSpanStart(this), sp.getSpanEnd(this), sp.getSpanFlags(this));
                        attach(tvNow); // stops the old image's animation, starts a GIF's
                        tvNow.invalidate();
                    }
                });
                // After showing it: warm the byte cache for the next image in the same
                // direction, so the following tap is near-instant.
                try {
                    fetch(prefetchUrl);
                } catch (Throwable ignored) {
                }
            }, "RifAlbumImage").start();
        }

        /** Centers [d] (decoded size w x h) in the box, scaled to fit. */
        private void fitIntoBox(Drawable d, int w, int h) {
            float scale = Math.min((float) boxW / w, (float) boxH / h);
            int fw = Math.max(1, Math.round(w * scale)), fh = Math.max(1, Math.round(h * scale));
            int left = (boxW - fw) / 2, top = (boxH - fh) / 2;
            d.setBounds(left, top, left + fw, top + fh);
        }
    }

    /**
     * Tap handler over an inline image. A plain image opens its link (rif's usual popup /
     * viewer) wherever it's tapped. An album image: left third = previous image, right
     * third = next, middle = open the album via its link.
     */
    private static final class ImageClickSpan extends ClickableSpan {
        private final ImageSpan image;
        private final URLSpan link;

        ImageClickSpan(ImageSpan image, URLSpan link) {
            this.image = image;
            this.link = link;
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            // No link styling: the image itself is drawn by its ImageSpan.
        }

        private int width() {
            if (image instanceof FitImageSpan) return ((FitImageSpan) image).width();
            Drawable d = image.getDrawable();
            return d == null ? 0 : d.getBounds().right;
        }

        @Override
        public void onClick(View widget) {
            try {
                if (!(widget instanceof TextView)) return;
                TextView tv = (TextView) widget;
                // rif's body text is selectable, so its movement method highlighted this span's
                // range on touch-down. rif's own links open a dialog that clears it; we don't,
                // and a lingering selection makes the next tap only clear it. Clear it here.
                CharSequence text = tv.getText();
                if (text instanceof Spannable) Selection.removeSelection((Spannable) text);

                float x = tapPosition(tv, image, width());
                // Not on the image (a tap on nearby text that resolved to this span): do
                // nothing, so the tap behaves like any other text tap (rif selects the comment).
                if (Float.isNaN(x)) return;

                // The body TextView is itself clickable (onClick="onListItemClick", which
                // selects/highlights the comment and re-binds rows). That click was already
                // queued for this tap; drop it so a tap on the image only does the image action.
                tv.cancelPendingInputEvents();

                // Album tap zones only with inline album navigation on; otherwise an album
                // behaves like any other inline image.
                // A video that isn't autoplaying starts playing inline on its first tap.
                if (image instanceof InlineVideos.VideoSpan
                        && InlineVideos.onTap(tv, (InlineVideos.VideoSpan) image)) {
                    return;
                }

                boolean navigate = image instanceof AlbumImageSpan && Settings.inlineAlbumNavigation();
                if (navigate && x < 0.33f) {
                    ((AlbumImageSpan) image).step(tv, -1);
                } else if (navigate && x > 0.67f) {
                    ((AlbumImageSpan) image).step(tv, +1);
                } else {
                    link.onClick(tv); // open the image/album the usual way
                }
            } catch (Throwable t) {
                Log.w(TAG, "image tap failed", t);
            }
        }
    }

    private static final Paint SCRIM = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint ARROW = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint BADGE_TEXT = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        SCRIM.setColor(0x99000000);
        ARROW.setColor(0xFFFFFFFF);
        ARROW.setStyle(Paint.Style.STROKE);
        ARROW.setStrokeCap(Paint.Cap.ROUND);
        ARROW.setStrokeJoin(Paint.Join.ROUND);
        BADGE_TEXT.setColor(0xFFFFFFFF);
    }

    // ---- decoding --------------------------------------------------------------

    private static Drawable toDrawable(byte[] data) {
        // GIF and WebP go through ImageDecoder (API 28+), which yields an
        // AnimatedImageDrawable for animated content and a static drawable otherwise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && (isGif(data) || isWebp(data))) {
            Drawable animated = decodeAnimated(data);
            if (animated != null) return animated;
        }
        Bitmap bmp = decodeScaled(data);
        if (bmp == null) return null;
        BitmapDrawable bd = new BitmapDrawable(Resources.getSystem(), bmp);
        bd.setBounds(0, 0, bmp.getWidth(), bmp.getHeight());
        return bd;
    }

    private static Drawable decodeAnimated(byte[] data) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(ByteBuffer.wrap(data));
            Drawable d = ImageDecoder.decodeDrawable(src, new ImageDecoder.OnHeaderDecodedListener() {
                @Override
                public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
                                            ImageDecoder.Source source) {
                    Size size = info.getSize();
                    int[] out = outSize(size.getWidth(), size.getHeight());
                    decoder.setTargetSize(out[0], out[1]);
                }
            });
            d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decodeScaled(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int[] out = outSize(bounds.outWidth, bounds.outHeight);
        int outW = out[0], outH = out[1];

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, outW);
        Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (decoded == null) return null;
        if (decoded.getWidth() == outW && decoded.getHeight() == outH) return decoded;

        Bitmap scaled = Bitmap.createScaledBitmap(decoded, outW, outH, true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }

    /**
     * Target on-screen size for an image of native size w x h. With "scale to fit"
     * on, fill the comment width (up- or down-scaling). With it off, keep native
     * size, only downscaling images wider than the comment. Height is always capped.
     */
    static int[] outSize(int w, int h) {
        if (w <= 0 || h <= 0) return new int[]{Math.max(1, w), Math.max(1, h)};
        int targetW = targetWidth();
        int maxH = maxHeight();
        int outW, outH;
        if (Settings.scaleInlineImages() || w > targetW) {
            outW = targetW;
            outH = Math.round(h * ((float) targetW / (float) w));
        } else {
            outW = w;
            outH = h;
        }
        if (outH > maxH) {
            outH = maxH;
            outW = Math.round(w * ((float) maxH / (float) h));
        }
        return new int[]{Math.max(1, outW), Math.max(1, outH)};
    }

    // ---- helpers ---------------------------------------------------------------

    private static int targetWidth() {
        Resources res = Resources.getSystem();
        return Math.max(1, res.getDisplayMetrics().widthPixels - dp(res, 24));
    }

    private static int maxHeight() {
        return Resources.getSystem().getDisplayMetrics().widthPixels * 2;
    }

    private static boolean isGif(byte[] data) {
        // "GIF8" magic.
        return data != null && data.length >= 4
                && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8';
    }

    private static boolean isWebp(byte[] data) {
        // RIFF....WEBP. ImageDecoder animates it if it's an animated WebP.
        return data != null && data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private static boolean isDirectImage(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        int cut = u.indexOf('?');
        if (cut >= 0) u = u.substring(0, cut);
        cut = u.indexOf('#');
        if (cut >= 0) u = u.substring(0, cut);

        if (u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png")
                || u.endsWith(".webp") || u.endsWith(".gif") || u.endsWith(".bmp")) {
            return true;
        }
        return u.startsWith("https://i.redd.it/") || u.startsWith("https://preview.redd.it/");
    }

    /**
     * Maps a comment link to an image URL to embed, or null to leave it as a link.
     * Direct image links pass through; known media-host page links are resolved to
     * their image via the page's og:image meta tag (with a small result cache).
     */
    private static String resolveImageUrl(String url) {
        if (url == null) return null;
        if (isDirectImage(url)) return url;

        // Giphy has a clean id -> animated-GIF URL mapping; prefer it over scraping.
        String giphy = giphyGifUrl(url);
        if (giphy != null) return giphy;

        if (!isResolvableHost(url)) return null;

        String cached = RESOLVED.get(url);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String image = fetchOgImage(url);
        RESOLVED.put(url, image == null ? "" : image);
        return image;
    }

    /**
     * Maps a Giphy link to its animated-GIF media URL, or null if not Giphy. The id
     * is the last '-' segment of a /gifs/ slug, or the segment before /giphy.* on a
     * media host.
     */
    private static String giphyGifUrl(String url) {
        try {
            String u = url.toLowerCase(Locale.US);
            String id = null;
            int gifs = u.indexOf("giphy.com/gifs/");
            if (gifs >= 0) {
                String path = url.substring(gifs + "giphy.com/gifs/".length());
                int cut = indexOfAny(path, "/?#");
                if (cut >= 0) path = path.substring(0, cut);
                int dash = path.lastIndexOf('-');
                id = dash >= 0 ? path.substring(dash + 1) : path;
            } else if (u.contains(".giphy.com/media/")) {
                int g = url.indexOf("/giphy.");
                if (g >= 0) {
                    String before = url.substring(0, g);
                    int s = before.lastIndexOf('/');
                    if (s >= 0) id = before.substring(s + 1);
                }
            }
            if (id == null || id.isEmpty() || !id.matches("[A-Za-z0-9]+")) return null;
            return "https://media.giphy.com/media/" + id + "/giphy.gif";
        } catch (Throwable t) {
            return null;
        }
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int idx = s.indexOf(chars.charAt(i));
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        return best;
    }

    private static boolean isResolvableHost(String url) {
        String u = url.toLowerCase(Locale.US);
        return u.startsWith("https://imgur.com/")
                || u.startsWith("https://www.imgur.com/")
                || u.startsWith("https://m.imgur.com/")
                || u.startsWith("https://redgifs.com/")
                || u.startsWith("https://www.redgifs.com/")
                || u.startsWith("https://gfycat.com/")
                || u.startsWith("https://www.gfycat.com/")
                || u.contains("giphy.com/")
                || u.contains("tenor.com/view/")
                || u.contains("reddit.com/gallery/");
    }

    private static String fetchOgImage(String pageUrl) {
        try {
            String html = fetchText(pageUrl);
            if (html == null) return null;
            Matcher m = OG_PROP_FIRST.matcher(html);
            if (!m.find()) {
                m = OG_CONTENT_FIRST.matcher(html);
                if (!m.find()) return null;
            }
            String image = decodeHtmlEntities(m.group(1));
            if (image == null || image.isEmpty()) return null;
            if (image.startsWith("//")) image = "https:" + image;
            return image;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fetchText(String url) {
        // og tags live in <head>, so a truncated page is fine.
        return fetchText(url, null, "text/html,application/xhtml+xml", MAX_HTML_BYTES);
    }

    /** GETs a text resource (truncated at maxBytes); null on any non-200 or error. */
    static String fetchText(String url, String authorization, String accept, int maxBytes) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", accept);
            if (authorization != null) conn.setRequestProperty("Authorization", authorization);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                if (authorization != null) Log.w(TAG, "api HTTP " + code + ": " + url);
                return null;
            }

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total > maxBytes) break;
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String decodeHtmlEntities(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&#x26;", "&")
                .replace("&#x2F;", "/")
                .replace("&#47;", "/");
    }

    private static byte[] fetch(String url) {
        byte[] cached = BYTES.get(url);
        if (cached != null) return cached;
        byte[] data = download(url);
        // Downloads may exceed the cache size (up to MAX_DOWNLOAD_BYTES); putting such an
        // entry would evict everything else and then itself, so skip caching big ones.
        if (data != null && data.length <= BYTES.maxSize() / 4) BYTES.put(url, data);
        return data;
    }

    private static byte[] download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "image/*");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "image fetch HTTP " + code + ": " + url);
                return null;
            }

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_DOWNLOAD_BYTES) {
                    Log.w(TAG, "image exceeds " + MAX_DOWNLOAD_BYTES + " bytes: " + url);
                    return null;
                }
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "image fetch error: " + url + " (" + t + ")");
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int sampleSize(int srcW, int targetW) {
        int sample = 1;
        int w = srcW;
        while (w / 2 >= targetW) {
            w /= 2;
            sample *= 2;
        }
        return sample;
    }

    private static int dp(Resources res, int value) {
        return Math.round(value * res.getDisplayMetrics().density);
    }

    /** True if only whitespace separates [start] from the start of its line (or the text). */
    static boolean startsLine(CharSequence cs, int start) {
        for (int i = start - 1; i >= 0; i--) {
            char c = cs.charAt(i);
            if (c == '\n') return true;
            if (!Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /**
     * Link display text that is just a media marker (e.g. the Reddit app renders a
     * gif upload as a "[gif]" link). For these we hide the text and show the image
     * inline rather than keeping the marker visible.
     */
    static boolean isHideableLinkText(String text) {
        if (text == null) return false;
        return text.trim().equalsIgnoreCase("[gif]");
    }

    /**
     * ImageSpan that reserves ~1/3 of a text line of extra space above the image
     * via the line ascent. Used only for a leading image so it sits a little
     * below the comment header instead of crowding it; the image itself stays
     * bottom-aligned (inherited draw), so the padding lands above it.
     */
    private static final class LeadingSpacedImageSpan extends FitImageSpan {
        LeadingSpacedImageSpan(Drawable d) {
            super(d);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            Rect bounds = getDrawable().getBounds();
            if (fm != null) {
                Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
                int pad = Math.round((pfm.descent - pfm.ascent) / 3f);
                fm.ascent = -bounds.bottom - pad;
                fm.top = fm.ascent;
                fm.descent = 0;
                fm.bottom = 0;
            }
            return bounds.right;
        }
    }
}
