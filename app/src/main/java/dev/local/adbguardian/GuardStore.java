package dev.local.adbguardian;

import android.content.Context;
import android.content.SharedPreferences;
import android.annotation.SuppressLint;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// 用户停止/拒绝恢复是启动与系统写入的前置约束，须在执行副作用之前落盘。
@SuppressLint("ApplySharedPref")
final class GuardStore {
    private final SharedPreferences prefs;
    GuardStore(Context context) { prefs = context.getSharedPreferences("guardian", Context.MODE_PRIVATE); }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    void setEnabled(boolean value) { prefs.edit().putBoolean("enabled", value).commit(); }
    boolean keepAwake() { return prefs.getBoolean("keep_awake", true); }
    void setKeepAwake(boolean value) { prefs.edit().putBoolean("keep_awake", value).apply(); }
    boolean keepScreenOn() { return prefs.getBoolean("keep_screen_on", false); }
    void setKeepScreenOn(boolean value) { prefs.edit().putBoolean("keep_screen_on", value).apply(); }
    boolean screenOverlay() { return prefs.getBoolean("screen_overlay", false); }
    void setScreenOverlay(boolean value) { prefs.edit().putBoolean("screen_overlay", value).apply(); }
    OverlayPosition overlayPosition() {
        return new OverlayPosition(prefs.getBoolean("overlay_right", true),
                prefs.getFloat("overlay_height_fraction", 0.5f));
    }
    void setOverlayPosition(OverlayPosition value) {
        prefs.edit().putBoolean("overlay_right", value.right)
                .putFloat("overlay_height_fraction", value.heightFraction).apply();
    }
    boolean blocked() { return prefs.getBoolean("blocked", false); }
    boolean pending() { return prefs.getBoolean("pending", false); }
    void savePolicy(GuardPolicy policy) {
        prefs.edit().putBoolean("blocked", policy.blocked()).putBoolean("pending", policy.pending()).commit();
    }
    void clearAttention() { prefs.edit().putBoolean("blocked", false).putBoolean("pending", false).commit(); }
    String title() { return prefs.getString("title", "尚未开始守护"); }
    String detail() { return prefs.getString("detail", "首次授权后，自动检查并尝试恢复无线调试。"); }
    long checkedAt() { return prefs.getLong("checked_at", 0); }
    String events() { return prefs.getString("events", ""); }

    void status(String title, String detail) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs.edit().putString("title", title)
                .putString("detail", detail).putLong("checked_at", now);
        // 只记录状态变化；不记录配对码、授权密钥、网络名称或原始系统响应。
        if (!title.equals(title()) || !detail.equals(detail())) {
            String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(now));
            String[] previous = events().split("\n");
            StringBuilder history = new StringBuilder(time).append("  ").append(title);
            for (int i = 0; i < Math.min(previous.length, 29); i++) {
                if (!previous[i].isEmpty()) history.append('\n').append(previous[i]);
            }
            editor.putString("events", history.toString());
        }
        editor.apply();
    }
}
