package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.cumulative.CumulativeCounter;
import io.micrometer.core.instrument.cumulative.CumulativeDistributionSummary;
import io.micrometer.core.instrument.cumulative.CumulativeFunctionCounter;
import io.micrometer.core.instrument.cumulative.CumulativeFunctionTimer;
import io.micrometer.core.instrument.cumulative.CumulativeTimer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.internal.DefaultGauge;
import io.micrometer.core.instrument.internal.DefaultLongTaskTimer;
import io.micrometer.core.instrument.internal.DefaultMeter;
import org.epics.pva.server.PVAServer;
import org.phoebus.pva.micrometer.internal.PvaCustomMeter;
import org.phoebus.pva.micrometer.internal.PvaDistributionSummary;
import org.phoebus.pva.micrometer.internal.PvaFunctionTimer;
import org.phoebus.pva.micrometer.internal.PvaGauge;
import org.phoebus.pva.micrometer.internal.PvaLongTaskTimer;
import org.phoebus.pva.micrometer.internal.PvaMeter;
import org.phoebus.pva.micrometer.internal.PvaMicrometerCounter;
import org.phoebus.pva.micrometer.internal.PvaTimer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Micrometer {@link MeterRegistry} backed by an EPICS PV Access (PVA) server.
 *
 * <p>Any Java application instrumented with Micrometer can publish all its metrics
 * as live PVA process variables by adding this registry. A poll loop fires at the
 * configured {@link PvaMeterRegistryConfig#step() step} interval and pushes current
 * meter values to subscribed PVA clients.
 *
 * <h3>Basic usage</h3>
 * <pre>{@code
 * PvaMeterRegistry registry = new PvaMeterRegistry(PvaMeterRegistryConfig.DEFAULT, Clock.SYSTEM);
 * Gauge.builder("jvm.heap.used", runtime, r -> r.totalMemory() - r.freeMemory())
 *      .register(registry);
 * }</pre>
 *
 * <p>Call {@link #close()} when done to stop the poll loop and (if this registry created
 * its own {@link PVAServer}) close the server.
 */
public class PvaMeterRegistry extends MeterRegistry {

    private static final Logger logger = Logger.getLogger(PvaMeterRegistry.class.getName());

    private final PvaMeterRegistryConfig config;
    private final PVAServer server;
    private final boolean ownsServer;
    private final ConcurrentHashMap<String, PvaMeter> pvMeters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Construct a registry that creates and owns its own {@link PVAServer}.
     *
     * @param config registry configuration
     * @param clock  clock for timestamping
     * @throws Exception if the PVAServer cannot start
     */
    public PvaMeterRegistry(PvaMeterRegistryConfig config, Clock clock) throws Exception {
        this(config, clock, new PVAServer(), true);
    }

    /**
     * Construct a registry that uses a caller-supplied {@link PVAServer}.
     *
     * <p>The supplied server will NOT be closed when this registry is closed.
     *
     * @param config registry configuration
     * @param clock  clock for timestamping
     * @param server pre-existing PVA server to publish PVs on
     */
    public PvaMeterRegistry(PvaMeterRegistryConfig config, Clock clock, PVAServer server) {
        this(config, clock, server, false);
    }

    private PvaMeterRegistry(PvaMeterRegistryConfig config, Clock clock,
            PVAServer server, boolean ownsServer) {
        super(clock);
        this.config = config;
        this.server = server;
        this.ownsServer = ownsServer;

        long stepMs = config.step().toMillis();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pva-meter-registry-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::pollAll, stepMs, stepMs, TimeUnit.MILLISECONDS);

        config().onMeterRemoved(this::onMeterRemoved);
    }

    // -------------------------------------------------------------------------
    // Poll loop
    // -------------------------------------------------------------------------

    /** Called on each scheduler tick: update all registered PV meters. */
    private void pollAll() {
        if (closed.get()) {
            return;
        }
        boolean always = config.alwaysPublish();
        for (PvaMeter pm : pvMeters.values()) {
            try {
                pm.tick(always);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error ticking PvaMeter", e);
            }
        }
    }

    /** Called when Micrometer removes a meter from the registry. */
    private void onMeterRemoved(Meter meter) {
        String pvName = config.namingStrategy().pvName(meter.getId());
        PvaMeter pm = pvMeters.remove(pvName);
        if (pm != null) {
            pm.close();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Stop the poll loop and, if this registry created its own PVAServer, close it.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (PvaMeter pm : pvMeters.values()) {
                pm.close();
            }
            pvMeters.clear();
            if (ownsServer) {
                try {
                    server.close();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error closing PVAServer", e);
                }
            }
            super.close();
        }
    }

    /**
     * Expose the underlying {@link PVAServer} so that callers (e.g. {@link PvaServiceBinder})
     * can create additional PVs on the same server.
     *
     * @return the PVA server used by this registry
     */
    public PVAServer getPVAServer() {
        return server;
    }

    /**
     * Register an additional {@link PvaMeter} to be polled on each tick.
     *
     * <p>Used by {@link PvaServiceBinder} to hook in health and info PVs that are not
     * backed by a standard Micrometer meter.
     *
     * @param pvName unique key (typically the PV name)
     * @param meter  the PvaMeter to register
     */
    public void addExtraMeter(String pvName, org.phoebus.pva.micrometer.internal.PvaMeter meter) {
        pvMeters.put(pvName, meter);
    }

    /**
     * Return the {@link PvaMeterRegistryConfig} used by this registry.
     */
    public PvaMeterRegistryConfig getConfig() {
        return config;
    }

    // -------------------------------------------------------------------------
    // MeterRegistry factory overrides
    // -------------------------------------------------------------------------

    @Override
    protected <T> Gauge newGauge(Meter.Id id, T obj, ToDoubleFunction<T> valueFunction) {
        String pvName = config.namingStrategy().pvName(id);
        checkNameCollision(pvName);
        try {
            PvaGauge pvGauge = PvaGauge.forGauge(pvName, obj, valueFunction, server);
            pvMeters.put(pvName, pvGauge);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create PVA gauge for " + pvName, e);
        }
        return new DefaultGauge<>(id, obj, valueFunction);
    }

    @Override
    protected Counter newCounter(Meter.Id id) {
        String pvName = config.namingStrategy().pvName(id);
        checkNameCollision(pvName);
        CumulativeCounter counter = new CumulativeCounter(id);
        try {
            PvaMicrometerCounter pvCounter = PvaMicrometerCounter.forCounter(pvName, counter, server);
            pvMeters.put(pvName, pvCounter);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create PVA counter for " + pvName, e);
        }
        return counter;
    }

    @Override
    protected Timer newTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig,
            io.micrometer.core.instrument.distribution.pause.PauseDetector pauseDetector) {
        String pvName = config.namingStrategy().pvName(id);
        checkNameCollision(pvName);
        CumulativeTimer timer = new CumulativeTimer(id, clock,
                distributionStatisticConfig.merge(defaultHistogramConfig()),
                pauseDetector, TimeUnit.SECONDS);
        try {
            PvaTimer pvTimer = new PvaTimer(pvName, timer, server);
            pvMeters.put(pvName, pvTimer);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create PVA timer for " + pvName, e);
        }
        return timer;
    }

    @Override
    protected DistributionSummary newDistributionSummary(Meter.Id id,
            DistributionStatisticConfig distributionStatisticConfig, double scale) {
        String pvName = config.namingStrategy().pvName(id);
        checkNameCollision(pvName);
        CumulativeDistributionSummary summary = new CumulativeDistributionSummary(id, clock,
                distributionStatisticConfig.merge(defaultHistogramConfig()), scale);
        try {
            PvaDistributionSummary pvSummary = new PvaDistributionSummary(pvName, summary, server);
            pvMeters.put(pvName, pvSummary);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create PVA distribution summary for " + pvName, e);
        }
        return summary;
    }

    @Override
    protected LongTaskTimer newLongTaskTimer(Meter.Id id,
            DistributionStatisticConfig distributionStatisticConfig) {
        String pvName = config.namingStrategy().pvName(id);
        checkNameCollision(pvName);
        DefaultLongTaskTimer ltt = new DefaultLongTaskTimer(id, clock, TimeUnit.SECONDS,
                distributionStatisticConfig, false);
        try {
            PvaLongTaskTimer pvLtt = new PvaLongTaskTimer(pvName, ltt, server);
            pvMeters.put(pvName, pvLtt);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create PVA long task timer for " + pvName, e);
        }
        return ltt;
    }

    @Override
    protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj,
            ToLongFunction<T> countFunction, ToDoubleFunction<T> totalTimeFunction,
            TimeUnit totalTimeFunctionUnit) {
        String pvName = config.namingStrategy().pvName(id);
        checkNameCollision(pvName);
        CumulativeFunctionTimer<T> ft = new CumulativeFunctionTimer<>(id, obj, countFunction,
                totalTimeFunction, totalTimeFunctionUnit, TimeUnit.SECONDS);
        try {
            PvaFunctionTimer pvFt = new PvaFunctionTimer(pvName, ft, server);
            pvMeters.put(pvName, pvFt);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create PVA function timer for " + pvName, e);
        }
        return ft;
    }

    @Override
    protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj,
            ToDoubleFunction<T> countFunction) {
        String pvName = config.namingStrategy().pvName(id);
        checkNameCollision(pvName);
        CumulativeFunctionCounter<T> fc = new CumulativeFunctionCounter<>(id, obj, countFunction);
        try {
            PvaMicrometerCounter pvFc = PvaMicrometerCounter.forFunctionCounter(pvName, obj,
                    countFunction, server);
            pvMeters.put(pvName, pvFc);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create PVA function counter for " + pvName, e);
        }
        return fc;
    }

    // newTimeGauge is intentionally not overridden: the MeterRegistry base class
    // implementation converts the value to the base time unit (seconds) and then
    // delegates to newGauge, which already creates the PVA wrapper.

    @Override
    protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        String pvName = config.namingStrategy().pvName(id);
        checkNameCollision(pvName);
        DefaultMeter meter = new DefaultMeter(id, type, measurements);
        try {
            PvaCustomMeter pvMeter = new PvaCustomMeter(pvName, meter, server);
            pvMeters.put(pvName, pvMeter);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create PVA custom meter for " + pvName, e);
        }
        return meter;
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.SECONDS;
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.NONE;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void checkNameCollision(String pvName) {
        if (pvMeters.containsKey(pvName)) {
            logger.log(Level.WARNING,
                    "PV name collision detected: " + pvName + " — using existing PV");
        }
    }
}
