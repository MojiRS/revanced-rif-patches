package app.revanced.extension.rif;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM tests for the pure URL and text helpers in {@link InlineImages}. */
public class InlineImagesTest {

    // attempts(): http:// must be tried as https:// first. imgur answers plain http with a 301 to
    // https and HttpURLConnection never follows http -> https, so trying http first fails.
    @Test
    public void httpIsTriedAsHttpsFirst() {
        assertArrayEquals(
                new String[]{"https://i.imgur.com/a.json", "http://i.imgur.com/a.json"},
                InlineImages.attempts("http://i.imgur.com/a.json"));
    }

    @Test
    public void httpSchemeIsCaseInsensitive() {
        assertArrayEquals(
                new String[]{"https://example.com/x", "HTTP://example.com/x"},
                InlineImages.attempts("HTTP://example.com/x"));
    }

    @Test
    public void httpsIsTriedTwiceUnchanged() {
        assertArrayEquals(
                new String[]{"https://example.com/x", "https://example.com/x"},
                InlineImages.attempts("https://example.com/x"));
    }

    @Test
    public void directImageByExtensionIgnoringQueryAndFragment() {
        assertTrue(InlineImages.isDirectImage("https://example.com/a.JPG"));
        assertTrue(InlineImages.isDirectImage("https://example.com/a.png?width=100"));
        assertTrue(InlineImages.isDirectImage("https://example.com/a.webp#frag"));
        assertFalse(InlineImages.isDirectImage("https://example.com/a.html?x=a.png"));
        assertFalse(InlineImages.isDirectImage("https://example.com/page"));
        assertFalse(InlineImages.isDirectImage(null));
    }

    @Test
    public void redditImageHostsAreDirect() {
        assertTrue(InlineImages.isDirectImage("https://i.redd.it/abc123"));
        assertTrue(InlineImages.isDirectImage("https://preview.redd.it/abc123?x=1"));
        assertFalse(InlineImages.isDirectImage("https://v.redd.it/abc123"));
    }

    @Test
    public void giphyPageLinkGivesMediaUrl() {
        assertEquals("https://media.giphy.com/media/XyZ123/giphy.webp",
                InlineImages.giphyImageUrl("https://giphy.com/gifs/funny-cat-XyZ123?x=1"));
        assertEquals("https://media.giphy.com/media/XyZ123/giphy.webp",
                InlineImages.giphyImageUrl("https://giphy.com/gifs/XyZ123"));
    }

    @Test
    public void giphyMediaLinkGivesMediaUrl() {
        assertEquals("https://media.giphy.com/media/XyZ123/giphy.webp",
                InlineImages.giphyImageUrl("https://media2.giphy.com/media/XyZ123/giphy.gif"));
    }

    @Test
    public void nonGiphyOrBadIdIsNull() {
        assertNull(InlineImages.giphyImageUrl("https://example.com/gifs/abc"));
        assertNull(InlineImages.giphyImageUrl("https://giphy.com/gifs/"));
        assertNull(InlineImages.giphyImageUrl("https://giphy.com/gifs/bad_id!"));
    }

    @Test
    public void imgflipPagesAreResolvedButTemplatesAreNot() {
        assertTrue(InlineImages.isResolvableHost("https://imgflip.com/i/7ope63"));
        assertTrue(InlineImages.isResolvableHost("https://www.imgflip.com/i/7ope63"));
        assertTrue(InlineImages.isResolvableHost("https://imgflip.com/gif/b250fo"));
        assertFalse(InlineImages.isResolvableHost("https://imgflip.com/meme/Drake-Hotline-Bling"));
        assertFalse(InlineImages.isResolvableHost("https://imgflip.com/memegenerator"));
    }

    @Test
    public void htmlEntitiesInUrlsAreDecoded() {
        assertEquals("https://x/a?b=1&c=2/3", InlineImages.decodeHtmlEntities("https://x/a?b=1&amp;c=2&#x2F;3"));
        assertNull(InlineImages.decodeHtmlEntities(null));
    }

    @Test
    public void gifMarkerLinkTextIsHideable() {
        assertTrue(InlineImages.isHideableLinkText(" [GIF] "));
        assertFalse(InlineImages.isHideableLinkText("gif"));
        assertFalse(InlineImages.isHideableLinkText(null));
    }
}
