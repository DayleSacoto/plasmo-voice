package su.plo.voice.platform.forge.debug;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.junit.Test;
import su.plo.voice.platform.forge.debug.SilenceMonitor.Event;
import su.plo.voice.platform.forge.debug.VoiceDebug.Category;

import static org.junit.Assert.*;

public class VoiceDebugTest {
    @Test
    public void disabledDiagnosticsWriteNothing() {
        List<String> lines = new ArrayList<>();
        VoiceDebug debug = new VoiceDebug((level, message, args, error) -> lines.add(level + " " + message));
        assertFalse(debug.enabled());
        debug.log(Category.UDP, "snapshot {}", 1);
        debug.warn(Category.KEEPALIVE, "silence {}", 2);
        debug.error(Category.THREAD, "failed", new IllegalStateException());
        assertTrue(lines.isEmpty());

        debug.setEnabled(true);
        debug.log(Category.UDP, "snapshot {}", 1);
        debug.warn(Category.KEEPALIVE, "silence {}", 2);
        assertEquals("INFO [PV DEBUG/STATE] debug logging enabled", lines.get(0));
        assertEquals("INFO [PV DEBUG/UDP] snapshot {}", lines.get(1));
        assertEquals("WARN [PV DEBUG/KEEPALIVE] silence {}", lines.get(2));

        debug.setEnabled(false);
        debug.log(Category.UDP, "snapshot {}", 1);
        assertEquals(3, lines.size());
    }

    @Test
    public void errorsKeepTheirStackTrace() {
        List<Throwable> errors = new ArrayList<>();
        VoiceDebug debug = new VoiceDebug((level, message, args, error) -> {
            if (level == Level.WARN) errors.add(error);
        });
        debug.setEnabled(true);
        IllegalStateException failure = new IllegalStateException("socket closed");
        debug.error(Category.THREAD, "worker stopped: generation={}", failure, 3L);
        assertSame(failure, errors.get(0));
    }

    @Test
    public void snapshotsAreRateLimited() {
        DebugInterval interval = new DebugInterval(5_000L);
        assertTrue(interval.due(1_000L));
        assertFalse(interval.due(1_001L));
        assertFalse(interval.due(5_999L));
        assertTrue(interval.due(6_000L));
        assertFalse(interval.due(10_999L));
    }

    @Test
    public void silenceIsReportedOnceThenRepeatedAndRecovers() {
        SilenceMonitor monitor = new SilenceMonitor(5_000L, 5_000L);
        assertEquals(Event.NONE, monitor.check(100_000L)); // nothing received yet
        monitor.reset(100_000L);
        assertEquals(Event.NONE, monitor.check(104_999L));

        assertEquals(Event.SILENT, monitor.check(105_000L));
        assertEquals(5_000L, monitor.age(105_000L));
        // Every 100 ms loop iteration: no spam until the repeat interval.
        for (long now = 105_100L; now < 110_000L; now += 100L) assertEquals(Event.NONE, monitor.check(now));
        assertEquals(Event.STILL_SILENT, monitor.check(110_000L));

        monitor.traffic(112_000L);
        assertEquals(Event.RECOVERED, monitor.check(112_050L));
        assertEquals(12_000L, monitor.age(112_050L));
        assertEquals(Event.NONE, monitor.check(113_000L));

        // The next outage is reported again.
        assertEquals(Event.SILENT, monitor.check(117_000L));
    }

    @Test
    public void stalledOrDeadWorkersAreReportedOnce() throws Exception {
        List<String> lines = new ArrayList<>();
        VoiceDebug debug = new VoiceDebug((level, message, args, error) -> lines.add(level + " " + String.format(
                message.replace("{}", "%s"), args)));
        debug.setEnabled(true);
        lines.clear();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
        }, "test-worker");
        worker.start();
        while (worker.getState() != Thread.State.WAITING) Thread.sleep(1L);
        WorkerWatch watch = new WorkerWatch("UDP test", 2_000L);
        watch.check(debug, worker, false, 1_000L); // no heartbeat yet
        watch.beat(1_000L);
        watch.check(debug, worker, false, 2_500L);
        assertTrue(lines.isEmpty());

        watch.check(debug, worker, false, 5_000L);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("WARN [PV DEBUG/THREAD] UDP test worker stalled: lastLoopAge=4000ms"));
        assertTrue(lines.get(0), lines.get(0).contains("test-worker") || lines.get(0).contains("await"));
        watch.check(debug, worker, false, 6_000L);
        assertEquals(1, lines.size());
        watch.beat(6_500L);
        watch.check(debug, worker, false, 7_000L);
        assertEquals("INFO [PV DEBUG/THREAD] UDP test worker resumed", lines.get(1));

        release.countDown();
        worker.join();
        watch.check(debug, worker, true, 8_000L); // closed endpoints are not reported
        assertEquals(2, lines.size());
        watch.check(debug, worker, false, 9_000L);
        watch.check(debug, worker, false, 10_000L);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2), lines.get(2).contains("worker is not running"));
    }

    @Test
    public void countersAndAges() {
        UdpStats stats = new UdpStats();
        stats.received(UdpStats.Kind.PING, 1_000L);
        stats.received(UdpStats.Kind.AUDIO, 1_020L);
        stats.received(UdpStats.Kind.OTHER, 1_030L);
        stats.sent(UdpStats.Kind.PING, 1_010L);
        stats.sent(UdpStats.Kind.AUDIO, 1_040L);
        assertEquals(3, stats.rx.get());
        assertEquals(1, stats.pingRx.get());
        assertEquals(1, stats.audioRx.get());
        assertEquals(2, stats.tx.get());
        assertEquals(1, stats.pingTx.get());
        assertEquals(1, stats.audioTx.get());
        assertEquals(1_030L, stats.lastRx);
        assertEquals(1_040L, stats.lastAudioTx);

        String snapshot = stats.snapshot(2_000L);
        assertTrue(snapshot, snapshot.startsWith("rxAge=970ms txAge=960ms pingRxAge=1000ms pingTxAge=990ms"));
        assertTrue(snapshot, snapshot.contains("rx=3 tx=2 pingRx=1 pingTx=1 audioRx=1 audioTx=1 failedTx=0"));
        assertEquals(-1L, VoiceDebug.age(2_000L, 0L));
    }
}
