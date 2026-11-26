package app.revanced.extension.rif;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills an empty Old Reddit sidebar with the new Reddit description.
 *
 * rif's sidebar shows `description_html` from `/r/NAME/about.json` and, when Reddit sends null
 * (or an empty string) for it (the Old Reddit sidebar is empty), its own "has not provided a description on Old Reddit"
 * text. `public_description_html` (the short new Reddit description) is in the same response, but
 * rif doesn't parse it. This copies it into `description_html` before rif parses the body, so rif
 * renders it exactly like a sidebar. If the new description is empty too, or anything goes wrong,
 * rif gets the original response.
 *
 * Called from the start of the sidebar loader's response parser, which runs on a pool thread.
 */
public final class SubredditDescription {
    private static final String TAG = "RifSubredditDescription";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    // Bodies larger than this are passed through (about.json is a few KB).
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Pattern NULL_DESCRIPTION =
            Pattern.compile("\"description_html\"(\\s*):(\\s*)(?:null|\"\")");

    private SubredditDescription() {}

    /** Hook target: returns the stream rif should parse, never null for a non-null input. */
    public static InputStream withFallback(InputStream in) {
        if (in == null) return null;
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                read.write(buf, 0, n);
                if (read.size() > MAX_BYTES) {
                    return new java.io.SequenceInputStream(
                            new ByteArrayInputStream(read.toByteArray()), in);
                }
            }
            return new ByteArrayInputStream(apply(read.toByteArray()));
        } catch (Throwable t) {
            // What was read so far: rif sees the same truncated input it would have.
            Log.w(TAG, "description fallback failed", t);
            return new ByteArrayInputStream(read.toByteArray());
        }
    }

    /**
     * The JSON to hand to rif: [original] itself unless its `description_html` is null or empty and
     * `public_description_html` is not, else a copy with the second in place of the first.
     */
    static byte[] apply(byte[] original) {
        try {
            String text = new String(original, UTF_8);
            // Cheap check first: nearly every subreddit has a sidebar.
            Matcher m = NULL_DESCRIPTION.matcher(text);
            if (!m.find()) return original;
            JSONObject data = new JSONObject(text).optJSONObject("data");
            if (data == null || !(data.isNull("description_html") || data.optString("description_html").isEmpty())
                    || data.isNull("public_description_html")) {
                return original;
            }
            String fallback = data.getString("public_description_html");
            if (fallback.trim().isEmpty()) return original;
            Log.d(TAG, "using public_description_html (" + fallback.length() + " chars)");
            String replaced = text.substring(0, m.start())
                    + "\"description_html\"" + m.group(1) + ":" + m.group(2)
                    + JSONObject.quote(fallback) + text.substring(m.end());
            return replaced.getBytes(UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "description fallback failed", t);
            return original;
        }
    }
}
