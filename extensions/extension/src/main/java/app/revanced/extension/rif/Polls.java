package app.revanced.extension.rif;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * token and user agent) and shows a read-only block of text in the link's place ("Hide poll
 * links", on by default) or under the link's line, which then reads "View Poll in Browser": a
 * header ("Poll · 2,048 votes · ends in 3 days"), one line per option, with percentages, counts and
 * bars when Reddit returns them (once the poll ended or the user voted), and while the poll is open
 * a footer ("Open in browser to vote/show results"). The block only lives in the TextView's copy of
 * the text, never in rif's cached body, so quoting, copying and editing the post see rif's text.
 * Never in the feed.
 *
 * <p>A tap on the block or on the (renamed) link opens the poll in the default browser, never rif's
 * own: rif's in-app browser follows Reddit's redirect from /poll/ to the post and routes it back
 * into rif. Returning from the browser fetches the poll again, so a new vote shows.
 */
public final class Polls {

    private static final String TAG = "RifPolls";
    /** Shown data older than this is fetched again (in the background) on the next bind. */
    private static final long STALE_MS = 2 * 60_000;
    /** A failed fetch is retried on a bind after this long. */
    private static final long RETRY_MS = 2 * 60_000;
    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 10000;
    private static final String[] RIF_PACKAGES = {"com.andrewshu.android.reddit", "com.andrewshu.android.redditdonation"};

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
    /** Waits for the return from the browser, then fetches this poll again (main thread). */
    private static ReturnWatch returnWatch;

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
     * Main thread: true if the post whose poll this is shows on screen (its comments page), so a
     * tap on its poll link (Fix share links' router hook) opens the browser instead of the post.
     */
    static boolean onPostPage(String id) {
        return visibleBody(id) != null;
    }

    private static TextView visibleBody(String id) {
        for (Map.Entry<TextView, String> b : new ArrayList<>(BOUND.entrySet())) {
            TextView tv = b.getKey();
            if (tv == null || !id.equals(b.getValue())) continue;
            if (tv.isAttachedToWindow() && tv.isShown() && tv.getWindowVisibility() == View.VISIBLE) return tv;
        }
        return null;
    }

    /** The poll id of the body's block, or of its first poll link. */
    private static String findPollId(CharSequence text) {
        if (!(text instanceof Spanned)) return null;
        BlockSpan b = block(text);
        if (b != null) return b.id;
        Spanned sp = (Spanned) text;
        for (URLSpan u : sp.getSpans(0, sp.length(), URLSpan.class)) {
            String id = PollModel.pollId(u.getURL());
            if (id != null) return id;
        }
        return null;
    }

    /** rif's own link to poll [id] in [sp] (not our replacement). */
    private static URLSpan findPollLink(Spanned sp, String id) {
        for (URLSpan u : sp.getSpans(0, sp.length(), URLSpan.class)) {
            if (!(u instanceof Ours) && id.equals(PollModel.pollId(u.getURL()))) return u;
        }
        return null;
    }

    private static BlockSpan block(CharSequence text) {
        if (!(text instanceof Spanned)) return null;
        Spanned sp = (Spanned) text;
        BlockSpan[] b = sp.getSpans(0, sp.length(), BlockSpan.class);
        return b.length == 0 ? null : b[0];
    }

    // ---- taps and the browser ---------------------------------------------------------------

    /**
     * From InlineImages' touch listener, on a lift over [tv]: true if it ends a tap (no drag, no
     * long press, no text selection) that went down and up on the poll block's lines. The poll
     * then opens in the browser, and the caller cancels the lift so it isn't also a text tap.
     */
    static boolean onTap(TextView tv, float[] down, MotionEvent up) {
        try {
            if (down == null || tv.hasSelection()) return false;
            CharSequence text = tv.getText();
            BlockSpan b = block(text);
            Layout layout = tv.getLayout();
            if (b == null || layout == null) return false;
            if (up.getEventTime() - up.getDownTime() >= ViewConfiguration.getLongPressTimeout()) return false;
            int slop = ViewConfiguration.get(tv.getContext()).getScaledTouchSlop();
            if (Math.abs(up.getX() - down[0]) > slop || Math.abs(up.getY() - down[1]) > slop) return false;
            Spanned sp = (Spanned) text;
            int start = sp.getSpanStart(b) + b.content, end = sp.getSpanEnd(b);
            if (start < 0 || end <= start) return false;
            int first = layout.getLineForOffset(start);
            int last = layout.getLineForOffset(end - 1);
            float top = layout.getLineTop(first), bottom = layout.getLineBottom(last);
            float offset = tv.getTotalPaddingTop() - tv.getScrollY();
            if (!within(down[1] - offset, top, bottom) || !within(up.getY() - offset, top, bottom)) return false;
            final String id = b.id;
            final Context ctx = tv.getContext();
            tv.post(() -> openInBrowser(ctx, id));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "poll tap failed", t);
            return false;
        }
    }

