package app.revanced.extension.rif;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills an empty Old Reddit sidebar with the new Reddit description and the subreddit's rules.
 *
 * rif's sidebar shows `description_html` from `/r/NAME/about.json` and, when Reddit sends null
 * (or an empty string) for it (the Old Reddit sidebar is empty), its own "has not provided a description on Old Reddit"
 * text. `public_description_html` (the short new Reddit description) is in the same response, but
 * rif doesn't parse it. This copies it into `description_html` before rif parses the body, so rif
 * renders it exactly like a sidebar, followed by a "Rules" heading and a numbered list of the rules
 * from `/r/NAME/about/rules.json` (logged in only; on any failure the description alone). If the
 * new description is empty too, or anything goes wrong, rif gets the original response.
 *
 * Called from the start of the sidebar loader's response parser, which runs on a pool thread.
 */
public final class SubredditDescription {
    private static final String TAG = "RifSubredditDescription";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    // Bodies larger than this are passed through (about.json is a few KB).
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Pattern NULL_DESCRIPTION =
            // Anchored to a key position so an escaped copy inside a string value doesn't match.
            Pattern.compile("([{,]\\s*)\"description_html\"(\\s*):(\\s*)(?:null|\"\")");
    private static final Pattern SUBREDDIT_NAME = Pattern.compile("[A-Za-z0-9_]{1,21}");
    /** Reddit's wrapper around rendered markdown: comment markers and the outer div. */
    private static final Pattern MD_WRAPPER = Pattern.compile(
            "^\\s*(?:<!-- SC_OFF -->)?\\s*<div class=\"md\">(.*)</div>\\s*(?:<!-- SC_ON -->)?\\s*$", Pattern.DOTALL);

    /** The rules request delays the sidebar: everything (token, request, retry) within this. */
    private static final long RULES_BUDGET_MS = 4000;
    private static final int CONNECT_MS = 3000;
    private static final int READ_MS = 3000;
    /** Rules fetched in the last 10 minutes are reused (reopening the sidebar, rotation). */
    private static final long CACHE_MS = 10 * 60 * 1000;

    /** The rules JSON (rules.json?raw_json=1) for a subreddit, or null. */
    interface RulesSource {
        String rules(String subreddit);
    }

    private static final Map<String, Object[]> CACHE = new LinkedHashMap<String, Object[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
            return size() > 16;
        }
    };

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "rif-subreddit-rules");
            t.setDaemon(true);
            return t;
        }
    });

    private static final RulesSource NETWORK = new RulesSource() {
        @Override
        public String rules(String subreddit) {
            return fetchRules(subreddit);
        }
    };

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
            return new ByteArrayInputStream(apply(read.toByteArray(), NETWORK));
        } catch (Throwable t) {
            // What was read so far, then the rest (or the original error) from the source.
            Log.w(TAG, "description fallback failed", t);
            return new java.io.SequenceInputStream(new ByteArrayInputStream(read.toByteArray()), in);
        }
    }

    /** {@link #apply(byte[], RulesSource)} without rules. */
    static byte[] apply(byte[] original) {
        return apply(original, null);
    }

    /**
     * The JSON to hand to rif: [original] itself unless its `description_html` is null or empty and
     * `public_description_html` is not, else a copy with the second (plus the rules from [rules],
     * if any) in place of the first.
     */
    static byte[] apply(byte[] original, RulesSource rules) {
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
            String name = data.optString("display_name");
            if (rules != null && SUBREDDIT_NAME.matcher(name).matches()) {
                try {
                    // Reddit sends about.json's HTML entity-escaped (rif unescapes it before rendering)
                    // unless the request asked for raw_json; the rules have to match.
                    fallback += rulesHtml(rules.rules(name), !fallback.contains("<"));
                } catch (Throwable t) {
                    Log.w(TAG, "rules failed", t);
                }
            }
            String replaced = text.substring(0, m.start())
                    + m.group(1) + "\"description_html\"" + m.group(2) + ":" + m.group(3)
                    + JSONObject.quote(fallback) + text.substring(m.end());
            return replaced.getBytes(UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "description fallback failed", t);
            return original;
        }
    }

    /**
     * A "Rules" heading and a numbered list (bold title, then the mods' explanation) from a
     * rules.json?raw_json=1 body; "" when there are no rules or the body can't be read.
     * [escaped]: return it entity-escaped, as about.json's HTML is without raw_json.
     */
    static String rulesHtml(String rulesJson, boolean escaped) {
        if (rulesJson == null || rulesJson.isEmpty()) return "";
        JSONArray list;
        try {
            list = new JSONObject(rulesJson).optJSONArray("rules");
        } catch (Throwable t) {
            Log.w(TAG, "rules unreadable: " + t);
            return "";
        }
        if (list == null) return "";
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject rule = list.optJSONObject(i);
            if (rule == null) continue;
            String title = text(rule, "short_name");
            String explanation = explanationHtml(rule);
            if (title.isEmpty() && explanation.isEmpty()) continue;
            items.append("<li>");
            if (!title.isEmpty()) items.append("<p><strong>").append(escape(title)).append("</strong></p>");
            items.append(explanation).append("</li>\n");
        }
        if (items.length() == 0) return "";
        String html = "<div class=\"md\"><h3>Rules</h3>\n<ol>\n" + items + "</ol>\n</div>";
        return escaped ? escape(html) : html;
    }

    /** A rule's explanation as HTML without Reddit's wrapper, or "". */
    private static String explanationHtml(JSONObject rule) {
        String html = text(rule, "description_html");
        if (!html.isEmpty()) {
            Matcher m = MD_WRAPPER.matcher(html);
            return (m.matches() ? m.group(1) : html).trim();
        }
        String plain = text(rule, "description");
        return plain.isEmpty() ? "" : "<p>" + escape(plain) + "</p>";
    }

    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key).trim();
    }

    /** HTML-escapes text the way Reddit escapes about.json's HTML (quotes stay as they are). */
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * rules.json for [name] as rif's logged-in user, or null when logged out, on failure or after
     * {@link #RULES_BUDGET_MS}. Blocking: called on rif's pool thread.
     */
    private static String fetchRules(final String name) {
        final Context ctx = Settings.context();
        if (ctx == null || !RedditApi.loggedIn(ctx)) return null;
        final String key = name.toLowerCase(java.util.Locale.ROOT);
        synchronized (CACHE) {
            Object[] hit = CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_MS) return (String) hit[1];
        }
        Future<String> f = POOL.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                RedditApi.Resp r = RedditApi.authed(ctx,
                        RedditApi.API + "/r/" + name + "/about/rules.json?raw_json=1", null, CONNECT_MS, READ_MS);
                if (r.code != 200) {
                    Log.w(TAG, "rules: HTTP " + r.code);
                    return null;
                }
                return r.body;
            }
        });
        try {
            String body = f.get(RULES_BUDGET_MS, TimeUnit.MILLISECONDS);
            if (body != null) {
                synchronized (CACHE) {
                    CACHE.put(key, new Object[] {System.currentTimeMillis(), body});
                }
            }
            return body;
        } catch (TimeoutException e) {
            f.cancel(true);
            Log.w(TAG, "rules: timed out");
        } catch (Throwable t) {
            Log.w(TAG, "rules failed: " + t);
        }
        return null;
    }
}
