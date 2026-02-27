package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Clock;
import org.epics.pva.client.PVAChannel;
import org.epics.pva.client.PVAClient;
import org.epics.pva.data.PVAInt;
import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PvaServiceBinder}.
 *
 * <p>Verifies that the health PV alarm severity reflects the worst health indicator,
 * and that the info PV contains the expected build metadata.
 */
@Timeout(30)
class PvaServiceBinderTest {

    private static final String SERVICE_PREFIX = "test.service";

    private PvaMeterRegistry registry;
    private PVAClient client;

    @BeforeEach
    void setUp() throws Exception {
        PvaMeterRegistryConfig config = new PvaMeterRegistryConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public Duration step() {
                return Duration.ofMillis(200);
            }
        };
        registry = new PvaMeterRegistry(config, Clock.SYSTEM);
        client = new PVAClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (registry != null) {
            registry.close();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void healthPv_downIndicator_majorAlarmSeverity() throws Exception {
        PvaServiceBinder.forService(SERVICE_PREFIX)
                .withoutGcMetrics()
                .withoutClassLoaderMetrics()
                .withoutThreadMetrics()
                .withHealthIndicator(() -> Health.down("DB unreachable"))
                .bindTo(registry);

        // Wait for a health poll tick
        Thread.sleep(500);

        String healthPvName = SERVICE_PREFIX + ".health";
        PVAChannel channel = client.getChannel(healthPvName);
        assertTrue(channel.connect(5, TimeUnit.SECONDS),
                "Health PV channel should connect: " + healthPvName);

        PVAStructure data = channel.read("").get(5, TimeUnit.SECONDS);
        assertNotNull(data, "Should receive health PV data");

        // Value should be "DOWN"
        PVAString valueField = data.get("value");
        assertNotNull(valueField, "Should have value field");
        assertEquals("DOWN", valueField.get(), "Health status should be DOWN");

        // Alarm severity should be MAJOR (2) for DOWN
        PVAStructure alarmStruct = data.get("alarm");
        assertNotNull(alarmStruct, "Should have alarm field");
        PVAInt severity = alarmStruct.get("severity");
        assertNotNull(severity, "Should have severity field");
        assertEquals(2, severity.get(), "DOWN health should have MAJOR alarm severity (2)");
    }

    @Test
    void healthPv_upIndicator_noAlarm() throws Exception {
        PvaServiceBinder.forService(SERVICE_PREFIX + ".up")
                .withoutGcMetrics()
                .withoutClassLoaderMetrics()
                .withoutThreadMetrics()
                .withHealthIndicator(() -> Health.up())
                .bindTo(registry);

        Thread.sleep(500);

        String healthPvName = SERVICE_PREFIX + ".up.health";
        PVAChannel channel = client.getChannel(healthPvName);
        assertTrue(channel.connect(5, TimeUnit.SECONDS));

        PVAStructure data = channel.read("").get(5, TimeUnit.SECONDS);
        assertNotNull(data);

        PVAString valueField = data.get("value");
        assertEquals("UP", valueField.get());

        PVAStructure alarmStruct = data.get("alarm");
        PVAInt severity = alarmStruct.get("severity");
        assertEquals(0, severity.get(), "UP health should have NO_ALARM (0)");
    }

    @Test
    void infoPv_containsVersionAndHost() throws Exception {
        PvaServiceBinder.forService(SERVICE_PREFIX + ".info.svc")
                .withBuildInfo("2.3.4", "2026-02-27", "abc1234")
                .withoutGcMetrics()
                .withoutClassLoaderMetrics()
                .withoutThreadMetrics()
                .bindTo(registry);

        // Info PV is static (set once at creation)
        String infoPvName = SERVICE_PREFIX + ".info.svc.info";
        PVAChannel channel = client.getChannel(infoPvName);
        assertTrue(channel.connect(5, TimeUnit.SECONDS),
                "Info PV channel should connect: " + infoPvName);

        PVAStructure data = channel.read("").get(5, TimeUnit.SECONDS);
        assertNotNull(data);

        PVAString valueField = data.get("value");
        assertNotNull(valueField, "Info PV should have value field");
        String json = valueField.get();
        assertNotNull(json, "Info PV value should not be null");

        // Verify the JSON contains expected fields
        assertTrue(json.contains("\"version\":\"2.3.4\""),
                "Info JSON should contain version: " + json);
        assertTrue(json.contains("\"gitCommit\":\"abc1234\""),
                "Info JSON should contain gitCommit: " + json);
        assertTrue(json.contains("\"host\""),
                "Info JSON should contain host: " + json);
    }

    @Test
    void healthPv_worstWinsSemantics_degradedAndDown_givesDown() throws Exception {
        PvaServiceBinder.forService(SERVICE_PREFIX + ".worst")
                .withoutGcMetrics()
                .withoutClassLoaderMetrics()
                .withoutThreadMetrics()
                .withHealthIndicator(() -> Health.degraded("high load"))
                .withHealthIndicator(() -> Health.down("disk full"))
                .bindTo(registry);

        Thread.sleep(500);

        String healthPvName = SERVICE_PREFIX + ".worst.health";
        PVAChannel channel = client.getChannel(healthPvName);
        assertTrue(channel.connect(5, TimeUnit.SECONDS));

        PVAStructure data = channel.read("").get(5, TimeUnit.SECONDS);
        PVAString valueField = data.get("value");
        assertEquals("DOWN", valueField.get(), "Worst-wins should pick DOWN");

        PVAStructure alarmStruct = data.get("alarm");
        PVAInt severity = alarmStruct.get("severity");
        assertEquals(2, severity.get(), "Worst-wins DOWN should have severity 2");
    }
}
