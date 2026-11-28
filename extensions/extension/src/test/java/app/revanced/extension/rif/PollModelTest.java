package app.revanced.extension.rif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

/**
 * JVM tests for {@link PollModel}: parsing Reddit's poll_data (shapes seen 2026-11-27 through
 * rif's OAuth client) and the block's wording. Needs the real org.json test dependency.
 */
public class PollModelTest {

    // An open poll the user hasn't voted in: no per-option vote_count.
    private static final String OPEN = "[{\"kind\":\"Listing\",\"data\":{\"children\":[{\"kind\":\"t3\",\"data\":{"
            + "\"id\":\"1y71ps3\",\"selftext\":\"x\\n\\n[View Poll](https://www.reddit.com/poll/1y71ps3)\","
            + "\"poll_data\":{\"prediction_status\":null,\"tournament_id\":null,"
            + "\"voting_end_timestamp\":1796066673442,\"total_vote_count\":2048,\"vote_updates_remained\":null,"
            + "\"is_prediction\":false,\"resolved_option_id\":null,\"user_won_amount\":null,"
            + "\"user_selection\":null,\"options\":[{\"text\":\"\\u201cWarthog\\u201d auto 210\",\"id\":\"33843883\"},"
            + "{\"text\":\"\\u201cStubby\\u201d voltaic smg\",\"id\":\"33843884\"},"
            + "{\"text\":\"LOK-1 Smart rifle\",\"id\":\"33843885\"}],\"total_stake_amount\":null}}}]}},"
            + "{\"kind\":\"Listing\",\"data\":{\"children\":[]}}]";

    // An ended poll: counts per option.
    private static final String ENDED = "{\"voting_end_timestamp\":1779027414394,\"options\":["
            + "{\"text\":\"yes\",\"vote_count\":6726,\"id\":\"32590831\"},"
            + "{\"text\":\"no\",\"vote_count\":184,\"id\":\"32590832\"},"
            + "{\"text\":\"yes but i would fail\",\"vote_count\":330,\"id\":\"32590833\"}],"
            + "\"user_selection\":\"32590832\",\"total_vote_count\":7240}";

    @Test
    public void parsesOpenPollWithoutCounts() {
        PollModel p = PollModel.parse(OPEN);
        assertNotNull(p);
        assertEquals(3, p.options.size());
        assertEquals("“Warthog” auto 210", p.options.get(0).text);
        assertEquals("33843884", p.options.get(1).id);
        assertEquals(-1, p.options.get(2).votes);
        assertEquals(2048, p.total);
        assertEquals(1796066673442L, p.endMs);
        assertNull(p.userSelection);
        assertFalse(p.hasCounts());
    }

    @Test
    public void parsesEndedPollWithCountsAndVote() {
        PollModel p = PollModel.parse(ENDED);
        assertNotNull(p);
        assertTrue(p.hasCounts());
        assertEquals(6726, p.options.get(0).votes);
        assertEquals("32590832", p.userSelection);
        assertTrue(p.isUserChoice(p.options.get(1)));
        assertFalse(p.isUserChoice(p.options.get(0)));
        assertEquals(93, p.percent(p.options.get(0)));
        assertEquals(3, p.percent(p.options.get(1)));
        assertEquals(5, p.percent(p.options.get(2)));
        assertTrue(p.leads(p.options.get(0)));
        assertFalse(p.leads(p.options.get(1)));
        assertEquals(6726f / 7240f, p.fraction(p.options.get(0)), 1e-6);
    }

    @Test
    public void noPollData() {
        assertNull(PollModel.parse("[{\"data\":{\"children\":[{\"data\":{\"id\":\"abc\"}}]}}]"));
        assertNull(PollModel.parse("{\"options\":[]}"));
        assertNull(PollModel.parse("<html>blocked</html>"));
        assertNull(PollModel.parse(null));
    }

    @Test
    public void zeroVotesNoDivision() {
        PollModel p = PollModel.parse("{\"options\":[{\"text\":\"a\",\"vote_count\":0,\"id\":\"1\"}],"
                + "\"total_vote_count\":0}");
        assertNotNull(p);
        assertEquals(0, p.percent(p.options.get(0)));
        assertEquals(0f, p.fraction(p.options.get(0)), 0);
        assertFalse(p.leads(p.options.get(0)));
    }

    @Test
    public void header() {
        PollModel p = PollModel.parse(ENDED);
        assertEquals("Poll · 7,240 votes · ended", p.header(1779027414394L + 1, Locale.US));
        PollModel open = PollModel.parse(OPEN);
        long end = open.endMs;
        assertEquals("Poll · 2,048 votes · ends in 3 days", open.header(end - 3 * 86_400_000L - 5, Locale.US));
        assertEquals("Poll · 1 vote", new PollModel(open.options, 1, 0, null).header(0, Locale.US));
    }

    @Test
    public void endText() {
        long now = 1_000_000_000L;
        assertNull(PollModel.endText(0, now));
        assertEquals("ended", PollModel.endText(now, now));
        assertEquals("ends in 1 min", PollModel.endText(now + 1, now));
        assertEquals("ends in 59 min", PollModel.endText(now + 59 * 60_000L, now));
        assertEquals("ends in 1 hour", PollModel.endText(now + 60 * 60_000L, now));
        assertEquals("ends in 47 hours", PollModel.endText(now + 47 * 3_600_000L + 1000, now));
        assertEquals("ends in 2 days", PollModel.endText(now + 48 * 3_600_000L, now));
        assertEquals("ends in 6 days", PollModel.endText(now + 6 * 86_400_000L + 5 * 3_600_000L, now));
    }

    @Test
    public void pollIds() {
        assertEquals("1y71ps3", PollModel.pollId("https://www.reddit.com/poll/1y71ps3"));
        assertEquals("1y71ps3", PollModel.pollId("https://reddit.com/poll/1Y71PS3/"));
        assertEquals("1y71ps3", PollModel.pollId("http://old.reddit.com/poll/1y71ps3?utm_source=x"));
        assertNull(PollModel.pollId("https://www.reddit.com/poll/"));
        assertNull(PollModel.pollId("https://www.reddit.com/r/polls/comments/1y71ps3/x"));
        assertNull(PollModel.pollId("https://example.com/poll/1y71ps3"));
        assertNull(PollModel.pollId("https://www.reddit.com/poll/1y71ps3/extra"));
        assertNull(PollModel.pollId(null));
    }
}
