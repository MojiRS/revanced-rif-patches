package app.revanced.extension.rif;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Reddit poll as the API gives it (the post's {@code poll_data}), and the wording of the poll
 * block. Pure logic (no Android), unit-tested; {@link Polls} does the fetching and drawing.
 *
 * <p>What Reddit returns (2026-11-27, rif's OAuth client): options with {@code text} and {@code id};
 * {@code vote_count} per option only once the poll has ended or the user has voted;
 * {@code total_vote_count}; {@code voting_end_timestamp} in ms; {@code user_selection} (an option
 * id, or null).
 */
final class PollModel {

    /** A poll post's link, which Reddit appends to the selftext ("View Poll"). The id is the post's id. */
    private static final Pattern POLL_URL = Pattern.compile(
            "^https?://(?:(?:www|old|new|np|m)\\.)?reddit\\.com/poll/([a-z0-9]+)/?(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    static final class Option {
        final String id;
        final String text;
        /** -1 when Reddit doesn't say (the poll is open and the user hasn't voted). */
        final long votes;

        Option(String id, String text, long votes) {
            this.id = id;
            this.text = text;
            this.votes = votes;
        }
    }

    final List<Option> options;
    final long total;
    /** 0 if unknown. */
    final long endMs;
    /** The option id the user voted for, or null. */
    final String userSelection;

    PollModel(List<Option> options, long total, long endMs, String userSelection) {
        this.options = Collections.unmodifiableList(options);
        this.total = total;
        this.endMs = endMs;
        this.userSelection = userSelection;
    }

    /** The post id of a poll link, or null if [url] isn't one. */
    static String pollId(String url) {
        if (url == null) return null;
        Matcher m = POLL_URL.matcher(url.trim());
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** True if every option has a vote count (bars and percentages can be shown). */
    boolean hasCounts() {
        if (options.isEmpty()) return false;
        for (Option o : options) if (o.votes < 0) return false;
        return true;
    }

    /**
     * The poll in a {@code /comments/<id>.json} reply (an array: the post listing, then the
     * comments), or in a bare {@code poll_data} object. Null if there is none or it has no options.
     */
    static PollModel parse(String json) {
        try {
            if (json == null) return null;
            String s = json.trim();
            JSONObject poll;
            if (s.startsWith("[")) {
                JSONObject post = new JSONArray(s).getJSONObject(0).getJSONObject("data")
                        .getJSONArray("children").getJSONObject(0).getJSONObject("data");
                poll = post.optJSONObject("poll_data");
            } else {
                poll = new JSONObject(s);
                if (poll.has("poll_data")) poll = poll.optJSONObject("poll_data");
            }
            return poll == null ? null : fromPollData(poll);
        } catch (Exception e) {
            return null;
        }
    }

    static PollModel fromPollData(JSONObject poll) {
        JSONArray arr = poll.optJSONArray("options");
        if (arr == null || arr.length() == 0) return null;
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String text = o.optString("text", "").trim();
            long votes = o.has("vote_count") && !o.isNull("vote_count") ? o.optLong("vote_count", -1) : -1;
            options.add(new Option(o.optString("id", ""), text, votes));
        }
        if (options.isEmpty()) return null;
        long total = poll.isNull("total_vote_count") ? 0 : poll.optLong("total_vote_count", 0);
        long end = poll.isNull("voting_end_timestamp") ? 0 : poll.optLong("voting_end_timestamp", 0);
        String sel = poll.isNull("user_selection") ? null : poll.optString("user_selection", null);
        if (sel != null && sel.isEmpty()) sel = null;
        return new PollModel(options, total, end, sel);
    }

    /** "Poll · 2,048 votes · ends in 3 days" / "Poll · 7,240 votes · ended". */
    String header(long nowMs, Locale locale) {
        StringBuilder sb = new StringBuilder("Poll · ");
        sb.append(votes(total, locale));
        String end = endText(endMs, nowMs);
        if (end != null) sb.append(" · ").append(end);
        return sb.toString();
    }

    static String votes(long n, Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(n) + (n == 1 ? " vote" : " votes");
    }

    static String count(long n, Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(n);
    }

    /** "ended", "ends in 5 min", "ends in 3 hours", "ends in 2 days"; null if unknown. */
    static String endText(long endMs, long nowMs) {
        if (endMs <= 0) return null;
        long left = endMs - nowMs;
        if (left <= 0) return "ended";
        long min = (left + 59_999) / 60_000; // round up: "ends in 0 min" never shows
        if (min < 60) return "ends in " + min + " min";
        long hours = min / 60;
        if (hours < 48) return "ends in " + hours + (hours == 1 ? " hour" : " hours");
        return "ends in " + Math.round(hours / 24.0) + " days"; // 2 days 23 hours: "3 days"
    }

    /** True once the poll's end time has passed. */
    boolean ended(long nowMs) {
        return endMs > 0 && endMs <= nowMs;
    }

    /** The denominator for percentages: the total, or the options' sum if the total is missing. */
    long countBase() {
        long sum = 0;
        for (Option o : options) if (o.votes > 0) sum += o.votes;
        return total > 0 ? Math.max(total, sum) : sum;
    }

    /** Whole percentage of [o] (0-100); 0 when nothing is counted. */
    int percent(Option o) {
        long base = countBase();
        if (base <= 0 || o.votes <= 0) return 0;
        return (int) Math.min(100, Math.round(100.0 * o.votes / base));
    }

    /** The bar's fill fraction for [o] (0-1). */
    float fraction(Option o) {
        long base = countBase();
        if (base <= 0 || o.votes <= 0) return 0f;
        return (float) Math.min(1.0, (double) o.votes / base);
    }

    /** True if [o] has the most votes (ties: all of them), and some votes were counted. */
    boolean leads(Option o) {
        if (o.votes <= 0) return false;
        for (Option other : options) if (other.votes > o.votes) return false;
        return true;
    }

    boolean isUserChoice(Option o) {
        return userSelection != null && userSelection.equals(o.id);
    }
}
