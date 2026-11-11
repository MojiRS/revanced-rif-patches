package app.revanced.extension.rif;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.core.util.c;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CommentMediaTest {

    private static Set<String> set(String... markers) {
        return new HashSet<>(Arrays.asList(markers));
    }

    @Test
    public void findsMarkersAndKeepsOnlyKnownOnes() {
        String text = "hi\n\n![img](a.png)\n\ntext ![gif](b.gif) ![img](not known.png) ![x](y) ![img]() ![img](a(b).png)";
        List<CommentMedia.Marker> all = CommentMedia.findMarkers(text);
        assertEquals(4, all.size());
        assertEquals("![img](a.png)", all.get(0).text);
        assertFalse(all.get(0).gif);
        assertTrue(all.get(1).gif);
        assertEquals(text.indexOf("![img](a.png)"), all.get(0).start);
        assertEquals(all.get(0).start + "![img](a.png)".length(), all.get(0).end);

        List<CommentMedia.Marker> known = CommentMedia.knownMarkers(text, set("![img](a.png)", "![gif](b.gif)"));
        assertEquals(2, known.size());
        assertTrue(CommentMedia.knownMarkers(text, Collections.<String>emptySet()).isEmpty());
        assertTrue(CommentMedia.knownMarkers("plain *text*", set("![img](a.png)")).isEmpty());
        assertTrue(CommentMedia.findMarkers(null).isEmpty());
    }

    @Test
    public void namesAreMadeSafeAndShort() {
        assertEquals("IMG_2024.jpg", CommentMedia.sanitizeName("IMG_2024.jpg", false));
        assertEquals("my_photo_1_.png", CommentMedia.sanitizeName("my photo (1).png", false));
        String evil = CommentMedia.sanitizeName("a)b](c\n d.png", false);
        assertFalse(evil.contains(")") || evil.contains("]") || evil.contains("[") || evil.contains("(") || evil.contains("\n"));
        assertEquals("c.png", CommentMedia.sanitizeName("/storage/emulated/0/c.png", false));
        assertEquals("image.jpg", CommentMedia.sanitizeName(null, false));
        assertEquals("image.gif", CommentMedia.sanitizeName("", true));
        assertEquals("image.gif", CommentMedia.sanitizeName("()[]", true));
        String longName = CommentMedia.sanitizeName("abcdefghijklmnopqrstuvwxyz0123456789abcdefghij.jpeg", false);
        assertEquals(32, longName.length());
        assertTrue(longName.endsWith(".jpeg"));
        // A sanitised name always makes a marker the finder recognises.
        String marker = "![img](" + CommentMedia.sanitizeName("we(ird) [name]\r\n.png", false) + ")";
        assertEquals(1, CommentMedia.findMarkers(marker).size());
    }

    @Test
    public void duplicateNamesGetANumber() {
        assertEquals("![img](a.png)", CommentMedia.markerFor("a.png", false, set()));
        assertEquals("![img](a-2.png)", CommentMedia.markerFor("a.png", false, set("![img](a.png)")));
        assertEquals("![img](a-3.png)", CommentMedia.markerFor("a.png", false, set("![img](a.png)", "![img](a-2.png)")));
        assertEquals("![gif](x-2)", CommentMedia.markerFor("x", true, set("![gif](x)")));
    }

    @Test
    public void markerGetsAParagraphOfItsOwn() {
        assertArrayEquals(new String[] {"", "\n\n"}, CommentMedia.spacing("", ""));
        assertArrayEquals(new String[] {"\n\n", "\n\n"}, CommentMedia.spacing("hello", ""));
        assertArrayEquals(new String[] {"\n", "\n"}, CommentMedia.spacing("hello\n", "\nworld"));
        assertArrayEquals(new String[] {"", ""}, CommentMedia.spacing("hello\n\n", "\n\nworld"));
        assertArrayEquals(new String[] {"\n\n", "\n\n"}, CommentMedia.spacing("a", "b"));
    }

    @Test
    public void isolatesTheTokenBetweenBlankLines() {
        String m = "![img](a.png)";
        String t = "![img](abc123)";
        assertEquals(t + "\n\n", CommentMedia.isolate(m, 0, m.length(), t));
        String text = "**bold** " + m + " after";
        int s = text.indexOf(m);
        assertEquals("**bold**\n\n" + t + "\n\nafter", CommentMedia.isolate(text, s, s + m.length(), t));
        // Already on its own paragraph: unchanged apart from the token.
        text = "top\n\n" + m + "\n\n- a\n- b";
        s = text.indexOf(m);
        assertEquals("top\n\n" + t + "\n\n- a\n- b", CommentMedia.isolate(text, s, s + m.length(), t));
        // A single newline on each side becomes a blank line.
        text = "top\n" + m + "\nbottom";
        s = text.indexOf(m);
        assertEquals("top\n\n" + t + "\n\nbottom", CommentMedia.isolate(text, s, s + m.length(), t));
        // A quote mark in front of it on its line is dropped; trailing blanks are too.
        text = "> quoted\n> " + m + "   \n> more";
        s = text.indexOf(m);
        assertEquals("> quoted\n\n" + t + "\n\n> more", CommentMedia.isolate(text, s, s + m.length(), t));
        // After text on the line: the text moves on.
        text = "a\n\n" + m + "caption";
        s = text.indexOf(m);
        assertEquals("a\n\n" + t + "\n\ncaption", CommentMedia.isolate(text, s, s + m.length(), t));
    }

    @Test
    public void tokensAndExtensions() {
        assertEquals("![img](id1)", CommentMedia.token("id1", false));
        assertEquals("![gif](id1)", CommentMedia.token("id1", true));
        assertEquals("gif", CommentMedia.urlExtension("image/gif"));
        assertEquals("png", CommentMedia.urlExtension("image/png"));
        assertNull(CommentMedia.urlExtension("image/jpeg"));
        assertNull(CommentMedia.urlExtension("image/webp"));
    }

    @Test
    public void countsMediaBlocksInAConvertedDocument() throws Exception {
        JSONObject doc = new JSONObject("{\"document\":[{\"e\":\"par\",\"c\":[{\"e\":\"text\",\"t\":\"hi\"}]},"
                + "{\"e\":\"img\",\"id\":\"abc\"},{\"e\":\"blockquote\",\"c\":[{\"e\":\"par\",\"c\":[{\"e\":\"text\",\"t\":\"q\"}]}]}]}");
        assertArrayEquals(new int[] {1, 1}, CommentMedia.countMedia(doc, "abc", false));
        // Wrong id or wrong type: counted, but not ours.
        assertArrayEquals(new int[] {1, 0}, CommentMedia.countMedia(doc, "other", false));
        assertArrayEquals(new int[] {1, 0}, CommentMedia.countMedia(doc, "abc", true));
        JSONObject two = new JSONObject("{\"document\":[{\"e\":\"img\",\"id\":\"abc\"},{\"e\":\"gif\",\"id\":\"abc\"}]}");
        assertEquals(2, CommentMedia.countMedia(two, "abc", false)[0]);
        JSONObject none = new JSONObject("{\"document\":[{\"e\":\"par\",\"c\":[{\"e\":\"text\",\"t\":\"![img](abc)\"}]}]}");
        assertEquals(0, CommentMedia.countMedia(none, "abc", false)[0]);
    }

    @Test
    public void readsTheConvertedDocument() {
        assertNotNull(CommentMedia.convertedDocument("{\"output\":{\"document\":[]},\"output_mode\":\"rtjson\",\"assets\":[]}"));
        assertNull(CommentMedia.convertedDocument("{\"output\":\"text\"}"));
        assertNull(CommentMedia.convertedDocument("{\"json\":{\"errors\":[]}}"));
        assertNull(CommentMedia.convertedDocument("not json"));
    }

    @Test
    public void richtextReplacesTextInPlace() {
        @SuppressWarnings("unchecked")
        c<String, String>[] pairs = new c[] {c.a("thing_id", "t3_x"), c.a("text", "hello"), c.a("r", "sub")};
        c<String, String>[] out = CommentMedia.withRichtext(pairs, "{\"document\":[]}");
        assertEquals(3, out.length);
        assertEquals("thing_id", out[0].a);
        assertEquals("richtext_json", out[1].a);
        assertEquals("{\"document\":[]}", out[1].b);
        assertEquals("r", out[2].a);
        // The input is untouched, and no pair has a null value.
        assertEquals("text", pairs[1].a);
        for (c<String, String> p : out) assertNotNull(p.b);
    }

    private static final String FLAT = "{\"name\":\"t1_abc\",\"id\":\"abc\",\"parent_id\":\"t3_x\",\"link_id\":\"t3_x\","
            + "\"body\":\"Some **text**\\n\\n*Processing img ID123...*\",\"body_html\":\"<p>Some <strong>text</strong></p>\\n<p><em>Processing img ID123...</em></p>\","
            + "\"author\":\"me\"}";

    @Test
    public void wrapsAFlatCommentAndRewritesThePlaceholder() throws Exception {
        String wrapped = CommentMedia.wrapComment(FLAT, "ID123", "png");
        assertNotNull(wrapped);
        JSONObject json = new JSONObject(wrapped).getJSONObject("json");
        assertEquals(0, json.getJSONArray("errors").length());
        JSONObject thing = json.getJSONObject("data").getJSONArray("things").getJSONObject(0);
        assertEquals("t1", thing.getString("kind"));
        JSONObject data = thing.getJSONObject("data");
        assertEquals("t1_abc", data.getString("name"));
        assertEquals("Some **text**\n\nhttps://i.redd.it/ID123.png", data.getString("body"));
        assertEquals("<p>Some <strong>text</strong></p>\n<p><a href=\"https://i.redd.it/ID123.png\">https://i.redd.it/ID123.png</a></p>",
                data.getString("body_html"));
    }

    @Test
    public void keepsThePlaceholderWithoutAnExtension() throws Exception {
        String wrapped = CommentMedia.wrapComment(FLAT, "ID123", null);
        JSONObject data = new JSONObject(wrapped).getJSONObject("json").getJSONObject("data")
                .getJSONArray("things").getJSONObject(0).getJSONObject("data");
        assertEquals("Some **text**\n\n*Processing img ID123...*", data.getString("body"));
    }

    @Test
    public void rewritesAnEntityEscapedPlaceholderToo() {
        String escaped = "&lt;p&gt;&lt;em&gt;Processing gif ID9...&lt;/em&gt;&lt;/p&gt;";
        assertEquals("&lt;p&gt;&lt;a href=&quot;https://i.redd.it/ID9.gif&quot;&gt;https://i.redd.it/ID9.gif&lt;/a&gt;&lt;/p&gt;",
                CommentMedia.rewritePlaceholder(escaped, "ID9", "https://i.redd.it/ID9.gif", true));
        // Another id's placeholder, and a body without one, stay as they are.
        assertEquals("*Processing img OTHER...*",
                CommentMedia.rewritePlaceholder("*Processing img OTHER...*", "ID9", "u", false));
        assertEquals("plain $1 \\ text", CommentMedia.rewritePlaceholder("plain $1 \\ text", "ID9", "u", false));
    }

    @Test
    public void leavesWrappedAndForeignRepliesAlone() {
        // Already wrapped (also with errors): rif shows Reddit's message itself.
        assertNull(CommentMedia.wrapComment("{\"json\":{\"errors\":[[\"RATELIMIT\",\"slow down\",\"ratelimit\"]]}}", "ID123", "png"));
        assertNull(CommentMedia.wrapComment("{\"json\":{\"errors\":[],\"data\":{\"things\":[]}}}", "ID123", "png"));
        assertNull(CommentMedia.wrapComment("not json", "ID123", "png"));
        assertNull(CommentMedia.wrapComment("{\"something\":1}", "ID123", "png"));
        assertEquals("RATELIMIT: slow down",
                CommentMedia.firstError("{\"json\":{\"errors\":[[\"RATELIMIT\",\"slow down\",\"ratelimit\"]]}}"));
        assertNull(CommentMedia.firstError("{\"json\":{\"errors\":[]}}"));
        assertNull(CommentMedia.firstError(FLAT));
    }

    @Test
    public void readsTheSubsMediaRules() {
        String about = "{\"data\":{\"comment_contribution_settings\":{\"allowed_media_types\":[\"static\",\"giphy\"]}}}";
        List<String> types = CommentMedia.allowedMediaTypes(about);
        assertEquals(Arrays.asList("static", "giphy"), types);
        assertTrue(CommentMedia.allows(types, false));
        assertFalse(CommentMedia.allows(types, true));
        assertTrue(CommentMedia.allowsAny(types));
        List<String> none = CommentMedia.allowedMediaTypes("{\"data\":{\"comment_contribution_settings\":{\"allowed_media_types\":[]}}}");
        assertEquals(0, none.size());
        assertFalse(CommentMedia.allowsAny(none));
        // Unknown (no setting, bad reply): allowed.
        assertNull(CommentMedia.allowedMediaTypes("{\"data\":{}}"));
        assertNull(CommentMedia.allowedMediaTypes("{\"data\":{\"comment_contribution_settings\":{}}}"));
        assertNull(CommentMedia.allowedMediaTypes("<html>"));
        assertTrue(CommentMedia.allows(null, true));
        assertTrue(CommentMedia.allowsAny(null));
        assertTrue(CommentMedia.isProfileSub("u_someone"));
        assertTrue(CommentMedia.isProfileSub("U_Someone"));
        assertFalse(CommentMedia.isProfileSub("mojitesting"));
        assertFalse(CommentMedia.isProfileSub(null));
    }

    @Test
    public void recordsSurviveAJsonRoundTripAndExpire() {
        long now = 10L * 24 * 3600 * 1000;
        java.util.ArrayList<CommentMedia.Rec> recs = new java.util.ArrayList<>();
        recs.add(new CommentMedia.Rec("![img](old.png)", "content://x/1", "image/png", now - 8L * 24 * 3600 * 1000));
        for (int i = 0; i < 25; i++) {
            recs.add(new CommentMedia.Rec("![img](f" + i + ".png)", "content://x/" + (i + 10), "image/png", now - 1000 + i));
        }
        List<CommentMedia.Rec> kept = CommentMedia.prune(recs, now);
        assertEquals(20, kept.size());
        assertEquals("![img](f5.png)", kept.get(0).marker);
        assertEquals("![img](f24.png)", kept.get(19).marker);
        List<CommentMedia.Rec> back = CommentMedia.recsFromJson(CommentMedia.recsToJson(kept));
        assertEquals(20, back.size());
        assertEquals("content://x/15", back.get(0).uri);
        assertEquals("image/png", back.get(0).mime);
        assertEquals(kept.get(3).at, back.get(3).at);
        assertTrue(CommentMedia.recsFromJson("garbage").isEmpty());
    }
}
