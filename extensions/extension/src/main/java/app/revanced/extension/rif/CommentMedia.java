package app.revanced.extension.rif;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Fragment;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.util.c;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Images and GIFs in comment replies (part of the "Reddit image hosting" patch).
 *
 * <ul>
 * <li>{@link #onReplyDialog}: the reply dialog gets an "image" button in the formatting bar (not for
 * private-message replies and comment edits). It picks a file and puts a text marker such as
 * {@code ![img](photo.png)} in the editor, as its own paragraph.</li>
 * <li>{@link #commentParams}: when the reply is sent (background thread) and the text holds a marker,
 * the file is uploaded to Reddit, the marker becomes {@code ![img](<asset id>)} on its own paragraph,
 * Reddit converts the markdown to rich text, and the comment goes out as {@code richtext_json}.
 * Reddit only accepts hosted media in rich text. A comment without a marker is not touched.</li>
 * <li>{@link #wrapCommentResponse}: Reddit answers such a comment with the bare comment object, rif
 * expects it wrapped in {@code json.data.things}. Its body is a "Processing" placeholder: the new
 * comment is polled for up to {@link #POLL_MAX_MS} until Reddit has written the media's real link
 * into it; if that doesn't happen, a PNG or GIF placeholder becomes an i.redd.it link and a
 * JPEG or WebP one stays until the thread is reloaded.</li>
 * </ul>
 * One media object per comment is Reddit's rule. A marker is only a label: the file it stands for
 * is kept in SharedPreferences ({@link Rec}), so it survives rotation, process death and rif's drafts.
 */
public final class CommentMedia {

    private static final String TAG = "RifCommentMedia";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String API = "https://oauth.reddit.com";

    static final String ONE_ONLY = "Only one image per comment.";
    static final String NO_IMAGES = "This community doesn't allow images in comments.";
    static final String NO_GIFS = "This community doesn't allow GIFs in comments.";
    static final String PICK_AGAIN = "Pick the image again.";
    static final String UNSUPPORTED = "Pick a PNG, JPEG, WebP or GIF image.";
    static final String NO_VIDEO = "Videos can't go in comments yet.";
    static final String TOO_LONG = "No room in the comment for the image.";
    static final String GIF_TOO_BIG = "The GIF is over Reddit's 100 MB limit.";

    /** Reddit's lease limit for a GIF. */
    static final long MAX_GIF_BYTES = 100L * 1024 * 1024;

    private static final String PREFS = "rif_comment_media";
    private static final String PREFS_KEY = "records";
    private static final long KEEP_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_RECORDS = 20;
    /** A pick that arrives while no reply dialog is up waits this long for one (process death, rotation). */
    private static final long PENDING_MS = 60000;
    /** Wait between polls for Reddit's processed comment body, and the longest total wait. */
    static final long POLL_MS = 2000;
    static final long POLL_MAX_MS = 12000;

    private CommentMedia() {}

    // ------------------------------------------------------- pure helpers

    /** {@code ![img](name)} or {@code ![gif](name)}; the name has no brackets, parentheses or line breaks. */
    private static final Pattern MARKER = Pattern.compile("!\\[(img|gif)\\]\\(([^()\\[\\]\\r\\n]*)\\)");

    /** One marker in a text. */
    static final class Marker {
        final int start;
        final int end;
        final String text;
        final boolean gif;

        Marker(int start, int end, String text, boolean gif) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.gif = gif;
        }
    }

    /** Every marker-shaped piece of {@code text}, in order. */
    static List<Marker> findMarkers(String text) {
        List<Marker> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = MARKER.matcher(text);
        while (m.find()) out.add(new Marker(m.start(), m.end(), m.group(), "gif".equals(m.group(1))));
        return out;
    }

    /** The markers of {@code text} that stand for a picked file (their text is in {@code known}). */
    static List<Marker> knownMarkers(String text, Set<String> known) {
        List<Marker> out = new ArrayList<>();
        for (Marker m : findMarkers(text)) {
            if (known.contains(m.text)) out.add(m);
        }
        return out;
    }

    /** A short, markdown-safe file name for a marker; {@code image.jpg} / {@code image.gif} when nothing is left. */
    static String sanitizeName(String raw, boolean gif) {
        String fallback = gif ? "image.gif" : "image.jpg";
        if (raw == null) return fallback;
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String s = raw.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '-') {
                sb.append(ch);
            } else if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
        }
        String t = sb.toString();
        int from = 0;
        int to = t.length();
        while (from < to && (t.charAt(from) == '_' || t.charAt(from) == '.')) from++;
        while (to > from && (t.charAt(to - 1) == '_' || t.charAt(to - 1) == '.')) to--;
        t = t.substring(from, to);
        if (t.length() == 0) return fallback;
        if (t.length() > 32) {
            int dot = t.lastIndexOf('.');
            String ext = dot > 0 && t.length() - dot <= 6 ? t.substring(dot) : "";
            String stem = ext.length() > 0 ? t.substring(0, dot) : t;
            t = stem.substring(0, Math.min(stem.length(), 32 - ext.length())) + ext;
        }
        return t;
    }

    /** The marker for {@code name}; if {@code taken} holds it already, the name gets "-2", "-3", ... before its extension. */
    static String markerFor(String name, boolean gif, Set<String> taken) {
        String kind = gif ? "gif" : "img";
        String candidate = "![" + kind + "](" + name + ")";
        if (!taken.contains(candidate)) return candidate;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; n < 1000; n++) {
            candidate = "![" + kind + "](" + stem + "-" + n + ext + ")";
            if (!taken.contains(candidate)) return candidate;
        }
        return "![" + kind + "](" + System.currentTimeMillis() + ")";
    }

    /** The line breaks to put before and after a marker inserted between {@code before} and {@code after}: a paragraph of its own. */
    static String[] spacing(String before, String after) {
        String prefix = before.length() == 0 || before.endsWith("\n\n") ? "" : before.endsWith("\n") ? "\n" : "\n\n";
        String suffix = after.startsWith("\n\n") ? "" : after.startsWith("\n") ? "\n" : "\n\n";
        return new String[] {prefix, suffix};
    }

    /**
     * {@code text} with the span [start, end) replaced by {@code token} as a paragraph of its own
     * (Reddit's converter drops the text of a paragraph that holds the token inline). A quote or
     * indent mark in front of the token on its line is dropped with it; text on the token's line is
     * moved to a paragraph of its own.
     */
    static String isolate(String text, int start, int end, String token) {
        String before = text.substring(0, start);
        String after = text.substring(end);
        int nl = before.lastIndexOf('\n');
        if (before.substring(nl + 1).matches("[ \\t>]*")) before = before.substring(0, nl + 1);
        else before = before.replaceFirst("[ \\t]+$", "");
        int nl2 = after.indexOf('\n');
        String rest = nl2 < 0 ? after : after.substring(0, nl2);
        if (rest.trim().length() == 0) {
            after = nl2 < 0 ? "" : after.substring(nl2);
        } else {
            after = after.replaceFirst("^[ \\t]+", "");
        }
        String[] gap = spacing(before, after);
        return before + gap[0] + token + gap[1] + after;
    }

    /** The media token Reddit's converter turns into a media block. */
    static String token(String assetId, boolean gif) {
        return "![" + (gif ? "gif" : "img") + "](" + assetId + ")";
    }

    /**
     * The picked file's extension in its i.redd.it URL, which answers at once for a PNG and a GIF. Null
     * for a JPEG or WebP (transcoded, so no i.redd.it URL: emulator check 2026-11-11): its placeholder
     * stays until the thread is reloaded.
     */
    static String urlExtension(String mime) {
        if ("image/gif".equals(mime)) return "gif";
        if ("image/png".equals(mime)) return "png";
        return null;
    }

    /**
     * Counts the media blocks (e is img or gif) anywhere in a converted rich-text document:
     * {total, those of the wanted type and id}.
     */
    static int[] countMedia(Object node, String id, boolean gif) {
        int[] n = new int[2];
        countMedia(node, id, gif ? "gif" : "img", n);
        return n;
    }

    private static void countMedia(Object node, String id, String type, int[] n) {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) countMedia(a.opt(i), id, type, n);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String e = o.optString("e", "");
            if ("img".equals(e) || "gif".equals(e)) {
                n[0]++;
                if (type.equals(e) && id.equals(o.optString("id", ""))) n[1]++;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) countMedia(o.opt(keys.next()), id, type, n);
        }
    }

    /** The document of a convert_rte_body_format reply ({"output":{"document":[..]}}), or null. */
    static JSONObject convertedDocument(String body) {
        try {
            JSONObject out = new JSONObject(body).optJSONObject("output");
            return out != null && out.optJSONArray("document") != null ? out : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** The parameters with {@code text} replaced, in place, by {@code richtext_json}; other pairs stay. */
    static c<String, String>[] withRichtext(c<String, String>[] pairs, String richtextJson) {
        c<String, String>[] out = pairs.clone();
        for (int i = 0; i < out.length; i++) {
            if (out[i] != null && "text".equals(out[i].a)) {
                out[i] = c.a("richtext_json", richtextJson);
                return out;
            }
        }
        return pairs;
    }

    /**
     * Rif's reply parser wants {json:{errors:[],data:{things:[{kind,data}]}}}; Reddit answers a rich-text
     * comment with the bare comment object. Returns the wrapped reply with the "Processing img|gif
     * ..." placeholder in body and body_html turned into a link to i.redd.it, or null when the reply
     * already has the json wrapper (errors included) or isn't a comment: pass it through then.
     */
    static String wrapComment(String reply, String assetId, String ext) {
        try {
            JSONObject o = new JSONObject(reply);
            if (o.has("json") || !(o.has("name") || o.has("id")) || !o.has("body")) return null;
            if (ext != null) {
                String url = "https://i.redd.it/" + assetId + "." + ext;
                String body = o.optString("body", null);
                if (body != null) o.put("body", rewritePlaceholder(body, assetId, url, false));
                String html = o.optString("body_html", null);
                if (html != null) o.put("body_html", rewritePlaceholder(html, assetId, url, true));
            }
            JSONObject thing = new JSONObject();
            thing.put("kind", "t1");
            thing.put("data", o);
            JSONObject data = new JSONObject();
            data.put("things", new JSONArray().put(thing));
            JSONObject json = new JSONObject();
            json.put("errors", new JSONArray());
            json.put("data", data);
            return new JSONObject().put("json", json).toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Turns Reddit's {@code *Processing img <id>...*} (markdown) or {@code <em>Processing gif <id>...</em>}
     * (html, raw or entity-escaped as in a non-raw_json reply) into the URL, or a link to it.
     */
    static String rewritePlaceholder(String s, String assetId, String url, boolean html) {
        String id = Pattern.quote(assetId);
        if (!html) {
            return s.replaceAll("\\*Processing (?:img|gif) " + id + "\\.\\.\\.\\*", Matcher.quoteReplacement(url));
        }
        s = s.replaceAll("<em>Processing (?:img|gif) " + id + "\\.\\.\\.</em>",
                Matcher.quoteReplacement("<a href=\"" + url + "\">" + url + "</a>"));
        return s.replaceAll("&lt;em&gt;Processing (?:img|gif) " + id + "\\.\\.\\.&lt;/em&gt;",
                Matcher.quoteReplacement("&lt;a href=&quot;" + url + "&quot;&gt;" + url + "&lt;/a&gt;"));
    }

    /** Reddit's stand-in for media it is still processing: {@code *Processing img <id>...*} (or gif). */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\*Processing (?:img|gif) [A-Za-z0-9]+\\.\\.\\.\\*");

    /** Whether a comment body still holds a "Processing" placeholder. */
    static boolean hasPlaceholder(String body) {
        return body != null && PLACEHOLDER.matcher(body).find();
    }

    /** A flat comment reply's {link id36, comment id36}, or null when it has neither or they look wrong. */
    static String[] pollTarget(String reply) {
        try {
            JSONObject o = new JSONObject(reply);
            if (o.has("json")) return null;
            String link = o.optString("link_id", "");
            String id = o.optString("id", "");
            if (id.length() == 0) id = o.optString("name", "");
            if (link.startsWith("t3_")) link = link.substring(3);
            if (id.startsWith("t1_")) id = id.substring(3);
            if (!link.matches("[a-z0-9]{1,20}") || !id.matches("[a-z0-9]{1,20}")) return null;
            return new String[] {link, id};
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * The comment {@code id} (id36) from a comment-permalink listing ({@code [post listing, comment
     * listing]}), its data object, or null when it isn't there.
     */
    static JSONObject commentFromListing(String json, String id) {
        try {
            JSONArray listings = new JSONArray(json);
            JSONObject comments = listings.optJSONObject(1);
            JSONObject data = comments != null ? comments.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children == null) return null;
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                JSONObject d = child != null ? child.optJSONObject("data") : null;
                if (d != null && id.equals(d.optString("id", ""))) return d;
            }
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * The flat reply with the processed comment's body and body_html in place of its own. The reply
     * itself is a raw_json one (body_html is plain HTML, as the emulator check of 2026-11-11 showed),
     * like the poll's, so the texts go in as they are. Returns the reply as it was when the processed
     * comment has no body or still has a placeholder.
     */
    static String mergeProcessed(String reply, JSONObject processed) {
        try {
            String body = processed.optString("body", null);
            String html = processed.optString("body_html", null);
            if (body == null || html == null || hasPlaceholder(body)) return reply;
            JSONObject o = new JSONObject(reply);
            o.put("body", body);
            o.put("body_html", html);
            return o.toString();
        } catch (JSONException e) {
            return reply;
        }
    }

    /** The reply's first error as "CODE: message" (for the log), or null if it has none. */
    static String firstError(String reply) {
        try {
            JSONArray errors = new JSONObject(reply).getJSONObject("json").optJSONArray("errors");
            if (errors == null || errors.length() == 0) return null;
            JSONArray first = errors.optJSONArray(0);
            return first != null ? first.optString(0) + ": " + first.optString(1) : errors.optString(0);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * The media types a subreddit allows in comments (about.json's comment_contribution_settings.
     * allowed_media_types: "static", "animated", ...), or null when the reply doesn't say.
     */
    static List<String> allowedMediaTypes(String aboutJson) {
        try {
            JSONObject data = new JSONObject(aboutJson).optJSONObject("data");
            JSONObject settings = data != null ? data.optJSONObject("comment_contribution_settings") : null;
            JSONArray types = settings != null ? settings.optJSONArray("allowed_media_types") : null;
            if (types == null) return null;
            List<String> out = new ArrayList<>();
            for (int i = 0; i < types.length(); i++) out.add(types.optString(i));
            return out;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Whether the allowed list (null: unknown, allow) lets a GIF ("animated") or a still image ("static") through. */
    static boolean allows(List<String> types, boolean gif) {
        return types == null || types.contains(gif ? "animated" : "static");
    }

    static boolean allowsAny(List<String> types) {
        return allows(types, false) || allows(types, true);
    }

    /** A profile "subreddit" (u_name) takes any media; so does an unknown name. */
    static boolean isProfileSub(String sub) {
        return sub != null && sub.toLowerCase(java.util.Locale.ROOT).startsWith("u_");
    }

    /** A picked file, as kept for its marker. */
    static final class Rec {
        final String marker;
        final String uri;
        final String mime;
        final long at;

        Rec(String marker, String uri, String mime, long at) {
            this.marker = marker;
            this.uri = uri;
            this.mime = mime;
            this.at = at;
        }
    }

    static String recsToJson(List<Rec> recs) {
        JSONArray out = new JSONArray();
        try {
            for (Rec r : recs) {
                out.put(new JSONObject().put("m", r.marker).put("u", r.uri).put("t", r.mime).put("at", r.at));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    static List<Rec> recsFromJson(String json) {
        List<Rec> out = new ArrayList<>();
        try {
            JSONArray in = new JSONArray(json);
            for (int i = 0; i < in.length(); i++) {
                JSONObject o = in.optJSONObject(i);
                if (o == null) continue;
                String marker = o.optString("m", "");
                String uri = o.optString("u", "");
                if (marker.length() == 0 || uri.length() == 0) continue;
                out.add(new Rec(marker, uri, o.optString("t", ""), o.optLong("at", 0)));
            }
        } catch (JSONException e) {
            Log.w(TAG, "records unreadable: " + e);
        }
        return out;
    }

    /** Drops records older than a week and all but the newest {@link #MAX_RECORDS}; the list is oldest first. */
    static List<Rec> prune(List<Rec> all, long now) {
        List<Rec> kept = new ArrayList<>();
        for (Rec r : all) {
            if (now - r.at < KEEP_MS) kept.add(r);
        }
        return kept.size() > MAX_RECORDS ? new ArrayList<>(kept.subList(kept.size() - MAX_RECORDS, kept.size())) : kept;
    }

    // -------------------------------------------------------------- records

    private static final Object RECORDS_LOCK = new Object();

    private static List<Rec> loadRecords(Context ctx) {
        synchronized (RECORDS_LOCK) {
            return recsFromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREFS_KEY, "[]"));
        }
    }

    private static Set<String> knownMarkerSet(Context ctx) {
        Set<String> known = new HashSet<>();
        try {
            for (Rec r : loadRecords(ctx)) known.add(r.marker);
        } catch (Throwable t) {
            Log.w(TAG, "records not read", t);
        }
        return known;
    }

    private static Rec recordFor(Context ctx, String marker) {
        for (Rec r : loadRecords(ctx)) {
            if (r.marker.equals(marker)) return r;
        }
        return null;
    }

    /** Saves the record for a marker (replacing one with the same marker) and drops stale ones. */
    private static void saveRecord(Context ctx, Rec rec) {
        try {
            synchronized (RECORDS_LOCK) {
                SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                List<Rec> all = new ArrayList<>(recsFromJson(prefs.getString(PREFS_KEY, "[]")));
                for (int i = all.size() - 1; i >= 0; i--) {
                    if (all.get(i).marker.equals(rec.marker)) all.remove(i);
                }
                all.add(rec);
                List<Rec> kept = prune(all, System.currentTimeMillis());
                for (Rec old : all) {
                    if (!kept.contains(old)) releaseGrant(ctx, old);
                }
                prefs.edit().putString(PREFS_KEY, recsToJson(kept)).apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "record not saved", t);
        }
    }

    /** Gives back the persistable read grant of a forgotten record (they are limited per app). */
    private static void releaseGrant(Context ctx, Rec rec) {
        try {
            ctx.getContentResolver().releasePersistableUriPermission(Uri.parse(rec.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ dialog UI

    /** One open reply dialog. The button's click listener keeps it alive; {@link #current} doesn't. */
    private static final class Dialog {
        final WeakReference<EditText> editor;
        final WeakReference<TextView> progress;
        final String sub;

        Dialog(EditText editor, TextView progress, String sub) {
            this.editor = new WeakReference<>(editor);
            this.progress = new WeakReference<>(progress);
            this.sub = sub;
        }
    }

    private static WeakReference<Dialog> current = new WeakReference<>(null);

    /** A picked file waiting for a dialog (see {@link #PENDING_MS}). */
    private static final class Pick {
        Uri uri;
        String mime;
        String name;
        String error;
        long at;
    }

    private static Pick pending;
    private static long lastTapMs;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Hook (reply dialog's onCreateView, after the formatting bar got its editor): adds the image
     * button after the bar's link button. Not for private-message replies and comment edits.
     * {@code args} are the dialog's arguments (parentKind, editThingName, subreddit).
     */
    public static void onReplyDialog(Object fragment, View bar, EditText editor, Bundle args) {
        try {
            if (bar == null || editor == null) return;
            String kind = args != null ? args.getString("parentKind") : null;
            String edit = args != null ? args.getString("editThingName") : null;
            if ("t4".equals(kind) || (edit != null && edit.length() > 0)) return;
            final String sub = args != null ? args.getString("subreddit") : null;
            Context ctx = bar.getContext();
            Activity activity = activityOf(ctx);
            if (activity == null) return;
            Context pkgCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            int linkId = id(ctx, "insert_link_button", "id");
            View link = linkId != 0 ? bar.findViewById(linkId) : null;
            if (link == null || !(link.getParent() instanceof ViewGroup)) return;
            ViewGroup row = (ViewGroup) link.getParent();

            ImageButton button = makeButton(ctx, bar, link);
            if (button == null) return;
            TextView progress = addProgressView(ctx, bar);
            final Dialog dialog = new Dialog(editor, progress, sub);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        onButton(dialog, v);
                    } catch (Throwable t) {
                        Log.w(TAG, "image button failed", t);
                    }
                }
            });
            row.addView(button, row.indexOfChild(link) + 1);
            current = new WeakReference<>(dialog);
            fetchRules(pkgCtx, sub);

            // A pick that arrived while this dialog was being recreated.
            if (pending != null) {
                final EditText target = editor;
                editor.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            applyPending(target);
                        } catch (Throwable t) {
                            Log.w(TAG, "pending pick failed", t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "reply dialog hook failed", t);
        }
    }

    private static int id(Context ctx, String name, String type) {
        return ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
    }

    private static Activity activityOf(Context ctx) {
        for (int i = 0; i < 10 && ctx != null; i++) {
            if (ctx instanceof Activity) return (Activity) ctx;
            if (!(ctx instanceof ContextWrapper)) return null;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    /** The button: the link button's size, padding and background, a photo icon in the bar's icon colour. */
    private static ImageButton makeButton(Context ctx, View bar, View link) {
        int icon = id(ctx, "outline_photo_library_gray50_24dp", "drawable");
        if (icon == 0) icon = id(ctx, "twotone_photo_library_white_24dp", "drawable");
        if (icon == 0) return null;
        ImageButton b = new ImageButton(ctx);
        b.setImageResource(icon);
        b.setColorFilter(barIconColor(ctx), PorterDuff.Mode.SRC_IN);
        if (link instanceof ImageView) b.setScaleType(((ImageView) link).getScaleType());
        Drawable bg = link.getBackground();
        if (bg != null && bg.getConstantState() != null) {
            b.setBackground(bg.getConstantState().newDrawable(ctx.getResources()));
        } else {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0) {
                b.setBackgroundResource(tv.resourceId);
            }
        }
        b.setPadding(link.getPaddingLeft(), link.getPaddingTop(), link.getPaddingRight(), link.getPaddingBottom());
        ViewGroup.LayoutParams lp = link.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            b.setLayoutParams(new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp));
        } else if (lp instanceof ViewGroup.MarginLayoutParams) {
            b.setLayoutParams(new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) lp));
        }
        b.setContentDescription("Add image");
        if (Build.VERSION.SDK_INT >= 26) setTooltip(b, "Add image");
        return b;
    }

    @TargetApi(26)
    private static void setTooltip(View v, String text) {
        v.setTooltipText(text);
    }

    /**
     * The colour of the bar's own icons: rif's dark theme uses white icons, the light themes grey
     * (#757575); which one is read from the name of the theme's link icon.
     */
    private static int barIconColor(Context ctx) {
        try {
            int attr = id(ctx, "editorInsertLinkDrawable", "attr");
            TypedValue tv = new TypedValue();
            if (attr != 0 && ctx.getTheme().resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
                String name = ctx.getResources().getResourceEntryName(tv.resourceId);
                return name.contains("white") ? Color.WHITE : 0xFF757575;
            }
        } catch (Throwable t) {
            Log.w(TAG, "bar icon colour: " + t);
        }
        return Color.WHITE;
    }

    /** A small "Uploading N%" text in the send row, before the send spinner; null if the row isn't found. */
    private static TextView addProgressView(Context ctx, View bar) {
        try {
            int spinnerId = id(ctx, "send_progress", "id");
            View spinner = spinnerId != 0 ? bar.getRootView().findViewById(spinnerId) : null;
            if (spinner == null || !(spinner.getParent() instanceof ViewGroup)) return null;
            ViewGroup row = (ViewGroup) spinner.getParent();
            TextView tv = new TextView(ctx);
            tv.setVisibility(View.GONE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            TypedArray a = ctx.obtainStyledAttributes(new int[] {android.R.attr.textColorSecondary});
            ColorStateList colors = a.getColorStateList(0);
            a.recycle();
            if (colors != null) tv.setTextColor(colors);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(tv, row.indexOfChild(spinner));
            return tv;
        } catch (Throwable t) {
            Log.w(TAG, "progress view not added", t);
            return null;
        }
    }

    private static void onButton(Dialog dialog, View button) {
        long now = System.currentTimeMillis();
        if (now - lastTapMs < 1000) return;
        lastTapMs = now;
        EditText editor = dialog.editor.get();
        Context ctx = button.getContext();
        if (editor == null) return;
        if (hasKnownMarker(ctx, editor.getText().toString())) {
            toast(ONE_ONLY);
            return;
        }
        List<String> rules = rulesFor(dialog.sub);
        if (!allowsAny(rules)) {
            toast(NO_IMAGES);
            return;
        }
        Activity activity = activityOf(ctx);
        if (activity == null || activity.isFinishing()) return;
        current = new WeakReference<>(dialog);
        android.app.FragmentManager fm = activity.getFragmentManager();
        Fragment old = fm.findFragmentByTag(PickerFragment.TAG);
        android.app.FragmentTransaction tx = fm.beginTransaction();
        if (old != null) tx.remove(old);
        tx.add(new PickerFragment(), PickerFragment.TAG);
        tx.commitAllowingStateLoss();
    }

    private static boolean hasKnownMarker(Context ctx, String text) {
        return !knownMarkers(text, knownMarkerSet(ctx)).isEmpty();
    }

    /** The headless fragment that runs the system picker and receives its result (rif's dialog has no result callback). */
    public static class PickerFragment extends Fragment {
        static final String TAG = "rif_comment_media_picker";
        private static final int REQUEST = 0x5c31;

        public PickerFragment() {}

        @Override
        public void onCreate(Bundle saved) {
            super.onCreate(saved);
            // Restored after process death: the picker (or its result) is already on its way.
            if (saved != null) return;
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQUEST);
            } catch (Throwable t) {
                Log.w(CommentMedia.TAG, "picker not started", t);
                toast("No app found to pick images.");
                removeSelf();
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode != REQUEST) return;
            try {
                Activity activity = getActivity();
                if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && activity != null) {
                    onPicked(activity.getApplicationContext(), data.getData());
                }
            } catch (Throwable t) {
                Log.w(CommentMedia.TAG, "pick result failed", t);
            }
            removeSelf();
        }

        private void removeSelf() {
            try {
                if (isAdded()) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
            } catch (Throwable t) {
                Log.w(CommentMedia.TAG, "picker not removed", t);
            }
        }
    }

    /** A file was picked (main thread): keep the read grant, look at the file off the main thread. */
    static void onPicked(final Context app, final Uri uri) {
        try {
            app.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable t) {
            Log.w(TAG, "no persistable grant: " + t);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Pick pick = inspect(app, uri);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            deliver(pick);
                        } catch (Throwable t) {
                            Log.w(TAG, "pick not delivered", t);
                        }
                    }
                });
            }
        }, "rif-comment-media-inspect").start();
    }

    private static Pick inspect(Context app, Uri uri) {
        Pick p = new Pick();
        p.uri = uri;
        p.at = System.currentTimeMillis();
        try {
            String mime = sniff(app, uri);
            if (mime != null && mime.startsWith("video/")) {
                p.error = NO_VIDEO;
            } else if (mime == null) {
                p.error = UNSUPPORTED;
            } else {
                p.mime = mime;
                p.name = displayName(app, uri);
            }
        } catch (Throwable t) {
            Log.w(TAG, "picked file not readable: " + t);
            p.error = PICK_AGAIN;
        }
        return p;
    }

    /** The file's type from its first bytes (GIF, PNG, JPEG, WebP, a video type), or null if it is none of those. */
    static String sniff(Context ctx, Uri uri) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("no stream for " + uri);
        try {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) throw new IOException("empty file");
            return ImageHosting.sniffMime(buf, n);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String displayName(Context ctx, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "name query failed: " + e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /** The picked file is inspected (main thread): into the open dialog, or kept for the next one. */
    private static void deliver(Pick pick) {
        if (pick.error != null) {
            toast(pick.error);
            return;
        }
        Dialog dialog = current.get();
        EditText editor = dialog != null ? dialog.editor.get() : null;
        if (editor == null || !editor.isAttachedToWindow()) {
            pending = pick;
            return;
        }
        apply(dialog, editor, pick);
    }

    private static void applyPending(EditText editor) {
        Pick pick = pending;
        Dialog dialog = current.get();
        if (pick == null || dialog == null || dialog.editor.get() != editor) return;
        pending = null;
        if (System.currentTimeMillis() - pick.at > PENDING_MS) return;
        apply(dialog, editor, pick);
    }

    /** Puts the marker for {@code pick} in the editor, unless the sub or the comment doesn't take it. */
    private static void apply(Dialog dialog, EditText editor, Pick pick) {
        Context ctx = editor.getContext().getApplicationContext();
        if (ctx == null) ctx = editor.getContext();
        boolean gif = "image/gif".equals(pick.mime);
        if (!allows(rulesFor(dialog.sub), gif)) {
            toast(gif ? NO_GIFS : NO_IMAGES);
            return;
        }
        Editable text = editor.getText();
        String orig = text.toString();
        if (hasKnownMarker(ctx, orig)) {
            toast(ONE_ONLY);
            return;
        }
        Set<String> taken = new HashSet<>();
        for (Rec r : loadRecords(ctx)) {
            if (!r.uri.equals(pick.uri.toString())) taken.add(r.marker);
        }
        String marker = markerFor(sanitizeName(pick.name, gif), gif, taken);
        int pos = editor.getSelectionEnd();
        if (pos < 0 || pos > orig.length()) pos = orig.length();
        String[] gap = spacing(orig.substring(0, pos), orig.substring(pos));
        String insert = gap[0] + marker + gap[1];
        text.insert(pos, insert);
        if (text.length() != orig.length() + insert.length()) {
            // The editor's length limit cut it: undo.
            text.replace(0, text.length(), orig);
            editor.setSelection(Math.min(pos, text.length()));
            toast(TOO_LONG);
            return;
        }
        editor.setSelection(Math.min(pos + insert.length(), text.length()));
        saveRecord(ctx, new Rec(marker, pick.uri.toString(), pick.mime, System.currentTimeMillis()));
    }

    // ------------------------------------------------------------ sub rules

    /** A sub's media rules; {@code types} is null when it has no such setting. */
    private static final class Rules {
        final List<String> types;

        Rules(List<String> types) {
            this.types = types;
        }
    }

    private static final Map<String, Rules> RULES = new HashMap<>();
    private static final Set<String> FETCHING = new HashSet<>();

    /** The allowed media types of a sub if known; null (allow) when unknown, failed or a profile. */
    private static List<String> rulesFor(String sub) {
        if (sub == null || sub.length() == 0 || isProfileSub(sub)) return null;
        synchronized (RULES) {
            Rules r = RULES.get(sub.toLowerCase(java.util.Locale.ROOT));
            return r != null ? r.types : null;
        }
    }

    /** Looks up a sub's comment media rules off the main thread, once per sub per process. */
    private static void fetchRules(final Context ctx, final String sub) {
        if (sub == null || sub.length() == 0 || isProfileSub(sub) || !sub.matches("[A-Za-z0-9_]+")) return;
        final String key = sub.toLowerCase(java.util.Locale.ROOT);
        synchronized (RULES) {
            if (RULES.containsKey(key) || !FETCHING.add(key)) return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ImageHosting.Resp r = ImageHosting.authed(ctx, API + "/r/" + sub + "/about.json?raw_json=1", null);
                    if (r.code == 200) {
                        synchronized (RULES) {
                            RULES.put(key, new Rules(allowedMediaTypes(r.body)));
                        }
                    } else {
                        Log.w(TAG, "rules for r/" + sub + ": HTTP " + r.code);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "rules for r/" + sub + " not read: " + t);
                } finally {
                    synchronized (RULES) {
                        FETCHING.remove(key);
                    }
                }
            }
        }, "rif-comment-media-rules").start();
    }

    // ----------------------------------------------------------------- send

    /** What a sent rich-text comment needs from its reply (see {@link #wrapCommentResponse}). */
    private static final class Sent {
        final String assetId;
        final String ext;

        Sent(String assetId, String ext) {
            this.assetId = assetId;
            this.ext = ext;
        }
    }

    /** Per CommentReplyTask: the parameters built for it (the task asks twice after a token refresh), and what it sent. */
    private static final Map<Object, c<String, String>[]> BUILT = Collections.synchronizedMap(new WeakHashMap<Object, c<String, String>[]>());
    private static final Map<Object, Sent> SENT = Collections.synchronizedMap(new WeakHashMap<Object, Sent>());

    /** A failure the user was told about already. */
    static final class SendFailed extends RuntimeException {
        SendFailed(String message) {
            super(message);
        }
    }

    /**
     * Hook (CommentReplyTask.d0, before it returns; background thread): the comment's text has no
     * marker, so the parameters are returned as they are, or its image is uploaded and the comment is
     * sent as rich text. A failure shows a toast and throws, so rif keeps the dialog and the text.
     */
    public static c<String, String>[] commentParams(c<String, String>[] pairs, Object task) {
        boolean working = false;
        try {
            if (pairs == null) return pairs;
            String text = null;
            for (c<String, String> p : pairs) {
                if (p != null && "text".equals(p.a)) text = p.b;
            }
            if (text == null) return pairs;
            c<String, String>[] built = BUILT.get(task);
            if (built != null) return built;
            Context ctx = Settings.context();
            if (ctx == null) return pairs;
            List<Marker> markers = knownMarkers(text, knownMarkerSet(ctx));
            if (markers.isEmpty()) return pairs;
            if (markers.size() > 1) throw fail(ONE_ONLY);
            working = true;
            built = send(ctx, pairs, text, markers.get(0), task);
            BUILT.put(task, built);
            return built;
        } catch (SendFailed e) {
            throw e;
        } catch (Throwable t) {
            Log.w(TAG, "comment params failed", t);
            throw fail("Couldn't send the image. Try again.");
        } finally {
            if (working) hideProgress();
        }
    }

    private static c<String, String>[] send(Context ctx, c<String, String>[] pairs, String text, Marker marker, Object task) {
        Rec rec = recordFor(ctx, marker.text);
        if (rec == null) throw fail(PICK_AGAIN);
        Uri uri = Uri.parse(rec.uri);
        String mime;
        try {
            mime = sniff(ctx, uri);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "picked file gone: " + e);
            throw fail(PICK_AGAIN);
        }
        if (mime == null || mime.startsWith("video/")) throw fail(mime == null ? UNSUPPORTED : NO_VIDEO);
        boolean gif = "image/gif".equals(mime);
        long length = ImageHosting.size(uri, ctx);
        if (length <= 0) throw fail(PICK_AGAIN);
        if (gif && length > MAX_GIF_BYTES) throw fail(GIF_TOO_BIG);

        File shrunk = null;
        try {
            Uri toUpload = uri;
            if (!gif && length > ImageHosting.MAX_STILL_BYTES) {
                showProgress("Shrinking image");
                shrunk = shrinkStill(ctx, uri, mime, ImageHosting.MAX_STILL_BYTES);
                toUpload = Uri.fromFile(shrunk);
                mime = "image/jpeg";
                length = shrunk.length();
            }
            final long total = length;
            showProgress("Uploading 0%");
            ImageHosting.Asset asset;
            try {
                asset = ImageHosting.uploadOnly(toUpload, ctx, new ImageHosting.Progress() {
                    private int last = -1;

                    @Override
                    public void sent(long bytes) {
                        int pct = total > 0 ? (int) Math.min(99, bytes * 100 / total) : 0;
                        if (pct == last) return;
                        last = pct;
                        showProgress("Uploading " + pct + "%");
                    }

                    @Override
                    public void reset() {
                        last = -1;
                        showProgress("Uploading 0%");
                    }
                });
            } catch (ImageHosting.UploadException e) {
                Log.w(TAG, "upload failed: " + e.getMessage());
                throw fail(e.getMessage());
            }
            showProgress("Sending");

            String markdown = isolate(text, marker.start, marker.end, token(asset.id, gif));
            JSONObject document = convert(ctx, markdown);
            int[] media = countMedia(document, asset.id, gif);
            if (media[0] != 1 || media[1] != 1) {
                Log.w(TAG, "converted document has " + media[0] + " media blocks, " + media[1] + " ours");
                throw fail("Couldn't send the image. Try again.");
            }
            c<String, String>[] out = withRichtext(pairs, document.toString());
            SENT.put(task, new Sent(asset.id, urlExtension(mime)));
            return out;
        } finally {
            if (shrunk != null) shrunk.delete();
        }
    }

    /** Reddit's own markdown to rich-text conversion (/api/convert_rte_body_format); the "output" document. */
    private static JSONObject convert(Context ctx, String markdown) {
        try {
            String form = "output_mode=rtjson&raw_json=1&markdown_text=" + URLEncoder.encode(markdown, "UTF-8");
            ImageHosting.Resp r = ImageHosting.authed(ctx, API + "/api/convert_rte_body_format", form.getBytes(UTF8));
            if (r.code != 200) {
                Log.w(TAG, "convert " + r.code + ": " + r.body);
                throw fail("Reddit couldn't convert the comment (HTTP " + r.code + ").");
            }
            JSONObject doc = convertedDocument(r.body);
            if (doc == null) {
                Log.w(TAG, "convert reply without document: " + r.body);
                throw fail("Reddit couldn't convert the comment.");
            }
            return doc;
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        } catch (ImageHosting.UploadException e) {
            Log.w(TAG, "convert request failed: " + e.getMessage());
            throw fail(e.getMessage());
        }
    }

    /** Shows {@code message} as a toast and returns the exception to throw. */
    private static SendFailed fail(String message) {
        toast(message);
        return new SendFailed(message);
    }

    static void toast(final String message) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = Settings.context();
                    if (ctx != null) Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Log.w(TAG, "toast failed", t);
                }
            }
        });
    }

    private static void showProgress(final String message) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Dialog d = current.get();
                    TextView tv = d != null ? d.progress.get() : null;
                    if (tv != null) {
                        tv.setText(message);
                        tv.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "progress not shown", t);
                }
            }
        });
    }

    private static void hideProgress() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Dialog d = current.get();
                    TextView tv = d != null ? d.progress.get() : null;
                    if (tv != null) tv.setVisibility(View.GONE);
                } catch (Throwable t) {
                    Log.w(TAG, "progress not hidden", t);
                }
            }
        });
    }

    // ---------------------------------------------------------------- reply

    /**
     * Hook (CommentReplyTask.j0, at entry): the reply of a comment sent as rich text is wrapped for
     * rif ({@link #wrapComment}); every other reply (and any reply with Reddit's errors, which rif
     * shows itself) is returned as it was.
     */
    public static InputStream wrapCommentResponse(Object task, InputStream in) {
        Sent sent = SENT.remove(task);
        if (sent == null || in == null) return in;
        byte[] raw;
        try {
            raw = readAll(in);
        } catch (IOException e) {
            Log.w(TAG, "response read failed: " + e);
            return new ByteArrayInputStream(new byte[0]);
        }
        try {
            String text = new String(raw, UTF8);
            text = waitForProcessed(text);
            String wrapped = wrapComment(text, sent.assetId, sent.ext);
            if (wrapped == null) {
                String error = firstError(text);
                if (error != null) Log.w(TAG, "Reddit refused the comment: " + error);
                return new ByteArrayInputStream(raw);
            }
            return new ByteArrayInputStream(wrapped.getBytes(UTF8));
        } catch (Throwable t) {
            Log.w(TAG, "response rewrite failed", t);
            return new ByteArrayInputStream(raw);
        }
    }

    /**
     * A flat reply whose body still has the placeholder: polls the new comment until Reddit has
     * rewritten its body (the media's real link), for at most {@link #POLL_MAX_MS}, and returns the
     * reply with that body. Any other reply, a timeout or an error gives the reply back unchanged: the
     * comment is posted already, so this must never fail the send. Background thread.
     */
    private static String waitForProcessed(String reply) {
        boolean shown = false;
        try {
            JSONObject flat = new JSONObject(reply);
            if (flat.has("json") || !hasPlaceholder(flat.optString("body", ""))) return reply;
            String[] target = pollTarget(reply);
            Context ctx = Settings.context();
            if (target == null || ctx == null) {
                Log.w(TAG, "no processing poll: target " + (target != null) + ", context " + (ctx != null));
                return reply;
            }
            String url = API + "/comments/" + target[0] + ".json?comment=" + target[1] + "&raw_json=1&limit=1&context=0";
            showProgress("Processing…");
            shown = true;
            long start = SystemClock.elapsedRealtime();
            int polls = 0;
            while (SystemClock.elapsedRealtime() - start + POLL_MS <= POLL_MAX_MS) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return reply;
                }
                polls++;
                try {
                    ImageHosting.Resp r = ImageHosting.authed(ctx, url, null);
                    if (r.code != 200) {
                        Log.w(TAG, "processing poll " + polls + ": HTTP " + r.code);
                        continue;
                    }
                    JSONObject comment = commentFromListing(r.body, target[1]);
                    if (comment == null) {
                        Log.w(TAG, "processing poll " + polls + ": comment not in the listing");
                        continue;
                    }
                    String merged = mergeProcessed(reply, comment);
                    if (!merged.equals(reply)) {
                        Log.d(TAG, "processed after "+ (SystemClock.elapsedRealtime() - start) + " ms (" + polls + " polls)");
                        return merged;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "processing poll " + polls + " failed: " + t);
                }
            }
            Log.w(TAG, "still processing after " + polls + " polls");
        } catch (Throwable t) {
            Log.w(TAG, "waiting for the processed comment failed", t);
        } finally {
            if (shown) hideProgress();
        }
        return reply;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // --------------------------------------------------------------- shrink

    /**
     * A JPEG copy of a still image that fits {@code maxBytes}: decoded at most 4096 px on a side
     * (turned upright by its EXIF orientation), saved at quality 90 and, if still too big, lower
     * quality and then smaller. A PNG's transparency becomes white. The file is in the cache dir.
     */
    private static File shrinkStill(Context ctx, Uri uri, String mime, long maxBytes) {
        File src = null;
        try {
            src = File.createTempFile("rif_cm_src", ".img", ctx.getCacheDir());
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw fail(PICK_AGAIN);
            try {
                OutputStream out = new FileOutputStream(src);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw fail("Couldn't shrink the image.");
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2;
            Bitmap bitmap = null;
            for (int tries = 0; bitmap == null && tries < 4; tries++, sample *= 2) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                try {
                    bitmap = BitmapFactory.decodeFile(src.getPath(), o);
                } catch (OutOfMemoryError e) {
                    Log.w(TAG, "decode out of memory at sample " + sample);
                }
            }
            if (bitmap == null) throw fail("Couldn't shrink the image.");
            bitmap = upright(bitmap, "image/jpeg".equals(mime) ? exifOrientation(src) : ExifInterface.ORIENTATION_NORMAL);
            if (bitmap.hasAlpha()) {
                Bitmap flat = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(flat);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap, 0, 0, null);
                bitmap.recycle();
                bitmap = flat;
            }
            File out = File.createTempFile("rif_cm_out", ".jpg", ctx.getCacheDir());
            byte[] bytes = null;
            for (int step = 0; step < 8 && bytes == null; step++) {
                for (int quality = 90; quality >= 60 && bytes == null; quality -= 15) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buf);
                    if (buf.size() <= maxBytes) bytes = buf.toByteArray();
                }
                if (bytes == null) {
                    Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * 3 / 4),
                            Math.max(1, bitmap.getHeight() * 3 / 4), true);
                    if (smaller != bitmap) bitmap.recycle();
                    bitmap = smaller;
                }
            }
            bitmap.recycle();
            if (bytes == null) throw fail("Couldn't shrink the image.");
            OutputStream os = new FileOutputStream(out);
            try {
                os.write(bytes);
            } finally {
                os.close();
            }
            return out;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            if (e instanceof SendFailed) throw (SendFailed) e;
            Log.w(TAG, "shrink failed: " + e);
            throw fail("Couldn't shrink the image.");
        } finally {
            if (src != null) src.delete();
        }
    }

    private static int exifOrientation(File file) {
        try {
            return new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap upright(Bitmap bitmap, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return bitmap;
        }
        Bitmap turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        if (turned != bitmap) bitmap.recycle();
        return turned;
    }
}
