package app.revanced.extension.rif;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.util.LruCache;
import android.util.Size;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

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
 *    only that TextView. Re-binds stop the previously-started animatables.
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

    // Animatables currently started per TextView, so a recycled row can stop them.
    private static final WeakHashMap<TextView, List<Animatable>> RUNNING = new WeakHashMap<>();

    private static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    // ---- background: embed images into the comment spannable -------------------

    public static void embed(SpannableStringBuilder body) {
        try {
            if (body == null) return;
            if (!Settings.inlineImages()) return; // feature disabled in settings
            if (Looper.myLooper() == Looper.getMainLooper()) return; // never block UI

            URLSpan[] links = body.getSpans(0, body.length(), URLSpan.class);
            if (links == null || links.length == 0) return;

            for (URLSpan link : links) {
                try {
                    String url = link.getURL();
                    if (!isDirectImage(url)) continue;

                    int start = body.getSpanStart(link);
                    int end = body.getSpanEnd(link);
                    if (start < 0 || end < 0 || start >= end) continue;

                    byte[] data = fetch(url);
                    if (data == null) continue;

                    Drawable drawable = toDrawable(data);
                    if (drawable == null) continue;

                    boolean leading = isBlank(body, 0, start);
                    ImageSpan span = leading
                            ? new LeadingSpacedImageSpan(drawable)
                            : new ImageSpan(drawable, ImageSpan.ALIGN_BASELINE);
                    body.setSpan(span, start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                } catch (Throwable ignored) {
                    // leave this link as a plain link
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- main thread: start/stop GIF animation for a bound TextView ------------

    public static void attach(TextView tv) {
        try {
            if (tv == null) return;

            // Stop animatables started for the previous comment on this recycled view.
            List<Animatable> prev = RUNNING.remove(tv);
            if (prev != null) {
                for (Animatable a : prev) {
                    try {
                        a.stop();
                        if (a instanceof Drawable) ((Drawable) a).setCallback(null);
                    } catch (Throwable ignored) {
                    }
                }
            }

            CharSequence cs = tv.getText();
            if (!(cs instanceof Spanned)) return;
            Spanned sp = (Spanned) cs;
            ImageSpan[] spans = sp.getSpans(0, sp.length(), ImageSpan.class);
            if (spans.length == 0) return;

            Drawable.Callback cb = null;
            List<Animatable> started = new ArrayList<>();
            for (ImageSpan span : spans) {
                Drawable d = span.getDrawable();
                if (d instanceof Animatable) {
                    if (cb == null) cb = callbackFor(tv);
                    d.setCallback(cb);
                    Animatable anim = (Animatable) d;
                    if (!anim.isRunning()) anim.start();
                    started.add(anim);
                }
            }
            if (!started.isEmpty()) RUNNING.put(tv, started);
        } catch (Throwable ignored) {
        }
    }

    private static Drawable.Callback callbackFor(final TextView tv) {
        return new Drawable.Callback() {
            @Override
            public void invalidateDrawable(Drawable who) {
                tv.invalidate();
            }

            @Override
            public void scheduleDrawable(Drawable who, Runnable what, long when) {
                tv.postDelayed(what, Math.max(0, when - SystemClock.uptimeMillis()));
            }

            @Override
            public void unscheduleDrawable(Drawable who, Runnable what) {
                tv.removeCallbacks(what);
            }
        };
    }

    // ---- decoding --------------------------------------------------------------

    private static Drawable toDrawable(byte[] data) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isGif(data)) {
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
    private static int[] outSize(int w, int h) {
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

    private static byte[] fetch(String url) {
        byte[] cached = BYTES.get(url);
        if (cached != null) return cached;
        byte[] data = download(url);
        if (data != null) BYTES.put(url, data);
        return data;
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
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

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

    private static boolean isBlank(CharSequence cs, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) return false;
        }
        return true;
    }

    /**
     * ImageSpan that reserves ~1/3 of a text line of extra space above the image
     * via the line ascent. Used only for a leading image so it sits a little
     * below the comment header instead of crowding it; the image itself stays
     * bottom-aligned (inherited draw), so the padding lands above it.
     */
    private static final class LeadingSpacedImageSpan extends ImageSpan {
        LeadingSpacedImageSpan(Drawable d) {
            super(d, ImageSpan.ALIGN_BASELINE);
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
