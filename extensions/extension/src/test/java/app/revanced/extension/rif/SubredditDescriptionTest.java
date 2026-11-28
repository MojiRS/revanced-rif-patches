package app.revanced.extension.rif;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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

    // ------------------------------------------------------------------ rules

    /** rules.json?raw_json=1 as Reddit sends it: raw HTML in Reddit's wrapper. */
    private static String rulesJson(String... titleAndHtml) {
        StringBuilder sb = new StringBuilder("{\"rules\": [");
        for (int i = 0; i < titleAndHtml.length; i += 2) {
            if (i > 0) sb.append(", ");
            sb.append("{\"kind\": \"all\", \"short_name\": ").append(quoted(titleAndHtml[i]))
                    .append(", \"description_html\": ")
                    .append(titleAndHtml[i + 1] == null ? "null" : quoted(titleAndHtml[i + 1]))
                    .append(", \"priority\": ").append(i / 2).append("}");
        }
        return sb.append("], \"site_rules\": [\"Spam\"]}").toString();
    }

    private static String wrapped(String inner) {
        return "<!-- SC_OFF --><div class=\"md\">" + inner + "\n</div><!-- SC_ON -->";
    }

    private static SubredditDescription.RulesSource source(final String json) {
        return new SubredditDescription.RulesSource() {
            @Override
            public String rules(String subreddit) {
                assertEquals("x", subreddit);
                return json;
            }
        };
    }

    private static final SubredditDescription.RulesSource MUST_NOT_CALL = new SubredditDescription.RulesSource() {
        @Override
        public String rules(String subreddit) {
            throw new AssertionError("rules fetched for " + subreddit);
        }
    };

    /** What rif does before rendering (StringEscapeUtils.unescapeHtml4, for these three). */
    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String description(byte[] out) throws Exception {
        return new JSONObject(new String(out, UTF_8)).getJSONObject("data").getString("description_html");
    }

    @Test
    public void rulesFollowTheDescription() throws Exception {
        String json = rulesJson(
                "No <trolling> & baiting", wrapped("<p>Be nice. See <a href=\"https://example.com/?a=1&amp;b=2\">the wiki</a>.</p>"),
                "Spoilers", wrapped("<p>Tag them.</p>\n\n<ul>\n<li>In titles too.</li>\n</ul>"));
        String out = description(SubredditDescription.apply(about("null", quoted(HTML)), source(json)));
        assertTrue(out.startsWith(HTML));
        String rules = out.substring(HTML.length());
        // Escaped like the rest of about.json: no raw markup reaches rif.
        assertFalse(rules.contains("<"));
        assertEquals("<div class=\"md\"><h3>Rules</h3>\n<ol>\n"
                + "<li><p><strong>No &lt;trolling&gt; &amp; baiting</strong></p>"
                + "<p>Be nice. See <a href=\"https://example.com/?a=1&amp;b=2\">the wiki</a>.</p></li>\n"
                + "<li><p><strong>Spoilers</strong></p><p>Tag them.</p>\n\n<ul>\n<li>In titles too.</li>\n</ul></li>\n"
                + "</ol>\n</div>", unescape(rules));
    }

    @Test
    public void ruleWithoutExplanationIsTitleOnly() {
        String html = SubredditDescription.rulesHtml(rulesJson("Only a title", null, "Blank", ""), false);
        assertEquals("<div class=\"md\"><h3>Rules</h3>\n<ol>\n"
                + "<li><p><strong>Only a title</strong></p></li>\n"
                + "<li><p><strong>Blank</strong></p></li>\n</ol>\n</div>", html);
    }

    @Test
    public void plainDescriptionWhenNoHtml() {
        String json = "{\"rules\": [{\"short_name\": \"T\", \"description\": \"a < b\", \"description_html\": null}]}";
        assertEquals("<div class=\"md\"><h3>Rules</h3>\n<ol>\n<li><p><strong>T</strong></p><p>a &lt; b</p></li>\n</ol>\n</div>",
                SubredditDescription.rulesHtml(json, false));
    }

    @Test
    public void noRulesMeansDescriptionOnly() throws Exception {
        byte[] plain = SubredditDescription.apply(about("null", quoted(HTML)));
        assertArrayEquals(plain, SubredditDescription.apply(about("null", quoted(HTML)), source("{\"rules\": []}")));
        assertArrayEquals(plain, SubredditDescription.apply(about("null", quoted(HTML)), source(null)));
        assertArrayEquals(plain, SubredditDescription.apply(about("null", quoted(HTML)), source("<html>blocked")));
        assertArrayEquals(plain, SubredditDescription.apply(about("null", quoted(HTML)),
                source(rulesJson("", null))));
        SubredditDescription.RulesSource throwing = new SubredditDescription.RulesSource() {
            @Override
            public String rules(String subreddit) {
                throw new RuntimeException("network");
            }
        };
        assertArrayEquals(plain, SubredditDescription.apply(about("null", quoted(HTML)), throwing));
    }

    @Test
    public void noRulesRequestWithoutFallback() {
        byte[] sidebar = about(quoted("&lt;p&gt;side&lt;/p&gt;"), quoted(HTML));
        assertSame(sidebar, SubredditDescription.apply(sidebar, MUST_NOT_CALL));
        byte[] nothing = about("null", "null");
        assertSame(nothing, SubredditDescription.apply(nothing, MUST_NOT_CALL));
        // A display name that can't be a subreddit is never put in a URL.
        byte[] odd = ("{\"data\": {\"display_name\": \"../x\", \"description_html\": null, "
                + "\"public_description_html\": " + quoted(HTML) + "}}").getBytes(UTF_8);
        SubredditDescription.apply(odd, MUST_NOT_CALL);
    }

    @Test
    public void rawDescriptionGetsRawRules() throws Exception {
        // A response requested with raw_json=1 (not rif's own): the rules aren't escaped either.
        String raw = "<div class=\"md\"><p>Hi &amp; bye</p></div>";
        String out = description(SubredditDescription.apply(about("null", quoted(raw)),
                source(rulesJson("A & B", wrapped("<p>x</p>")))));
        assertEquals(raw + "<div class=\"md\"><h3>Rules</h3>\n<ol>\n"
                + "<li><p><strong>A &amp; B</strong></p><p>x</p></li>\n</ol>\n</div>", out);
    }
}
