package app.morphe.extension.youtube.hardlock;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Session timer with mandatory cooldown and HMAC anti-tamper protection.
 *
 * States:
 *  - NO_SESSION : no active session → show time picker
 *  - ACTIVE     : session in progress → allow normal usage
 *  - EXPIRED    : session elapsed, cooldown started → show cooldown dialog
 *  - COOLDOWN   : waiting for cooldown to finish → show cooldown dialog
 *  - TAMPERED   : HMAC mismatch → block everything
 */
@SuppressWarnings("unused")
public final class TimerManager {

    // SharedPreferences keys
    private static final String KEY_SESSION_END = "session_end_ms";
    private static final String KEY_SESSION_COOLDOWN = "session_cooldown_ms";
    private static final String KEY_COOLDOWN_UNTIL = "cooldown_until_ms";

    // session minutes → cooldown minutes
    private static final int[] SESSION_OPTIONS_MIN = {5, 10, 15, 30, 60};
    private static final int[] COOLDOWN_DURATIONS_MIN = {60, 60, 60, 120, 180};
    private static final String[] OPTION_LABELS = {
            "5 minutos", "10 minutos", "15 minutos", "30 minutos", "1 hora"
    };

    private static volatile boolean initialized = false;

    /**
     * Injection point — called from Application.onCreate via TimerPatch.
     */
    public static synchronized void initialize(Context context) {
        if (initialized) return;
        initialized = true;

        Application app = (Application) context.getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                checkStateOnForeground(activity);
            }

            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    // ─── State machine ────────────────────────────────────────────────────────

    private static void checkStateOnForeground(Activity activity) {
        Context context = activity.getApplicationContext();
        SharedPreferences prefs = HardLockPrefs.getTimerPrefs(context);

        // 1. Verify HMAC integrity of session end.
        Long sessionEnd = HardLockPrefs.getLongVerified(context, prefs, KEY_SESSION_END, 0L);
        if (sessionEnd == null) {
            showTamperedDialog(activity);
            return;
        }

        // 2. Verify HMAC integrity of cooldown.
        Long cooldownUntil = HardLockPrefs.getLongVerified(context, prefs, KEY_COOLDOWN_UNTIL, 0L);
        if (cooldownUntil == null) {
            showTamperedDialog(activity);
            return;
        }

        long now = System.currentTimeMillis();

        // 3. Currently in cooldown → block.
        if (cooldownUntil > now) {
            showCooldownDialog(activity, cooldownUntil);
            return;
        }

        // 4. Session was active but just expired → start cooldown.
        if (sessionEnd > 0 && sessionEnd <= now) {
            Long cooldownMs = HardLockPrefs.getLongVerified(context, prefs, KEY_SESSION_COOLDOWN, 0L);
            long cooldownDuration = (cooldownMs != null && cooldownMs > 0)
                    ? cooldownMs
                    : TimeUnit.HOURS.toMillis(1); // default 1h if somehow not set

            long newCooldownUntil = now + cooldownDuration;
            HardLockPrefs.putLongWithHmac(context, prefs, KEY_COOLDOWN_UNTIL, newCooldownUntil);
            // Clear session so it doesn't re-trigger.
            HardLockPrefs.putLongWithHmac(context, prefs, KEY_SESSION_END, 0L);
            HardLockPrefs.putLongWithHmac(context, prefs, KEY_SESSION_COOLDOWN, 0L);

            showCooldownDialog(activity, newCooldownUntil);
            return;
        }

        // 5. No active session → ask for time.
        if (sessionEnd == 0) {
            showTimePickerDialog(activity, prefs, context);
            return;
        }

        // 6. Active session — allow normal usage.
    }

    // ─── Dialogs ──────────────────────────────────────────────────────────────

