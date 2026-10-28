package dev.local.adbguardian;

import android.app.Notification;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.lang.ref.WeakReference;

public final class GuardService extends Service {
    static final String ACTION_STOP = "dev.local.adbguardian.STOP";
    static final String ACTION_RETRY = "dev.local.adbguardian.RETRY";
    private static final String CHANNEL = "guardian";
    private static final int NOTIFICATION_ID = 1;
    private static final long CHECK_INTERVAL_MS = 30_000;
    static boolean running;
    private static WeakReference<GuardService> activeService = new WeakReference<>(null);
    private static ScreenGuardPolicy.State screenStatus = ScreenGuardPolicy.State.OFF;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = this::inspect;
    private GuardStore store;
    private GuardPolicy policy;
    private PowerManager.WakeLock wakeLock;
    private PowerManager powerManager;
    private PowerManager.WakeLock screenWakeLock;
    private KeyguardManager keyguardManager;
    private ScreenStatusOverlay screenOverlay;
    private final ScreenGuardPolicy screenPolicy = new ScreenGuardPolicy();
    private boolean guardReady;
    private boolean screenOff;
    private boolean destroyed;
    private boolean thermalRegistered;
    private ConnectivityManager connectivity;
    private boolean registered;
    private String notificationStatus = "";
    private String guardTitle = "正在检查无线调试";

