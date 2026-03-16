package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Timer;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVALong;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.server.ServerPV;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.pva.micrometer.internal.PvaDistributionSummary;
import org.phoebus.pva.micrometer.internal.PvaLongTaskTimer;
import org.phoebus.pva.micrometer.internal.PvaMeter;
import org.phoebus.pva.micrometer.internal.PvaTimer;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group F — test coverage gaps:
 * <ol>
 *   <li>{@link PvaMeter#fieldName} with non-VALUE statistics</li>
 *   <li>Timer / DistributionSummary with non-NONE percentile config</li>
 *   <li>LongTaskTimer + percentile config (silent discard documented)</li>
 *   <li>{@link PvaServiceBinder#bindTo} called twice</li>
 *   <li>Concurrency / stress tests for counters and CAS max tracking</li>
 * </ol>
 */
class GroupFCoverageTest {

    private static final PvaMeterRegistryConfig TEST_CONFIG = new PvaMeterRegistryConfig() {
        @Override
        public String get(String key) { return null; }

        @Override
        public Duration step() { return Duration.ofHours(1); }
    };

    private PvaMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PvaMeterRegistry(TEST_CONFIG, Clock.SYSTEM);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    // =========================================================================
    // 1. PvaMeter.fieldName() with non-VALUE statistics
    //
    // fieldName() is package-private to the internal package; we test it via the
    // observable behaviour: the PVA structure must contain a field whose name is
    // the lower-cased Statistic enum name.
    // =========================================================================

    @Test
    void pvaMeter_fieldName_countStatistic() {
        Meter meter = Meter.builder("test.fm.count", Meter.Type.OTHER,
                        Collections.singletonList(
                                new Measurement(() -> 5.0, Statistic.COUNT)))
                .register(registry);

        PVAStructure data = ((PvaMeter) meter).getInitialData();
        assertNotNull(data.get("count"),
                "PVA field for COUNT statistic must be named 'count'");
    }

    @Test
    void pvaMeter_fieldName_totalTimeStatistic() {
        Meter meter = Meter.builder("test.fm.total.time", Meter.Type.OTHER,
                        Collections.singletonList(
                                new Measurement(() -> 1.5, Statistic.TOTAL_TIME)))
                .register(registry);

        PVAStructure data = ((PvaMeter) meter).getInitialData();
        assertNotNull(data.get("total_time"),
                "PVA field for TOTAL_TIME statistic must be named 'total_time'");
    }

    @Test
    void pvaMeter_fieldName_maxStatistic() {
        Meter meter = Meter.builder("test.fm.max", Meter.Type.OTHER,
                        Collections.singletonList(
                                new Measurement(() -> 10.0, Statistic.MAX)))
                .register(registry);

        PVAStructure data = ((PvaMeter) meter).getInitialData();
        assertNotNull(data.get("max"),
                "PVA field for MAX statistic must be named 'max'");
    }

    @Test
    void pvaMeter_fieldName_activeTasksStatistic() {
        Meter meter = Meter.builder("test.fm.active.tasks", Meter.Type.OTHER,
                        Collections.singletonList(
                                new Measurement(() -> 3.0, Statistic.ACTIVE_TASKS)))
                .register(registry);

        PVAStructure data = ((PvaMeter) meter).getInitialData();
        assertNotNull(data.get("active_tasks"),
                "PVA field for ACTIVE_TASKS statistic must be named 'active_tasks'");
    }

    @Test
    void pvaMeter_fieldName_durationStatistic() {
        Meter meter = Meter.builder("test.fm.duration", Meter.Type.OTHER,
                        Collections.singletonList(
                                new Measurement(() -> 2.0, Statistic.DURATION)))
                .register(registry);

        PVAStructure data = ((PvaMeter) meter).getInitialData();
        assertNotNull(data.get("duration"),
                "PVA field for DURATION statistic must be named 'duration'");
    }

    @Test
    void pvaMeter_multipleStatistics_allFieldsPresent() {
        Meter meter = Meter.builder("test.fm.multi", Meter.Type.OTHER,
                        Arrays.asList(
                                new Measurement(() -> 1.0, Statistic.VALUE),
                                new Measurement(() -> 2.0, Statistic.COUNT),
                                new Measurement(() -> 0.5, Statistic.MAX)))
                .register(registry);

        PVAStructure data = ((PvaMeter) meter).getInitialData();
        assertNotNull(data.get("value"), "Expected 'value' field");
        assertNotNull(data.get("count"), "Expected 'count' field");
        assertNotNull(data.get("max"),   "Expected 'max' field");
    }

    @Test
    void pvaMeter_updatePvWritesMultipleStatistics() throws Exception {
        double[] vals = {7.0, 3.0};
        Meter meter = Meter.builder("test.fm.multi.update", Meter.Type.OTHER,
                        Arrays.asList(
                                new Measurement(() -> vals[0], Statistic.VALUE),
                                new Measurement(() -> vals[1], Statistic.COUNT)))
                .register(registry);

        PvaMeter pvaMeter = (PvaMeter) meter;
        PVAStructure data = pvaMeter.getInitialData();
        ServerPV serverPV = registryServerPv("test.fm.multi.update");

        pvaMeter.updatePv(serverPV);

        assertEquals(7.0, ((PVADouble) data.get("value")).get(), 1e-9);
        assertEquals(3.0, ((PVADouble) data.get("count")).get(), 1e-9);
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    // =========================================================================
    // 2. Timer / DistributionSummary with non-NONE percentile config
    //
    // publishPercentiles() requests histogram support. PvaTimer and
    // PvaDistributionSummary forward the config to their Abstract parent classes
    // for internal percentile computation but do not publish extra PVA fields —
    // the three standard fields (count / totalTime / max, or count / total / max)
    // must still be present and correct.
    // =========================================================================

    @Test
    void timer_withPercentileConfig_registersWithoutError() {
        Timer timer = Timer.builder("test.timer.pct")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        assertNotNull(timer, "Timer with percentile config must register without error");
        PvaTimer pvaTimer = (PvaTimer) timer;
        PVAStructure data = pvaTimer.getInitialData();
        assertNotNull(data.get("count"),     "Timer must still have 'count' field");
        assertNotNull(data.get("totalTime"), "Timer must still have 'totalTime' field");
        assertNotNull(data.get("max"),       "Timer must still have 'max' field");
    }

    @Test
    void timer_withPercentileConfig_recordAndUpdatePvWork() throws Exception {
        Timer timer = Timer.builder("test.timer.pct.update")
                .publishPercentiles(0.5, 0.99)
                .register(registry);
        timer.record(Duration.ofMillis(100));
        timer.record(Duration.ofMillis(200));

        PvaTimer pvaTimer = (PvaTimer) timer;
        PVAStructure data = pvaTimer.getInitialData();
        ServerPV serverPV = registryServerPv("test.timer.pct.update");

        pvaTimer.updatePv(serverPV);

        assertEquals(2L, ((PVALong) data.get("count")).get());
        assertEquals(0.3, ((PVADouble) data.get("totalTime")).get(), 1e-6,
                "totalTime must be 100 ms + 200 ms = 0.3 s");
        assertEquals(0.2, ((PVADouble) data.get("max")).get(), 1e-6,
                "max must be 200 ms = 0.2 s");
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    @Test
    void distributionSummary_withPercentileConfig_registersWithoutError() {
        DistributionSummary summary = DistributionSummary.builder("test.summary.pct")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        assertNotNull(summary,
                "DistributionSummary with percentile config must register without error");
        PvaDistributionSummary pvaSummary = (PvaDistributionSummary) summary;
        PVAStructure data = pvaSummary.getInitialData();
        assertNotNull(data.get("count"), "Summary must still have 'count' field");
        assertNotNull(data.get("total"), "Summary must still have 'total' field");
        assertNotNull(data.get("max"),   "Summary must still have 'max' field");
    }

    @Test
    void distributionSummary_withPercentileConfig_recordAndUpdatePvWork() throws Exception {
        DistributionSummary summary = DistributionSummary.builder("test.summary.pct.update")
                .publishPercentiles(0.5, 0.99)
                .register(registry);
        summary.record(10.0);
        summary.record(20.0);

        PvaDistributionSummary pvaSummary = (PvaDistributionSummary) summary;
        PVAStructure data = pvaSummary.getInitialData();
        ServerPV serverPV = registryServerPv("test.summary.pct.update");

        pvaSummary.updatePv(serverPV);

        assertEquals(2L, ((PVALong) data.get("count")).get());
        assertEquals(30.0, ((PVADouble) data.get("total")).get(), 1e-9);
        assertEquals(20.0, ((PVADouble) data.get("max")).get(), 1e-9);
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    // =========================================================================
    // 3. LongTaskTimer + percentile config (silent discard documented)
    //
    // PvaMeterRegistry.newLongTaskTimer() accepts a DistributionStatisticConfig
    // but PvaLongTaskTimer ignores it (documented: "histograms are out of scope").
    // =========================================================================

    @Test
    void longTaskTimer_withPercentileConfig_registersWithoutError() {
        LongTaskTimer ltt = LongTaskTimer.builder("test.ltt.pct")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        assertNotNull(ltt, "LongTaskTimer with percentile config must register without error");
        PvaLongTaskTimer pvaLtt = (PvaLongTaskTimer) ltt;
        PVAStructure data = pvaLtt.getInitialData();
        assertNotNull(data.get("activeTasks"), "LTT must still have 'activeTasks' field");
        assertNotNull(data.get("duration"),    "LTT must still have 'duration' field");
    }

    @Test
    void longTaskTimer_withPercentileConfig_functionallyIdenticalToDefault() throws Exception {
        // Percentile config is silently discarded; start/stop behaviour must be unchanged.
        LongTaskTimer ltt = LongTaskTimer.builder("test.ltt.pct.fn")
                .publishPercentiles(0.5)
                .register(registry);

        LongTaskTimer.Sample s1 = ltt.start();
        LongTaskTimer.Sample s2 = ltt.start();
        assertEquals(2, ltt.activeTasks(), "Two active samples must be tracked");

        s1.stop();
        assertEquals(1, ltt.activeTasks(), "One sample remains after first stop");

        s2.stop();
        assertEquals(0, ltt.activeTasks(), "No active samples after both stop");

        PvaLongTaskTimer pvaLtt = (PvaLongTaskTimer) ltt;
        PVAStructure data = pvaLtt.getInitialData();
        ServerPV serverPV = registryServerPv("test.ltt.pct.fn");

        pvaLtt.updatePv(serverPV);

        assertEquals(0L, ((PVALong) data.get("activeTasks")).get(),
                "activeTasks in PVA structure must be 0");
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    // =========================================================================
    // 4. PvaServiceBinder.bindTo() called twice
    //
    // The Javadoc states the method "may be called at most once" but specifies no
    // exception.  The observable effects are: no exception, and common-tag filters
    // accumulate (Micrometer deduplicates tags with the same key, so subsequent
    // meter registrations still see the service tag exactly once).
    // =========================================================================

    @Test
    void serviceBinder_bindToCalledTwice_doesNotThrow() {
        PvaServiceBinder binder = PvaServiceBinder.forService("test.double.bind")
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics();

        binder.bindTo(registry);
        binder.bindTo(registry); // second call must not throw
    }

    @Test
    void serviceBinder_bindToCalledTwice_meterCountDoesNotDecrease() {
        PvaServiceBinder binder = PvaServiceBinder.forService("test.double.bind2")
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics();

        binder.bindTo(registry);
        int afterFirst = registry.getMeters().size();

        binder.bindTo(registry);
        // Micrometer deduplicates meters with the same ID, so the second bind is
        // effectively a no-op for meters that already exist.
        assertTrue(registry.getMeters().size() >= afterFirst,
                "Meter count must not decrease after a second bindTo()");
    }

    @Test
    void serviceBinder_bindToCalledTwice_newMeterCarriesServiceTag() {
        // Meters registered after a double-bind must carry the service tag from the
        // common-tag filters that were added during both bindTo() invocations.
        String prefix = "test.double.bind3";
        PvaServiceBinder binder = PvaServiceBinder.forService(prefix)
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics();

        binder.bindTo(registry);
        binder.bindTo(registry);

        // Use a unique name to avoid collision with any JVM-binder meter.
        Counter c = Counter.builder("test.double.bind3.counter").register(registry);

        boolean hasServiceTag = c.getId().getTags().stream()
                .anyMatch(t -> "service".equals(t.getKey()) && prefix.equals(t.getValue()));
        assertTrue(hasServiceTag,
                "Counter registered after double-bind must carry service=" + prefix + " tag");
    }

    // =========================================================================
    // 5. Concurrency / stress tests
    //
    // Verify thread-safety of the DoubleAdder counter, LongAdder timer fields,
    // and the CAS-based running-maximum in PvaTimer and PvaDistributionSummary.
    // =========================================================================

    @Test
    void counter_concurrentIncrements_finalCountIsCorrect() throws InterruptedException {
        Counter counter = Counter.builder("test.conc.counter").register(registry);
        int threads = 8;
        int incrementsPerThread = 1_000;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int j = 0; j < incrementsPerThread; j++) {
                    counter.increment();
                }
                done.countDown();
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "All counter threads must complete within 10 s");
        pool.shutdown();

        assertEquals((double) threads * incrementsPerThread, counter.count(), 1e-9,
                "Concurrent increments must sum correctly");
    }

    @Test
    void timer_concurrentRecords_countIsCorrect() throws InterruptedException {
        Timer timer = Timer.builder("test.conc.timer").register(registry);
        int threads = 8;
        int recordsPerThread = 500;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int j = 0; j < recordsPerThread; j++) {
                    timer.record(Duration.ofMillis(1));
                }
                done.countDown();
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "All timer threads must complete within 10 s");
        pool.shutdown();

        assertEquals((long) threads * recordsPerThread, timer.count(),
                "Timer count must equal threads × records per thread");
        assertEquals(0.001, timer.max(TimeUnit.SECONDS), 1e-9,
                "Timer max must be 1 ms (all records identical)");
    }

    @Test
    void timer_casMaxTracking_largestValueWins() throws InterruptedException {
        // Each thread records a distinct duration; the CAS loop must ensure the
        // final max equals the largest value recorded across all threads.
        Timer timer = Timer.builder("test.conc.timer.max").register(registry);
        int threads = 8;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException ignored) {}
                // Thread 0 records 1 s, thread 1 records 2 s, …, thread 7 records 8 s.
                timer.record(Duration.ofSeconds(idx + 1));
                done.countDown();
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "All CAS-max timer threads must complete within 10 s");
        pool.shutdown();

        assertEquals(8.0, timer.max(TimeUnit.SECONDS), 1e-9,
                "CAS max must equal the largest duration recorded (8 s)");
    }

    @Test
    void distributionSummary_concurrentRecords_countIsCorrect() throws InterruptedException {
        DistributionSummary summary = DistributionSummary
                .builder("test.conc.summary").register(registry);
        int threads = 8;
        int recordsPerThread = 500;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int j = 0; j < recordsPerThread; j++) {
                    summary.record(1.0);
                }
                done.countDown();
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "All summary threads must complete within 10 s");
        pool.shutdown();

        assertEquals((long) threads * recordsPerThread, summary.count(),
                "Summary count must equal threads × records per thread");
        assertEquals(1.0, summary.max(), 1e-9,
                "Summary max must be 1.0 (all records identical)");
    }

    @Test
    void distributionSummary_casMaxTracking_largestValueWins() throws InterruptedException {
        DistributionSummary summary = DistributionSummary
                .builder("test.conc.summary.max").register(registry);
        int threads = 8;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException ignored) {}
                // Thread 0 records 1.0, thread 1 records 2.0, …, thread 7 records 8.0.
                summary.record(idx + 1.0);
                done.countDown();
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "All CAS-max summary threads must complete within 10 s");
        pool.shutdown();

        assertEquals(8.0, summary.max(), 1e-9,
                "CAS max must equal the largest value recorded (8.0)");
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private ServerPV registryServerPv(String pvName) {
        ServerPV pv = registry.serverPv(pvName);
        if (pv == null) {
            throw new AssertionError("No ServerPV found for PV name '" + pvName + "'");
        }
        return pv;
    }
}
