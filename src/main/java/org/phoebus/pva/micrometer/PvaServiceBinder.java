package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import org.phoebus.pva.micrometer.internal.HealthPv;
import org.phoebus.pva.micrometer.internal.InfoPv;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder that wires standard JVM metrics, optional build information, and
 * health indicators into a {@link PvaMeterRegistry}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PvaServiceBinder.forService("archiver.engine")
 *     .withBuildInfo("1.0.0", "2024-01-15", "abc1234")
 *     .withHealthIndicator(() -> dbPool.isOpen()
 *             ? Health.up()
 *             : Health.down("connection pool closed"))
 *     .withoutGcMetrics()
 *     .bindTo(registry);
 * }</pre>
 *
 * <h2>What {@link #bindTo} does</h2>
 * <ol>
 *   <li>Adds a {@code service=<prefix>} common tag via
 *       {@code registry.config().meterFilter(MeterFilter.commonTags(...))} so all
 *       Micrometer-binder-produced meters carry the prefix in their PV names.</li>
 *   <li>Binds {@link JvmMemoryMetrics}, {@link ProcessorMetrics}, and
 *       {@link UptimeMetrics} unconditionally.</li>
 *   <li>Conditionally binds {@link JvmGcMetrics}, {@link JvmThreadMetrics}, and
 *       {@link ClassLoaderMetrics}.</li>
 *   <li>Creates a {@code <prefix>.info} PV with static build metadata (JSON) if
 *       {@link #withBuildInfo} was called.</li>
 *   <li>Creates a {@code <prefix>.health} PV updated on every poll tick if at least
 *       one {@link HealthIndicator} was registered via {@link #withHealthIndicator}.</li>
 * </ol>
 */
public final class PvaServiceBinder {

    private final String prefix;

    /** Non-null when {@link #withBuildInfo} has been called. */
    private BuildInfo buildInfo = null;

    private final List<HealthIndicator> healthIndicators = new ArrayList<>();

    private record BuildInfo(String version, String buildDate, String gitCommit) {}

    private boolean excludeGcMetrics = false;
    private boolean excludeThreadMetrics = false;
    private boolean excludeClassLoaderMetrics = false;

    private PvaServiceBinder(String prefix) {
        this.prefix = prefix;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a new binder scoped to the given service prefix.
     *
     * <p>The prefix is used as the value of the {@code service} common tag injected
     * before calling Micrometer binders, so PV names derived from those binders will
     * include it in their tag set.  It is also used as the name prefix for the
     * {@code .build} and {@code .health} raw PVA channels.
     *
     * @param prefix service identifier, e.g. {@code "archiver.engine"}; must not be null
     * @return a new binder instance
     */
    public static PvaServiceBinder forService(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix must not be null");
        }
        return new PvaServiceBinder(prefix);
    }

    // -------------------------------------------------------------------------
    // Configuration methods
    // -------------------------------------------------------------------------

    /**
     * Stores build metadata to publish as a one-time {@code <prefix>.info} PVA channel.
     *
     * <p>The channel is an {@code NTScalar string} whose {@code value} is a JSON object
     * containing the supplied fields plus the current hostname.  It is created during
     * {@link #bindTo} and its value never changes.
     * Any of the three parameters may be {@code null} (they will be omitted from the JSON).
     *
     * @param version   artifact version string, e.g. {@code "1.0.0-SNAPSHOT"}
     * @param buildDate ISO-8601 build date, e.g. {@code "2024-01-15"}
     * @param gitCommit short Git commit hash, e.g. {@code "abc1234"}
     * @return {@code this} for chaining
     */
    public PvaServiceBinder withBuildInfo(String version, String buildDate, String gitCommit) {
        this.buildInfo = new BuildInfo(version, buildDate, gitCommit);
        return this;
    }

    /**
     * Appends a health indicator whose result is aggregated into the
     * {@code <prefix>.health} PVA channel on every poll tick.
     *
     * <p>Multiple indicators are combined: the worst status wins and messages are
     * joined with {@code "; "}.
     *
     * @param indicator health check to add; must not be null
     * @return {@code this} for chaining
     */
    public PvaServiceBinder withHealthIndicator(HealthIndicator indicator) {
        if (indicator == null) {
            throw new IllegalArgumentException("indicator must not be null");
        }
        this.healthIndicators.add(indicator);
        return this;
    }

    /**
     * Suppresses binding of {@link JvmGcMetrics} (useful when the JVM GC is managed
     * externally or the additional metrics are not desired).
     *
     * @return {@code this} for chaining
     */
    public PvaServiceBinder withoutGcMetrics() {
        this.excludeGcMetrics = true;
        return this;
    }

    /**
     * Suppresses binding of {@link JvmThreadMetrics}.
     *
     * @return {@code this} for chaining
     */
    public PvaServiceBinder withoutThreadMetrics() {
        this.excludeThreadMetrics = true;
        return this;
    }

    /**
     * Suppresses binding of {@link ClassLoaderMetrics}.
     *
     * @return {@code this} for chaining
     */
    public PvaServiceBinder withoutClassLoaderMetrics() {
        this.excludeClassLoaderMetrics = true;
        return this;
    }

    // -------------------------------------------------------------------------
    // Main binding
    // -------------------------------------------------------------------------

    /**
     * Applies all configured binders and PVs to the given registry.
     *
     * <p>This method may be called at most once on a given registry; calling it a
     * second time would add duplicate common-tag filters.
     *
     * @param registry the registry to bind to; must not be null
     */
    public void bindTo(PvaMeterRegistry registry) {
        // Inject service=<prefix> into every meter produced by the binders below so
        // their PV names include the prefix via the tag.
        registry.config().meterFilter(MeterFilter.commonTags(Tags.of("service", prefix)));

        // Always-on JVM metrics.
        new JvmMemoryMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);

        // Conditional JVM metrics.
        if (!excludeGcMetrics) {
            new JvmGcMetrics().bindTo(registry);
        }
        if (!excludeThreadMetrics) {
            new JvmThreadMetrics().bindTo(registry);
        }
        if (!excludeClassLoaderMetrics) {
            new ClassLoaderMetrics().bindTo(registry);
        }

        // Build-info PV — one-shot NTScalar string with JSON value; never updated after creation.
        if (buildInfo != null) {
            new InfoPv(registry, prefix + ".info", prefix,
                    buildInfo.version(), buildInfo.buildDate(), buildInfo.gitCommit());
        }

        // Health PV — NTScalar string updated on every poll tick.
        if (!healthIndicators.isEmpty()) {
            HealthPv healthPv = new HealthPv(registry, prefix + ".health", List.copyOf(healthIndicators));
            registry.registerTickListener(healthPv::tick);
        }
    }
}
