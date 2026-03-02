package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.AbstractMeter;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVALong;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAAlarm.AlarmStatus;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a Micrometer {@link LongTaskTimer} as a custom PVA structure with type name
 * {@code micrometer:LongTaskTimer:1.0}.
 *
 * <p>The PV structure contains four fields published on every poll tick:
 * <ul>
 *   <li>{@code activeTasks} — number of tasks currently in flight (long)</li>
 *   <li>{@code duration} — total elapsed time of all active tasks in seconds (double)</li>
 *   <li>{@code alarm} / {@code timeStamp} — standard EPICS metadata</li>
 * </ul>
 *
 * <p>Each call to {@link #start()} begins a new task and returns a {@link Sample} that
 * the caller holds until the operation completes.  Calling {@link Sample#stop()} removes
 * the task from the active set and returns its duration in nanoseconds.
 *
 * <p>This class is package-internal; use
 * {@link org.phoebus.pva.micrometer.PvaMeterRegistry} to register long task timers.
 */
public final class PvaLongTaskTimer extends AbstractMeter implements LongTaskTimer {

    private final Clock clock;

    /** Maps each active task's ID to its start time in nanoseconds (from {@code clock.monotonicTime()}). */
    private final ConcurrentHashMap<Long, Long> tasks = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(0L);

    /** The mutable structure updated in-place on every poll tick. */
    private final PVAStructure data;

    /** Cached field references for efficient in-place updates. */
    private final PVALong activeTasksField;
    private final PVADouble durationField;
    private final PVAAlarm alarmField;

    /**
     * Creates a long-task-timer wrapper.
     *
     * @param id    meter identity (name + tags)
     * @param clock Micrometer clock used to measure elapsed time
     */
    public PvaLongTaskTimer(Meter.Id id, Clock clock) {
        super(id);
        this.clock = clock;
        this.data = buildInitialData();
        this.activeTasksField = data.get("activeTasks");
        this.durationField = data.get("duration");
        this.alarmField = data.get("alarm");
    }

    private static PVAStructure buildInitialData() {
        return new PVAStructure("", "micrometer:LongTaskTimer:1.0",
                new PVALong("activeTasks", false, 0L),
                new PVADouble("duration", 0.0),
                new PVAAlarm(),
                new PVATimeStamp());
    }

    // -------------------------------------------------------------------------
    // LongTaskTimer implementation
    // -------------------------------------------------------------------------

    @Override
    public Sample start() {
        long id = nextId.getAndIncrement();
        long startNanos = clock.monotonicTime();
        tasks.put(id, startNanos);
        return new SampleImpl(id, startNanos);
    }

    @Override
    public int activeTasks() {
        return tasks.size();
    }

    @Override
    public double duration(TimeUnit unit) {
        long now = clock.monotonicTime();
        long totalNanos = 0L;
        for (long startNanos : tasks.values()) {
            totalNanos += now - startNanos;
        }
        return (double) totalNanos / unit.toNanos(1);
    }

    @Override
    public double max(TimeUnit unit) {
        long now = clock.monotonicTime();
        long maxNanos = 0L;
        for (long startNanos : tasks.values()) {
            long elapsed = now - startNanos;
            if (elapsed > maxNanos) {
                maxNanos = elapsed;
            }
        }
        return (double) maxNanos / unit.toNanos(1);
    }

    @Override
    public TimeUnit baseTimeUnit() {
        return TimeUnit.SECONDS;
    }

    /**
     * Returns a minimal histogram snapshot. This registry does not support percentiles
     * or SLO buckets on LongTaskTimer; histograms are out of scope per the design plan.
     */
    @Override
    public HistogramSnapshot takeSnapshot() {
        return HistogramSnapshot.empty(
                (long) activeTasks(),
                duration(TimeUnit.NANOSECONDS),
                max(TimeUnit.NANOSECONDS));
    }

    @Override
    public Iterable<Measurement> measure() {
        return Arrays.asList(
                new Measurement(() -> (double) activeTasks(), Statistic.ACTIVE_TASKS),
                new Measurement(() -> duration(baseTimeUnit()), Statistic.DURATION));
    }

    // -------------------------------------------------------------------------
    // PVA support
    // -------------------------------------------------------------------------

    /**
     * Returns the initial PVA structure used when registering this PV with the server.
     * The same instance is updated in-place on every poll tick.
     */
    public PVAStructure getInitialData() {
        return data;
    }

    /**
     * Reads current active task statistics, updates the PVA structure, and pushes to clients.
     *
     * @param serverPV the PVA channel to update
     * @throws Exception if {@code serverPV.update()} fails
     */
    public void updatePv(ServerPV serverPV) throws Exception {
        try {
            activeTasksField.set((long) activeTasks());
            durationField.set(duration(TimeUnit.SECONDS));
            alarmField.set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage()
                    : "Error reading long task timer value";
            alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, msg);
        }
        PVATimeStamp.set(data, Instant.now());
        serverPV.update(data);
    }

    // -------------------------------------------------------------------------
    // Sample implementation
    // -------------------------------------------------------------------------

    private final class SampleImpl extends LongTaskTimer.Sample {

        private final long id;
        private final long startNanos;

        SampleImpl(long id, long startNanos) {
            this.id = id;
            this.startNanos = startNanos;
        }

        @Override
        public long stop() {
            tasks.remove(id);
            return clock.monotonicTime() - startNanos;
        }

        @Override
        public double duration(TimeUnit unit) {
            Long taskStart = tasks.get(id);
            if (taskStart == null) {
                // Already stopped — return 0 rather than negative elapsed.
                return 0.0;
            }
            return (double) (clock.monotonicTime() - taskStart) / unit.toNanos(1);
        }
    }
}
