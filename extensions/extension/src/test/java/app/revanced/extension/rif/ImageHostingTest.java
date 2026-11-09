package app.revanced.extension.rif;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.Charset;

public class ImageHostingTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void sniffsTheFileTypeFromItsBytes() {
        assertEquals("image/gif", ImageHosting.sniffMime("GIF89a....".getBytes(UTF8), 10));
        assertEquals("image/png", ImageHosting.sniffMime(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10}, 6));
        assertEquals("image/jpeg", ImageHosting.sniffMime(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}, 4));
        assertEquals("image/webp", ImageHosting.sniffMime("RIFF\0\0\0\0WEBPVP8 ".getBytes(UTF8), 16));
        assertNull(ImageHosting.sniffMime("hello".getBytes(UTF8), 5));
        assertNull(ImageHosting.sniffMime(new byte[] {'G', 'I'}, 2));
        assertEquals("gif", ImageHosting.extensionFor("image/gif"));
        assertEquals("jpg", ImageHosting.extensionFor("image/jpeg"));
    }

    @Test
    public void assetUrlFromAction() {
        assertEquals("https://reddit-uploaded-media.s3-accelerate.amazonaws.com/abc",
                ImageHosting.assetUrl("//reddit-uploaded-media.s3-accelerate.amazonaws.com", "abc"));
        assertEquals("https://example.com/abc", ImageHosting.assetUrl("https://example.com/", "abc"));
    }

    @Test
    public void findsTheAssetIdInOurUrls() {
        assertEquals("abc", ImageHosting.assetIdOf("https://reddit-uploaded-media.s3-accelerate.amazonaws.com/abc"));
        assertEquals("abc", ImageHosting.assetIdOf("https://reddit-uploaded-media.s3-accelerate.amazonaws.com/abc/?x=1"));
        assertNull(ImageHosting.assetIdOf("https://i.imgur.com/abc.png"));
        assertNull(ImageHosting.assetIdOf(null));
    }

    @Test
    public void parsesTheLeaseAndBuildsTheMultipartBody() throws Exception {
        String json = "{\"args\":{\"action\":\"//s3.example\",\"fields\":[{\"name\":\"key\",\"value\":\"k1\"},"
                + "{\"name\":\"acl\",\"value\":\"private\"}]},\"asset\":{\"asset_id\":\"zzz\"}}";
        ImageHosting.Lease lease = ImageHosting.parseLease(json);
        assertEquals("//s3.example", lease.action);
        assertEquals("zzz", lease.id);
        assertEquals(2, lease.fields.size());
        String head = new String(ImageHosting.multipartHead("B", lease.fields, "image.gif", "image/gif"), UTF8);
        assertEquals("--B\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\nk1\r\n"
                + "--B\r\nContent-Disposition: form-data; name=\"acl\"\r\n\r\nprivate\r\n"
                + "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.gif\"\r\n"
                + "Content-Type: image/gif\r\n\r\n", head);
        assertArrayEquals("\r\n--B--\r\n".getBytes(UTF8), ImageHosting.multipartTail("B"));
    }

    @Test
    public void recognisesTheProcessingReply() {
        String pending = "{\"json\":{\"errors\":[],\"data\":{\"user_submitted_page\":\"https://www.reddit.com/user/x/submitted/\","
                + "\"websocket_url\":\"wss://example\"}}}";
        assertEquals("https://www.reddit.com/user/x/submitted/", ImageHosting.pendingReplyUserPage(pending));
        // A normal post reply, an error reply and junk are left alone.
        assertNull(ImageHosting.pendingReplyUserPage(
                "{\"json\":{\"errors\":[],\"data\":{\"url\":\"https://www.reddit.com/r/a/comments/b/c/\",\"id\":\"b\",\"name\":\"t3_b\"}}}"));
        assertNull(ImageHosting.pendingReplyUserPage(
                "{\"json\":{\"errors\":[[\"BAD_IMAGE\",\"x\",\"url\"]],\"data\":{\"websocket_url\":\"wss://example\"}}}"));
        assertNull(ImageHosting.pendingReplyUserPage("not json"));
    }

    @Test
    public void findsThePostByItsImageId() throws Exception {
        String listing = "{\"data\":{\"children\":["
                + "{\"data\":{\"url\":\"https://i.redd.it/other.png\",\"permalink\":\"/r/a/comments/1/x/\",\"id\":\"1\"}},"
                + "{\"data\":{\"url\":\"https://i.redd.it/abc123.gif\",\"permalink\":\"/r/a/comments/2/y/\",\"id\":\"2\"}}]}}";
        assertArrayEquals(new String[] {"/r/a/comments/2/y/", "2"}, ImageHosting.findPost(listing, "abc123"));
        assertNull(ImageHosting.findPost(listing, "missing"));
        assertNull(ImageHosting.findPost("{}", "abc123"));

        JSONObject reply = new JSONObject(ImageHosting.successReply("https://www.reddit.com/r/a/comments/2/y/", "2"));
        JSONObject data = reply.getJSONObject("json").getJSONObject("data");
        assertEquals("t3_2", data.getString("name"));
        assertEquals(0, reply.getJSONObject("json").getJSONArray("errors").length());
    }
}
