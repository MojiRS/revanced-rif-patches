package app.revanced.extension.rif;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.core.util.c;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Reddit image hosting": the Submit screen's Image tab uploads to Reddit instead of imgur and
 * submits a native image post.
 *
 * rif's flow stays as it is (pick, size dialog, progress bar, URL field, submit as a link post);
 * hooks swap the parts underneath:
 * <ul>
 * <li>{@link #upload}: replaces the body of rif's uploader task. Reddit's media lease
 * (/api/media/asset.json), then a multipart POST to S3. Returns the asset URL, which rif puts in
 * the URL field.</li>
 * <li>{@link #needsResize}: GIFs are never resized, stills only above 20 MB.</li>
 * <li>{@link #fixParams}: SubmitTask's form parameters; a link post to one of our asset URLs
 * becomes kind=image (an asset that was already posted is uploaded again first).</li>
 * <li>{@link #wrapResponse}: Reddit answers an image post with a websocket URL and no post; the
 * post is looked up on the user's submitted page so rif can open it.</li>
 * </ul>
 */
public final class ImageHosting {

    private static final String TAG = "RifImageHosting";
    private static final String API = "https://oauth.reddit.com";
    private static final String FALLBACK_VERSION = "5.6.22";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Largest still image sent as is (bytes); larger ones are resized to this. */
    static final long MAX_STILL_BYTES = 20L * 1024 * 1024;

    private static final int MAX_ASSETS = 8;
    private static final long POLL_INTERVAL_MS = 1500;
    private static final long POLL_TIMEOUT_MS = 30000;

    /** An uploaded asset: where it came from, so a second post can upload it again. */
    private static final class Asset {
        final String url;
        final String id;
        final Uri source;
        final Context context;
        boolean submitted;

        Asset(String url, String id, Uri source, Context context) {
            this.url = url;
            this.id = id;
            this.source = source;
            this.context = context;
        }
    }

    /** Asset id to asset, newest last. */
    private static final Map<String, Asset> ASSETS = new LinkedHashMap<String, Asset>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Asset> eldest) {
            return size() > MAX_ASSETS;
        }
    };

    /** The image post just sent (set by {@link #fixParams}, used once by {@link #wrapResponse}). */
    private static Asset pendingPost;

    /** Error text of the upload that just failed on this thread; see {@link #takeError}. */
    private static final ThreadLocal<String> ERROR = new ThreadLocal<>();

    /** A failure whose message is shown to the user as is. */
    static final class UploadException extends Exception {
        UploadException(String message) {
            super(message);
        }
    }

    private interface Progress {
        void sent(long bytes);
    }

    private ImageHosting() {}

    // ---------------------------------------------------------------- hooks

    /**
     * Hook 1: replaces the body of rif's uploader's doInBackground (runs on a background
     * thread). {@code task} is rif's uploader, a PropertyChangeListener that feeds the
     * progress bar. Returns the asset URL, or null with {@link #takeError} set.
     */
    public static String upload(Object task, Uri uri, Context context) {
        ERROR.remove();
        try {
            Context ctx = context != null ? context : Settings.context();
            if (ctx == null || uri == null) throw new UploadException("Couldn't read the image.");
            final PropertyChangeListener listener =
                    task instanceof PropertyChangeListener ? (PropertyChangeListener) task : null;
            Progress progress = new Progress() {
                private long last = -1;

                @Override
                public void sent(long bytes) {
                    if (listener == null || bytes - last < 32 * 1024) return;
                    last = bytes;
                    try {
                        listener.propertyChange(new PropertyChangeEvent(listener, "sent", null, Long.valueOf(bytes)));
                    } catch (Throwable ignored) {
                    }
                }
            };
            Asset asset = uploadAsset(uri, ctx, progress);
            return asset.url;
        } catch (UploadException e) {
            Log.w(TAG, "upload failed: " + e.getMessage());
            ERROR.set(e.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "upload failed", t);
            ERROR.set("Reddit upload failed.");
        }
        return null;
    }

    /** The error text left by the last {@link #upload} failure on this thread (cleared by reading). */
    public static String takeError() {
        String e = ERROR.get();
        ERROR.remove();
        return e;
    }

    /** The file's size in bytes for rif's progress bar, or -1 (indeterminate). */
    public static long size(Uri uri, Context context) {
        try {
            Context ctx = context != null ? context : Settings.context();
            long n = countAndSniff(ctx, uri, true).length;
            return n > 0 ? n : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Hook 2: whether rif should resize the picked image before uploading. GIFs never
     * (resizing flattens them to one frame); stills only above 20 MB.
     */
    public static boolean needsResize(Uri uri, long size) {
        if (size <= MAX_STILL_BYTES) return false;
        try {
            Context ctx = Settings.context();
            if (ctx != null && uri != null) {
                return !"image/gif".equals(countAndSniff(ctx, uri, false).mime);
            }
        } catch (Throwable t) {
            Log.w(TAG, "mime sniff failed", t);
        }
        return true;
    }

    /**
     * Hook 3: SubmitTask.d0's form parameters. A link post to one of our asset URLs becomes an
     * image post. A reused asset makes a broken post, so one that was sent before is uploaded again.
     */
    public static c<String, String>[] fixParams(c<String, String>[] pairs) {
        try {
            if (pairs == null) return pairs;
            int kindAt = -1;
            int urlAt = -1;
            for (int i = 0; i < pairs.length; i++) {
                if (pairs[i] == null) continue;
                if ("kind".equals(pairs[i].a)) kindAt = i;
                else if ("url".equals(pairs[i].a)) urlAt = i;
            }
            if (kindAt < 0 || urlAt < 0 || !"link".equals(pairs[kindAt].b)) return pairs;
            String url = pairs[urlAt].b;
            Asset asset;
            synchronized (ASSETS) {
                asset = ASSETS.get(assetIdOf(url));
            }
            if (asset == null) return pairs;
            if (asset.submitted) {
                try {
                    Asset again = uploadAsset(asset.source, asset.context, null);
                    asset = again;
                    pairs[urlAt] = c.a("url", again.url);
                } catch (Throwable t) {
                    Log.w(TAG, "re-upload failed: " + t);
                }
            }
            asset.submitted = true;
            pairs[kindAt] = c.a("kind", "image");
            synchronized (ASSETS) {
                pendingPost = asset;
            }
        } catch (Throwable t) {
            Log.w(TAG, "submit params failed", t);
        }
        return pairs;
    }

    /**
     * Hook 4: SubmitTask.k0's response stream. Only touched for an image post made through
     * {@link #fixParams}; any other response is returned as it was.
     */
    public static InputStream wrapResponse(InputStream in) {
        Asset asset;
        synchronized (ASSETS) {
            asset = pendingPost;
            pendingPost = null;
        }
        if (asset == null || in == null) return in;
        byte[] raw;
        try {
            raw = readAll(in);
        } catch (IOException e) {
            Log.w(TAG, "response read failed: " + e);
            return new ByteArrayInputStream(new byte[0]);
        }
        try {
            String userPage = pendingReplyUserPage(new String(raw, UTF8));
            if (userPage == null) return new ByteArrayInputStream(raw);
            String[] post = waitForPost(asset);
            String reply = post != null
                    ? successReply("https://www.reddit.com" + post[0], post[1])
                    : successReply(userPage, "");
            return new ByteArrayInputStream(reply.getBytes(UTF8));
        } catch (Throwable t) {
            Log.w(TAG, "response rewrite failed", t);
            return new ByteArrayInputStream(raw);
        }
    }

    // ------------------------------------------------------- pure helpers

    /** Reddit's asset URL for an uploaded id: what the Submit form's URL field holds. */
    static String assetUrl(String action, String assetId) {
        String base = action.startsWith("//") ? "https:" + action : action;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + assetId;
    }

    /** The asset id in one of Reddit's upload URLs (…amazonaws.com/{id}), else null. */
    static String assetIdOf(String url) {
        if (url == null || !url.startsWith("https://reddit-uploaded-media.")) return null;
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String id = path.substring(path.lastIndexOf('/') + 1);
        return id.length() > 0 ? id : null;
    }

    /** MIME type from a file's first bytes; null if it isn't GIF, PNG, JPEG or WebP. */
    static String sniffMime(byte[] b, int n) {
        if (n >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (n >= 4 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (n >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    static String extensionFor(String mime) {
        if ("image/gif".equals(mime)) return "gif";
        if ("image/png".equals(mime)) return "png";
        if ("image/webp".equals(mime)) return "webp";
        return "jpg";
    }

    /** The parts of Reddit's /api/media/asset.json reply the upload needs. */
    static final class Lease {
        String action;
        String id;
        final List<String[]> fields = new ArrayList<>();
    }

    static Lease parseLease(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONObject args = o.getJSONObject("args");
        Lease lease = new Lease();
        lease.action = args.getString("action");
        lease.id = o.getJSONObject("asset").getString("asset_id");
        JSONArray fields = args.getJSONArray("fields");
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.getJSONObject(i);
            lease.fields.add(new String[] {f.getString("name"), f.getString("value")});
        }
        return lease;
    }

    /** The multipart body up to the file's bytes: every lease field in order, then the file part's header. */
    static byte[] multipartHead(String boundary, List<String[]> fields, String fileName, String mime) {
        StringBuilder sb = new StringBuilder();
        for (String[] f : fields) {
            sb.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(f[0]).append("\"\r\n\r\n")
                    .append(f[1]).append("\r\n");
        }
        sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
                .append("Content-Type: ").append(mime).append("\r\n\r\n");
        return sb.toString().getBytes(UTF8);
    }

    static byte[] multipartTail(String boundary) {
        return ("\r\n--" + boundary + "--\r\n").getBytes(UTF8);
    }

    /**
     * Non-null (the user's submitted page, possibly empty) when the submit reply is the image
     * post's "processing" reply: no errors, a websocket URL and no post url.
     */
    static String pendingReplyUserPage(String body) {
        try {
            JSONObject json = new JSONObject(body).getJSONObject("json");
            JSONArray errors = json.optJSONArray("errors");
            if (errors != null && errors.length() > 0) return null;
            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;
            if (data.optString("url", "").length() > 0) return null;
            if (data.optString("websocket_url", "").length() == 0) return null;
            return data.optString("user_submitted_page", "");
        } catch (JSONException e) {
            return null;
        }
    }

    /** {permalink, id} of the newest post in a submitted listing whose url holds i.redd.it/{assetId}, or null. */
    static String[] findPost(String listing, String assetId) {
        try {
            JSONArray children = new JSONObject(listing).getJSONObject("data").getJSONArray("children");
            String needle = "i.redd.it/" + assetId;
            for (int i = 0; i < children.length(); i++) {
                JSONObject d = children.getJSONObject(i).getJSONObject("data");
                String url = d.optString("url", "");
                String permalink = d.optString("permalink", "");
                String id = d.optString("id", "");
                if (url.contains(needle) && permalink.startsWith("/") && id.length() > 0) {
                    return new String[] {permalink, id};
                }
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    /** A submit reply in the shape rif's SubmitTask parses; {@code id} may be empty. */
    static String successReply(String url, String id) {
        try {
            JSONObject data = new JSONObject();
            data.put("url", url);
            data.put("id", id);
            data.put("name", id.length() > 0 ? "t3_" + id : "");
            JSONObject json = new JSONObject();
            json.put("errors", new JSONArray());
            json.put("data", data);
            return new JSONObject().put("json", json).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ uploading

    /** Uploads {@code uri} to Reddit and remembers it for the submit hooks. */
    private static Asset uploadAsset(Uri uri, Context ctx, Progress progress) throws UploadException {
        Sniff sniff;
        try {
            sniff = countAndSniff(ctx, uri, true);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "read failed: " + e);
            throw new UploadException("Couldn't read the image.");
        }
        if (sniff.length <= 0) throw new UploadException("Couldn't read the image.");
        String fileName = "image." + extensionFor(sniff.mime);

        String form;
        try {
            form = "filepath=" + URLEncoder.encode(fileName, "UTF-8")
                    + "&mimetype=" + URLEncoder.encode(sniff.mime, "UTF-8") + "&raw_json=1";
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        Resp lease = authed(ctx, API + "/api/media/asset.json", form.getBytes(UTF8));
        if (lease.code != 200) {
            Log.w(TAG, "asset request " + lease.code + ": " + lease.body);
            throw new UploadException("Reddit upload failed (HTTP " + lease.code + ").");
        }
        Lease l;
        try {
            l = parseLease(lease.body);
        } catch (JSONException e) {
            Log.w(TAG, "asset reply: " + e);
            throw new UploadException("Reddit upload failed (bad reply).");
        }

        postToS3(ctx, uri, l, fileName, sniff, progress);

        Context app = ctx.getApplicationContext();
        Asset asset = new Asset(assetUrl(l.action, l.id), l.id, uri, app != null ? app : ctx);
        synchronized (ASSETS) {
            ASSETS.put(l.id, asset);
        }
        return asset;
    }

    private static void postToS3(Context ctx, Uri uri, Lease l, String fileName, Sniff sniff, Progress progress)
            throws UploadException {
        String boundary = "----rif" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = multipartHead(boundary, l.fields, fileName, sniff.mime);
        byte[] tail = multipartTail(boundary);
        String action = l.action.startsWith("//") ? "https:" + l.action : l.action;
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(action).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setFixedLengthStreamingMode(head.length + sniff.length + tail.length);
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new UploadException("Couldn't read the image.");
            OutputStream out = conn.getOutputStream();
            out.write(head);
            byte[] buf = new byte[16 * 1024];
            long sent = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sent += n;
                if (progress != null) progress.sent(sent);
            }
            out.write(tail);
            out.close();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = readText(conn.getErrorStream());
                Log.w(TAG, "S3 upload " + code + ": " + body);
                throw new UploadException("Reddit upload failed (HTTP " + code + ").");
            }
        } catch (IOException e) {
            Log.w(TAG, "S3 upload failed: " + e);
            throw new UploadException("Reddit upload failed (network error).");
        } finally {
            closeQuietly(in);
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------- requests

    private static final class Resp {
        int code;
        String body;
    }

    /** One request to oauth.reddit.com with the user's token; a 401/403 gets a fresh token and one retry. */
    private static Resp authed(Context ctx, String url, byte[] form) throws UploadException {
        String token = token(ctx);
        Resp r = call(ctx, url, form, token);
        if (r.code == 401 || r.code == 403) {
            try {
                AccountManager.get(ctx).invalidateAuthToken("com.reddit", token);
            } catch (Throwable ignored) {
            }
            token = token(ctx);
            r = call(ctx, url, form, token);
        }
        return r;
    }

    private static Resp call(Context ctx, String url, byte[] form, String token) throws UploadException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", userAgent(ctx));
            conn.setRequestProperty("Authorization", "bearer " + token);
            if (form != null) {
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream out = conn.getOutputStream();
                out.write(form);
                out.close();
            }
            Resp r = new Resp();
            r.code = conn.getResponseCode();
            r.body = readText(r.code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return r;
        } catch (IOException e) {
            Log.w(TAG, "request failed: " + e);
            throw new UploadException("Reddit upload failed (network error).");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String userName(Context ctx) {
        return ctx.getSharedPreferences("credentials", Context.MODE_PRIVATE).getString("username", null);
    }

    /** rif's own sign-in token for the logged-in account (blocking: background threads only). */
    private static String token(Context ctx) throws UploadException {
        String name = userName(ctx);
        if (name == null || name.length() == 0) throw new UploadException("Log in to upload images.");
        try {
            String t = AccountManager.get(ctx).blockingGetAuthToken(new Account(name, "com.reddit"), "redditisfun_oauth2", false);
            if (t != null && t.length() > 0) return t;
        } catch (Exception e) {
            Log.w(TAG, "token failed: " + e);
        }
        throw new UploadException("Reddit sign-in failed. Open rif and log in again.");
    }

    private static String userAgent(Context ctx) {
        String v = FALLBACK_VERSION;
        try {
            String name = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            if (name != null) v = name;
        } catch (Throwable ignored) {
        }
        return "reddit is fun (Android) " + v;
    }

    // ------------------------------------------------------ response lookup

    /** Polls the user's newest posts until the one for {@code asset} shows up; {permalink, id} or null. */
    private static String[] waitForPost(Asset asset) {
        long end = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        try {
            String name = userName(asset.context);
            if (name == null || name.length() == 0) return null;
            String url = API + "/user/" + URLEncoder.encode(name, "UTF-8") + "/submitted.json?sort=new&limit=5&raw_json=1";
            while (System.currentTimeMillis() < end) {
                Thread.sleep(POLL_INTERVAL_MS);
                Resp r = authed(asset.context, url, null);
                if (r.code == 200) {
                    String[] post = findPost(r.body, asset.id);
                    if (post != null) return post;
                } else {
                    Log.w(TAG, "submitted listing " + r.code);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.w(TAG, "post lookup failed: " + t);
        }
        return null;
    }

    // -------------------------------------------------------------- file io

    private static final class Sniff {
        String mime;
        long length;
    }

    private static Sniff countAndSniff(Context ctx, Uri uri) throws IOException {
        return countAndSniff(ctx, uri, false);
    }

    /** MIME type from the bytes (else the provider's), and the byte count when {@code count} is set. */
    private static Sniff countAndSniff(Context ctx, Uri uri, boolean count) throws IOException {
        Sniff s = new Sniff();
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("no stream for " + uri);
        try {
            byte[] buf = new byte[16 * 1024];
            int n = in.read(buf);
            if (n <= 0) return s;
            s.length = n;
            s.mime = sniffMime(buf, n);
            if (s.mime == null) {
                String t = ctx.getContentResolver().getType(uri);
                s.mime = t != null && t.startsWith("image/") ? t : "image/jpeg";
            }
            if (!count) return s;
            int m;
            while ((m = in.read(buf)) > 0) s.length += m;
            return s;
        } finally {
            closeQuietly(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String readText(InputStream in) {
        if (in == null) return "";
        try {
            byte[] b = readAll(in);
            return new String(b, 0, Math.min(b.length, 1 << 20), UTF8);
        } catch (IOException e) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