    private static boolean within(float y, float top, float bottom) {
        return y >= top && y <= bottom;
    }

    /**
     * Opens poll [id] in the default browser (not rif: rif's own browser routes the redirect to
     * the post back into rif). The poll's data goes stale, and is fetched again once the user
     * comes back, so a vote made there shows.
     */
    static void openInBrowser(Context ctx, String id) {
        try {
            if (ctx == null) ctx = Settings.context();
            if (ctx == null) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/poll/" + id));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            // Chrome and others reuse one tab per app for this.
            intent.putExtra("com.android.browser.application_id", ctx.getPackageName());
            if (!(ctx instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String browser = defaultBrowser(ctx);
            if (browser != null) intent.setPackage(browser);
            Poll e = CACHE.get(id);
            if (e != null) e.fetchedAt = 0;
            try {
                ctx.startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException ex) {
                if (browser == null) throw ex;
                intent.setPackage(null);
                ctx.startActivity(intent);
            }
            watchReturn(ctx, id);
        } catch (ActivityNotFoundException ex) {
            try {
                Toast.makeText(ctx, "No browser found", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "open in browser failed", t);
        }
    }

    /**
     * The default browser's package, as rif finds it for its external browser (what opens a bare
     * "https://"), or null if there is none, the system would ask, or it would be rif itself.
     */
    private static String defaultBrowser(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
                    PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo ai = ri == null ? null : ri.activityInfo;
            if (ai == null || ai.packageName == null || ai.name == null) return null;
            if (ai.name.contains("Resolver") || ai.name.contains(".internal.")) return null;
            if (ai.packageName.equals(ctx.getPackageName())) return null;
            for (String p : RIF_PACKAGES) if (p.equals(ai.packageName)) return null;
            return ai.packageName;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void watchReturn(Context ctx, String id) {
        Context app = ctx.getApplicationContext();
        if (!(app instanceof Application)) return;
        if (returnWatch != null) returnWatch.stop();
        returnWatch = new ReturnWatch((Application) app, id);
    }

    /**
     * After the browser opened: the next time one of rif's activities resumes after having been
     * stopped (the user came back), the poll is fetched again. A chooser dialog only pauses rif.
     */
    private static final class ReturnWatch implements Application.ActivityLifecycleCallbacks {
        private final Application app;
        private final String id;
        private boolean left;

        ReturnWatch(Application app, String id) {
            this.app = app;
            this.id = id;
            app.registerActivityLifecycleCallbacks(this);
        }

        void stop() {
            app.unregisterActivityLifecycleCallbacks(this);
            if (returnWatch == this) returnWatch = null;
        }

        @Override public void onActivityStopped(Activity a) {
            left = true;
        }

        @Override public void onActivityResumed(Activity a) {
            if (!left) return;
            stop();
            try {
                Poll e = CACHE.get(id);
                if (e == null || !e.inFlight) fetch(id);
            } catch (Throwable t) {
                Log.w(TAG, "refetch failed", t);
            }
        }

        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
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
        } catch (Throwable t) {
            Log.w(TAG, "loaded failed", t);
        }
    }

    // ---- the block ------------------------------------------------------------------------

    /**
     * Puts the poll's block in [tv]'s text, or brings it up to date; no-op if it's current. The
     * block replaces a region of rif's text: the link's whole line when the link is hidden (and
     * alone on its line), else the link and the rest of its line, followed by the block. The
     * region's original text is kept in the BlockSpan, so a rebuild starts from rif's text.
     */
    private static void show(TextView tv, String id) {
        Poll e = CACHE.get(id);
        if (e == null || e.model == null) return;
        CharSequence cur = tv.getText();
        if (!(cur instanceof Spanned)) return;
        boolean hide = Settings.hidePollLinks();
        BlockSpan old = block(cur);
        if (old != null && old.id.equals(id) && old.version == e.version && old.hide == hide) return;

        SpannableStringBuilder ssb = new SpannableStringBuilder(cur);
        if (old != null) restore(ssb, old);
        URLSpan link = findPollLink(ssb, id);
        if (link == null) return;
        int linkStart = ssb.getSpanStart(link), linkEnd = ssb.getSpanEnd(link);
        if (linkStart < 0 || linkEnd < linkStart) return;
        int lineStart = linkStart;
        while (lineStart > 0 && ssb.charAt(lineStart - 1) != '\n') lineStart--;
        int lineEnd = linkEnd;
        while (lineEnd < ssb.length() && ssb.charAt(lineEnd) != '\n') lineEnd++;
        boolean alone = ssb.subSequence(lineStart, linkStart).toString().trim().isEmpty()
                && ssb.subSequence(linkEnd, lineEnd).toString().trim().isEmpty();
        boolean hidden = hide && alone;
        int from = hidden ? lineStart : linkStart;

        SpannableStringBuilder region = new SpannableStringBuilder();
        if (!hidden) {
            String text = ssb.subSequence(linkStart, linkEnd).toString();
            region.append(hide ? text : PollModel.linkLabel(text));
            region.setSpan(new Ours.Link(link.getURL(), id), 0, region.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            region.append(copyInside(ssb, linkEnd, lineEnd));
        }
        SpannableStringBuilder block = build(tv, e.model, !hidden);
        int content = region.length() + (hidden ? 0 : 2); // where the header starts
        region.append(block);
        BlockSpan span = new BlockSpan(id, e.version, hide, copyInside(ssb, from, lineEnd), content);
        region.setSpan(span, 0, region.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        replace(ssb, from, lineEnd, region);

        // The body is wrap_content: the bars span the TextView's width, so it takes the full
        // width while it shows a poll (only the text box's empty right side changes).
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp != null && lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            tv.setLayoutParams(lp);
        }
        tv.setText(ssb);
        InlineImages.trackTouches(tv); // taps on the block need the touch position
    }

    /** Puts back the text the block's region replaced. */
    private static void restore(SpannableStringBuilder ssb, BlockSpan b) {
        int start = ssb.getSpanStart(b), end = ssb.getSpanEnd(b);
        if (start < 0 || end < start) return;
        replace(ssb, start, end, b.original);
    }

    /**
     * [ssb]'s text from [start] to [end] with the spans that lie inside it (not ones that only
     * overlap it: those stay where they are in [ssb]).
     */
    private static SpannableStringBuilder copyInside(SpannableStringBuilder ssb, int start, int end) {
        SpannableStringBuilder copy = new SpannableStringBuilder(ssb.subSequence(start, end).toString());
        for (Object o : ssb.getSpans(start, end, Object.class)) {
            if (o instanceof Ours) continue;
            int s = ssb.getSpanStart(o), en = ssb.getSpanEnd(o);
            if (s < start || en > end || (s == en && (s == start || s == end))) continue;
            copy.setSpan(o, s - start, en - start, ssb.getSpanFlags(o));
        }
        return copy;
    }

    /**
     * Replaces [start, end) of [ssb] with [with]: the spans inside the range go (copyInside keeps
     * them), the ones around it keep their range rather than stretching over [with].
     */
    private static void replace(SpannableStringBuilder ssb, int start, int end, CharSequence with) {
        for (Object o : ssb.getSpans(start, end, Object.class)) {
            int s = ssb.getSpanStart(o), en = ssb.getSpanEnd(o);
            if (s >= start && en <= end && !(s == en && (s == start || s == end))) ssb.removeSpan(o);
        }
        ssb.delete(start, end);
        insertKeepingSpans(ssb, start, with);
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

    /**
     * The block's text and spans: header, options and (while open) footer, after a blank line
     * that separates it from the link's line when [afterLink].
     */
    static SpannableStringBuilder build(TextView tv, PollModel poll, boolean afterLink) {
        Locale locale = Locale.getDefault();
        long now = System.currentTimeMillis();
        int textColor = tv.getCurrentTextColor();
        int secondary = withAlpha(textColor, 0x99);
        ColorStateList links = tv.getLinkTextColors();
        int accent = links != null ? links.getDefaultColor() : textColor;
        float density = tv.getResources().getDisplayMetrics().density;
        boolean counts = poll.hasCounts();

        SpannableStringBuilder sb = new SpannableStringBuilder(afterLink ? "\n\n" : "");
        secondaryLine(sb, poll.header(now, locale), secondary);

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
        String footer = poll.footer(now);
        if (footer != null) {
            sb.append('\n');
            secondaryLine(sb, footer, secondary);
        }
        return sb;
    }

    /** Appends a line styled like the header: secondary colour, a little smaller. */
    private static void secondaryLine(SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new Ours.Color(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new Ours.Size(0.9f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

        /** The poll link while the block shows: opens the poll in the browser. */
        final class Link extends URLSpan implements Ours {
            private final String id;

            Link(String url, String id) {
                super(url);
                this.id = id;
            }

            @Override
            public void onClick(View widget) {
                try {
                    if (widget instanceof TextView) {
                        // rif's movement method selected the link on touch-down; the body's own
                        // click (select the post) is queued for this tap: drop both.
                        CharSequence text = ((TextView) widget).getText();
                        if (text instanceof Spannable) Selection.removeSelection((Spannable) text);
                        widget.cancelPendingInputEvents();
                    }
                    openInBrowser(widget.getContext(), id);
                } catch (Throwable t) {
                    Log.w(TAG, "poll link failed", t);
                }
            }
        }
    }

    /**
     * The block's whole region, with the poll id and data version it shows, whether the link was
     * hidden, rif's text the region replaced, and where in it the block's header starts.
     */
    static final class BlockSpan implements Ours {
        final String id;
        final int version;
        final boolean hide;
        final CharSequence original;
        final int content;

        BlockSpan(String id, int version, boolean hide, CharSequence original, int content) {
            this.id = id;
            this.version = version;
            this.hide = hide;
            this.original = original;
            this.content = content;
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
