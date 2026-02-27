package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Gauge;
import org.epics.pva.client.PVAChannel;
import org.epics.pva.client.PVAClient;
import org.epics.pva.data.PVAInt;
import org.epics.pva.data.PVAStructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that PVA alarm severity is set correctly when a gauge's value supplier throws.
 *
 * <p>EPICS alarm severities:
 * <ul>
 *   <li>0 = NO_ALARM</li>
 *   <li>1 = MINOR</li>
 *   <li>2 = MAJOR</li>
 *   <li>3 = INVALID</li>
 * </ul>
 */
@Timeout(30)
class AlarmTest {

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
    void gauge_throwingSupplier_setsInvalidAlarm() throws Exception {
        // Register a gauge whose supplier always throws
        Gauge.builder("alarm.test.gauge", () -> {
            throw new RuntimeException("Simulated sensor failure");
        }).register(registry);

        // Wait for the poll loop to call tick() (which will catch the exception and set INVALID alarm)
        Thread.sleep(600);

        PVAChannel channel = client.getChannel("alarm.test.gauge");
        assertTrue(channel.connect(5, TimeUnit.SECONDS), "PVA channel should connect");

        PVAStructure data = channel.read("").get(5, TimeUnit.SECONDS);
        assertNotNull(data, "Should have received PV data");

        // The alarm structure contains severity
        PVAStructure alarmStruct = data.get("alarm");
        assertNotNull(alarmStruct, "Should have alarm field");

        PVAInt severity = alarmStruct.get("severity");
        assertNotNull(severity, "Should have severity field");

        // INVALID = 3
        assertEquals(3, severity.get(),
                "Gauge with throwing supplier should have INVALID alarm severity");
    }

    @Test
    void gauge_normalSupplier_noAlarm() throws Exception {
        Gauge.builder("noalarm.test.gauge", () -> 42.0).register(registry);

        Thread.sleep(500);

        PVAChannel channel = client.getChannel("noalarm.test.gauge");
        assertTrue(channel.connect(5, TimeUnit.SECONDS));

        PVAStructure data = channel.read("").get(5, TimeUnit.SECONDS);
        assertNotNull(data);

        PVAStructure alarmStruct = data.get("alarm");
        assertNotNull(alarmStruct);

        PVAInt severity = alarmStruct.get("severity");
        assertNotNull(severity);

        // NO_ALARM = 0
        assertEquals(0, severity.get(), "Normal gauge should have NO_ALARM severity");
    }
}
