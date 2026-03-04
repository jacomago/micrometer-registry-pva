package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.AbstractMeter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.TimeGauge;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAAlarm.AlarmStatus;
import org.epics.pva.data.nt.PVAScalar;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * Wraps a Micrometer {@link TimeGauge} as a PVA {@code NTScalar double} channel.
 *
 * <p>The value is always published in <em>seconds</em>, normalised from the meter's
 * declared {@link TimeUnit}.  This makes the PV directly usable by EPICS clients
 * without requiring unit-conversion knowledge.
 *
 * <p>This class is package-internal; use {@link PvaMeterRegistry} to register time gauges.
 *
 * @param <T> the type of the observed object
 */
public final class PvaTimeGauge<T> extends AbstractMeter implements TimeGauge {

    private final WeakReference<T> ref;
    private final TimeUnit valueFunctionUnit;
    private final ToDoubleFunction<T> valueFunction;

    /** The mutable NTScalar structure updated in-place on every poll tick. */
    private final PVAScalar<PVADouble> data;

    /** Cached reference to the "value" field for efficient in-place updates. */
    private final PVADouble valueField;

    /** Cached reference to the "alarm" sub-structure for efficient in-place updates. */
    private final PVAAlarm alarmField;

    /**
     * Creates a time-gauge wrapper.
     *
     * @param id                meter identity (name + tags)
     * @param obj               the object whose state is observed; may be {@code null}
     * @param valueFunctionUnit time unit of the values returned by {@code valueFunction}
     * @param valueFunction     function applied to {@code obj} to obtain the current value
     */
    public PvaTimeGauge(Meter.Id id, T obj, TimeUnit valueFunctionUnit,
            ToDoubleFunction<T> valueFunction) {
        super(id);
        this.ref = new WeakReference<>(obj);
        this.valueFunctionUnit = valueFunctionUnit;
        this.valueFunction = valueFunction;
        this.data = buildInitialData();
        this.valueField = data.get("value");
        this.alarmField = data.get("alarm");
    }

    private static PVAScalar<PVADouble> buildInitialData() {
        return PvStructures.buildDoubleScalar();
    }

    @Override
    public TimeUnit baseTimeUnit() {
        return valueFunctionUnit;
    }

    /**
     * Returns the current value in the meter's declared base time unit.
     * Returns {@link Double#NaN} if the weak reference has been cleared.
     */
    @Override
    public double value() {
        return value(valueFunctionUnit);
    }

    /**
     * Returns the current value converted to {@code unit}.
     * Returns {@link Double#NaN} if the weak reference has been cleared.
     */
    @Override
    public double value(TimeUnit unit) {
        T obj = ref.get();
        if (obj == null) {
            return Double.NaN;
        }
        double raw = valueFunction.applyAsDouble(obj);
        // Convert using nanosecond arithmetic to preserve double precision,
        // avoiding the long-cast truncation in TimeUnit.convert().
        double nanos = raw * valueFunctionUnit.toNanos(1);
        return nanos / unit.toNanos(1);
    }

    @Override
    public Iterable<Measurement> measure() {
        return Collections.singletonList(
                new Measurement(() -> value(TimeUnit.NANOSECONDS), Statistic.VALUE));
    }

    /**
     * Returns the initial {@code NTScalar double} structure used when registering this PV
     * with the {@code PVAServer}.  The same instance is updated in-place on every poll tick.
     */
    public PVAScalar<PVADouble> getInitialData() {
        return data;
    }

    /**
     * Reads the current value (normalised to <em>seconds</em>), updates alarm and timestamp,
     * and pushes the result to subscribed PVA clients.
     *
     * @param serverPV the PVA server PV to update; must have been created with the structure
     *                 returned by {@link #getInitialData()}
     * @throws Exception if {@code serverPV.update()} fails
     */
    public void updatePv(ServerPV serverPV) throws Exception {
        try {
            double seconds = value(TimeUnit.SECONDS);
            valueField.set(seconds);
            if (Double.isNaN(seconds)) {
                alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER,
                        PvStructures.GC_ALARM_MESSAGE);
            } else {
                alarmField.set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Error reading time gauge value";
            alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, msg);
        }
        PVATimeStamp.set(data, Instant.now());
        serverPV.update(data);
    }
}
