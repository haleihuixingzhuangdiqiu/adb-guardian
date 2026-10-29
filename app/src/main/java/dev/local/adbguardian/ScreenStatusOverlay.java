package dev.local.adbguardian;

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import java.util.Collections;

/** 真实可见的侧边把手；只在服务主线程调用，不承担屏幕唤醒或亮度控制。 */
final class ScreenStatusOverlay implements ComponentCallbacks, DisplayManager.DisplayListener {
    private static final String TAG = "ScreenStatusOverlay";
    private static final int[] MOVE_ACTIONS = {
            R.id.overlay_move_left, R.id.overlay_move_right, R.id.overlay_move_up, R.id.overlay_move_down
    };
    private static final int[] MOVE_LABELS = {
            R.string.screen_overlay_move_left, R.string.screen_overlay_move_right,
            R.string.screen_overlay_move_up, R.string.screen_overlay_move_down
    };
    private final Context context;
    private final WindowManager windows;
    private final DisplayManager displays;
    private final GuardStore store;
    private final FailureListener failureListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable collapse = () -> render(false);
    private TextView view;
    private WindowManager.LayoutParams params;
    private OverlayPosition position;
    private OverlayPosition.Area area;
    private boolean added, configurationRegistered, displayRegistered, tracking, dragging;
    private float downX, downY;
    private int startX, startY;

    interface FailureListener {
        void onFailure(boolean removed);
    }

    ScreenStatusOverlay(Context context, FailureListener failureListener) {
        this.context = context;
        this.failureListener = failureListener;
        windows = context.getSystemService(WindowManager.class);
        displays = context.getSystemService(DisplayManager.class);
        store = new GuardStore(context);
    }

