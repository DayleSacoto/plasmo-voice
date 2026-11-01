package su.plo.voice.platform.forge.network.groups;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConnectionQuotaTest {
    @Test
    public void oneFloodingConnectionDoesNotUseUpAnotherOnesQuota() {
        ConnectionQuota<String> quota = new ConnectionQuota<>(3);
        for (int i = 0; i < 3; i++) assertTrue(quota.tryAcquire("flooder"));
        assertFalse(quota.tryAcquire("flooder"));
        assertFalse(quota.tryAcquire("flooder"));
        assertEquals(3, quota.queued("flooder"));

        assertTrue(quota.tryAcquire("other"));
        assertEquals(1, quota.queued("other"));
    }

    @Test
    public void releasingFreesASlotAndForgetsIdleConnections() {
        ConnectionQuota<String> quota = new ConnectionQuota<>(2);
        assertTrue(quota.tryAcquire("a"));
        assertTrue(quota.tryAcquire("a"));
        assertFalse(quota.tryAcquire("a"));
        quota.release("a");
        assertTrue(quota.tryAcquire("a"));
        quota.release("a");
        quota.release("a");
        assertEquals(0, quota.queued("a"));
        // releasing an unknown connection is harmless
        quota.release("a");
        quota.release("unknown");
        assertTrue(quota.tryAcquire("a"));
    }
}
