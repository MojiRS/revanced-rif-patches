package app.revanced.extension.rif;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.Charset;

/** JVM tests for {@link SubredditDescription#apply} (needs the real org.json, see build.gradle.kts). */
public class SubredditDescriptionTest {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String HTML = "&lt;div class=\"md\"&gt;&lt;p&gt;Café \"quoted\" \\ &amp; more&lt;/p&gt;&lt;/div&gt;";

    private static byte[] about(String descriptionHtml, String publicHtml) {
        return ("{\"kind\": \"t5\", \"data\": {\"display_name\": \"x\", \"subscribers\": 123456789012, "
                + "\"created_utc\": 1.2345678E9, \"description_html\": " + descriptionHtml
                + ", \"public_description_html\": " + publicHtml + ", \"title\": \"t\"}}").getBytes(UTF_8);
    }

    private static String quoted(String s) {
        return JSONObject.quote(s);
    }

    @Test
    public void nullDescriptionTakesPublicDescription() throws Exception {
        byte[] out = SubredditDescription.apply(about("null", quoted(HTML)));
        JSONObject data = new JSONObject(new String(out, UTF_8)).getJSONObject("data");
        assertEquals(HTML, data.getString("description_html"));
        assertEquals(HTML, data.getString("public_description_html"));
        // Everything else is byte-for-byte what Reddit sent.
        assertArrayEquals(about(quoted(HTML), quoted(HTML)), out);
    }

    @Test
    public void emptyDescriptionTakesPublicDescription() throws Exception {
        byte[] out = SubredditDescription.apply(about("\"\"", quoted(HTML)));
        assertArrayEquals(about(quoted(HTML), quoted(HTML)), out);
    }

    @Test
    public void realSidebarIsUntouched() {
        byte[] in = about(quoted("&lt;p&gt;side&lt;/p&gt;"), quoted(HTML));
        assertSame(in, SubredditDescription.apply(in));
    }

    @Test
    public void nothingToFallBackOnIsUntouched() {
        byte[] nullPublic = about("null", "null");
        assertSame(nullPublic, SubredditDescription.apply(nullPublic));
        byte[] blank = about("null", "\"  \"");
        assertSame(blank, SubredditDescription.apply(blank));
        byte[] absent = "{\"kind\":\"t5\",\"data\":{\"description_html\":null}}".getBytes(UTF_8);
        assertSame(absent, SubredditDescription.apply(absent));
    }

    @Test
    public void notJsonIsUntouched() {
        byte[] junk = "\"description_html\": null <html>".getBytes(UTF_8);
        assertSame(junk, SubredditDescription.apply(junk));
    }

    @Test
    public void escapedCopyInEarlierStringIsNotMatched() throws Exception {
        String title = quoted("see \"description_html\": null here");
        byte[] in = ("{\"data\": {\"title\": " + title + ", \"description_html\": null, "
                + "\"public_description_html\": " + quoted(HTML) + "}}").getBytes(UTF_8);
        JSONObject data = new JSONObject(new String(SubredditDescription.apply(in), UTF_8)).getJSONObject("data");
        assertEquals(HTML, data.getString("description_html"));
        assertEquals("see \"description_html\": null here", data.getString("title"));
        // Only the title's copy and no real key: untouched.
        byte[] only = ("{\"data\": {\"title\": " + title + ", \"description_html\": \"x\", "
                + "\"public_description_html\": " + quoted(HTML) + "}}").getBytes(UTF_8);
        assertSame(only, SubredditDescription.apply(only));
    }

    @Test
    public void handlesCompactJson()throws Exception {
        byte[] in = ("{\"data\":{\"description_html\":null,\"public_description_html\":" + quoted(HTML) + "}}")
                .getBytes(UTF_8);
        JSONObject data = new JSONObject(new String(SubredditDescription.apply(in), UTF_8)).getJSONObject("data");
        assertEquals(HTML, data.getString("description_html"));
    }
}
