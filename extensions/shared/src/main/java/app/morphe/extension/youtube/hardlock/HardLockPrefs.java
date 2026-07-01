package app.morphe.extension.youtube.hardlock;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-protected SharedPreferences wrapper for the HardLock system.
 * All sensitive values (timer timestamps, ban list) are stored with a SHA-256 HMAC
 * derived from a per-installation UUID. No Android permissions are required.
 */
@SuppressWarnings("unused")
public final class HardLockPrefs {

    static final String TIMER_PREFS = "ytch_timer";
    static final String HARDLOCK_PREFS = "ytch_hardlock";
    private static final String KEY_INSTALL_UUID = "install_uuid";
    private static final String HMAC_SUFFIX = "_hmac";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String HMAC_KEY_PREFIX = "ytch_hardlock_v1_";

    private HardLockPrefs() {}

    public static SharedPreferences getTimerPrefs(Context context) {
        return context.getSharedPreferences(TIMER_PREFS, Context.MODE_PRIVATE);
    }

    public static SharedPreferences getHardLockPrefs(Context context) {
        return context.getSharedPreferences(HARDLOCK_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Returns (or generates) the installation UUID.
     * Does not require any Android permission.
     */
    public static String getInstallUUID(Context context) {
        SharedPreferences prefs = getTimerPrefs(context);
        String uuid = prefs.getString(KEY_INSTALL_UUID, null);
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_INSTALL_UUID, uuid).apply();
        }
        return uuid;
    }

    private static byte[] getHmacKey(Context context) {
        return (HMAC_KEY_PREFIX + getInstallUUID(context)).getBytes(StandardCharsets.UTF_8);
    }

    /** Computes HMAC-SHA256 of data using the installation-specific key. */
    public static String computeHmac(Context context, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(getHmacKey(context), HMAC_ALGO));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Saves a long value with its HMAC.
     */
    public static void putLongWithHmac(Context context, SharedPreferences prefs,
                                        String key, long value) {
        String hmac = computeHmac(context, key + "=" + value);
        prefs.edit()
                .putLong(key, value)
                .putString(key + HMAC_SUFFIX, hmac)
                .apply();
    }

    /**
     * Reads a long value and verifies its HMAC.
     * Returns {@code null} if the value has been tampered with.
     * Returns {@code defaultValue} if the key was never set.
     */
    public static Long getLongVerified(Context context, SharedPreferences prefs,
                                        String key, long defaultValue) {
        long value = prefs.getLong(key, defaultValue);
        String storedHmac = prefs.getString(key + HMAC_SUFFIX, "");

        if (value == defaultValue && storedHmac.isEmpty()) {
            return defaultValue; // Key was never written — not tampered.
        }
        String expectedHmac = computeHmac(context, key + "=" + value);
        if (!constantTimeEquals(expectedHmac, storedHmac)) {
            return null; // HMAC mismatch — tampered!
        }
        return value;
    }

    /**
     * Saves a String value with its HMAC.
     */
    public static void putStringWithHmac(Context context, SharedPreferences prefs,
                                          String key, String value) {
        String hmac = computeHmac(context, key + "=" + value);
        prefs.edit()
                .putString(key, value)
                .putString(key + HMAC_SUFFIX, hmac)
                .apply();
    }

    /**
     * Reads a String value and verifies its HMAC.
     * Returns {@code null} if tampered, {@code defaultValue} if never set.
     */
    public static String getStringVerified(Context context, SharedPreferences prefs,
                                            String key, String defaultValue) {
        String value = prefs.getString(key, defaultValue);
        if (value == null) value = defaultValue;
        String storedHmac = prefs.getString(key + HMAC_SUFFIX, "");

        if (value.equals(defaultValue) && storedHmac.isEmpty()) {
            return defaultValue;
        }
        String expectedHmac = computeHmac(context, key + "=" + value);
        if (!constantTimeEquals(expectedHmac, storedHmac)) {
            return null;
        }
        return value;
    }

    /** Constant-time string comparison to prevent timing attacks. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