    private final PowerManager.OnThermalStatusChangedListener thermalListener = status -> {
        if (running && !destroyed) updateScreenGuard(DeviceState.battery(this));
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { schedule(1_000); }
        @Override public void onLost(Network network) { schedule(1_000); }
    };
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!running || destroyed) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOff = true;
                releaseScreenGuard();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOff = false;
            }
            if (Intent.ACTION_POWER_CONNECTED.equals(action) || Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                schedule(0);
            } else {
                updateScreenGuard(DeviceState.battery(GuardService.this));
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new GuardStore(this);
        policy = new GuardPolicy(store.pending(), store.blocked(), SystemClock.elapsedRealtime());
        powerManager = getSystemService(PowerManager.class);
        keyguardManager = getSystemService(KeyguardManager.class);
        screenOverlay = new ScreenStatusOverlay(this);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdbGuardian:Charging");
        wakeLock.setReferenceCounted(false);
        screenOff = !powerManager.isInteractive();
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // 先持久化关闭；后续任何回调和重启都不能重新恢复用户已停止的守护。
            store.setEnabled(false);
            store.status("守护已停止", "自动恢复、加强保活与屏幕保持已停止，现有无线调试设置保持不变。");
            stopLocksAndCallbacks();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!store.enabled()) {
            stopLocksAndCallbacks();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RETRY.equals(intent.getAction())) {
            store.clearAttention();
            policy = new GuardPolicy(false, false, SystemClock.elapsedRealtime());
        }
        try {
            startForeground(NOTIFICATION_ID, notification("正在检查无线调试"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (SecurityException | IllegalStateException error) {
            store.status("系统阻止后台守护", "请重新打开应用，并检查通知、后台运行和自启动设置。");
            stopLocksAndCallbacks();
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        activeService = new WeakReference<>(this);
        if (!registered) {
            connectivity = getSystemService(ConnectivityManager.class);
            NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            connectivity.registerNetworkCallback(request, networkCallback, handler);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(powerReceiver, filter, null, handler);
            registered = true;
        }
        schedule(0);
        return START_STICKY;
    }

    private void schedule(long delay) {
        if (!running || destroyed) return;
        handler.removeCallbacks(check);
        handler.postDelayed(check, delay);
    }

    private void inspect() {
        if (!running || destroyed) return;
        if (!store.enabled()) { stopLocksAndCallbacks(); stopSelf(); return; }
        DeviceState.BatterySnapshot battery = DeviceState.battery(this);
        boolean hasPermission = DeviceState.permission(this);
        boolean wifi = DeviceState.wifi(this);
        updateWakeLock(hasPermission && wifi && !policy.blocked(), battery.charging);
        GuardPolicy.State state;
        try {
            state = policy.evaluate(true, hasPermission, wifi, DeviceState.wirelessEnabled(this), SystemClock.elapsedRealtime());
        } catch (SecurityException error) {
            policy.rejected();
            store.savePolicy(policy);
            report("系统限制调试设置", "系统拒绝读取无线调试状态。请检查一次性授权；此机型可能需要单独适配。");
            releaseWakeLock();
            guardReady = false;
            updateScreenGuard(battery);
            schedule(CHECK_INTERVAL_MS);
            return;
        }
        store.savePolicy(policy);
        guardReady = state == GuardPolicy.State.READY;
        switch (state) {
            case PERMISSION:
                report("等待一次性授权", "请从已连接的电脑执行授权命令，再回到本应用。");
                break;
            case WIFI:
                report("等待 Wi-Fi", "网络连接恢复后再检查；本应用不会主动切换或开启 Wi-Fi。");
                break;
            case READY:
                report("守护运行中", "无线调试开关已开启。电脑连接与配对状态请在电脑端确认。");
                break;
            case VERIFY:
                report("等待系统响应", "已请求恢复无线调试；如系统弹出网络确认，请在手机上处理。");
                break;
            case ATTENTION:
                releaseWakeLock();
                report("需要确认系统设置", "系统未保留无线调试开关。请确认可信网络或厂商限制，再点“确认后重试”。");
                break;
            case ENABLE:
                // 写入前保存 pending，进程中断后仍须验证，不能重复触发信任确认。
                policy.requested(SystemClock.elapsedRealtime());
                store.savePolicy(policy);
                try {
                    boolean accepted = Settings.Global.putInt(getContentResolver(), DeviceState.WIFI_DEBUG_SETTING, 1);
                    if (!accepted) {
                        policy.rejected();
                        store.savePolicy(policy);
                        releaseWakeLock();
                        report("系统拒绝恢复", "请打开无线调试设置完成确认，再点“确认后重试”。");
                    } else {
                        report("正在尝试恢复", "请求已交给系统，稍后确认开关状态；暂不代表电脑已连接。");
                    }
                } catch (SecurityException error) {
                    policy.rejected();
                    store.savePolicy(policy);
                    releaseWakeLock();
                    report("授权不可用", "系统拒绝修改无线调试设置。请重新授权，或检查该机型是否支持此权限。");
                }
                break;
            case STOPPED:
                stopLocksAndCallbacks();
                stopSelf();
                return;
        }
        updateScreenGuard(battery);
        schedule(policy.pending() ? GuardPolicy.VERIFY_DELAY_MS : CHECK_INTERVAL_MS);
    }

    private void updateWakeLock(boolean eligible, boolean charging) {
        if (eligible && store.keepAwake() && charging) {
            // 非引用计数锁每轮续期；超时回调在应用进程执行，不能保证进程冻结时按时释放。
            wakeLock.acquire(90_000);
        } else releaseWakeLock();
    }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }

    // Activity 只通知现存服务；保存屏幕选项不启动服务，也不改变 enabled。
    static void screenOptionChanged() {
        GuardService service = activeService.get();
        if (service != null && running && !service.destroyed) {
            service.updateScreenGuard(DeviceState.battery(service));
        }
    }

    @SuppressWarnings("deprecation")
    private void updateScreenGuard(DeviceState.BatterySnapshot battery) {
        if (!running || destroyed) return;
        boolean enabled = store.keepScreenOn() && store.enabled();
        ScreenGuardPolicy.State next;
        try {
            // 不依赖插电状态。热状态读取/监听失败时，按未知数据暂停，并在下次更新重试。
            int thermal = -1;
            if (enabled) {
                if (!thermalRegistered) {
                    powerManager.addThermalStatusListener(handler::post, thermalListener);
                    thermalRegistered = true;
                }
                thermal = powerManager.getCurrentThermalStatus();
            } else if (thermalRegistered) {
                powerManager.removeThermalStatusListener(thermalListener);
                thermalRegistered = false;
            }
            next = screenPolicy.evaluate(enabled, guardReady,
                    !screenOff && powerManager.isInteractive(),
                    powerManager.isWakeLockLevelSupported(PowerManager.SCREEN_DIM_WAKE_LOCK),
                    battery.screenBattery, thermal);
            if (next == ScreenGuardPolicy.State.ACTIVE) {
                next = requestScreenGuard();
            }
        } catch (SecurityException | IllegalStateException | IllegalArgumentException | UnsupportedOperationException error) {
            screenPolicy.evaluate(enabled, guardReady, false, true, battery.screenBattery, -1);
            // 不修改用户选项；后续事件或例行检查可以恢复。
            next = enabled ? ScreenGuardPolicy.State.FAILURE : ScreenGuardPolicy.State.OFF;
        }
        if (next != ScreenGuardPolicy.State.ACTIVE
                && next != ScreenGuardPolicy.State.REQUESTED_OVERLAY
                && next != ScreenGuardPolicy.State.REQUESTED_BACKGROUND_LIMITED) {
            if (!releaseScreenGuard()) next = ScreenGuardPolicy.State.OVERLAY_CLEANUP_FAILURE;
        }
        screenStatus = next;
        updateNotification();
    }

    @SuppressWarnings("deprecation")
    private ScreenGuardPolicy.State requestScreenGuard() {
        boolean compatible = store.screenOverlay();
        if (compatible) {
            if (screenLocked()) return ScreenGuardPolicy.State.WAITING_UNLOCK;
            if (!Settings.canDrawOverlays(this)) return ScreenGuardPolicy.State.OVERLAY_PERMISSION;
            if (!screenOverlay.show()) return ScreenGuardPolicy.State.OVERLAY_FAILURE;
        } else if (!screenOverlay.hide()) {
            return ScreenGuardPolicy.State.OVERLAY_CLEANUP_FAILURE;
        }
        if (screenWakeLock == null) {
            // 无唤醒标志；获取/续期即使与电源键并发，也不能点亮已关闭的屏幕。
            screenWakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "AdbGuardian:Screen");
            screenWakeLock.setReferenceCounted(false);
        }
        screenWakeLock.acquire(90_000);
        // Binder 调用期间状态可能变化，锁屏或撤权后不能继续保留候选窗口和锁。
        if (screenOff || !powerManager.isInteractive()) {
            screenOff = true;
            return ScreenGuardPolicy.State.SCREEN_OFF;
        }
        if (compatible && screenLocked()) return ScreenGuardPolicy.State.WAITING_UNLOCK;
        if (compatible && !Settings.canDrawOverlays(this)) return ScreenGuardPolicy.State.OVERLAY_PERMISSION;
        // isHeld 只用于检测申请失败，不能证明 OEM 允许后台 DIM 生效。
        if (!screenWakeLock.isHeld()) return ScreenGuardPolicy.State.FAILURE;
        if (compatible) return ScreenGuardPolicy.State.REQUESTED_OVERLAY;
        if ("xiaomi".equalsIgnoreCase(Build.MANUFACTURER)) {
            return ScreenGuardPolicy.State.REQUESTED_BACKGROUND_LIMITED;
        }
        return ScreenGuardPolicy.State.ACTIVE;
    }

    private boolean screenLocked() {
        return keyguardManager == null || keyguardManager.isKeyguardLocked() || keyguardManager.isDeviceLocked();
    }

    private void releaseScreenWakeLock() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
    }

    private boolean releaseScreenGuard() {
        releaseScreenWakeLock();
        return screenOverlay == null || screenOverlay.hide();
    }

    static int screenStatusResource() {
        switch (screenStatus) {
            case ACTIVE: return R.string.screen_active;
            case REQUESTED_OVERLAY: return R.string.screen_overlay_requested;
            case REQUESTED_BACKGROUND_LIMITED: return R.string.screen_background_limited;
            case WAITING_UNLOCK: return R.string.screen_unlock_waiting;
            case OVERLAY_PERMISSION: return R.string.screen_overlay_permission_required;
            case OVERLAY_FAILURE: return R.string.screen_overlay_failure;
            case OVERLAY_CLEANUP_FAILURE: return R.string.screen_overlay_cleanup_failure;
            case SCREEN_OFF: return R.string.screen_waiting;
            case PROTECTION: return R.string.screen_protection;
            case UNSUPPORTED: return R.string.screen_unsupported;
            case WAITING_GUARD: return R.string.screen_guard_waiting;
            case FAILURE: return R.string.screen_failure;
            default: return R.string.screen_off;
        }
    }

    private void stopLocksAndCallbacks() {
        running = false;
        activeService.clear();
        guardReady = false;
        handler.removeCallbacksAndMessages(null);
        boolean removed = releaseScreenGuard();
        screenStatus = removed ? ScreenGuardPolicy.State.OFF : ScreenGuardPolicy.State.OVERLAY_CLEANUP_FAILURE;
        if (!removed) store.status("悬浮状态条移除失败", "屏幕锁已释放；请重新打开应用检查。若状态条仍在，可从系统设置强行停止本应用。");
        releaseWakeLock();
    }

    private void report(String title, String detail) {
        store.status(title, detail);
        guardTitle = title;
        updateNotification();
    }
    private void updateNotification() {
        if (!running || destroyed) return;
        String title = guardTitle + " · " + getString(screenStatusResource());
        if (!title.equals(notificationStatus)) {
            notificationStatus = title;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(title));
        }
    }
    private Notification notification(String title) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, GuardService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name)).setContentText(title).setOngoing(true)
                .setOnlyAlertOnce(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build()).build();
    }
    @Override public void onDestroy() {
        destroyed = true;
        stopLocksAndCallbacks();
        if (registered) {
            connectivity.unregisterNetworkCallback(networkCallback);
            unregisterReceiver(powerReceiver);
            registered = false;
        }
        if (thermalRegistered) {
            powerManager.removeThermalStatusListener(thermalListener);
            thermalRegistered = false;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
