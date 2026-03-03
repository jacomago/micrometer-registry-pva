package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import org.epics.pva.PVASettings;
import org.epics.pva.client.ClientChannelState;
import org.epics.pva.client.PVAChannel;
import org.epics.pva.client.PVAClient;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVALong;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAScalar;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import org.phoebus.pva.micrometer.internal.PvaDistributionSummary;
import org.phoebus.pva.micrometer.internal.PvaFunctionCounter;
import org.phoebus.pva.micrometer.internal.PvaFunctionTimer;
import org.phoebus.pva.micrometer.internal.PvaGauge;
import org.phoebus.pva.micrometer.internal.PvaLongTaskTimer;
import org.phoebus.pva.micrometer.internal.PvaMeter;
import org.phoebus.pva.micrometer.internal.PvaMicrometerCounter;
import org.phoebus.pva.micrometer.internal.PvaTimeGauge;
import org.phoebus.pva.micrometer.internal.PvaTimer;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PvaMeterRegistry} covering scalar meter PV wrappers.
 *
 * <p>The round-trip tests create a real {@link PVAServer} (via the registry), call
 * {@code updatePv()} directly on the wrappers, and then read back the NTScalar structure
 * fields to verify the value, alarm severity, and timestamp are set correctly.
 *
 * <p>These tests require no external EPICS infrastructure — they exercise only the
 * server-side PV creation and update path.
 */
class PvaMeterRegistryTest {

