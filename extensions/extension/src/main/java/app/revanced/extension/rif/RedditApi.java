package app.revanced.extension.rif;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Requests to oauth.reddit.com as rif's logged-in user, with rif's own user agent. Shared by the
 * patches that call Reddit's API themselves (image hosting, subreddit description fallback).
 * Blocking: background threads only.
 */
public final class RedditApi {
    private static final String TAG = "RifRedditApi";
    static final String API = "https://oauth.reddit.com";
    private static final String FALLBACK_VERSION = "5.6.22";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Resp {
        int code;
        String body;
    }

    /** No account is logged in to rif. */
    static final class LoggedOut extends IOException {
        LoggedOut() {
            super("not logged in");
        }
    }

    /** rif's account gave no token. */
    static final class NoToken extends IOException {
        NoToken() {
            super("no token");
        }
    }

    private RedditApi() {}

    /**
     * One request with the user's token (a POST when {@code form} is non-null); a 401/403 gets a
     * fresh token and one retry. Throws {@link LoggedOut}, {@link NoToken}, or another IOException
     * when the request itself fails.
     */
    static Resp authed(Context ctx, String url, byte[] form, int connectMs, int readMs) throws IOException {
        String token = token(ctx);
        Resp r = call(ctx, url, form, token, connectMs, readMs);
        if (r.code == 401 || r.code == 403) {
            try {
                AccountManager.get(ctx).invalidateAuthToken("com.reddit", token);
            } catch (Throwable ignored) {
            }
            token = token(ctx);
            r = call(ctx, url, form, token, connectMs, readMs);
        }
        return r;
    }

    /**
     * A GET with rif's user agent and no token, as rif itself makes its requests when no account
     * is logged in (to www.reddit.com).
     */
    static Resp anonymous(Context ctx, String url, int connectMs, int readMs) throws IOException {
        return call(ctx, url, null, null, connectMs, readMs);
    }

    private static Resp call(Context ctx, String url, byte[] form, String token, int connectMs, int readMs)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", userAgent(ctx));
            if (token != null) conn.setRequestProperty("Authorization", "bearer " + token);
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
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** The logged-in account's name, or null. */
    static String userName(Context ctx) {
        return ctx.getSharedPreferences("credentials", Context.MODE_PRIVATE).getString("username", null);
    }

    static boolean loggedIn(Context ctx) {
        String name = userName(ctx);
        return name != null && name.length() > 0;
    }

    /** rif's own sign-in token for the logged-in account (blocking: background threads only). */
    private static String token(Context ctx) throws IOException {
        String name = userName(ctx);
        if (name == null || name.length() == 0) throw new LoggedOut();
        try {
            String t = AccountManager.get(ctx).blockingGetAuthToken(new Account(name, "com.reddit"), "redditisfun_oauth2", false);
            if (t != null && t.length() > 0) return t;
        } catch (Exception e) {
            Log.w(TAG, "token failed: " + e);
        }
        throw new NoToken();
    }

    /** rif's own user agent (what its Reddit requests send, after any Spoof client patch); set by the patches. */
    private static volatile String rifUserAgent;

    /** Called by patched rif code with the result of rif's user-agent method. */
    public static void setUserAgent(String ua) {
        if (ua != null && ua.length() > 0) rifUserAgent = ua;
    }

    static String userAgent(Context ctx) {
        String set = rifUserAgent;
        if (set != null) return set;
        String v = FALLBACK_VERSION;
        try {
            String name = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            if (name != null) v = name;
        } catch (Throwable ignored) {
        }
        return "reddit is fun (Android) " + v;
    }

    private static String readText(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] b = out.toByteArray();
            return new String(b, 0, Math.min(b.length, 1 << 20), UTF8);
        } catch (IOException e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
