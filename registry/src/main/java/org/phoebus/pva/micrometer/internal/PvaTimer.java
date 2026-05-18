package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.AbstractTimer;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVALong;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAAlarm.AlarmStatus;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Wraps a Micrometer {@link io.micrometer.core.instrument.Timer} as a custom PVA structure
 * with type name {@code micrometer:Timer:1.0}.
 *
 * <p>The PV structure contains four fields published on every poll tick:
 * <ul>
 *   <li>{@code count} — cumulative number of timings recorded (long)</li>
 *   <li>{@code totalTime} — cumulative total duration in seconds (double)</li>
 *   <li>{@code max} — maximum single duration seen in seconds (double)</li>
 *   <li>{@code alarm} / {@code timeStamp} — standard EPICS metadata</li>
 * </ul>
 *
 * <p>This class is package-internal; use {@link org.phoebus.pva.micrometer.PvaMeterRegistry}
 * to register timers.
 */
public final class PvaTimer extends AbstractTimer {

    private final LongAdder count = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final AtomicLong maxNanos = new AtomicLong(0L);

    /** The mutable structure updated in-place on every poll tick. */
    private final PVAStructure data;

    /** Cached field references for efficient in-place updates. */
    private final PVALong countField;
    private final PVADouble totalTimeField;
    private final PVADouble maxField;
    private final PVAAlarm alarmField;

    /**
     * Creates a timer wrapper.
     *
     * @param id                          meter identity (name + tags)
     * @param clock                       Micrometer clock
     * @param distributionStatisticConfig histogram/percentile config (typically NONE)
     * @param pauseDetector               pause detector for GC-pause compensation
     */
    public PvaTimer(Meter.Id id, Clock clock,
            DistributionStatisticConfig distributionStatisticConfig,
            PauseDetector pauseDetector) {
        super(id, clock, distributionStatisticConfig, pauseDetector, TimeUnit.SECONDS, false);
        this.data = buildInitialData();
        this.countField = data.get("count");
        this.totalTimeField = data.get("totalTime");
        this.maxField = data.get("max");
        this.alarmField = data.get("alarm");
    }

    private static PVAStructure buildInitialData() {
        return new PVAStructure("", "micrometer:Timer:1.0",
                new PVALong("count", false, 0L),
                new PVADouble("totalTime", 0.0),
                new PVADouble("max", 0.0),
                new PVAAlarm(),
                new PVATimeStamp());
    }

    @Override
    protected void recordNonNegative(long amount, TimeUnit unit) {
        long nanos = unit.toNanos(amount);
        count.increment();
        totalNanos.add(nanos);
        // CAS loop to update running maximum without locking.
        long prev;
        do {
            prev = maxNanos.get();
        } while (nanos > prev && !maxNanos.compareAndSet(prev, nanos));
    }

    @Override
    public long count() {
        return count.sum();
    }

    @Override
    public double totalTime(TimeUnit unit) {
        return (double) totalNanos.sum() / unit.toNanos(1);
    }

    @Override
    public double max(TimeUnit unit) {
        return (double) maxNanos.get() / unit.toNanos(1);
    }

    /**
     * Returns the initial PVA structure used when registering this PV with the server.
     * The same instance is updated in-place on every poll tick.
     */
    public PVAStructure getInitialData() {
        return data;
    }

    /**
     * Reads current timer statistics, updates the PVA structure, and pushes to clients.
     *
     * @param serverPV the PVA channel to update
     * @throws Exception if {@code serverPV.update()} fails
     */
    public void updatePv(ServerPV serverPV) throws Exception {
        try {
            countField.set(count());
            totalTimeField.set(totalTime(TimeUnit.SECONDS));
            maxField.set(max(TimeUnit.SECONDS));
            alarmField.set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Error reading timer value";
            alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, msg);
        }
        PVATimeStamp.set(data, Instant.now());
        serverPV.update(data);
    }
}
