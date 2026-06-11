package app.revanced.extension.rif;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Inline comment images.
 *
 * Injected at the start of rif's CommentThing.e(SpannableStringBuilder), which
 * receives the fully-rendered comment body (with link spans already applied) on
 * a background thread, before the body is cached/displayed. For each link span
 * that points at a direct image, we synchronously fetch + scale the bitmap and
 * overlay an ImageSpan over the link text — so the image is embedded before the
 * row is ever measured (no async invalidation or RecyclerView resize needed).
 *
 * v1 scope: direct image links only (path ends in a known image extension, or a
 * known direct-image host like i.redd.it). The original link span is kept, so
 * tapping the image still opens it full-screen.
 */
public final class InlineImages {

    private InlineImages() {}

    // ~16 MB bitmap cache keyed by URL, shared across all comments.
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final int MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    /** Entry point invoked from the patched CommentThing.e(). */
    public static void embed(SpannableStringBuilder body) {
        try {
            if (body == null) return;
            // Never block the UI thread; this should always run on i0's worker.
            if (Looper.myLooper() == Looper.getMainLooper()) return;

            URLSpan[] links = body.getSpans(0, body.length(), URLSpan.class);
            if (links == null || links.length == 0) return;

            for (URLSpan link : links) {
                try {
                    String url = link.getURL();
                    if (!isDirectImage(url)) continue;

                    int start = body.getSpanStart(link);
                    int end = body.getSpanEnd(link);
                    if (start < 0 || end < 0 || start >= end) continue;

                    Bitmap bmp = load(url);
                    if (bmp == null) continue;

                    BitmapDrawable d = new BitmapDrawable(Resources.getSystem(), bmp);
                    d.setBounds(0, 0, bmp.getWidth(), bmp.getHeight());
                    // Overlay the image over the link text. The link span stays
                    // underneath so tapping still opens the image full-screen.
                    body.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE),
                            start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                } catch (Throwable ignored) {
                    // Leave this link as a plain link on any failure.
                }
            }
        } catch (Throwable ignored) {
            // Never break comment rendering.
        }
    }

    private static boolean isDirectImage(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        // Strip query/fragment before checking the extension.
        int cut = u.indexOf('?');
        if (cut >= 0) u = u.substring(0, cut);
        cut = u.indexOf('#');
        if (cut >= 0) u = u.substring(0, cut);

        if (u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png")
                || u.endsWith(".webp") || u.endsWith(".gif") || u.endsWith(".bmp")) {
            return true;
        }
        // i.redd.it / preview.redd.it serve direct images even without an extension.
        return u.startsWith("https://i.redd.it/") || u.startsWith("https://preview.redd.it/");
    }

    private static Bitmap load(String url) {
        Bitmap cached = CACHE.get(url);
        if (cached != null) return cached;

        byte[] data = download(url);
        if (data == null) return null;

        Bitmap bmp = decodeScaled(data);
        if (bmp != null) CACHE.put(url, bmp);
        return bmp;
    }

    private static byte[] download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "rif-inline-images");
            conn.setRequestProperty("Accept", "image/*");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return null;

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_DOWNLOAD_BYTES) return null;
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap decodeScaled(byte[] data) {
        // Target width = screen width minus a small margin; cap height so a tall
        // image can't produce an absurd row.
        Resources res = Resources.getSystem();
        int screenW = res.getDisplayMetrics().widthPixels;
        int targetW = Math.max(1, screenW - dp(res, 24));
        int maxH = screenW * 2;

        // First pass: bounds only.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        // Second pass: subsample down toward the target width.
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, targetW);
        Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (decoded == null) return null;

        // Scale to exactly targetW (preserve aspect), then clamp height.
        int w = decoded.getWidth();
        int h = decoded.getHeight();
        if (w <= 0 || h <= 0) return decoded;

        float scale = (float) targetW / (float) w;
        int outW = targetW;
        int outH = Math.round(h * scale);
        if (outH > maxH) {
            outH = maxH;
            outW = Math.round(w * ((float) maxH / (float) h));
        }
        if (outW <= 0 || outH <= 0) return decoded;

        Bitmap scaled = Bitmap.createScaledBitmap(decoded, outW, outH, true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
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
}
