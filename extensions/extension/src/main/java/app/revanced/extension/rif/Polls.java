package app.revanced.extension.rif;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TabStopSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reddit polls (part of "Inline comment images"): rif doesn't parse {@code poll_data}, so a poll
 * post shows only its text and the "View Poll" link Reddit appends to it
 * ({@code reddit.com/poll/<post id>}), which rif opens in its browser, and Reddit redirects that
 * back to the post (a loop).
 *
 * <p>On the comments page {@link #bind} (hooked after the post body's setText) finds that link,
 * fetches the post's JSON once in the background ({@code /comments/<id>.json?limit=1}, with rif's
 * token and user agent) and shows a read-only block of text under the link's line: a header
 * ("Poll · 2,048 votes · ends in 3 days") and one line per option, with percentages, counts and
 * bars when Reddit returns them (once the poll ended or the user voted). The block only lives in
 * the TextView's copy of the text, never in rif's cached body, so quoting, copying and editing the
 * post see rif's text. Never in the feed. A tap on the link is handled by {@link ShareLinks}, which
 * calls {@link #onLinkTap}.
 */
public final class Polls {

    private static final String TAG = "RifPolls";
    /** Shown data older than this is fetched again (in the background) on the next bind. */
    private static final long STALE_MS = 2 * 60_000;
    /** A failed fetch is retried on a bind after this long. */
    private static final long RETRY_MS = 2 * 60_000;
    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 10000;

    static final int TAP_UNKNOWN = 0; // no block for this poll on screen: open the post
    static final int TAP_HANDLED = 1; // scrolled to it, or will once it loads
    static final int TAP_FAILED = 2;  // its fetch failed: rif's own behaviour

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RifPolls");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** What's known about one poll (by post id). */
    private static final class Poll {
        PollModel model;
        long fetchedAt;
        long failedAt;
        boolean inFlight;
        int version;
        /** Link taps waiting for the fetch: true = shown, false = failed. */
        final List<Callback> waiters = new ArrayList<>();
    }

    interface Callback {
        void done(boolean shown);
    }

    /** Main thread only. */
    private static final Map<String, Poll> CACHE = new LinkedHashMap<String, Poll>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Poll> eldest) {
            return size() > 32;
        }
    };
    /** Comments-page post bodies showing a poll link, and the poll's id (keys weak; main thread). */
    private static final WeakHashMap<TextView, String> BOUND = new WeakHashMap<>();

    private Polls() {}

    /** Stub the patch rewrites to return rif's user agent (as Spoof client leaves it). */
    static String rifUserAgent() {
        return null;
    }

    /** Main thread, right after rif sets the comments-page post body. */
    public static void bind(TextView tv) {
        try {
            if (tv == null) return;
            String id = findPollId(tv.getText());
            if (id == null) {
                BOUND.remove(tv);
                return;
            }
            BOUND.put(tv, id);
            show(tv, id);
            Poll e = CACHE.get(id);
            long now = SystemClock.elapsedRealtime();
            if (e == null || (!e.inFlight && (e.model == null
                    ? now - e.failedAt >= RETRY_MS
                    : now - e.fetchedAt >= STALE_MS))) {
                fetch(id);
            }
        } catch (Throwable t) {
            Log.w(TAG, "bind failed", t);
        }
    }

    /**
     * Main thread, from InlineImages.attach (rif or our image code set the body's text again
     * outside the bind, e.g. once an image loads): put the block back if the text lost it.
     */
    static void reattach(TextView tv) {
        try {
            String id = BOUND.get(tv);
            if (id == null) return;
            if (!id.equals(findPollId(tv.getText()))) {
                BOUND.remove(tv);
                return;
            }
            show(tv, id);
        } catch (Throwable t) {
            Log.w(TAG, "reattach failed", t);
        }
    }

    /**
     * Main thread: a tap on a poll link reached rif's router (after the external-browser rules).
     * Scrolls to that poll's block if a post body on screen shows it, waits for it if it's still
     * loading, or reports that rif should handle it.
     */
    static int onLinkTap(String id, Runnable fallback) {
        TextView tv = visibleBody(id);
        if (tv == null) return TAP_UNKNOWN;
        if (block(tv.getText()) != null) {
            scrollTo(tv);
            return TAP_HANDLED;
        }
        Poll e = CACHE.get(id);
        if (e != null && e.inFlight) {
            e.waiters.add(shown -> {
                if (shown) {
                    TextView v = visibleBody(id);
                    if (v != null) scrollTo(v);
                } else {
                    fallback.run();
                }
            });
            return TAP_HANDLED;
        }
        return TAP_FAILED;
    }

    private static TextView visibleBody(String id) {
        for (Map.Entry<TextView, String> b : new ArrayList<>(BOUND.entrySet())) {
            TextView tv = b.getKey();
            if (tv == null || !id.equals(b.getValue())) continue;
            if (tv.isAttachedToWindow() && tv.isShown() && tv.getWindowVisibility() == View.VISIBLE) return tv;
        }
        return null;
    }

    private static void scrollTo(TextView tv) {
        tv.post(() -> {
            try {
                Layout layout = tv.getLayout();
                Spanned text = (Spanned) tv.getText();
                BlockSpan b = block(text);
                if (layout == null || b == null) return;
                int first = layout.getLineForOffset(Math.min(text.length(), text.getSpanStart(b) + 2));
                int last = layout.getLineForOffset(Math.max(0, text.getSpanEnd(b) - 1));
                int top = tv.getTotalPaddingTop();
                Rect r = new Rect(0, top + layout.getLineTop(first), tv.getWidth(), top + layout.getLineBottom(last));
                tv.requestRectangleOnScreen(r, false);
            } catch (Throwable t) {
                Log.w(TAG, "scroll failed", t);
            }
        });
    }

    private static String findPollId(CharSequence text) {
        if (!(text instanceof Spanned)) return null;
        Spanned sp = (Spanned) text;
        for (URLSpan u : sp.getSpans(0, sp.length(), URLSpan.class)) {
            String id = PollModel.pollId(u.getURL());
            if (id != null) return id;
        }
        return null;
    }

    private static URLSpan findPollLink(Spanned sp, String id) {
        for (URLSpan u : sp.getSpans(0, sp.length(), URLSpan.class)) {
            if (id.equals(PollModel.pollId(u.getURL()))) return u;
        }
        return null;
    }

    private static BlockSpan block(CharSequence text) {
        if (!(text instanceof Spanned)) return null;
        Spanned sp = (Spanned) text;
        BlockSpan[] b = sp.getSpans(0, sp.length(), BlockSpan.class);
        return b.length == 0 ? null : b[0];
    }

    // ---- fetching ---------------------------------------------------------------------------

    private static void fetch(String id) {
        Poll e = CACHE.get(id);
        if (e == null) {
            e = new Poll();
            CACHE.put(id, e);
        }
        if (e.inFlight) return;
        e.inFlight = true;
        final Context ctx = Settings.context();
        EXECUTOR.execute(() -> {
            PollModel model = null;
            try {
                if (ctx != null) model = load(ctx, id);
            } catch (Throwable t) {
                Log.w(TAG, "poll " + id + ": " + t);
            }
            final PollModel m = model;
            MAIN.post(() -> loaded(id, m));
        });
    }

    /** Background: the poll of post [id], or null. */
    private static PollModel load(Context ctx, String id) throws Exception {
        try {
            String ua = rifUserAgent();
            if (ua != null) RedditApi.setUserAgent(ua);
        } catch (Throwable t) {
            Log.w(TAG, "user agent: " + t);
        }
        String path = "/comments/" + id + ".json?limit=1&raw_json=1";
        RedditApi.Resp r;
        try {
            r = RedditApi.authed(ctx, RedditApi.API + path, null, CONNECT_MS, READ_MS);
        } catch (RedditApi.LoggedOut lo) {
            r = RedditApi.anonymous(ctx, "https://www.reddit.com" + path, CONNECT_MS, READ_MS);
        }
        if (r.code != 200) {
            Log.w(TAG, "poll " + id + ": HTTP " + r.code);
            return null;
        }
        PollModel m = PollModel.parse(r.body);
        if (m == null) Log.w(TAG, "poll " + id + ": no poll_data");
        return m;
    }

    private static void loaded(String id, PollModel model) {
        try {
            Poll e = CACHE.get(id);
            if (e == null) {
                e = new Poll();
                CACHE.put(id, e);
            }
            e.inFlight = false;
            long now = SystemClock.elapsedRealtime();
            if (model != null) {
                e.model = model;
                e.fetchedAt = now;
                e.failedAt = 0;
                e.version++;
            } else {
                e.failedAt = now;
            }
            for (Map.Entry<TextView, String> b : new ArrayList<>(BOUND.entrySet())) {
                TextView tv = b.getKey();
                if (tv != null && id.equals(b.getValue()) && tv.isAttachedToWindow()) show(tv, id);
            }
            List<Callback> waiters = new ArrayList<>(e.waiters);
            e.waiters.clear();
            boolean shown = e.model != null;
            for (Callback c : waiters) {
                try {
                    c.done(shown);
                } catch (Throwable t) {
                    Log.w(TAG, "tap callback failed", t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "loaded failed", t);
        }
    }

    // ---- the block ------------------------------------------------------------------------

    /** Puts the poll's block in [tv]'s text, or brings it up to date; no-op if it's current. */
    private static void show(TextView tv, String id) {
        Poll e = CACHE.get(id);
        if (e == null || e.model == null) return;
        CharSequence cur = tv.getText();
        if (!(cur instanceof Spanned)) return;
        BlockSpan old = block(cur);
        if (old != null && old.id.equals(id) && old.version == e.version) return;
        URLSpan link = findPollLink((Spanned) cur, id);
        if (link == null) return;

        SpannableStringBuilder ssb = new SpannableStringBuilder(cur);
        if (old != null) removeBlock(ssb, old);
        int linkEnd = ssb.getSpanEnd(link);
        if (linkEnd < 0) return;
        int at = linkEnd;
        while (at < ssb.length() && ssb.charAt(at) != '\n') at++;

        SpannableStringBuilder block = build(tv, e.model, id, e.version);
        insertKeepingSpans(ssb, at, block);
        // The body is wrap_content: the bars span the TextView's width, so it takes the full
        // width while it shows a poll (only the text box's empty right side changes).
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp != null && lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            tv.setLayoutParams(lp);
        }
        tv.setText(ssb);
    }

    private static void removeBlock(SpannableStringBuilder ssb, BlockSpan b) {
        int start = ssb.getSpanStart(b), end = ssb.getSpanEnd(b);
        if (start < 0 || end < start) return;
        for (Object o : ssb.getSpans(start, end, Object.class)) {
            if (o instanceof Ours) ssb.removeSpan(o);
        }
        ssb.delete(start, end);
    }

    /**
     * Inserts [block] at [at] without stretching the body's own spans over it: a span that ended
     * or started exactly at [at] keeps its range (SpannableStringBuilder would extend inclusive
     * ones over inserted text).
     */
    private static void insertKeepingSpans(SpannableStringBuilder ssb, int at, CharSequence block) {
        Object[] spans = ssb.getSpans(at, at, Object.class);
        int n = spans.length;
        int[] starts = new int[n], ends = new int[n], flags = new int[n];
        for (int i = 0; i < n; i++) {
            starts[i] = ssb.getSpanStart(spans[i]);
            ends[i] = ssb.getSpanEnd(spans[i]);
            flags[i] = ssb.getSpanFlags(spans[i]);
        }
        ssb.insert(at, block);
        int len = block.length();
        for (int i = 0; i < n; i++) {
            int s = starts[i], en = ends[i];
            if (s < at && en > at) continue; // covers the point: it covers the block too
            int ns = s < at ? s : (s == at && en == at ? at : s + len);
            int ne = en < at ? en : (en == at ? at : en + len);
            if (ns > ne) continue;
            if (ssb.getSpanStart(spans[i]) != ns || ssb.getSpanEnd(spans[i]) != ne) {
                try {
                    ssb.setSpan(spans[i], ns, ne, flags[i]);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** The block's text and spans, starting with the blank line that separates it from the link. */
    static SpannableStringBuilder build(TextView tv, PollModel poll, String id, int version) {
        Locale locale = Locale.getDefault();
        long now = System.currentTimeMillis();
        int textColor = tv.getCurrentTextColor();
        int secondary = withAlpha(textColor, 0x99);
        ColorStateList links = tv.getLinkTextColors();
        int accent = links != null ? links.getDefaultColor() : textColor;
        float density = tv.getResources().getDisplayMetrics().density;
        boolean counts = poll.hasCounts();

        SpannableStringBuilder sb = new SpannableStringBuilder("\n\n");
        int headerStart = sb.length();
        sb.append(poll.header(now, locale));
        sb.setSpan(new Ours.Color(secondary), headerStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new Ours.Size(0.9f), headerStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Leading column: the percentage, or a bullet while counts are hidden.
        TextPaint paint = tv.getPaint();
        String widest = counts ? "100%" : "•";
        int column = (int) Math.ceil(paint.measureText(widest) + 8 * density);
        // Text inside a bar starts a little in from its rounded edge.
        int inset = counts ? Math.round(6 * density) : 0;
        for (PollModel.Option o : poll.options) {
            sb.append('\n');
            int start = sb.length();
            sb.append(counts ? poll.percent(o) + "%" : "•").append('\t');
            int textStart = sb.length();
            sb.append(o.text.isEmpty() ? " " : o.text);
            boolean mine = poll.isUserChoice(o);
            if (mine) {
                sb.setSpan(new Ours.Bold(), textStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(" ✓");
            }
            if (counts) {
                int c = sb.length();
                sb.append(" · ").append(PollModel.count(o.votes, locale));
                sb.setSpan(new Ours.Color(secondary), c, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int end = sb.length();
            sb.setSpan(new Ours.Tab(column), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new Ours.Indent(inset, inset + column), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new OptionSpan(counts ? poll.fraction(o) : -1f, mine, textColor, accent,
                    Math.round(3 * density), 4 * density), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.setSpan(new BlockSpan(id, version), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Marks every span of the block, so a rebuild removes exactly ours. */
    interface Ours {
        final class Color extends ForegroundColorSpan implements Ours {
            Color(int c) {
                super(c);
            }
        }

        final class Size extends RelativeSizeSpan implements Ours {
            Size(float s) {
                super(s);
            }
        }

        final class Bold extends StyleSpan implements Ours {
            Bold() {
                super(android.graphics.Typeface.BOLD);
            }
        }

        final class Tab extends TabStopSpan.Standard implements Ours {
            Tab(int where) {
                super(where);
            }
        }

        final class Indent extends LeadingMarginSpan.Standard implements Ours {
            Indent(int first, int rest) {
                super(first, rest);
            }
        }
    }

    /** The whole block (from its blank line), with the poll id and data version it shows. */
    static final class BlockSpan implements Ours {
        final String id;
        final int version;

        BlockSpan(String id, int version) {
            this.id = id;
            this.version = version;
        }
    }

    /**
     * One option's line(s): a little padding above and below, and behind it a faint track with
     * the vote share filled in the theme's link colour (stronger for the user's own vote). No
     * bar while counts are hidden ([fraction] < 0).
     */
    static final class OptionSpan implements LineBackgroundSpan, LineHeightSpan, Ours {
        private final float fraction;
        private final boolean mine;
        private final int textColor;
        private final int accent;
        private final int pad;
        private final float radius;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private final float[] radii = new float[8];

        OptionSpan(float fraction, boolean mine, int textColor, int accent, int pad, float radius) {
            this.fraction = fraction;
            this.mine = mine;
            this.textColor = textColor;
            this.accent = accent;
            this.pad = pad;
            this.radius = radius;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int lineHeight,
                                 Paint.FontMetricsInt fm) {
            if (!(text instanceof Spanned)) return;
            Spanned sp = (Spanned) text;
            if (start <= sp.getSpanStart(this)) {
                fm.ascent -= pad;
                fm.top -= pad;
            }
            if (end >= sp.getSpanEnd(this)) {
                fm.descent += pad;
                fm.bottom += pad;
            }
        }

        @Override
        public void drawBackground(Canvas canvas, Paint p, int left, int right, int top, int baseline,
                                   int bottom, CharSequence text, int start, int end, int lineNumber) {
            if (fraction < 0 || !(text instanceof Spanned)) return;
            Spanned sp = (Spanned) text;
            // An option that wraps is one bar: only its outer corners are rounded.
            boolean first = start <= sp.getSpanStart(this);
            boolean last = end >= sp.getSpanEnd(this);
            float t = first ? top + pad / 2f : top;
            float b = last ? bottom - pad / 2f : bottom;
            fill.setColor(withAlpha(textColor, 0x14));
            bar(canvas, left, t, right, b, first, last);
            if (fraction > 0) {
                fill.setColor(withAlpha(accent, mine ? 0x66 : 0x3D));
                bar(canvas, left, t, left + Math.max(2 * radius, (right - left) * fraction), b, first, last);
            }
        }

        private void bar(Canvas canvas, float l, float t, float r, float b, boolean first, boolean last) {
            float top = first ? radius : 0, bottom = last ? radius : 0;
            radii[0] = radii[1] = radii[2] = radii[3] = top;
            radii[4] = radii[5] = radii[6] = radii[7] = bottom;
            rect.set(l, t, r, b);
            path.reset();
            path.addRoundRect(rect, radii, Path.Direction.CW);
            canvas.drawPath(path, fill);
        }
    }
}
