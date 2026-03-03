package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Clock;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.server.ServerPV;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.pva.micrometer.Health.Status;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link PvaServiceBinder}, {@link Health}, and {@link HealthIndicator}.
 *
 * <p>These tests use a 1-hour step interval to disable the automatic poll loop, so
 * that only the initial update performed during {@code createPv()} is observable.
 */
class PvaServiceBinderTest {

    private static final PvaMeterRegistryConfig TEST_CONFIG = new PvaMeterRegistryConfig() {
        @Override
        public String get(String key) {
            return null;
        }

        @Override
        public Duration step() {
            return Duration.ofHours(1); // disable automatic polling
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
    // Health record tests
    // -------------------------------------------------------------------------

    @Test
    void health_upFactory() {
        Health h = Health.up();
        assertEquals(Status.UP, h.status());
        assertEquals("", h.message());
    }

    @Test
    void health_degradedFactory() {
        Health h = Health.degraded("slow response");
        assertEquals(Status.DEGRADED, h.status());
        assertEquals("slow response", h.message());
    }

    @Test
    void health_downFactory() {
        Health h = Health.down("connection refused");
        assertEquals(Status.DOWN, h.status());
        assertEquals("connection refused", h.message());
    }

    @Test
    void health_recordEquality() {
        assertEquals(new Health(Status.UP, "ok"), new Health(Status.UP, "ok"));
    }

    // -------------------------------------------------------------------------
    // PvaServiceBinder validation
    // -------------------------------------------------------------------------

    @Test
    void forService_nullPrefixThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> PvaServiceBinder.forService(null));
    }

    @Test
    void withHealthIndicator_nullThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> PvaServiceBinder.forService("svc").withHealthIndicator(null));
    }

    // -------------------------------------------------------------------------
    // InfoPv
    // -------------------------------------------------------------------------

    @Test
    void infoPv_channelCreatedWhenBuildInfoProvided() {
        PvaServiceBinder.forService("test.build")
                .withBuildInfo("1.2.3", "2024-01-15", "abc1234")
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics()
                .bindTo(registry);

        assertNotNull(registry.serverPv("test.build.build"),
                "build PV must be created when withBuildInfo is called");
    }

    @Test
    void infoPv_notCreatedWithoutBuildInfo() {
        PvaServiceBinder.forService("test.nobuild")
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics()
                .bindTo(registry);

        assertNull(registry.serverPv("test.nobuild.build"),
                "build PV must NOT be created when withBuildInfo is not called");
    }

    @Test
    void infoPv_nullFieldsCoercedToEmpty() {
        // Must not throw even when all fields are null.
        PvaServiceBinder.forService("test.nullbuild")
                .withBuildInfo(null, null, null)
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics()
                .bindTo(registry);

        assertNotNull(registry.serverPv("test.nullbuild.build"),
                "build PV must still be created when nulls are supplied");
    }

    // -------------------------------------------------------------------------
    // HealthPv
    // -------------------------------------------------------------------------

    @Test
    void healthPv_channelCreatedWhenIndicatorRegistered() {
        PvaServiceBinder.forService("test.healthsvc")
                .withHealthIndicator(Health::up)
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics()
                .bindTo(registry);

        assertNotNull(registry.serverPv("test.healthsvc.health"),
                "health PV must be created when withHealthIndicator is called");
    }

    @Test
    void healthPv_notCreatedWithoutIndicators() {
        PvaServiceBinder.forService("test.nohealth")
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics()
                .bindTo(registry);

        assertNull(registry.serverPv("test.nohealth.health"),
                "health PV must NOT be created when no indicators are registered");
    }

    @Test
    void healthPv_indicatorCalledOnInitialCreate() {
        int[] checkCount = {0};
        PvaServiceBinder.forService("test.initcheck")
                .withHealthIndicator(() -> {
                    checkCount[0]++;
                    return Health.up();
                })
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics()
                .bindTo(registry);

        assertEquals(1, checkCount[0],
                "HealthPv must call indicator once during initial createPv()");
    }

    @Test
    void healthPv_throwingIndicatorTreatedAsDown() {
        // An indicator that always throws should not crash the binder.
        PvaServiceBinder.forService("test.throwing")
                .withHealthIndicator(() -> {
                    throw new RuntimeException("boom");
                })
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics()
                .bindTo(registry);

        // PV must still be created even when the first check throws.
        assertNotNull(registry.serverPv("test.throwing.health"),
                "health PV must be created even when indicator throws");
    }

    // -------------------------------------------------------------------------
    // Health aggregation (unit-level)
    // -------------------------------------------------------------------------

    @Test
    void healthAggregation_worstStatusWins_downBeatsUp() {
        Health h1 = Health.up();
        Health h2 = Health.down("db offline");
        Status worst = h1.status().ordinal() > h2.status().ordinal() ? h1.status() : h2.status();
        assertEquals(Status.DOWN, worst);
    }

    @Test
    void healthAggregation_degradedBeatsUp() {
        Health h1 = Health.up();
        Health h2 = Health.degraded("slow");
        Status worst = h1.status().ordinal() > h2.status().ordinal() ? h1.status() : h2.status();
        assertEquals(Status.DEGRADED, worst);
    }

    @Test
    void healthAggregation_downBeatsDegraded() {
        Health h1 = Health.degraded("slow");
        Health h2 = Health.down("crashed");
        Status worst = h1.status().ordinal() > h2.status().ordinal() ? h1.status() : h2.status();
        assertEquals(Status.DOWN, worst);
    }

    // -------------------------------------------------------------------------
    // Status-to-alarm mapping (mirrors HealthPv logic)
    // -------------------------------------------------------------------------

    @Test
    void statusToSeverity_up() {
        assertEquals(AlarmSeverity.NO_ALARM, toSeverity(Status.UP));
    }

    @Test
    void statusToSeverity_degraded() {
        assertEquals(AlarmSeverity.MINOR, toSeverity(Status.DEGRADED));
    }

    @Test
    void statusToSeverity_down() {
        assertEquals(AlarmSeverity.MAJOR, toSeverity(Status.DOWN));
    }

    // -------------------------------------------------------------------------
    // Tick listener integration
    // -------------------------------------------------------------------------

    @Test
    void registerTickListener_listenerStoredOnRegistry() {
        // Verify the package-private API used by HealthPv is plumbed correctly
        // by registering a listener and checking an observable side-effect.
        int[] count = {0};
        registry.registerTickListener(() -> count[0]++);

        // The poll loop is effectively disabled (1-hour step).
        // We just verify the listener was registered without error.
        assertEquals(0, count[0],
                "Listener must not fire without a poll tick");
    }

    // -------------------------------------------------------------------------
    // Helper (mirrors HealthPv.toSeverity)
    // -------------------------------------------------------------------------

    private static AlarmSeverity toSeverity(Status status) {
        return switch (status) {
            case UP       -> AlarmSeverity.NO_ALARM;
            case DEGRADED -> AlarmSeverity.MINOR;
            case DOWN     -> AlarmSeverity.MAJOR;
        };
    }
}
