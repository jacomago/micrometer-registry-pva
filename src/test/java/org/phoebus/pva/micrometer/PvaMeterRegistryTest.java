package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import org.epics.pva.client.PVAChannel;
import org.epics.pva.client.PVAClient;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAStructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PvaMeterRegistry} that start a real PVA server
 * and connect a local PVAClient to verify published values.
 */
@Timeout(30)
class PvaMeterRegistryTest {

    private static final String PREFIX = "test.registry";

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
            public String prefix() {
                return PREFIX;
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
    void gauge_publishesDoubleValue() throws Exception {
        AtomicLong value = new AtomicLong(42L);
        Gauge.builder("my.gauge", value, AtomicLong::doubleValue)
                .register(registry);

        // Wait for at least one poll
        Thread.sleep(500);

        String pvName = "my.gauge";
        PVAChannel channel = client.getChannel(pvName);
        assertTrue(channel.connect(5, TimeUnit.SECONDS), "PVA channel should connect");

        PVAStructure data = channel.read("").get(5, TimeUnit.SECONDS);
        assertNotNull(data);
        PVADouble valueField = data.get("value");
        assertNotNull(valueField);
        assertEquals(42.0, valueField.get(), 0.001);

        // Update value and wait for next poll
        value.set(99L);
        Thread.sleep(500);
        data = channel.read("").get(5, TimeUnit.SECONDS);
        valueField = data.get("value");
        assertEquals(99.0, valueField.get(), 0.001);
    }

    @Test
    void counter_publishesCumulativeCount() throws Exception {
        Counter counter = Counter.builder("my.counter")
                .register(registry);

        counter.increment(3.0);

        // Wait for poll
        Thread.sleep(500);

        String pvName = "my.counter";
        PVAChannel channel = client.getChannel(pvName);
        assertTrue(channel.connect(5, TimeUnit.SECONDS), "PVA channel should connect");

        PVAStructure data = channel.read("").get(5, TimeUnit.SECONDS);
        assertNotNull(data);
        PVADouble valueField = data.get("value");
        assertNotNull(valueField);
        assertEquals(3.0, valueField.get(), 0.001);
    }

    @Test
    void registryClose_shutsDownPollLoop() throws Exception {
        Gauge.builder("close.test.gauge", () -> 1.0).register(registry);
        registry.close();

        // After close, the registry should be marked closed
        assertTrue(registry.isClosed());
    }
}
