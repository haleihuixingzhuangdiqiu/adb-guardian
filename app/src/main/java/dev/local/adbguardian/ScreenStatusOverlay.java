package dev.local.adbguardian;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;

/** 真实可见的静态提示；只在服务主线程调用，不承担屏幕唤醒或亮度控制。 */
final class ScreenStatusOverlay {
    private static final String TAG = "ScreenStatusOverlay";
    private final Context context;
    private final WindowManager windows;
    private TextView view;
    private boolean added;

    ScreenStatusOverlay(Context context) {
        this.context = context;
        windows = context.getSystemService(WindowManager.class);
    }

    boolean show() {
        if (added) return true;
        if (view != null && !hide()) return false;
        try {
            TextView label = new TextView(context);
            label.setText(R.string.screen_overlay_label);
            label.setTextSize(12);
            label.setTextColor(context.getColor(R.color.surface));
            label.setMaxWidth(dp(200));
            label.setPadding(dp(10), dp(6), dp(10), dp(6));
            label.setFocusable(false);
            label.setClickable(false);
            GradientDrawable background = new GradientDrawable();
            background.setColor(context.getColor(R.color.ink));
            background.setCornerRadius(dp(8));
            label.setBackground(background);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            // Android 12+ 会拦截过于不透明的非可信窗口下方触摸；保持可读且允许穿透。
            if (Build.VERSION.SDK_INT >= 31) {
                params.alpha = Math.min(0.8f, context.getSystemService(InputManager.class)
                        .getMaximumObscuringOpacityForTouch());
                if (params.alpha <= 0) throw new IllegalStateException("Visible touch-through overlay unavailable");
            }
            params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            params.x = dp(8);
            params.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            params.setTitle(context.getString(R.string.screen_overlay_title));
            view = label;
            windows.addView(label, params);
            added = true;
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Cannot add screen status overlay", error);
            hide();
            return false;
        }
    }

    boolean hide() {
        if (view == null) return true;
        try {
            windows.removeViewImmediate(view);
            view = null;
            added = false;
            return true;
        } catch (IllegalArgumentException error) {
            if (!view.isAttachedToWindow() && view.getParent() == null) {
                // addView 失败或系统已移除；与仍然挂载的窗口移除失败区分。
                Log.w(TAG, "Screen status overlay already detached", error);
                view = null;
                added = false;
                return true;
            }
            Log.e(TAG, "Cannot remove attached screen status overlay", error);
        } catch (RuntimeException error) {
            Log.e(TAG, "Cannot remove screen status overlay", error);
        }
        added = false;
        return false; // 保留引用供下次清理，服务释放屏幕锁并明确显示限制。
    }

    boolean isVisible() {
        // addView 成功不足以证明当前仍有可见窗口；OEM 额外隐藏策略仍需实测。
        return added && view != null && view.isAttachedToWindow() && view.isShown()
                && view.getWindowVisibility() == android.view.View.VISIBLE;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
