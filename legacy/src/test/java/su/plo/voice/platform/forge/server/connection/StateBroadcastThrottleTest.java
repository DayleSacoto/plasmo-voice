package su.plo.voice.platform.forge.server.connection;

import org.junit.Test;

import static org.junit.Assert.*;

public class StateBroadcastThrottleTest {
    @Test
    public void firstChangeIsImmediateAndBurstKeepsTrailingUpdate() {
        StateBroadcastThrottle throttle = new StateBroadcastThrottle();
        assertTrue(throttle.changed(1_000));
        assertFalse(throttle.due(1_100));
        assertFalse(throttle.changed(1_100));
        assertFalse(throttle.changed(1_200));
        assertFalse(throttle.due(1_249));
        assertTrue(throttle.due(1_250)); // one trailing broadcast for the whole burst
        assertFalse(throttle.due(2_000));
    }

    @Test
    public void spacedChangesAreAllImmediate() {
        StateBroadcastThrottle throttle = new StateBroadcastThrottle();
        assertTrue(throttle.changed(0));
        assertTrue(throttle.changed(250));
        assertTrue(throttle.changed(10_000));
        assertFalse(throttle.due(20_000));
    }
}
