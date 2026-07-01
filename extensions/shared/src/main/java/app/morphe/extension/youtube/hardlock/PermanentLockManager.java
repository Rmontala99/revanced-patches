package app.morphe.extension.youtube.hardlock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Freezes settings in the more restrictive direction only.
 * <p>
 * Once a boolean setting's key is locked, it can still be saved as {@code true}
 * (more restrictive) but any attempt to save it as {@code false} (less restrictive,
 * i.e. turning off a restriction) is blocked. Locked keys are stored with an HMAC
 * and are append-only: a key can never be unlocked once locked. If the stored data
 * is found to be tampered with, every setting is treated as locked (fail closed).
 */
@SuppressWarnings("unused")
public final class PermanentLockManager {

    private static final String KEY_LOCKED_FEATURES = "locked_features";
    private static final String LOCK_TOAST_MESSAGE =
            "Este ajuste está bloqueado permanentemente y no se puede desactivar.";

    private static volatile boolean interceptorRegistered = false;

    private PermanentLockManager() {}

    /**
     * @return The set of locked setting keys, or {@code null} if the stored data
     * has been tampered with.
     */
    private static Set<String> readLockedKeys(Context context) {
        if (context == null) {
            return new LinkedHashSet<>();
        }

        SharedPreferences prefs = HardLockPrefs.getHardLockPrefs(context);
        String stored = HardLockPrefs.getStringVerified(context, prefs, KEY_LOCKED_FEATURES, "");
        if (stored == null) {
            return null; // Tampered.
        }

        Set<String> keys = new LinkedHashSet<>();
        if (!stored.isEmpty()) {
            keys.addAll(Arrays.asList(stored.split(",")));
        }
        return keys;
    }

    private static void writeLockedKeys(Context context, Set<String> keys) {
        SharedPreferences prefs = HardLockPrefs.getHardLockPrefs(context);
        HardLockPrefs.putStringWithHmac(context, prefs, KEY_LOCKED_FEATURES, String.join(",", keys));
    }

    /**
     * Permanently locks the given setting keys. Append-only: a key already locked
     * stays locked, and no key can ever be removed from the locked set.
     *
     * @return true if the lock was applied (or the keys were already locked);
     * false if the existing data is tampered with and could not be safely modified.
     */
    public static synchronized boolean activate(Context context, String... lockedKeys) {
        if (context == null) {
            return false;
        }

        Set<String> keys = readLockedKeys(context);
        if (keys == null) {
            Logger.printException(() -> "Refusing to modify tampered permanent lock data");
            return false;
        }

        boolean changed = false;
        for (String key : lockedKeys) {
            if (keys.add(key)) {
                changed = true;
            }
        }
        if (changed) {
            writeLockedKeys(context, keys);
        }
        return true;
    }

    /**
     * Fail-closed: if the lock data has been tampered with, every key is considered locked.
     */
    public static boolean isLocked(Context context, String key) {
        Set<String> keys = readLockedKeys(context);
        if (keys == null) {
            return true; // Tampered - fail closed.
        }
        return keys.contains(key);
    }

    /**
     * Injection point - called from Application.onCreate via PermanentLockPatch.
     * Registers the {@link Setting.SaveInterceptor} that blocks turning a locked
     * boolean setting back to {@code false}.
     */
    public static synchronized void initialize(Context context) {
        if (interceptorRegistered) {
            return;
        }
        interceptorRegistered = true;

        Setting.addSaveInterceptor((setting, newValue) -> {
            if (!Boolean.FALSE.equals(newValue)) {
                return false; // Only the "less restrictive" (false) direction is ever blocked.
            }
            Context appContext = Utils.getContext();
            if (appContext == null || !isLocked(appContext, setting.key)) {
                return false;
            }
            Utils.showToastShort(LOCK_TOAST_MESSAGE);
            return true;
        });
    }
}
