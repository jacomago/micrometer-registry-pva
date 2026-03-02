package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.AbstractMeter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAAlarm.AlarmStatus;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * Wraps a Micrometer {@link FunctionTimer} as a custom PVA structure with type name
 * {@code micrometer:FunctionTimer:1.0}.
 *
 * <p>The PV structure contains four fields published on every poll tick:
 * <ul>
 *   <li>{@code count} — event count derived from the count function (double)</li>
 *   <li>{@code totalTime} — cumulative duration in seconds (double)</li>
 *   <li>{@code alarm} / {@code timeStamp} — standard EPICS metadata</li>
 * </ul>
 *
 * <p>The observed object is held via a {@link WeakReference} to avoid preventing garbage
 * collection.  If the reference is cleared, the alarm is set to {@code INVALID}.
 *
 * <p>This class is package-internal; use
 * {@link org.phoebus.pva.micrometer.PvaMeterRegistry} to register function timers.
 *
 * @param <T> the type of the observed object
 */
public final class PvaFunctionTimer<T> extends AbstractMeter implements FunctionTimer {

    private final WeakReference<T> ref;
    private final ToLongFunction<T> countFunction;
    private final ToDoubleFunction<T> totalTimeFunction;
    private final TimeUnit totalTimeFunctionUnit;

    /** The mutable structure updated in-place on every poll tick. */
    private final PVAStructure data;

    /** Cached field references for efficient in-place updates. */
    private final PVADouble countField;
    private final PVADouble totalTimeField;
    private final PVAAlarm alarmField;

    /**
     * Creates a function-timer wrapper.
     *
     * @param id                    meter identity (name + tags)
     * @param obj                   the state object; may be {@code null}
     * @param countFunction         function that extracts the event count from {@code obj}
     * @param totalTimeFunction     function that extracts the total elapsed time from {@code obj}
     * @param totalTimeFunctionUnit time unit of the value returned by {@code totalTimeFunction}
     */
    public PvaFunctionTimer(Meter.Id id, T obj,
            ToLongFunction<T> countFunction,
            ToDoubleFunction<T> totalTimeFunction,
            TimeUnit totalTimeFunctionUnit) {
        super(id);
        this.ref = new WeakReference<>(obj);
        this.countFunction = countFunction;
        this.totalTimeFunction = totalTimeFunction;
        this.totalTimeFunctionUnit = totalTimeFunctionUnit;
        this.data = buildInitialData();
        this.countField = data.get("count");
        this.totalTimeField = data.get("totalTime");
        this.alarmField = data.get("alarm");
    }

    private static PVAStructure buildInitialData() {
        return new PVAStructure("", "micrometer:FunctionTimer:1.0",
                new PVADouble("count", 0.0),
                new PVADouble("totalTime", 0.0),
                new PVAAlarm(),
                new PVATimeStamp());
    }

    // -------------------------------------------------------------------------
    // FunctionTimer implementation
    // -------------------------------------------------------------------------

    @Override
    public double count() {
        T obj = ref.get();
        return obj != null ? (double) countFunction.applyAsLong(obj) : Double.NaN;
    }

    @Override
    public double totalTime(TimeUnit unit) {
        T obj = ref.get();
        if (obj == null) {
            return Double.NaN;
        }
        double rawTotal = totalTimeFunction.applyAsDouble(obj);
        // Convert from totalTimeFunctionUnit to the requested unit via nanoseconds.
        return rawTotal * totalTimeFunctionUnit.toNanos(1) / (double) unit.toNanos(1);
    }

    @Override
    public TimeUnit baseTimeUnit() {
        return TimeUnit.SECONDS;
    }

    @Override
    public Iterable<Measurement> measure() {
        return Arrays.asList(
                new Measurement(this::count, Statistic.COUNT),
                new Measurement(() -> totalTime(baseTimeUnit()), Statistic.TOTAL_TIME));
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
     * Reads current count and totalTime, updates the PVA structure, and pushes to clients.
     *
     * <p>If the weak reference has been cleared or a function throws, the alarm is set to
     * {@code INVALID} and the last published values are retained.
     *
     * @param serverPV the PVA channel to update
     * @throws Exception if {@code serverPV.update()} fails
     */
    public void updatePv(ServerPV serverPV) throws Exception {
        try {
            double currentCount = count();
            double currentTotalTime = totalTime(TimeUnit.SECONDS);
            if (Double.isNaN(currentCount) || Double.isNaN(currentTotalTime)) {
                alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER,
                        "Object reference garbage collected");
            } else {
                countField.set(currentCount);
                totalTimeField.set(currentTotalTime);
                alarmField.set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage()
                    : "Error reading function timer value";
            alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, msg);
        }
        PVATimeStamp.set(data, Instant.now());
        serverPV.update(data);
    }
}
