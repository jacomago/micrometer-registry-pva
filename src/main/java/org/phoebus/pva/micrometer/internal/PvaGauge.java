package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.AbstractMeter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Statistic;
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
import java.util.function.ToDoubleFunction;

/**
 * Wraps a Micrometer {@link Gauge} as a PVA {@code NTScalar double} channel.
 *
 * <p>The value supplier is invoked on every poll tick.  The result is written to a
 * privately-owned {@code PVAScalar<PVADouble>} structure whose alarm severity is set to
 * {@code NO_ALARM} on success or {@code INVALID} if the supplier throws.  The current
 * wall-clock time is stamped on every update.
 *
 * <p>The supplier object is held via a {@link WeakReference} to avoid preventing garbage
 * collection of the instrumented object.  If the reference is cleared, {@link #value()}
 * returns {@link Double#NaN} and the alarm is set to {@code INVALID}.
 *
 * <p>This class is package-internal; use {@link PvaMeterRegistry} to register gauges.
 *
 * @param <T> the type of the observed object
 */
public final class PvaGauge<T> extends AbstractMeter implements Gauge {

    private final WeakReference<T> ref;
    private final ToDoubleFunction<T> valueFunction;

    /** The mutable NTScalar structure updated in-place on every poll tick. */
    private final PVAScalar<PVADouble> data;

    /** Cached reference to the "value" field for efficient in-place updates. */
    private final PVADouble valueField;

    /** Cached reference to the "alarm" sub-structure for efficient in-place updates. */
    private final PVAAlarm alarmField;

    /**
     * Creates a gauge wrapper.
     *
     * @param id            meter identity (name + tags)
     * @param obj           the object whose state is observed; may be {@code null}
     * @param valueFunction function applied to {@code obj} to obtain the current value
     */
    public PvaGauge(Meter.Id id, T obj, ToDoubleFunction<T> valueFunction) {
        super(id);
        this.ref = new WeakReference<>(obj);
        this.valueFunction = valueFunction;
        this.data = PVAScalar.doubleScalarBuilder(0.0)
                .name("")
                .alarm(new PVAAlarm())
                .timeStamp(new PVATimeStamp())
                .build();
        this.valueField = data.get("value");
        this.alarmField = data.get("alarm");
    }

    /**
     * Returns the current gauge value by applying the value function to the observed object.
     * Returns {@link Double#NaN} if the weak reference has been cleared.
     */
    @Override
    public double value() {
        T obj = ref.get();
        return obj != null ? valueFunction.applyAsDouble(obj) : Double.NaN;
    }

    @Override
    public Iterable<Measurement> measure() {
        return Collections.singletonList(new Measurement(this::value, Statistic.VALUE));
    }

    /**
     * Returns the initial {@code NTScalar double} structure used when registering this PV
     * with the {@code PVAServer}.  The same instance is updated in-place on every poll tick.
     */
    public PVAScalar<PVADouble> getInitialData() {
        return data;
    }

    /**
     * Reads the current gauge value, updates the NTScalar structure (value, alarm, timestamp),
     * and pushes the result to subscribed PVA clients via {@code serverPV.update()}.
     *
     * <p>If the value function throws or the weak reference is cleared, the alarm severity
     * is set to {@code INVALID} and the last good value is retained.
     *
     * @param serverPV the PVA server PV to update; must have been created with the structure
     *                 returned by {@link #getInitialData()}
     * @throws Exception if {@code serverPV.update()} fails
     */
    public void updatePv(ServerPV serverPV) throws Exception {
        try {
            double current = value();
            valueField.set(current);
            if (Double.isNaN(current)) {
                alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER,
                        "Object reference garbage collected");
            } else {
                alarmField.set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Error reading gauge value";
            alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, msg);
        }
        PVATimeStamp.set(data, Instant.now());
        serverPV.update(data);
    }
}

