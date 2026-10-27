package dev.local.adbguardian;

/** 无平台依赖的恢复策略。系统回写关闭后暂停，避免在未信任的网络上反复触发确认弹窗。 */
final class GuardPolicy {
    static final long VERIFY_DELAY_MS = 5_000L;
    enum State { STOPPED, PERMISSION, WIFI, READY, ENABLE, VERIFY, ATTENTION }

    private boolean pending;
    private boolean blocked;
    private long requestedAt;

    GuardPolicy(boolean pending, boolean blocked, long now) {
        this.pending = pending;
        this.blocked = blocked;
        requestedAt = now;
    }

    State evaluate(boolean enabled, boolean permission, boolean wifi, boolean setting, long now) {
        if (!enabled) return State.STOPPED;
        if (!permission) return State.PERMISSION;
        if (!wifi) {
            pending = false;
            return State.WIFI;
        }
        if (pending) {
            if (now - requestedAt < VERIFY_DELAY_MS) return State.VERIFY;
            pending = false;
            if (!setting) blocked = true;
        }
        if (setting) {
            blocked = false;
            return State.READY;
        }
        return blocked ? State.ATTENTION : State.ENABLE;
    }

    void requested(long now) { pending = true; requestedAt = now; }
    void rejected() { pending = false; blocked = true; }
    boolean pending() { return pending; }
    boolean blocked() { return blocked; }
}
