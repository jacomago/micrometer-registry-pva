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
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;
import org.phoebus.pva.micrometer.internal.PvaDistributionSummary;
import org.phoebus.pva.micrometer.internal.PvaFunctionCounter;
import org.phoebus.pva.micrometer.internal.PvaFunctionTimer;
import org.phoebus.pva.micrometer.internal.PvaGauge;
import org.phoebus.pva.micrometer.internal.PvaLongTaskTimer;
import org.phoebus.pva.micrometer.internal.PvaMeter;
import org.phoebus.pva.micrometer.internal.PvaMicrometerCounter;
import org.phoebus.pva.micrometer.internal.PvaTimeGauge;
import org.phoebus.pva.micrometer.internal.PvaTimer;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A Micrometer {@link MeterRegistry} that publishes all registered meters as live EPICS
 * PV Access (PVA) channels.
 *
 * <p>Scalar meters ({@link Gauge}, {@link Counter}, {@link TimeGauge},
 * {@link FunctionCounter}) are mapped to {@code NTScalar double} channels
 * ({@code epics:nt/NTScalar:1.0}), which EPICS clients (Phoebus/CSS, {@code pvget},
 * soft-IOC displays) can read without custom data-binding.
 *
 * <p>A single background thread polls all registered meters at the interval configured
 * by {@link PvaMeterRegistryConfig#step()} and pushes updates to subscribed clients via
 * {@code ServerPV.update()}.  The alarm severity of each channel is set to
 * {@code NO_ALARM} on success or {@code INVALID} if the meter's value function throws.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Minimal — owns and starts its own PVAServer:
 * PvaMeterRegistry registry = new PvaMeterRegistry(PvaMeterRegistryConfig.DEFAULT, Clock.SYSTEM);
 *
 * // With a PVAServer the caller already manages:
 * PVAServer server = new PVAServer();
 * PvaMeterRegistry registry = new PvaMeterRegistry(config, Clock.SYSTEM, server);
 *
 * // Participate in a composite alongside Prometheus:
 * CompositeMeterRegistry composite = new CompositeMeterRegistry();
 * composite.add(prometheusMeterRegistry);
 * composite.add(pvaMeterRegistry);
 *
 * // Shutdown (stops poll loop; closes owned PVAServer):
 * registry.close();
 * }</pre>
 */
public class PvaMeterRegistry extends MeterRegistry {

    private static final Logger logger = Logger.getLogger(PvaMeterRegistry.class.getName());

    private final PvaMeterRegistryConfig config;
    private final PVAServer pvaServer;
    private final boolean ownsServer;
    private final ScheduledExecutorService pollExecutor;

    /** Maps each registered meter's ID to the PVA channel that backs it. */
    private final ConcurrentHashMap<Meter.Id, ServerPV> serverPVs = new ConcurrentHashMap<>();

    /**
     * Maps each registered meter's ID to the action that reads its value and pushes it
     * to the corresponding PVA channel.  Invoked by the poll loop on every tick.
     */
    private final ConcurrentHashMap<Meter.Id, Runnable> pollActions = new ConcurrentHashMap<>();

    /**
     * Tracks PV names that are currently registered, enabling fast O(1) collision detection
     * before attempting to create a duplicate channel on the server.
     */
    private final Set<String> registeredPvNames = ConcurrentHashMap.newKeySet();

    /**
     * Maps raw PVA channel names to their {@link ServerPV}s for non-meter channels
     * created by {@link #createRawPv(String, PVAStructure)} (e.g., {@code InfoPv},
     * {@code HealthPv}).
     */
    private final ConcurrentHashMap<String, ServerPV> rawPvs = new ConcurrentHashMap<>();