    boolean show() {
        if (added && isVisible()) return true;
        if (view != null && !hide()) return false;
        try {
            position = store.overlayPosition();
            view = new TextView(context);
            view.setTextSize(12);
            view.setTextColor(context.getColor(R.color.surface));
            view.setGravity(Gravity.CENTER);
            view.setFocusable(false);
            view.setClickable(true);
            view.setBackground(background());
            view.setOnClickListener(v -> {
                if (render(true)) handler.postDelayed(collapse, 3_000);
            });
            view.setOnTouchListener(this::touch);
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                    for (int i = 0; i < MOVE_ACTIONS.length; i++) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                MOVE_ACTIONS[i], context.getString(MOVE_LABELS[i])));
                    }
                }

                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    for (int i = 0; i < MOVE_ACTIONS.length; i++) {
                        if (action != MOVE_ACTIONS[i]) continue;
                        tracking = dragging = false;
                        host.setPressed(false);
                        position = new OverlayPosition(i < 2 ? i == 1 : position.right,
                                position.heightFraction + (i == 2 ? -0.1f : i == 3 ? 0.1f : 0));
                        if (render(false)) store.setOverlayPosition(position);
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
            params = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            // 实际窗口外的触摸直接交给应用；沿用 Android 12+ 的遮挡透明度限制。
            if (Build.VERSION.SDK_INT >= 31) {
                params.alpha = Math.min(0.8f, context.getSystemService(InputManager.class)
                        .getMaximumObscuringOpacityForTouch());
                if (params.alpha <= 0) throw new IllegalStateException("Visible overlay unavailable");
            }
            // 使用显示屏坐标自行扣除安全区，避免 WMS 再次加上系统栏 inset。
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setTitle(context.getString(R.string.screen_overlay_title));
            if (!render(false)) return false;
            windows.addView(view, params);
            added = true;
            context.registerComponentCallbacks(this);
            configurationRegistered = true;
            displays.registerDisplayListener(this, handler);
            displayRegistered = true;
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                refreshGeometry(false);
                return insets;
            });
            return true;
        } catch (RuntimeException error) {
            return failed("Cannot add screen status overlay", error);
        }
    }

    /** Layout only the actual visible handle/label; never create an invisible hit-area extension. */
    private boolean render(boolean expanded) {
        handler.removeCallbacks(collapse);
        if (view == null) return false;
        try {
            area = safeArea();
            view.setText(expanded ? context.getString(R.string.screen_overlay_label) : "│");
            view.setPadding(expanded ? dp(10) : 0, expanded ? dp(6) : 0,
                    expanded ? dp(10) : 0, expanded ? dp(6) : 0);
            view.setContentDescription(context.getString(expanded
                    ? R.string.screen_overlay_expanded : R.string.screen_overlay_handle));
            view.measure(View.MeasureSpec.makeMeasureSpec(Math.min(dp(200), area.width()),
                    View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            OverlayPosition.Frame frame = area.place(position,
                    expanded ? Math.max(dp(24), view.getMeasuredWidth()) : dp(24),
                    expanded ? Math.max(dp(48), view.getMeasuredHeight()) : dp(48));
            applyFrame(frame);
            return true;
        } catch (RuntimeException error) {
            return failed("Cannot update screen status overlay", error);
        }
    }

    private void applyFrame(OverlayPosition.Frame frame) {
        boolean changed = params.x != frame.x || params.y != frame.y
                || params.width != frame.width || params.height != frame.height;
        params.x = frame.x;
        params.y = frame.y;
        params.width = frame.width;
        params.height = frame.height;
        // 只为可见把手这段边缘保留拖动；其余侧边仍由系统返回手势处理。
        view.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, frame.width, frame.height)));
        if (added && changed) windows.updateViewLayout(view, params);
    }

    private boolean touch(View target, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            handler.removeCallbacks(collapse);
            tracking = true;
            dragging = false;
            downX = event.getRawX();
            downY = event.getRawY();
            startX = params.x;
            startY = params.y;
            target.setPressed(true);
        } else if (tracking) {
            if (action == MotionEvent.ACTION_CANCEL || event.getPointerCount() != 1) {
                finishGesture();
            } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                int slop = ViewConfiguration.get(context).getScaledTouchSlop();
                dragging |= dx * dx + dy * dy > slop * slop;
                if (dragging) {
                    try {
                        applyFrame(area.drag(startX + Math.round(dx), startY + Math.round(dy),
                                params.width, params.height));
                    } catch (RuntimeException error) {
                        failed("Cannot drag screen status overlay", error);
                        return true;
                    }
                }
                if (action == MotionEvent.ACTION_UP) {
                    if (dragging) finishGesture();
                    else {
                        tracking = false;
                        target.setPressed(false);
                        target.performClick();
                    }
                }
            }
        }
        return true;
    }

    private void finishGesture() {
        tracking = dragging = false;
        view.setPressed(false);
        position = area.snap(new OverlayPosition.Frame(params.x, params.y, params.width, params.height));
        if (render(false)) store.setOverlayPosition(position);
    }

    private OverlayPosition.Area safeArea() {
        WindowMetrics metrics = windows.getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        return new OverlayPosition.Area(safe.left, safe.top,
                bounds.width() - safe.right, bounds.height() - safe.bottom);
    }

    private void refreshGeometry(boolean force) {
        if (!added) return;
        try {
            OverlayPosition.Area next = safeArea();
            // 亮度等显示事件和未改变安全区的 inset 分发不应打断拖动或三秒展示。
            if (!force && area.left == next.left && area.top == next.top
                    && area.right == next.right && area.bottom == next.bottom) return;
            tracking = dragging = false;
            view.setPressed(false);
            render(false);
        } catch (RuntimeException error) {
            failed("Cannot refresh screen status overlay", error);
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) { refreshGeometry(true); }
    @Override public void onLowMemory() { }
    @Override public void onDisplayChanged(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) refreshGeometry(false);
    }
    @Override public void onDisplayAdded(int displayId) { }
    @Override public void onDisplayRemoved(int displayId) { }

    boolean hide() {
        handler.removeCallbacks(collapse);
        tracking = dragging = false;
        if (configurationRegistered) context.unregisterComponentCallbacks(this);
        if (displayRegistered) displays.unregisterDisplayListener(this);
        configurationRegistered = displayRegistered = false;
        if (view == null) return true;
        view.setOnApplyWindowInsetsListener(null);
        view.setOnTouchListener(null);
        view.setOnClickListener(null);
        view.setAccessibilityDelegate(null);
        try {
            windows.removeViewImmediate(view);
            view = null;
            added = false;
            return true;
        } catch (IllegalArgumentException error) {
            if (!view.isAttachedToWindow() && view.getParent() == null) {
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
                && view.getWindowVisibility() == View.VISIBLE;
    }

    private boolean failed(String message, RuntimeException error) {
        Log.e(TAG, message, error);
        boolean wasAdded = added;
        boolean removed = hide();
        // 拖动、收起和几何回调不经过服务检查：失败时立即释放屏幕锁并更新状态。
        if (wasAdded) failureListener.onFailure(removed);
        return false;
    }

    private StateListDrawable background() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, background(R.color.muted));
        states.addState(new int[]{}, background(R.color.ink));
        return states;
    }

    private GradientDrawable background(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(context.getColor(color));
        background.setCornerRadius(dp(8));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
