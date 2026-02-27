package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.config.MeterRegistryConfig;

import java.time.Duration;

/**
 * Configuration for {@link PvaMeterRegistry}.
 *
 * <p>Implement this interface to customise the registry behaviour. The default
 * implementation ({@link #DEFAULT}) returns {@code null} for all keys, causing
 * every default value to be used.
 */
public interface PvaMeterRegistryConfig extends MeterRegistryConfig {

    /** Default configuration — every setting uses its declared default. */
    PvaMeterRegistryConfig DEFAULT = key -> null;

    @Override
    default String prefix() {
        return "pva";
    }

    /**
     * How often meter values are polled and pushed to PVA subscribers.
     * Defaults to 10 seconds.
     */
    default Duration step() {
        String v = get(prefix() + ".step");
        return v != null ? Duration.parse(v) : Duration.ofSeconds(10);
    }

    /**
     * When {@code true} (the default), the poll loop publishes every meter
     * value on every tick even when the value has not changed. This prevents
     * stale-data alarms on clients that monitor the PV.
     */
    default boolean alwaysPublish() {
        String v = get(prefix() + ".alwaysPublish");
        return v == null || Boolean.parseBoolean(v);
    }

    /**
     * The naming strategy used to derive PV names from Micrometer meter IDs.
     * Defaults to {@link PvNamingStrategy#DOTS_WITH_BRACE_TAGS}.
     */
    default PvNamingStrategy namingStrategy() {
        return PvNamingStrategy.DOTS_WITH_BRACE_TAGS;
    }
}