    /**
     * Additional tick listeners registered by non-meter PVs (e.g., {@code HealthPv}).
     * Invoked at the end of every poll tick, after all meter poll actions.
     */
    private final List<Runnable> tickListeners = new CopyOnWriteArrayList<>();

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a registry that owns and manages its own {@link PVAServer}.
     * The server is started immediately and closed when {@link #close()} is called.
     *
     * @param config registry configuration
     * @param clock  clock used by Micrometer for rate calculations
     */
    public PvaMeterRegistry(PvaMeterRegistryConfig config, Clock clock) {
        this(config, clock, createServer(), true);
    }

    /**
     * Creates a registry that shares a caller-supplied {@link PVAServer}.
     * The supplied server is <em>not</em> closed when {@link #close()} is called.
     *
     * @param config    registry configuration
     * @param clock     clock used by Micrometer for rate calculations
     * @param pvaServer an already-running PVA server to register channels on
     */
    public PvaMeterRegistry(PvaMeterRegistryConfig config, Clock clock, PVAServer pvaServer) {
        this(config, clock, pvaServer, false);
    }

    private PvaMeterRegistry(PvaMeterRegistryConfig config, Clock clock,
            PVAServer pvaServer, boolean ownsServer) {
        super(clock);
        this.config = config;
        this.pvaServer = pvaServer;
        this.ownsServer = ownsServer;
        this.pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pva-meter-poll");
            t.setDaemon(true);
            return t;
        });

        // Close the backing ServerPV whenever a meter is removed from the registry.
        config().onMeterRemoved(meter -> {
            String pvName = config.namingStrategy().pvName(meter.getId());
            registeredPvNames.remove(pvName);
            ServerPV pv = serverPVs.remove(meter.getId());
            if (pv != null) {
                pv.close();
            }
            pollActions.remove(meter.getId());
        });

