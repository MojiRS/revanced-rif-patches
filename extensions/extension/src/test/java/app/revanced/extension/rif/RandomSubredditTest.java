package app.revanced.extension.rif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class RandomSubredditTest {

    @Test
    public void recognisesRandomListings() {
        assertEquals(Boolean.FALSE, RandomSubreddit.randomKind("/r/random"));
        assertEquals(Boolean.FALSE, RandomSubreddit.randomKind("/r/random/"));
        assertEquals(Boolean.FALSE, RandomSubreddit.randomKind("/r/random/new/.json"));
        assertEquals(Boolean.FALSE, RandomSubreddit.randomKind("/r/Random/hot"));
        assertEquals(Boolean.FALSE, RandomSubreddit.randomKind("/r/random.json"));
        assertEquals(Boolean.FALSE, RandomSubreddit.randomKind("/r/random/top.json"));
        assertEquals(Boolean.TRUE, RandomSubreddit.randomKind("/r/randnsfw/rising/.json"));
        assertEquals(Boolean.TRUE, RandomSubreddit.randomKind("/r/randnsfw"));
    }

    @Test
    public void ignoresEverythingElse() {
        assertNull(RandomSubreddit.randomKind(null));
        assertNull(RandomSubreddit.randomKind(""));
        assertNull(RandomSubreddit.randomKind("/r/randomstuff/hot"));
        assertNull(RandomSubreddit.randomKind("/r/randomstuff"));
        assertNull(RandomSubreddit.randomKind("/r/random/about.json"));
        assertNull(RandomSubreddit.randomKind("/r/random/comments/abc/title/"));
        assertNull(RandomSubreddit.randomKind("/r/pics/hot"));
        assertNull(RandomSubreddit.randomKind("/user/random/submitted"));
        assertNull(RandomSubreddit.randomKind("/random"));
        assertNull(RandomSubreddit.randomKind("/r/all/random"));
    }

    @Test
    public void swapsTheName() {
        assertEquals("/r/woahdude/new/.json", RandomSubreddit.swapPath("/r/random/new/.json", "woahdude"));
        assertEquals("/r/woahdude", RandomSubreddit.swapPath("/r/random", "woahdude"));
        assertEquals("/r/woahdude.json", RandomSubreddit.swapPath("/r/random.json", "woahdude"));
        assertEquals("/r/iso8601/hot", RandomSubreddit.swapPath("/r/randnsfw/hot", "iso8601"));
        assertNull(RandomSubreddit.swapPath("/r/pics/hot", "woahdude"));
    }

    @Test
    public void parsesTheReply() {
        assertEquals("woahdude", RandomSubreddit.parseName("{\"url\":\"/r/woahdude/\"}"));
        assertEquals("30rock", RandomSubreddit.parseName("{ \"url\" : \"\\/r\\/30rock\\/\" }"));
        assertEquals("a_b", RandomSubreddit.parseName("{\"url\":\"/r/a_b\"}"));
    }

    private static List<Integer> ids(Integer... ids) {
        return new ArrayList<>(Arrays.asList(ids));
    }

    @Test
    public void rowGoesBelowRandom() {
        List<Integer> all = ids(0, 1, 2, 3, 4);
        RandomSubreddit.insertPickerRow(all);
        assertEquals(ids(0, 1, 2, 3, 5, 4), all);
        List<Integer> noMod = ids(1, 2, 3);
        RandomSubreddit.insertPickerRow(noMod);
        assertEquals(ids(1, 2, 3, 5), noMod);
    }

    @Test
    public void rowGoesBeforeModWhenRandomIsHidden() {
        List<Integer> list = ids(0, 1, 2, 4);
        RandomSubreddit.insertPickerRow(list);
        assertEquals(ids(0, 1, 2, 5, 4), list);
        List<Integer> noMod = ids(0, 1, 2);
        RandomSubreddit.insertPickerRow(noMod);
        assertEquals(ids(0, 1, 2, 5), noMod);
        List<Integer> empty = ids();
        RandomSubreddit.insertPickerRow(empty);
        assertEquals(ids(5), empty);
    }

    @Test
    public void rowIsAddedOnce() {
        List<Integer> list = ids(2, 3, 5, 4);
        RandomSubreddit.insertPickerRow(list);
        assertEquals(ids(2, 3, 5, 4), list);
    }

    @Test
    public void labelsOnlyOurRow() {
        assertEquals("randnsfw", RandomSubreddit.pickerLabel(null, 5));
        assertEquals("random", RandomSubreddit.pickerLabel("random", 3));
        assertEquals("mod", RandomSubreddit.pickerLabel("mod", 4));
        assertNull(RandomSubreddit.pickerLabel(null, 6));
    }

    @Test
    public void rowIsOffByDefault() {
        // No app Context on the JVM, so the setting reads its default.
        ArrayList<Integer> list = new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4));
        RandomSubreddit.addPickerRow(list);
        assertEquals(ids(0, 1, 2, 3, 4), list);
    }

    @Test
    public void rejectsBadReplies() {
        assertNull(RandomSubreddit.parseName(null));
        assertNull(RandomSubreddit.parseName(""));
        assertNull(RandomSubreddit.parseName("<html>error</html>"));
        assertNull(RandomSubreddit.parseName("{\"url\":\"/u/someone/\"}"));
        assertNull(RandomSubreddit.parseName("{\"url\":\"/r/a/b/../c\"}"));
        assertNull(RandomSubreddit.parseName("{\"error\":\"x\"}"));
    }
}
