package dev.local.adbguardian;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static dev.local.adbguardian.ScreenGuardPolicy.State.*;
import static org.junit.Assert.*;

public final class GuardStoreTest {
    private static ScreenGuardPolicy.Battery battery(int percent, Integer temperature) {
        return new ScreenGuardPolicy.Battery(percent, 100, temperature);
    }

    // 只替代 Android 存储边界，执行真实 GuardStore 的转换去重、格式和共享历史上限逻辑。
    private static SharedPreferences memoryPreferences() {
        Map<String, Object> values = new HashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getString":
                        case "getLong": return values.getOrDefault(args[0], args[1]);
                        case "edit":
                            Map<String, Object> pending = new HashMap<>();
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, input) -> {
                                        switch (operation.getName()) {
                                            case "putString":
                                            case "putLong": pending.put((String) input[0], input[1]); return editor;
                                            case "apply": values.putAll(pending); return null;
                                            case "commit": values.putAll(pending); return true;
                                            default: throw new UnsupportedOperationException(operation.getName());
                                        }
                                    });
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    @Test public void screenOffPreservesPriorProtectionWithoutOverwritingWirelessStatus() {
        GuardStore store = new GuardStore(memoryPreferences());
        store.status("正在尝试恢复", "请求已交给系统");
        long checkedAt = store.checkedAt();
        ScreenGuardPolicy.Battery warm = battery(80, 375);
        store.screenStatus(PROTECTION, "屏幕保持：保护暂停", ScreenGuardPolicy.protectionReason(warm, 2), warm, 2);
        store.screenStatus(SCREEN_OFF, "屏幕保持：关屏等待", "", warm, 2);

        String[] history = store.events().split("\n");
        assertEquals(3, history.length);
        assertTrue(history[0].contains("屏幕保持：关屏等待"));
        assertTrue(history[1].contains("屏幕保持：保护暂停（等待系统热状态≤轻度（1））"));
        assertTrue(history[1].contains("电量 80.0% · 电池 37.5℃ · 热状态 2"));
        assertTrue(history[2].endsWith("正在尝试恢复"));
        assertEquals("正在尝试恢复", store.title());
        assertEquals("请求已交给系统", store.detail());
        assertEquals(checkedAt, store.checkedAt());
    }

    @Test public void repeatedChecksAndStoreRecreationDoNotLogReadoutChangesOrPulses() {
        SharedPreferences prefs = memoryPreferences();
        GuardStore store = new GuardStore(prefs);
        store.screenStatus(REQUESTED_OVERLAY, "屏幕保持：已请求", "", battery(80, 300), 0);
        String initial = store.events();
        for (int check = 0; check < 60; check++) {
            new GuardStore(prefs).screenStatus(REQUESTED_OVERLAY, "屏幕保持：已请求", "", battery(79, 310 + check), 1);
        }
        assertEquals(initial, store.events());
        store.screenStatus(PROTECTION, "屏幕保持：保护暂停", ScreenGuardPolicy.protectionReason(battery(20, 300), 0), battery(20, 300), 0);
        String paused = store.events();
        store.screenStatus(PROTECTION, "屏幕保持：保护暂停", ScreenGuardPolicy.protectionReason(battery(24, 300), 0), battery(24, 300), 0);
        assertEquals(paused, store.events());
        store.screenStatus(PROTECTION, "屏幕保持：保护暂停", ScreenGuardPolicy.protectionReason(battery(80, 390), 0), battery(80, 390), 0);
        assertEquals(3, store.events().split("\n").length);
        assertTrue(store.events().split("\n")[0].contains("等待电池温度≤38℃"));
    }

    @Test public void wirelessAndScreenTransitionsShareTheSameThirtyEntryBound() {
        GuardStore store = new GuardStore(memoryPreferences());
        for (int transition = 0; transition < 20; transition++) {
            store.status("无线转换 " + transition, "状态变化");
            store.screenStatus(transition % 2 == 0 ? ACTIVE : SCREEN_OFF, transition % 2 == 0 ? "屏幕保持：已请求" : "屏幕保持：关屏等待", "", battery(80, 300), 0);
        }
        String[] history = store.events().split("\n");
        assertEquals(30, history.length);
        assertTrue(history[0].contains("屏幕保持：关屏等待"));
        assertTrue(history[1].endsWith("无线转换 19"));
        assertTrue(history[29].endsWith("无线转换 5"));
    }

    @Test public void invalidAndMissingHealthReadoutsAreExplicitlyUnknown() {
        GuardStore store = new GuardStore(memoryPreferences());
        store.screenStatus(PROTECTION, "屏幕保持：保护暂停", ScreenGuardPolicy.protectionReason(null, -1), null, -1);
        assertTrue(store.events().contains("电量 未知 · 电池 未知 · 热状态 未知"));
        store.screenStatus(SCREEN_OFF, "屏幕保持：关屏等待", "", new ScreenGuardPolicy.Battery(80, 0, null), 7);
        assertTrue(store.events().split("\n")[0].contains("电量 未知 · 电池 未知 · 热状态 未知"));
    }
}