        // Start the poll loop immediately, then repeat at each step interval.
        long stepMillis = config.step().toMillis();
        pollExecutor.scheduleAtFixedRate(this::poll, 0, stepMillis,
                TimeUnit.MILLISECONDS);
    }

    // -------------------------------------------------------------------------
    // Poll loop
    // -------------------------------------------------------------------------

    /** Invoked on every poll tick: runs all registered poll actions then tick listeners. */
    private void poll() {
        for (Runnable action : pollActions.values()) {
            try {
                action.run();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Unexpected error in PVA poll action", e);
            }
        }
        for (Runnable listener : tickListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Unexpected error in PVA tick listener", e);
            }
        }
    }

    /**
     * Registers a PVA channel for the given meter and schedules the poll action that
     * keeps it up to date.
     *
     * @param id         meter ID used as the map key
     * @param initialData the NTScalar structure returned by the wrapper's
     *                   {@code getInitialData()} method; passed to
     *                   {@code PVAServer.createPV()} as the type definition
     * @param pvName     PVA channel name derived from the meter ID via the naming strategy
     * @param pollAction lambda that calls {@code wrapper.updatePv(serverPV)}
     */
    private void registerPv(Meter.Id id, PVAStructure initialData,
            String pvName, ThrowingRunnable pollAction) {
        if (!registeredPvNames.add(pvName)) {
            throw new IllegalArgumentException(
                    "A PV with name '" + pvName + "' is already registered in this registry");
        }
        try {
            ServerPV serverPV = pvaServer.createPV(pvName, initialData);
            serverPVs.put(id, serverPV);
            pollActions.put(id, () -> {
                try {
                    pollAction.run(serverPV);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to update PV '" + pvName + "'", e);
                }
            });
        } catch (Exception e) {
            registeredPvNames.remove(pvName);
            throw new RuntimeException("Failed to create PVA channel '" + pvName + "'", e);
        }
    }

    /** Functional interface for a poll action that may throw a checked exception. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run(ServerPV serverPV) throws Exception;
    }

    // -------------------------------------------------------------------------
    // MeterRegistry abstract methods — scalar types (Task 3)
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@code NTScalar double} PVA channel and schedules a poll action that
     * reads the gauge value, sets alarm severity, and stamps the current wall-clock time
     * on every tick.
     */
    @Override
    protected <T> Gauge newGauge(Meter.Id id, T obj, ToDoubleFunction<T> valueFunction) {
        PvaGauge<T> gauge = new PvaGauge<>(id, obj, valueFunction);
        String pvName = config.namingStrategy().pvName(id);
        registerPv(id, gauge.getInitialData(), pvName, gauge::updatePv);
        return gauge;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@code NTScalar double} PVA channel backed by a thread-safe
     * {@link java.util.concurrent.atomic.DoubleAdder}.  The channel value is the
     * cumulative count since the counter was registered.
     */
    @Override
    protected Counter newCounter(Meter.Id id) {
        PvaMicrometerCounter counter = new PvaMicrometerCounter(id);
        String pvName = config.namingStrategy().pvName(id);
        registerPv(id, counter.getInitialData(), pvName, counter::updatePv);
        return counter;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@code NTScalar double} PVA channel whose value is always published in
     * <em>seconds</em>, normalised from the meter's declared {@link TimeUnit}.
     */
    @Override
    protected <T> TimeGauge newTimeGauge(Meter.Id id, T obj, TimeUnit valueFunctionUnit,
            ToDoubleFunction<T> valueFunction) {
        PvaTimeGauge<T> timeGauge = new PvaTimeGauge<>(id, obj, valueFunctionUnit, valueFunction);
        String pvName = config.namingStrategy().pvName(id);
        registerPv(id, timeGauge.getInitialData(), pvName, timeGauge::updatePv);
        return timeGauge;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@code NTScalar double} PVA channel; the count function is invoked on
     * every poll tick.  The referenced object is held via a weak reference.
     */
    @Override
    protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj,
            ToDoubleFunction<T> countFunction) {
        PvaFunctionCounter<T> counter = new PvaFunctionCounter<>(id, obj, countFunction);
        String pvName = config.namingStrategy().pvName(id);
        registerPv(id, counter.getInitialData(), pvName, counter::updatePv);
        return counter;
    }

    // -------------------------------------------------------------------------
    // MeterRegistry abstract methods — complex types (Task 4)
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@code micrometer:Timer:1.0} PVA channel that publishes
     * {@code count}, {@code totalTime} (seconds), and {@code max} (seconds) on every tick.
     */
    @Override
    protected Timer newTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig,
            PauseDetector pauseDetector) {
        PvaTimer timer = new PvaTimer(id, clock, distributionStatisticConfig, pauseDetector);
        String pvName = config.namingStrategy().pvName(id);
        registerPv(id, timer.getInitialData(), pvName, timer::updatePv);
        return timer;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@code micrometer:Summary:1.0} PVA channel that publishes
     * {@code count}, {@code total}, and {@code max} on every tick.
     */
    @Override
    protected DistributionSummary newDistributionSummary(Meter.Id id,
            DistributionStatisticConfig distributionStatisticConfig, double scale) {
        PvaDistributionSummary summary =
                new PvaDistributionSummary(id, clock, distributionStatisticConfig, scale);
        String pvName = config.namingStrategy().pvName(id);
        registerPv(id, summary.getInitialData(), pvName, summary::updatePv);
        return summary;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@code micrometer:Meter:1.0} PVA channel with one {@code double}
     * field per measurement, named by the measurement's
     * {@link io.micrometer.core.instrument.Statistic} enum constant (lower-cased).
     */
    @Override
    protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        PvaMeter meter = new PvaMeter(id, measurements);
        String pvName = config.namingStrategy().pvName(id);
        registerPv(id, meter.getInitialData(), pvName, meter::updatePv);
        return meter;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@code micrometer:FunctionTimer:1.0} PVA channel that publishes
     * {@code count} and {@code totalTime} (seconds) on every tick.
     * The referenced object is held via a weak reference.
     */
    @Override
    protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj,
            ToLongFunction<T> countFunction, ToDoubleFunction<T> totalTimeFunction,
            TimeUnit totalTimeFunctionUnit) {
        PvaFunctionTimer<T> ft = new PvaFunctionTimer<>(
                id, obj, countFunction, totalTimeFunction, totalTimeFunctionUnit);
        String pvName = config.namingStrategy().pvName(id);
        registerPv(id, ft.getInitialData(), pvName, ft::updatePv);
        return ft;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@code micrometer:LongTaskTimer:1.0} PVA channel that publishes
     * {@code activeTasks} and {@code duration} (seconds) on every tick.
     */
    @Override
    protected LongTaskTimer newLongTaskTimer(Meter.Id id,
            DistributionStatisticConfig distributionStatisticConfig) {
        PvaLongTaskTimer ltt = new PvaLongTaskTimer(id, clock);
        String pvName = config.namingStrategy().pvName(id);
        registerPv(id, ltt.getInitialData(), pvName, ltt::updatePv);
        return ltt;
    }

    /**
     * Returns {@link TimeUnit#SECONDS} — EPICS conventionally uses SI base units, and
     * publishing time values in seconds avoids unit-conversion knowledge in EPICS clients.
     */
    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.SECONDS;
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.NONE;
    }

    // -------------------------------------------------------------------------
    // Package-private support (tests and internal binder PVs)
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link ServerPV} registered under the given PVA channel name, or
     * {@code null} if no such channel exists.
     *
     * <p>Package-private to allow same-package tests to retrieve the backing ServerPV
     * without requiring reflection or a full PVA client round-trip.
     */
    ServerPV serverPv(String pvName) {
        // Check meter-backed PVs first.
        ServerPV found = serverPVs.values().stream()
                .filter(pv -> pvName.equals(pv.getName()))
                .findFirst()
                .orElse(null);
        if (found != null) {
            return found;
        }
        // Fall back to raw PVs (InfoPv, HealthPv, etc.).
        return rawPvs.get(pvName);
    }

    /**
     * Creates a raw PVA channel not backed by a Micrometer meter.
     *
     * <p>Used by {@code InfoPv} and {@code HealthPv} to publish non-meter data
     * (build metadata, aggregate health status) on the same PVAServer.
     * The channel is closed when the server shuts down.
     *
     * <p><em>Note:</em> public only because {@code InfoPv} and {@code HealthPv} live
     * in the {@code internal} sub-package; not intended for general consumer use.
     *
     * @param pvName      PVA channel name
     * @param initialData PVA structure defining the channel type and initial values
     * @return the newly created {@link ServerPV}
     * @throws RuntimeException if the channel cannot be created
     */
    public ServerPV createRawPv(String pvName, PVAStructure initialData) {
        try {
            ServerPV pv = pvaServer.createPV(pvName, initialData);
            rawPvs.put(pvName, pv);
            return pv;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create raw PVA channel '" + pvName + "'", e);
        }
    }

    /**
     * Registers a listener that is invoked on every poll tick, after all meter poll
     * actions have run.
     *
     * <p>Used by {@code HealthPv} to push updated health status to subscribed clients
     * on each poll tick without requiring a Micrometer meter.
     *
     * <p><em>Note:</em> public only because {@code HealthPv} lives in the
     * {@code internal} sub-package; not intended for general consumer use.
     *
     * @param listener the action to invoke on each tick
     */
    public void registerTickListener(Runnable listener) {
        tickListeners.add(listener);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Shuts down the poll executor, closes all PVA channels (sending
     * {@code CMD_DESTROY_CHANNEL} to connected clients), and — if this registry owns its
     * {@link PVAServer} — closes the server.
     *
     * <p>Meters that were registered before this call are still accessible via the
     * Micrometer API after {@code close()} returns, but their PVA channels will be gone.
     */
    @Override
    public void close() {
        pollExecutor.shutdown();
        try {
            if (!pollExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                pollExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close every ServerPV so clients receive CMD_DESTROY_CHANNEL.
        serverPVs.values().forEach(pv -> {
            try {
                pv.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing ServerPV during registry shutdown", e);
            }
        });
        serverPVs.clear();
        rawPvs.clear();
        pollActions.clear();
        registeredPvNames.clear();
        tickListeners.clear();

        if (ownsServer) {
            try {
                pvaServer.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing PVAServer during registry shutdown", e);
            }
        }

        super.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PVAServer createServer() {
        try {
            return new PVAServer();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start PVAServer for PvaMeterRegistry", e);
        }
    }
}
