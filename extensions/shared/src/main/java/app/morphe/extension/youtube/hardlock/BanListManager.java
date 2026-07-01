package app.morphe.extension.youtube.hardlock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONException;

import app.morphe.extension.shared.utils.Logger;

/**
 * Append-only list of banned search/channel terms, protected with an HMAC so the
 * SharedPreferences file cannot be edited manually to remove an entry.
 * <p>
 * Terms can only ever be added, never removed. If the stored data is found to
 * be tampered with, lookups fail closed (everything is treated as banned)
 * so the restriction cannot be bypassed by corrupting the data.
 */
@SuppressWarnings("unused")
public final class BanListManager {

    private static final String KEY_BAN_LIST_TERMS = "ban_list_terms";

    /**
     * Bumped every time a term is added, so callers (KeywordContentFilter) can
     * cheaply detect changes without re-reading and HMAC-verifying SharedPreferences
     * on every call.
     */
    private static final AtomicInteger version = new AtomicInteger();

    private BanListManager() {}

    /**
     * @return The current ban list, or {@code null} if the stored data has been tampered with.
     */
    private static List<String> readTerms(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }

        SharedPreferences prefs = HardLockPrefs.getHardLockPrefs(context);
        String json = HardLockPrefs.getStringVerified(context, prefs, KEY_BAN_LIST_TERMS, "[]");
        if (json == null) {
            return null; // Tampered.
        }

        List<String> terms = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0, length = array.length(); i < length; i++) {
                terms.add(array.getString(i));
            }
        } catch (JSONException e) {
            Logger.printException(() -> "Failed to parse ban list", e);
        }
        return terms;
    }

    private static void writeTerms(Context context, List<String> terms) {
        SharedPreferences prefs = HardLockPrefs.getHardLockPrefs(context);
        JSONArray array = new JSONArray();
        for (String term : terms) {
            array.put(term);
        }
        HardLockPrefs.putStringWithHmac(context, prefs, KEY_BAN_LIST_TERMS, array.toString());
    }

    /**
     * Adds a term to the ban list. Append-only: existing terms can never be removed,
     * and a term that is already present is a no-op.
     *
     * @return true if the term is now (or was already) in the ban list;
     * false if the existing data is tampered with and could not be safely modified.
     */
    public static synchronized boolean addTerm(Context context, String term) {
        if (context == null || term == null) {
            return false;
        }
        String normalized = term.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }

        List<String> terms = readTerms(context);
        if (terms == null) {
            Logger.printException(() -> "Refusing to modify tampered ban list");
            return false;
        }

        if (!terms.contains(normalized)) {
            terms.add(normalized);
            writeTerms(context, terms);
            version.incrementAndGet();
        }
        return true;
    }

    /**
     * @return true if the ban list data has been tampered with (HMAC mismatch).
     */
    public static boolean isTampered(Context context) {
        return readTerms(context) == null;
    }

    /**
     * Fail-closed substring match: if the data has been tampered with, every text is
     * considered banned so the restriction cannot be bypassed by corrupting the data.
     */
    public static boolean isTermBanned(Context context, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        List<String> terms = readTerms(context);
        if (terms == null) {
            return true; // Tampered - fail closed.
        }

        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Ban list terms joined by newline, ready to be merged into
     * {@code KeywordContentFilter}'s phrase list. Returns an empty string if
     * tampered, so corrupted data is never fed into the filter.
     */
    public static String getBanListPhrases(Context context) {
        List<String> terms = readTerms(context);
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        return String.join("\n", terms);
    }

    /**
     * @return A counter that increases every time a term is added.
     * Used by callers to cheaply detect changes without re-reading SharedPreferences.
     */
    public static int getVersion() {
        return version.get();
    }

    /**
     * Injection point. Called from {@code VideoInformation.setVideoInformation()} whenever
     * a new video's channel is known. Sends the user Home if the channel matches a banned term,
     * so banned channels cannot be reached even if a video link is opened directly.
     */
    public static void onChannelDetected(String channelId, String channelName, Context context) {
        if (context == null) {
            return;
        }
        if (isTermBanned(context, channelName)) {
            Logger.printDebug(() -> "Blocking banned channel: " + channelName);
            TimerManager.sendToHome(context);
        }
    }

    /**
     * Injection point - called from Application.onCreate via BanListPatch.
     * Performs an early tamper check so corruption is logged at startup
     * instead of only being discovered on first use.
     */
    public static void initialize(Context context) {
        if (isTampered(context)) {
            Logger.printException(() -> "Ban list data has been tampered with");
        }
    }
}
