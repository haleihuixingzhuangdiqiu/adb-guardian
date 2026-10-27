package dev.local.adbguardian;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GuardPolicyTest {
    @Test public void missingPermissionAndNetworkNeverRequestEnabling() {
        GuardPolicy policy = new GuardPolicy(false, false, 0);
        assertEquals(GuardPolicy.State.PERMISSION, policy.evaluate(true, false, true, false, 0));
        assertEquals(GuardPolicy.State.WIFI, policy.evaluate(true, true, false, false, 0));
        assertEquals(GuardPolicy.State.ENABLE, policy.evaluate(true, true, true, false, 0));
    }
    @Test public void restoredSettingMustWaitBeforeReportingReady() {
        GuardPolicy policy = new GuardPolicy(false, false, 0);
        policy.requested(100);
        assertEquals(GuardPolicy.State.VERIFY, policy.evaluate(true, true, true, true, 200));
        assertEquals(GuardPolicy.State.READY, policy.evaluate(true, true, true, true, 5_100));
        assertFalse(policy.pending());
        assertFalse(policy.blocked());
    }
    @Test public void systemRejectsOrUntrustedNetworkPausesWithoutRepeatedDialogs() {
        GuardPolicy policy = new GuardPolicy(false, false, 0);
        policy.requested(0);
        assertEquals(GuardPolicy.State.ATTENTION, policy.evaluate(true, true, true, false, 5_000));
        assertEquals(GuardPolicy.State.ATTENTION, policy.evaluate(true, true, true, false, 100_000));
        assertTrue(policy.blocked());
        assertEquals(GuardPolicy.State.READY, policy.evaluate(true, true, true, true, 100_001));
        assertFalse(policy.blocked());
    }
    @Test public void networkLossAllowsRecoveryAfterReconnectWithoutSpuriousRejection() {
        GuardPolicy policy = new GuardPolicy(false, false, 0);
        policy.requested(0);
        assertEquals(GuardPolicy.State.WIFI, policy.evaluate(true, true, false, false, 1_000));
        assertFalse(policy.pending());
        assertEquals(GuardPolicy.State.ENABLE, policy.evaluate(true, true, true, false, 6_000));
    }
    @Test public void stoppedGuardNeverEnablesEvenWithPendingRequest() {
        GuardPolicy policy = new GuardPolicy(true, false, 0);
        assertEquals(GuardPolicy.State.STOPPED, policy.evaluate(false, true, true, false, 6_000));
    }
    @Test public void restartRemembersRejectionAndPendingWrite() {
        GuardPolicy blocked = new GuardPolicy(false, true, 0);
        assertEquals(GuardPolicy.State.ATTENTION, blocked.evaluate(true, true, true, false, 30_000));
        GuardPolicy pending = new GuardPolicy(true, false, 20_000);
        assertEquals(GuardPolicy.State.VERIFY, pending.evaluate(true, true, true, false, 20_000));
        assertEquals(GuardPolicy.State.ATTENTION, pending.evaluate(true, true, true, false, 25_000));
    }
    @Test public void revokedPermissionDoesNotHideRejectionOrTriggerWrites() {
        GuardPolicy policy = new GuardPolicy(false, true, 0);
        assertEquals(GuardPolicy.State.PERMISSION, policy.evaluate(true, false, true, false, 0));
        assertEquals(GuardPolicy.State.ATTENTION, policy.evaluate(true, true, true, false, 30_000));
    }
}
