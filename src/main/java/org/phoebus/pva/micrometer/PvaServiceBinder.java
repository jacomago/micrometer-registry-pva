package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import org.phoebus.pva.micrometer.internal.HealthPv;
import org.phoebus.pva.micrometer.internal.InfoPv;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builder that registers a standard set of PVs for any Java web service.
 *
 * <p>Optionally registers:
 * <ul>
 *   <li>JVM metrics via standard Micrometer binders (memory, GC, threads, CPU, uptime, classes)</li>
 *   <li>A health PV ({@code <prefix>.health}) whose alarm severity tracks worst-wins health</li>
 *   <li>A static info PV ({@code <prefix>.info}) containing service identity as a JSON string</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * PvaServiceBinder.forService("myapp")
 *     .withBuildInfo("1.4.2", "2026-02-26", "abc1234")
 *     .withHealthIndicator(() -> dbIsUp ? Health.up() : Health.down("DB unreachable"))
 *     .bindTo(registry);
 * }</pre>
 */
public final class PvaServiceBinder {

    private static final Logger logger = Logger.getLogger(PvaServiceBinder.class.getName());

    private final String prefix;
    private String version;
    private String buildDate;
    private String gitCommit;
    private final List<HealthIndicator> healthIndicators = new ArrayList<>();
    private boolean includeGcMetrics = true;
    private boolean includeThreadMetrics = true;
    private boolean includeClassLoaderMetrics = true;

    private PvaServiceBinder(String prefix) {
        this.prefix = prefix;
    }

    /** Start building a binder with the given PV name prefix. */
    public static PvaServiceBinder forService(String prefix) {
        return new PvaServiceBinder(prefix);
    }

    /** Set static build/version info for the info PV. */
    public PvaServiceBinder withBuildInfo(String version, String buildDate, String gitCommit) {
        this.version = version;
        this.buildDate = buildDate;
        this.gitCommit = gitCommit;
        return this;
    }

    /** Add a health indicator (multiple calls are combined with worst-wins semantics). */
    public PvaServiceBinder withHealthIndicator(HealthIndicator indicator) {
        healthIndicators.add(indicator);
        return this;
    }

    /** Exclude GC metrics from the JVM binders. */
    public PvaServiceBinder withoutGcMetrics() {
        this.includeGcMetrics = false;
        return this;
    }

    /** Exclude thread metrics from the JVM binders. */
    public PvaServiceBinder withoutThreadMetrics() {
        this.includeThreadMetrics = false;
        return this;
    }

    /** Exclude class loader metrics from the JVM binders. */
    public PvaServiceBinder withoutClassLoaderMetrics() {
        this.includeClassLoaderMetrics = false;
        return this;
    }

    /**
     * Register all configured PVs and meters into the given registry.
     *
     * @param registry the registry to bind to
     */
    public void bindTo(PvaMeterRegistry registry) {
        // JVM binders — these create ordinary Micrometer meters which the
        // registry publishes via the normal poll loop
        new JvmMemoryMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);

        if (includeGcMetrics) {
            try {
                new JvmGcMetrics().bindTo(registry);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not bind JVM GC metrics", e);
            }
        }
        if (includeThreadMetrics) {
            new JvmThreadMetrics().bindTo(registry);
        }
        if (includeClassLoaderMetrics) {
            new ClassLoaderMetrics().bindTo(registry);
        }

        // Static info PV
        String infoPvName = prefix + ".info";
        try {
            InfoPv infoPv = new InfoPv(infoPvName, prefix, version, buildDate, gitCommit,
                    registry.getPVAServer());
            // Register info PV close with registry shutdown (via shutdown hook or manual call)
            Runtime.getRuntime().addShutdownHook(new Thread(infoPv::close,
                    "close-info-pv-" + infoPvName));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create info PV: " + infoPvName, e);
        }

        // Health PV (polled on each tick via the registry's poll loop)
        if (!healthIndicators.isEmpty()) {
            String healthPvName = prefix + ".health";
            try {
                HealthPv healthPv = new HealthPv(healthPvName, healthIndicators,
                        registry.getPVAServer());
                registry.addExtraMeter(healthPvName, healthPv);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to create health PV: " + healthPvName, e);
            }
        }
    }
}
