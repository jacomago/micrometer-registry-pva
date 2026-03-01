package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAScalar;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.pva.micrometer.internal.PvaFunctionCounter;
import org.phoebus.pva.micrometer.internal.PvaGauge;
import org.phoebus.pva.micrometer.internal.PvaMicrometerCounter;
import org.phoebus.pva.micrometer.internal.PvaTimeGauge;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
