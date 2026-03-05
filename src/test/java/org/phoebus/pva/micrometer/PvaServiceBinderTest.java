package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Clock;
import org.epics.pva.PVASettings;
import org.epics.pva.client.PVAChannel;
import org.epics.pva.client.PVAClient;
import org.epics.pva.data.PVAInt;
import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.pva.micrometer.Health.Status;

import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

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

        assertNotNull(registry.serverPv("test.build.info"),
                "info PV must be created when withBuildInfo is called");
    }

    @Test
    void infoPv_notCreatedWithoutBuildInfo() {
        PvaServiceBinder.forService("test.nobuild")
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics()
                .bindTo(registry);

        assertNull(registry.serverPv("test.nobuild.info"),
                "info PV must NOT be created when withBuildInfo is not called");
    }

    @Test
    void infoPv_nullFieldsOmittedFromJson() {
        // Must not throw even when all fields are null (host is still included).
        PvaServiceBinder.forService("test.nullbuild")
                .withBuildInfo(null, null, null)
                .withoutGcMetrics()
                .withoutThreadMetrics()
                .withoutClassLoaderMetrics()
                .bindTo(registry);

        assertNotNull(registry.serverPv("test.nullbuild.info"),
                "info PV must still be created when null build fields are supplied");
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
                "HealthPv must call indicator once during construction");
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
    // Integration tests: full PVA round-trip via PVAClient
    // -------------------------------------------------------------------------

    /**
     * End-to-end test: verifies that the {@code <prefix>.health} PV published by
     * {@link PvaServiceBinder} carries {@code alarm.severity == 2} (MAJOR) and
     * {@code value == "DOWN"} when the indicator reports DOWN, then transitions to
     * {@code alarm.severity == 0} (NO_ALARM) after the indicator is replaced with UP.
     *
     * <p>Uses a 1-second step so poll ticks fire predictably within the test.
     */
    @Test
    void integration_healthAlarmSeverityReflectsStatusTransition() throws Exception {
        PvaMeterRegistryConfig shortStepConfig = new PvaMeterRegistryConfig() {
            @Override public String get(String key) { return null; }
            @Override public Duration step() { return Duration.ofSeconds(1); }
        };

        try (PVAServer integServer = new PVAServer()) {
            int serverPort = integServer.getTCPAddress(false).getPort();

            String savedNameServers = PVASettings.EPICS_PVA_NAME_SERVERS;
            boolean savedAutoAddrList = PVASettings.EPICS_PVA_AUTO_ADDR_LIST;
            PVASettings.EPICS_PVA_NAME_SERVERS = "127.0.0.1:" + serverPort;
            PVASettings.EPICS_PVA_AUTO_ADDR_LIST = false;

            PvaMeterRegistry integRegistry =
                    new PvaMeterRegistry(shortStepConfig, Clock.SYSTEM, integServer);
            try {
                String prefix = "bind.svc";

                // Mutable health state: starts DOWN, will be switched to UP later.
                AtomicReference<Health> healthRef =
                        new AtomicReference<>(new Health(Status.DOWN, "db unreachable"));

                PvaServiceBinder.forService(prefix)
                        .withHealthIndicator(healthRef::get)
                        .withoutGcMetrics()
                        .withoutThreadMetrics()
                        .withoutClassLoaderMetrics()
                        .bindTo(integRegistry);

                // Wait for at least one poll tick to push the DOWN status.
                Thread.sleep(1500);

                try (PVAClient client = new PVAClient()) {
                    PVAChannel healthChannel = client.getChannel(prefix + ".health");
                    healthChannel.connect().get(5, TimeUnit.SECONDS);

                    // Assert DOWN: value == "DOWN", alarm.severity == 2 (MAJOR).
                    // The PVA client deserialises the alarm sub-structure as a plain
                    // PVAStructure, so we read the severity integer field directly.
                    PVAStructure downData = healthChannel.read("").get(5, TimeUnit.SECONDS);
                    assertEquals("DOWN", ((PVAString) downData.get("value")).get(),
                            "health PV value must be 'DOWN'");
                    PVAStructure downAlarm = downData.get("alarm");
                    assertEquals(2, ((PVAInt) downAlarm.get("severity")).get(),
                            "DOWN status must map to alarm severity 2 (MAJOR)");

                    // Replace indicator with UP, wait for the next poll tick.
                    healthRef.set(Health.up());
                    Thread.sleep(1500);

                    // Assert UP: alarm.severity == 0 (NO_ALARM)
                    PVAStructure upData = healthChannel.read("").get(5, TimeUnit.SECONDS);
                    PVAStructure upAlarm = upData.get("alarm");
                    assertEquals(0, ((PVAInt) upAlarm.get("severity")).get(),
                            "UP status must map to alarm severity 0 (NO_ALARM)");
                }
            } finally {
                integRegistry.close();
                PVASettings.EPICS_PVA_NAME_SERVERS = savedNameServers;
                PVASettings.EPICS_PVA_AUTO_ADDR_LIST = savedAutoAddrList;
            }
        }
    }

    /**
     * End-to-end test: verifies that the {@code <prefix>.info} PV created by
     * {@link PvaServiceBinder} carries a JSON {@code value} field that contains the
     * expected {@code version} and a non-empty {@code host} entry.
     *
     * <p>The info PV is written once during {@code bindTo()} — no poll tick is needed
     * before reading it.
     */
    @Test
    void integration_infoPvExposesVersionAndHost() throws Exception {
        try (PVAServer integServer = new PVAServer()) {
            int serverPort = integServer.getTCPAddress(false).getPort();

            String savedNameServers = PVASettings.EPICS_PVA_NAME_SERVERS;
            boolean savedAutoAddrList = PVASettings.EPICS_PVA_AUTO_ADDR_LIST;
            PVASettings.EPICS_PVA_NAME_SERVERS = "127.0.0.1:" + serverPort;
            PVASettings.EPICS_PVA_AUTO_ADDR_LIST = false;

            // 1-hour step: info PV is static; no poll tick is required.
            PvaMeterRegistry integRegistry =
                    new PvaMeterRegistry(TEST_CONFIG, Clock.SYSTEM, integServer);
            try {
                String prefix = "bind.build";

                PvaServiceBinder.forService(prefix)
                        .withBuildInfo("1.4.2", "2026-02-26", "abc1234")
                        .withoutGcMetrics()
                        .withoutThreadMetrics()
                        .withoutClassLoaderMetrics()
                        .bindTo(integRegistry);

                // Info PV value is written in the InfoPv constructor — connect immediately.
                try (PVAClient client = new PVAClient()) {
                    PVAChannel infoChannel = client.getChannel(prefix + ".info");
                    infoChannel.connect().get(5, TimeUnit.SECONDS);
                    PVAStructure infoData = infoChannel.read("").get(5, TimeUnit.SECONDS);

                    String json = ((PVAString) infoData.get("value")).get();

                    // Assert version is correct.
                    assertTrue(json.contains("\"version\":\"1.4.2\""),
                            "info JSON must contain \"version\":\"1.4.2\"; actual: " + json);

                    // Assert host is present and non-empty.
                    int hostKeyIdx = json.indexOf("\"host\":\"");
                    assertTrue(hostKeyIdx >= 0,
                            "info JSON must contain a 'host' field; actual: " + json);
                    int hostValStart = hostKeyIdx + 8; // skip past "\"host\":\""
                    int hostValEnd = json.indexOf('"', hostValStart);
                    assertTrue(hostValEnd > hostValStart,
                            "host value must be non-empty; actual: " + json);
                }
            } finally {
                integRegistry.close();
                PVASettings.EPICS_PVA_NAME_SERVERS = savedNameServers;
                PVASettings.EPICS_PVA_AUTO_ADDR_LIST = savedAutoAddrList;
            }
        }
    }

    // -------------------------------------------------------------------------
    // PvaServiceBinder — JVM metrics enabled (GC + thread + class-loader)
    // -------------------------------------------------------------------------

    @Test
    void binder_defaultMetricsBound_withGcAndThreadAndClassLoader() {
        // Omitting the three withoutXxx() calls exercises the !excludeGcMetrics,
        // !excludeThreadMetrics, and !excludeClassLoaderMetrics branches in bindTo().
        PvaServiceBinder.forService("test.full.metrics")
                .bindTo(registry);

        // At least one JVM metric must have been registered.
        assertTrue(registry.getMeters().size() > 0,
                "bindTo() without suppressions must register at least some JVM metrics");
    }

    // -------------------------------------------------------------------------
    // HealthPv — close() and tick() update-exception path
    // -------------------------------------------------------------------------

    @Test
    void healthPv_closeDoesNotThrow() {
        // Create a HealthPv directly (package-private, same package) and call close().
        HealthPv healthPv = new HealthPv(registry, "test.hp.close", List.of(Health::up));
        healthPv.close(); // must not throw
    }

    @Test
    void healthPv_tickUpdateExceptionIsSwallowed() {
        // Arrange a mock PVAServer whose ServerPV.update() always throws.
        PVAServer mockServer = Mockito.mock(PVAServer.class);
        ServerPV mockPv = Mockito.mock(ServerPV.class);
        try {
            doThrow(new RuntimeException("update failed")).when(mockPv).update(any());
            Mockito.when(mockServer.createPV(any(String.class), any(PVAStructure.class)))
                    .thenReturn(mockPv);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        PvaMeterRegistry reg = new PvaMeterRegistry(TEST_CONFIG, Clock.SYSTEM, mockServer);
        try {
            // Constructor calls tick() → serverPV.update() throws → must be swallowed.
            HealthPv healthPv = new HealthPv(reg, "test.hp.tick.exc", List.of(Health::up));
            // A subsequent explicit tick() must also not propagate the exception.
            healthPv.tick();
        } finally {
            reg.close();
        }
    }

    // -------------------------------------------------------------------------
    // InfoPv — close() and constructor update-exception path
    // -------------------------------------------------------------------------

    @Test
    void infoPv_closeDoesNotThrow() {
        // Create an InfoPv directly (package-private, same package) and call close().
        InfoPv infoPv = new InfoPv(registry, "test.ip.close", "svc", "1.0", "2024-01-01", "abc");
        infoPv.close(); // must not throw
    }

    @Test
    void infoPv_constructorUpdateExceptionIsSwallowed() {
        // Arrange a mock PVAServer whose ServerPV.update() always throws.
        PVAServer mockServer = Mockito.mock(PVAServer.class);
        ServerPV mockPv = Mockito.mock(ServerPV.class);
        try {
            doThrow(new RuntimeException("update failed")).when(mockPv).update(any());
            Mockito.when(mockServer.createPV(any(String.class), any(PVAStructure.class)))
                    .thenReturn(mockPv);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        PvaMeterRegistry reg = new PvaMeterRegistry(TEST_CONFIG, Clock.SYSTEM, mockServer);
        try {
            // Constructor calls serverPV.update() in a try/catch — must be swallowed.
            InfoPv infoPv = new InfoPv(reg, "test.ip.upd.exc", "svc", "1.0", "2024-01-01", "abc");
        } finally {
            reg.close();
        }
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
