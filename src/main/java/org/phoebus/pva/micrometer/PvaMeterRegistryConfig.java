package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.config.MeterRegistryConfig;

import java.time.Duration;

/**
 * Configuration SPI for {@link PvaMeterRegistry}.
 *
 * <p>The default implementation {@link #DEFAULT} returns {@code null} for every key,
 * causing all properties to fall back to their declared {@code default} values.
 *
 * <p>Property-based overriding follows the standard Micrometer convention: implement
 * {@link #get(String)} to read from an external source (system properties, config files,
 * etc.).  The property prefix is {@code "pva"}, so for example the step property is
 * accessible as {@code "pva.step"}.
 *
 * <pre>{@code
 * // One-liner with all defaults:
 * PvaMeterRegistry registry = new PvaMeterRegistry(PvaMeterRegistryConfig.DEFAULT, Clock.SYSTEM);
 *
 * // Custom configuration via anonymous class:
 * PvaMeterRegistry registry = new PvaMeterRegistry(
 *     new PvaMeterRegistryConfig() {
 *         public Duration step() { return Duration.ofSeconds(5); }
 *     },
 *     Clock.SYSTEM);
 * }</pre>
 */
public interface PvaMeterRegistryConfig extends MeterRegistryConfig {

    /** Singleton that accepts all defaults. */
    PvaMeterRegistryConfig DEFAULT = key -> null;

    /**
     * Property prefix used when looking up overrides via {@link #get(String)}.
     * The full key for a property named {@code "step"} is {@code "pva.step"}.
     */
    @Override
    default String prefix() {
        return "pva";
    }

    /**
     * How often the poll loop reads meter values and pushes them to subscribed PVA clients.
     *
     * <p>Default: 10 seconds.  Lower values increase update frequency but also network traffic
     * and CPU usage.  Sub-second intervals are not recommended.
     */
    default Duration step() {
        String v = get(prefix() + ".step");
        return v == null ? Duration.ofSeconds(10) : Duration.parse(v);
    }

    /**
     * Strategy for converting a {@link io.micrometer.core.instrument.Meter.Id} (name + tags)
     * into a PVA channel name string.
     *
     * <p>Default: {@link PvNamingStrategy#DOTS_WITH_BRACE_TAGS} — dots preserved in the meter
     * name, tags appended in Prometheus-style brace notation.
     */
    default PvNamingStrategy namingStrategy() {
        return PvNamingStrategy.DOTS_WITH_BRACE_TAGS;
    }
}
