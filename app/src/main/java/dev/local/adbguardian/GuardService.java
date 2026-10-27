package dev.local.adbguardian;

import android.app.Notification;
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

public final class GuardService extends Service {
    static final String ACTION_STOP = "dev.local.adbguardian.STOP";
    static final String ACTION_RETRY = "dev.local.adbguardian.RETRY";
    private static final String CHANNEL = "guardian";
    private static final int NOTIFICATION_ID = 1;
    private static final long CHECK_INTERVAL_MS = 30_000;
    static boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = this::inspect;
    private GuardStore store;
    private GuardPolicy policy;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivity;
    private boolean registered;
    private String notificationStatus = "";

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { schedule(1_000); }
        @Override public void onLost(Network network) { schedule(1_000); }
    };
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { schedule(0); }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new GuardStore(this);
        policy = new GuardPolicy(store.pending(), store.blocked(), SystemClock.elapsedRealtime());
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdbGuardian:Charging");
        wakeLock.setReferenceCounted(false);
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // 先持久化关闭；后续任何回调和重启都不能重新恢复用户已停止的守护。
            store.setEnabled(false);
            store.status("守护已停止", "自动恢复与加强保活已停止，现有无线调试设置保持不变。");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!store.enabled()) {
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
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (!registered) {
            connectivity = getSystemService(ConnectivityManager.class);
            NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            connectivity.registerNetworkCallback(request, networkCallback, handler);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(powerReceiver, filter);
            registered = true;
        }
        schedule(0);
        return START_STICKY;
    }

    private void schedule(long delay) {
        handler.removeCallbacks(check);
        if (running) handler.postDelayed(check, delay);
    }

    private void inspect() {
        if (!store.enabled()) { stopSelf(); return; }
        boolean hasPermission = DeviceState.permission(this);
        boolean wifi = DeviceState.wifi(this);
        updateWakeLock(hasPermission && wifi && !policy.blocked());
        GuardPolicy.State state;
        try {
            state = policy.evaluate(true, hasPermission, wifi, DeviceState.wirelessEnabled(this), SystemClock.elapsedRealtime());
        } catch (SecurityException error) {
            policy.rejected();
            store.savePolicy(policy);
            report("系统限制调试设置", "系统拒绝读取无线调试状态。请检查一次性授权；此机型可能需要单独适配。");
            releaseWakeLock();
            schedule(CHECK_INTERVAL_MS);
            return;
        }
        store.savePolicy(policy);
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
                stopSelf();
                return;
        }
        schedule(policy.pending() ? GuardPolicy.VERIFY_DELAY_MS : CHECK_INTERVAL_MS);
    }

    private void updateWakeLock(boolean eligible) {
        if (eligible && store.keepAwake() && DeviceState.charging(this)) {
            // 有超时的非引用计数锁每轮续期；进程停滞也不会永久持锁。
            wakeLock.acquire(90_000);
        } else releaseWakeLock();
    }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
    private void report(String title, String detail) {
        store.status(title, detail);
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
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (registered) {
            connectivity.unregisterNetworkCallback(networkCallback);
            unregisterReceiver(powerReceiver);
            registered = false;
        }
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