    /** Short step interval so the poll loop does not interfere during direct-update tests. */
    private static final PvaMeterRegistryConfig TEST_CONFIG = new PvaMeterRegistryConfig() {
        @Override
        public String get(String key) {
            return null;
        }

        @Override
        public Duration step() {
            return Duration.ofHours(1); // effectively disable automatic polling in tests
        }
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

    // -------------------------------------------------------------------------
    // Gauge tests
    // -------------------------------------------------------------------------

    @Test
    void gauge_valueIsReadFromSupplier() {
        double[] source = {42.0};
        Gauge gauge = Gauge.builder("test.gauge", source, a -> a[0])
                .register(registry);

        assertEquals(42.0, gauge.value(), 1e-9);

        source[0] = 99.0;
        assertEquals(99.0, gauge.value(), 1e-9);
    }

    @Test
    void gauge_pvaStructureHasCorrectInitialLayout() {
        double[] source = {7.0};
        Gauge gauge = Gauge.builder("test.gauge.layout", source, a -> a[0])
                .register(registry);

        // The gauge is backed by a PvaGauge; retrieve and inspect its structure.
        PvaGauge<?> pvaGauge = (PvaGauge<?>) gauge;
        PVAScalar<PVADouble> data = pvaGauge.getInitialData();

        assertNotNull(data, "NTScalar structure must not be null");
        assertNotNull(data.get("value"), "NTScalar must have a 'value' field");
        assertNotNull(data.get("alarm"), "NTScalar must have an 'alarm' sub-structure");
        assertNotNull(data.get("timeStamp"), "NTScalar must have a 'timeStamp' sub-structure");

        // Initial value written by PVAScalar builder is 0.0.
        assertEquals(0.0, ((PVADouble) data.get("value")).get(), 1e-9);
    }

    @Test
    void gauge_updatePvWritesValueAndClearsAlarm() throws Exception {
        double[] source = {55.5};
        Gauge gauge = Gauge.builder("test.gauge.update", source, a -> a[0])
                .register(registry);

        PvaGauge<?> pvaGauge = (PvaGauge<?>) gauge;
        PVAScalar<PVADouble> data = pvaGauge.getInitialData();

        // Obtain the ServerPV that was created when the gauge was registered.
        ServerPV serverPV = registryServerPv("test.gauge.update");

        // Manually invoke the poll-tick update.
        pvaGauge.updatePv(serverPV);

        // The value field should now reflect the supplier.
        assertEquals(55.5, ((PVADouble) data.get("value")).get(), 1e-9);

        // Alarm severity should be NO_ALARM (0).
        PVAAlarm alarm = data.get("alarm");
        assertEquals(AlarmSeverity.NO_ALARM, alarm.alarmSeverity(),
                "Alarm severity must be NO_ALARM after a successful read");
    }

    @Test
    void gauge_throwingSupplierSetsInvalidAlarm() throws Exception {
        // Non-null object whose value function always throws.
        Object nonNull = new Object();
        Gauge gauge = Gauge.builder("test.gauge.throw", nonNull, obj -> {
            throw new RuntimeException("simulated supplier failure");
        }).register(registry);

        PvaGauge<?> pvaGauge = (PvaGauge<?>) gauge;
        PVAScalar<PVADouble> data = pvaGauge.getInitialData();
        ServerPV serverPV = registryServerPv("test.gauge.throw");

        pvaGauge.updatePv(serverPV);

        PVAAlarm alarm = data.get("alarm");
        assertEquals(AlarmSeverity.INVALID, alarm.alarmSeverity(),
                "Alarm severity must be INVALID when supplier throws");
    }

    @Test
    void gauge_nullRefSetsInvalidAlarm() throws Exception {
        // Gauge whose observed object has been garbage collected (simulated by passing null).
        Gauge gauge = Gauge.builder("test.gauge.nullref", (Object) null,
                obj -> 1.0).register(registry);

        PvaGauge<?> pvaGauge = (PvaGauge<?>) gauge;
        PVAScalar<PVADouble> data = pvaGauge.getInitialData();
        ServerPV serverPV = registryServerPv("test.gauge.nullref");

        pvaGauge.updatePv(serverPV);

        // value() returns NaN when the weak ref is cleared; updatePv should set INVALID.
        PVAAlarm alarm = data.get("alarm");
        assertEquals(AlarmSeverity.INVALID, alarm.alarmSeverity(),
                "Alarm severity must be INVALID when the observed object ref is null");
    }

    // -------------------------------------------------------------------------
    // Counter tests
    // -------------------------------------------------------------------------

    @Test
    void counter_startsAtZero() {
        Counter counter = Counter.builder("test.counter").register(registry);
        assertEquals(0.0, counter.count(), 1e-9);
    }

    @Test
    void counter_incrementsCorrectly() {
        Counter counter = Counter.builder("test.counter.incr").register(registry);
        counter.increment();
        counter.increment(4.5);
        assertEquals(5.5, counter.count(), 1e-9);
    }

    @Test
    void counter_updatePvWritesCumulativeCount() throws Exception {
        Counter counter = Counter.builder("test.counter.update").register(registry);
        counter.increment(10.0);
        counter.increment(5.0);

        PvaMicrometerCounter pvaCounter = (PvaMicrometerCounter) counter;
        PVAScalar<PVADouble> data = pvaCounter.getInitialData();
        ServerPV serverPV = registryServerPv("test.counter.update");

        pvaCounter.updatePv(serverPV);

        assertEquals(15.0, ((PVADouble) data.get("value")).get(), 1e-9);
        PVAAlarm alarm = data.get("alarm");
        assertEquals(AlarmSeverity.NO_ALARM, alarm.alarmSeverity());
    }

    // -------------------------------------------------------------------------
    // TimeGauge tests
    // -------------------------------------------------------------------------

    @Test
    void timeGauge_valueNormalisedToSeconds() {
        long[] sourceMs = {2000L}; // 2000 milliseconds = 2 seconds
        TimeGauge tg = TimeGauge
                .builder("test.timegauge", sourceMs, TimeUnit.MILLISECONDS, a -> a[0])
                .register(registry);

        assertEquals(2.0, tg.value(TimeUnit.SECONDS), 1e-6,
                "TimeGauge value in seconds must equal 2000 ms / 1000");
    }

    @Test
    void timeGauge_updatePvPublishesSeconds() throws Exception {
        long[] sourceMs = {3500L}; // 3.5 seconds
        TimeGauge tg = TimeGauge
                .builder("test.timegauge.update", sourceMs, TimeUnit.MILLISECONDS, a -> a[0])
                .register(registry);

        PvaTimeGauge<?> pvaTg = (PvaTimeGauge<?>) tg;
        PVAScalar<PVADouble> data = pvaTg.getInitialData();
        ServerPV serverPV = registryServerPv("test.timegauge.update");

        pvaTg.updatePv(serverPV);

        assertEquals(3.5, ((PVADouble) data.get("value")).get(), 1e-6,
                "PVA channel value must be 3.5 seconds");
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    // -------------------------------------------------------------------------
    // FunctionCounter tests
    // -------------------------------------------------------------------------

    @Test
    void functionCounter_countDerivedFromFunction() {
        AtomicLong src = new AtomicLong(0);
        FunctionCounter fc = FunctionCounter
                .builder("test.fn.counter", src, AtomicLong::doubleValue)
                .register(registry);

        assertEquals(0.0, fc.count(), 1e-9);

        src.set(42);
        assertEquals(42.0, fc.count(), 1e-9);
    }

    @Test
    void functionCounter_updatePvWritesCurrentCount() throws Exception {
        AtomicLong src = new AtomicLong(7);
        FunctionCounter fc = FunctionCounter
                .builder("test.fn.counter.update", src, AtomicLong::doubleValue)
                .register(registry);

        PvaFunctionCounter<?> pvaFc = (PvaFunctionCounter<?>) fc;
        PVAScalar<PVADouble> data = pvaFc.getInitialData();
        ServerPV serverPV = registryServerPv("test.fn.counter.update");

        pvaFc.updatePv(serverPV);

        assertEquals(7.0, ((PVADouble) data.get("value")).get(), 1e-9);
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    // -------------------------------------------------------------------------
    // PV naming tests
    // -------------------------------------------------------------------------

    @Test
    void pvNaming_defaultStrategyPreservesDots() {
        Gauge.builder("my.service.queue.size", () -> 0.0).register(registry);

        // With DOTS_WITH_BRACE_TAGS (default, no tags) the PV name equals the meter name.
        String pvName = PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(
                new Meter.Id("my.service.queue.size", Tags.empty(), null, null, Meter.Type.GAUGE));
        assertEquals("my.service.queue.size", pvName);
    }

    @Test
    void pvNaming_tagsAppendedInBraceNotation() {
        Meter.Id id = new Meter.Id("requests.total",
                Tags.of("method", "GET", "status", "200"),
                null, null, Meter.Type.COUNTER);

        String pvName = PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(id);
        assertEquals("requests.total{method=\"GET\",status=\"200\"}", pvName);
    }

    @Test
    void pvNaming_colonStrategy() {
        Meter.Id id = new Meter.Id("my.service.errors",
                Tags.of("host", "srv01"),
                null, null, Meter.Type.COUNTER);

        String pvName = PvNamingStrategy.COLONS.pvName(id);
        assertEquals("my:service:errors:host:srv01", pvName);
    }

    // -------------------------------------------------------------------------
    // PV name collision tests
    // -------------------------------------------------------------------------

    @Test
    void registry_duplicatePvNameThrowsIllegalArgumentException() {
        // NAME_ONLY drops tags, so two meters with different tags but same name collide.
        PvaMeterRegistryConfig nameOnlyConfig = new PvaMeterRegistryConfig() {
            @Override
            public String get(String key) { return null; }

            @Override
            public Duration step() { return Duration.ofHours(1); }

            @Override
            public PvNamingStrategy namingStrategy() { return PvNamingStrategy.NAME_ONLY; }
        };

        PvaMeterRegistry nameOnlyRegistry = new PvaMeterRegistry(nameOnlyConfig, Clock.SYSTEM);
        try {
            // First registration with this PV name succeeds.
            Gauge.builder("collision.test", () -> 1.0)
                    .tag("instance", "a")
                    .register(nameOnlyRegistry);

            // Second meter produces the same PV name — must throw.
            assertThrows(IllegalArgumentException.class, () ->
                    Gauge.builder("collision.test", () -> 2.0)
                            .tag("instance", "b")
                            .register(nameOnlyRegistry),
                    "Registering a second meter that maps to the same PV name must throw");
        } finally {
            nameOnlyRegistry.close();
        }
    }

    @Test
    void registry_sameNameDifferentTagsWithDefaultStrategyDoesNotCollide() {
        // With DOTS_WITH_BRACE_TAGS (default), tags are part of the PV name,
        // so meters with the same metric name but different tags get distinct PV names.
        Gauge.builder("no.collision", () -> 1.0).tag("host", "srv1").register(registry);
        Gauge.builder("no.collision", () -> 2.0).tag("host", "srv2").register(registry);
        // No exception — both are registered with distinct PV names.
    }

    // -------------------------------------------------------------------------
    // Registry lifecycle
    // -------------------------------------------------------------------------

    @Test
    void registry_closeDoesNotThrow() {
        // Register a couple of meters, then close — should not throw.
        Gauge.builder("test.lifecycle.gauge", () -> 1.0).register(registry);
        Counter.builder("test.lifecycle.counter").register(registry);
        registry.close();
        // Re-create for @AfterEach to close cleanly.
        registry = new PvaMeterRegistry(TEST_CONFIG, Clock.SYSTEM);
    }

    @Test
    void registry_ownsServerClosesItOnClose() {
        // The registry created in @BeforeEach owns its PVAServer.
        // Closing the registry should not throw even if called twice.
        registry.close();
        registry.close(); // second close must be idempotent.
        registry = new PvaMeterRegistry(TEST_CONFIG, Clock.SYSTEM);
    }

    @Test
    void registry_sharedServerNotClosedOnClose() throws Exception {
        try (PVAServer sharedServer = new PVAServer()) {
            PvaMeterRegistry sharedRegistry =
                    new PvaMeterRegistry(TEST_CONFIG, Clock.SYSTEM, sharedServer);
            Gauge.builder("test.shared.gauge", () -> 1.0).register(sharedRegistry);
            sharedRegistry.close(); // must not close sharedServer
            // sharedServer still open — no exception from double-close
        } // try-with-resources closes sharedServer here
    }

    // -------------------------------------------------------------------------
    // Timer tests
    // -------------------------------------------------------------------------

    @Test
    void timer_startsAtZero() {
        Timer timer = Timer.builder("test.timer").register(registry);
        assertEquals(0L, timer.count());
        assertEquals(0.0, timer.totalTime(TimeUnit.SECONDS), 1e-9);
        assertEquals(0.0, timer.max(TimeUnit.SECONDS), 1e-9);
    }

    @Test
    void timer_recordsCorrectly() {
        Timer timer = Timer.builder("test.timer.record").register(registry);
        timer.record(Duration.ofMillis(500));
        timer.record(Duration.ofSeconds(2));

        assertEquals(2L, timer.count());
        assertEquals(2.5, timer.totalTime(TimeUnit.SECONDS), 1e-6);
        assertEquals(2.0, timer.max(TimeUnit.SECONDS), 1e-6);
    }

    @Test
    void timer_pvaStructureHasCorrectFields() {
        Timer timer = Timer.builder("test.timer.struct").register(registry);

        PvaTimer pvaTimer = (PvaTimer) timer;
        PVAStructure data = pvaTimer.getInitialData();

        assertNotNull(data.get("count"), "Timer structure must have a 'count' field");
        assertNotNull(data.get("totalTime"), "Timer structure must have a 'totalTime' field");
        assertNotNull(data.get("max"), "Timer structure must have a 'max' field");
        assertNotNull(data.get("alarm"), "Timer structure must have an 'alarm' field");
        assertNotNull(data.get("timeStamp"), "Timer structure must have a 'timeStamp' field");
    }

    @Test
    void timer_updatePvWritesValuesAndClearsAlarm() throws Exception {
        Timer timer = Timer.builder("test.timer.update").register(registry);
        timer.record(Duration.ofSeconds(1));
        timer.record(Duration.ofSeconds(3));

        PvaTimer pvaTimer = (PvaTimer) timer;
        PVAStructure data = pvaTimer.getInitialData();
        ServerPV serverPV = registryServerPv("test.timer.update");

        pvaTimer.updatePv(serverPV);

        assertEquals(2L, ((PVALong) data.get("count")).get());
        assertEquals(4.0, ((PVADouble) data.get("totalTime")).get(), 1e-6,
                "totalTime must be 4.0 seconds");
        assertEquals(3.0, ((PVADouble) data.get("max")).get(), 1e-6,
                "max must be 3.0 seconds");
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    // -------------------------------------------------------------------------
    // DistributionSummary tests
    // -------------------------------------------------------------------------

    @Test
    void distributionSummary_startsAtZero() {
        DistributionSummary summary = DistributionSummary.builder("test.summary").register(registry);
        assertEquals(0L, summary.count());
        assertEquals(0.0, summary.totalAmount(), 1e-9);
        assertEquals(0.0, summary.max(), 1e-9);
    }

    @Test
    void distributionSummary_recordsCorrectly() {
        DistributionSummary summary = DistributionSummary.builder("test.summary.record")
                .register(registry);
        summary.record(10.0);
        summary.record(30.0);

        assertEquals(2L, summary.count());
        assertEquals(40.0, summary.totalAmount(), 1e-9);
        assertEquals(30.0, summary.max(), 1e-9);
    }

    @Test
    void distributionSummary_pvaStructureHasCorrectFields() {
        DistributionSummary summary = DistributionSummary.builder("test.summary.struct")
                .register(registry);

        PvaDistributionSummary pvaSummary = (PvaDistributionSummary) summary;
        PVAStructure data = pvaSummary.getInitialData();

        assertNotNull(data.get("count"), "Summary structure must have a 'count' field");
        assertNotNull(data.get("total"), "Summary structure must have a 'total' field");
        assertNotNull(data.get("max"), "Summary structure must have a 'max' field");
        assertNotNull(data.get("alarm"), "Summary structure must have an 'alarm' field");
        assertNotNull(data.get("timeStamp"), "Summary structure must have a 'timeStamp' field");
    }

    @Test
    void distributionSummary_updatePvWritesValuesAndClearsAlarm() throws Exception {
        DistributionSummary summary = DistributionSummary.builder("test.summary.update")
                .register(registry);
        summary.record(5.0);
        summary.record(15.0);

        PvaDistributionSummary pvaSummary = (PvaDistributionSummary) summary;
        PVAStructure data = pvaSummary.getInitialData();
        ServerPV serverPV = registryServerPv("test.summary.update");

        pvaSummary.updatePv(serverPV);

        assertEquals(2L, ((PVALong) data.get("count")).get());
        assertEquals(20.0, ((PVADouble) data.get("total")).get(), 1e-9);
        assertEquals(15.0, ((PVADouble) data.get("max")).get(), 1e-9);
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    // -------------------------------------------------------------------------
    // LongTaskTimer tests
    // -------------------------------------------------------------------------

    @Test
    void longTaskTimer_startsWithNoActiveTasks() {
        LongTaskTimer ltt = LongTaskTimer.builder("test.ltt").register(registry);
        assertEquals(0, ltt.activeTasks());
        assertEquals(0.0, ltt.duration(TimeUnit.SECONDS), 1e-9);
    }

    @Test
    void longTaskTimer_tracksActiveTasksCorrectly() {
        LongTaskTimer ltt = LongTaskTimer.builder("test.ltt.active").register(registry);

        LongTaskTimer.Sample s1 = ltt.start();
        LongTaskTimer.Sample s2 = ltt.start();
        assertEquals(2, ltt.activeTasks());

        s1.stop();
        assertEquals(1, ltt.activeTasks());

        s2.stop();
        assertEquals(0, ltt.activeTasks());
    }

    @Test
    void longTaskTimer_pvaStructureHasCorrectFields() {
        LongTaskTimer ltt = LongTaskTimer.builder("test.ltt.struct").register(registry);

        PvaLongTaskTimer pvaLtt = (PvaLongTaskTimer) ltt;
        PVAStructure data = pvaLtt.getInitialData();

        assertNotNull(data.get("activeTasks"), "LongTaskTimer must have an 'activeTasks' field");
        assertNotNull(data.get("duration"), "LongTaskTimer must have a 'duration' field");
        assertNotNull(data.get("alarm"), "LongTaskTimer must have an 'alarm' field");
        assertNotNull(data.get("timeStamp"), "LongTaskTimer must have a 'timeStamp' field");
    }

    @Test
    void longTaskTimer_updatePvWritesActiveTaskCountAndClearsAlarm() throws Exception {
        LongTaskTimer ltt = LongTaskTimer.builder("test.ltt.update").register(registry);
        LongTaskTimer.Sample s = ltt.start();

        PvaLongTaskTimer pvaLtt = (PvaLongTaskTimer) ltt;
        PVAStructure data = pvaLtt.getInitialData();
        ServerPV serverPV = registryServerPv("test.ltt.update");

        pvaLtt.updatePv(serverPV);

        assertEquals(1L, ((PVALong) data.get("activeTasks")).get(),
                "activeTasks must be 1 while sample is running");
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());

        s.stop();
        pvaLtt.updatePv(serverPV);
        assertEquals(0L, ((PVALong) data.get("activeTasks")).get(),
                "activeTasks must be 0 after sample stopped");
    }

    // -------------------------------------------------------------------------
    // FunctionTimer tests
    // -------------------------------------------------------------------------

    @Test
    void functionTimer_countAndTotalTimeDerivedFromFunctions() {
        AtomicLong completions = new AtomicLong(0);
        AtomicLong totalMillis = new AtomicLong(0);

        FunctionTimer ft = FunctionTimer
                .builder("test.fn.timer", completions,
                        AtomicLong::get,
                        obj -> (double) totalMillis.get(),
                        TimeUnit.MILLISECONDS)
                .register(registry);

        assertEquals(0.0, ft.count(), 1e-9);
        assertEquals(0.0, ft.totalTime(TimeUnit.SECONDS), 1e-9);

        completions.set(5);
        totalMillis.set(2000); // 2000 ms = 2 s
        assertEquals(5.0, ft.count(), 1e-9);
        assertEquals(2.0, ft.totalTime(TimeUnit.SECONDS), 1e-6);
    }

    @Test
    void functionTimer_updatePvWritesValuesAndClearsAlarm() throws Exception {
        AtomicLong completions = new AtomicLong(3);
        AtomicLong totalMillis = new AtomicLong(1500); // 1.5 s

        FunctionTimer ft = FunctionTimer
                .builder("test.fn.timer.update", completions,
                        AtomicLong::get,
                        obj -> (double) totalMillis.get(),
                        TimeUnit.MILLISECONDS)
                .register(registry);

        PvaFunctionTimer<?> pvaFt = (PvaFunctionTimer<?>) ft;
        PVAStructure data = pvaFt.getInitialData();
        ServerPV serverPV = registryServerPv("test.fn.timer.update");

        pvaFt.updatePv(serverPV);

        assertEquals(3.0, ((PVADouble) data.get("count")).get(), 1e-9);
        assertEquals(1.5, ((PVADouble) data.get("totalTime")).get(), 1e-6,
                "totalTime must be 1.5 seconds");
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    // -------------------------------------------------------------------------
    // Custom Meter (catch-all) tests
    // -------------------------------------------------------------------------

    @Test
    void customMeter_pvaStructureHasOneFieldPerMeasurement() {
        double[] source = {42.0, 7.0};
        Meter meter = Meter.builder("test.custom.meter", Meter.Type.OTHER,
                        Collections.singletonList(
                                new Measurement(() -> source[0], Statistic.VALUE)))
                .register(registry);

        PvaMeter pvaMeter = (PvaMeter) meter;
        PVAStructure data = pvaMeter.getInitialData();

        assertNotNull(data.get("value"), "Custom meter must have a 'value' field");
        assertNotNull(data.get("alarm"), "Custom meter must have an 'alarm' field");
        assertNotNull(data.get("timeStamp"), "Custom meter must have a 'timeStamp' field");
    }

    @Test
    void customMeter_updatePvWritesMeasurementValues() throws Exception {
        double[] source = {99.0};
        Meter meter = Meter.builder("test.custom.meter.update", Meter.Type.OTHER,
                        Collections.singletonList(
                                new Measurement(() -> source[0], Statistic.VALUE)))
                .register(registry);

        PvaMeter pvaMeter = (PvaMeter) meter;
        PVAStructure data = pvaMeter.getInitialData();
        ServerPV serverPV = registryServerPv("test.custom.meter.update");

        pvaMeter.updatePv(serverPV);

        assertEquals(99.0, ((PVADouble) data.get("value")).get(), 1e-9);
        assertEquals(AlarmSeverity.NO_ALARM,
                ((PVAAlarm) data.get("alarm")).alarmSeverity());
    }

    // -------------------------------------------------------------------------
    // Integration test: full PVA round-trip via PVAClient
    // -------------------------------------------------------------------------

    /**
     * End-to-end test: verifies that a {@link Gauge} and a {@link Counter} registered
     * with a real {@code PvaMeterRegistry} are accessible as live PVA channels, and that
     * {@link PvaMeterRegistry#close()} causes connected clients to receive a
     * channel-destroyed notification.
     *
     * <p>The test uses a 1-second step so that at least two poll ticks fire during the
     * {@code Thread.sleep(2500)} pause, ensuring the PVA channels carry the expected
     * values before the client connects.
     *
     * <p>When the server closes a channel via {@code CMD_DESTROY_CHANNEL} on a
     * <em>connected</em> (not client-initiated closing) channel, the PVA client library
     * calls {@code resetConnection()} which transitions the channel to
     * {@link ClientChannelState#INIT} — not {@code CLOSED}.  The {@code CLOSED} state is
     * only reached through a client-initiated close.  The test therefore counts down the
     * destroyed latch on {@code INIT} transitions that occur after the channel was
     * previously {@code CONNECTED}.
     */
    @Test
    void integration_gaugeAndCounterValuesAvailableViaPvaClient() throws Exception {
        PvaMeterRegistryConfig shortStepConfig = new PvaMeterRegistryConfig() {
            @Override
            public String get(String key) { return null; }

            @Override
            public Duration step() { return Duration.ofSeconds(1); }
        };

        // Start an explicit PVAServer so we can read its actual TCP port.
        // Passing it to PvaMeterRegistry via the shared-server constructor avoids the
        // registry creating a second server that might bind a different port.
        try (PVAServer integServer = new PVAServer()) {
            // getTCPAddress(false) returns the InetSocketAddress the server is listening on.
            int serverPort = integServer.getTCPAddress(false).getPort();

            // Configure the PVA client for direct TCP connection to the known port,
            // bypassing UDP broadcast/multicast (which is unreliable in CI containers).
            String savedNameServers = PVASettings.EPICS_PVA_NAME_SERVERS;
            boolean savedAutoAddrList = PVASettings.EPICS_PVA_AUTO_ADDR_LIST;
            PVASettings.EPICS_PVA_NAME_SERVERS = "127.0.0.1:" + serverPort;
            PVASettings.EPICS_PVA_AUTO_ADDR_LIST = false;

            PvaMeterRegistry integRegistry =
                    new PvaMeterRegistry(shortStepConfig, Clock.SYSTEM, integServer);
            try {
                // Register a static gauge and a counter incremented to 7.
                Gauge.builder("integration.gauge", () -> 42.0).register(integRegistry);
                Counter counter = Counter.builder("integration.counter").register(integRegistry);
                counter.increment(7);

                // Allow two poll ticks (step = 1 s → at least two full cycles in 2 500 ms).
                Thread.sleep(2500);

                // Track channel-destroyed notifications for both PVs.
                // The server-side CMD_DESTROY_CHANNEL causes resetConnection() → INIT on
                // connected channels, so we latch on INIT transitions post-connection.
                CountDownLatch destroyedLatch = new CountDownLatch(2);

                try (PVAClient client = new PVAClient()) {
                    // --- Gauge PV ---
                    // wasConnected tracks whether the channel reached CONNECTED at least once,
                    // so that the first INIT (server destroy) can be distinguished from any
                    // INIT that might occur before the initial connection completes.
                    java.util.concurrent.atomic.AtomicBoolean gaugeConnected =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    PVAChannel gaugeChannel = client.getChannel("integration.gauge",
                            (ch, state) -> {
                                if (state == ClientChannelState.CONNECTED) {
                                    gaugeConnected.set(true);
                                } else if (gaugeConnected.get() && state == ClientChannelState.INIT) {
                                    destroyedLatch.countDown();
                                }
                            });
                    gaugeChannel.connect().get(5, TimeUnit.SECONDS);
                    PVAStructure gaugeStructure = gaugeChannel.read("").get(5, TimeUnit.SECONDS);
                    assertEquals(42.0, ((PVADouble) gaugeStructure.get("value")).get(), 1e-9,
                            "Gauge PV value must be 42.0");

                    // --- Counter PV ---
                    java.util.concurrent.atomic.AtomicBoolean counterConnected =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    PVAChannel counterChannel = client.getChannel("integration.counter",
                            (ch, state) -> {
                                if (state == ClientChannelState.CONNECTED) {
                                    counterConnected.set(true);
                                } else if (counterConnected.get() && state == ClientChannelState.INIT) {
                                    destroyedLatch.countDown();
                                }
                            });
                    counterChannel.connect().get(5, TimeUnit.SECONDS);
                    PVAStructure counterStructure = counterChannel.read("").get(5, TimeUnit.SECONDS);
                    assertEquals(7.0, ((PVADouble) counterStructure.get("value")).get(), 1e-9,
                            "Counter PV value must be 7.0");

                    // Closing the registry sends CMD_DESTROY_CHANNEL to connected clients.
                    integRegistry.close();

                    assertTrue(destroyedLatch.await(5, TimeUnit.SECONDS),
                            "Both PV channels must receive a channel-destroyed notification within 5 s");
                }
            } finally {
                integRegistry.close(); // idempotent; ensures cleanup if an assertion fails early
                PVASettings.EPICS_PVA_NAME_SERVERS = savedNameServers;
                PVASettings.EPICS_PVA_AUTO_ADDR_LIST = savedAutoAddrList;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link ServerPV} that the registry created for the given PVA channel name.
     * Uses the package-private {@link PvaMeterRegistry#serverPv(String)} accessor.
     */
    private ServerPV registryServerPv(String pvName) {
        ServerPV pv = registry.serverPv(pvName);
        if (pv == null) {
            throw new AssertionError("No ServerPV found for PV name '" + pvName + "'");
        }
        return pv;
    }
}