    private static void showTimePickerDialog(Activity activity,
                                              SharedPreferences prefs,
                                              Context context) {
        Dialog dialog = buildDialog(activity);
        LinearLayout root = buildRootLayout(activity);

        addTitle(root, activity, "YouTube Clean 🔒");
        addBody(root, activity, "¿Cuánto tiempo quieres usar YouTube hoy?");

        for (int i = 0; i < SESSION_OPTIONS_MIN.length; i++) {
            final int sessionMin = SESSION_OPTIONS_MIN[i];
            final long cooldownMs = TimeUnit.MINUTES.toMillis(COOLDOWN_DURATIONS_MIN[i]);
            addVerticalGap(root, activity, 6);
            Button btn = buildButton(activity, OPTION_LABELS[i]);
            btn.setOnClickListener(v -> {
                long now = System.currentTimeMillis();
                long sessionEnd = now + TimeUnit.MINUTES.toMillis(sessionMin);
                HardLockPrefs.putLongWithHmac(context, prefs, KEY_SESSION_END, sessionEnd);
                HardLockPrefs.putLongWithHmac(context, prefs, KEY_SESSION_COOLDOWN, cooldownMs);
                HardLockPrefs.putLongWithHmac(context, prefs, KEY_COOLDOWN_UNTIL, 0L);
                dialog.dismiss();
            });
            root.addView(btn);
        }

        finishDialog(dialog, root, activity);
    }

    private static void showCooldownDialog(Activity activity, long cooldownUntil) {
        Dialog dialog = buildDialog(activity);
        LinearLayout root = buildRootLayout(activity);

        addTitle(root, activity, "Tiempo agotado 🔒");

        long remaining = Math.max(0, cooldownUntil - System.currentTimeMillis());
        long remainingMins = TimeUnit.MILLISECONDS.toMinutes(remaining) + 1;
        String until = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(cooldownUntil));

        addBody(root, activity,
                "No puedes volver a ver vídeos hasta las " + until + ".\n\n"
                        + "Tiempo restante: " + remainingMins + " min.");

        addVerticalGap(root, activity, 12);
        Button closeBtn = buildButton(activity, "Cerrar");
        closeBtn.setOnClickListener(v -> {
            dialog.dismiss();
            sendToHome(activity);
        });
        root.addView(closeBtn);

        finishDialog(dialog, root, activity);
    }

    private static void showTamperedDialog(Activity activity) {
        Dialog dialog = buildDialog(activity);
        LinearLayout root = buildRootLayout(activity);

        addTitle(root, activity, "Acceso bloqueado 🔒");
        addBody(root, activity,
                "Se ha detectado una modificación no autorizada en los datos del temporizador.\n\n"
                        + "No puedes usar la app hasta que se restablezca.");

        addVerticalGap(root, activity, 12);
        Button closeBtn = buildButton(activity, "Cerrar");
        closeBtn.setOnClickListener(v -> {
            dialog.dismiss();
            sendToHome(activity);
        });
        root.addView(closeBtn);

        finishDialog(dialog, root, activity);
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    static void sendToHome(Context context) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private static Dialog buildDialog(Activity activity) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    private static LinearLayout buildRootLayout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 24);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(0xFF1A1A1A);
        return layout;
    }

    private static void addTitle(LinearLayout root, Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(20f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(context, 12));
        tv.setLayoutParams(lp);
        root.addView(tv);
    }

    private static void addBody(LinearLayout root, Context context, String text) {
        ScrollView sv = new ScrollView(context);
        sv.setVerticalScrollBarEnabled(false);
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.LTGRAY);
        tv.setTextSize(15f);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams inner = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(inner);
        sv.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(context, 16));
        sv.setLayoutParams(lp);
        root.addView(sv);
    }

    private static Button buildButton(Context context, String label) {
        Button btn = new Button(context);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(0xFFCC0000); // YouTube red
        btn.setAllCaps(false);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)));
        return btn;
    }

    private static void addVerticalGap(LinearLayout root, Context context, int dpValue) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, dpValue)));
        root.addView(v);
    }

    private static void finishDialog(Dialog dialog, LinearLayout root, Activity activity) {
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
