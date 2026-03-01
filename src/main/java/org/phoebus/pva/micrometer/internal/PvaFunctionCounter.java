package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.AbstractMeter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
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
 * Wraps a Micrometer {@link FunctionCounter} as a PVA {@code NTScalar double} channel.
 *
 * <p>Unlike {@link PvaMicrometerCounter}, the count is derived by applying a user-supplied
 * function to a referenced object on every poll tick.  The referenced object is held via a
 * {@link WeakReference} to avoid preventing garbage collection of the instrumented object.
 *
 * <p>The function result is expected to be monotonically increasing (consistent with the
 * Micrometer {@link FunctionCounter} contract).
 *
 * <p>This class is package-internal; use {@link PvaMeterRegistry} to register function counters.
 *
 * @param <T> the type of the observed object
 */
public final class PvaFunctionCounter<T> extends AbstractMeter implements FunctionCounter {

    private final WeakReference<T> ref;
    private final ToDoubleFunction<T> countFunction;

    /** The mutable NTScalar structure updated in-place on every poll tick. */
    private final PVAScalar<PVADouble> data;

    /** Cached reference to the "value" field for efficient in-place updates. */
    private final PVADouble valueField;

    /** Cached reference to the "alarm" sub-structure for efficient in-place updates. */
    private final PVAAlarm alarmField;

    /**
     * Creates a function-counter wrapper.
     *
     * @param id            meter identity (name + tags)
     * @param obj           the object whose state is observed; may be {@code null}
     * @param countFunction function applied to {@code obj} to obtain the current count
     */
    public PvaFunctionCounter(Meter.Id id, T obj, ToDoubleFunction<T> countFunction) {
        super(id);
        this.ref = new WeakReference<>(obj);
        this.countFunction = countFunction;
        this.data = buildInitialData();
        this.valueField = data.get("value");
        this.alarmField = data.get("alarm");
    }

    private static PVAScalar<PVADouble> buildInitialData() {
        try {
            return PVAScalar.doubleScalarBuilder(0.0)
                    .name("")
                    .alarm(new PVAAlarm())
                    .timeStamp(new PVATimeStamp())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build NTScalar structure", e);
        }
    }

    /**
     * Returns the current count by applying the count function to the observed object.
     * Returns {@link Double#NaN} if the weak reference has been cleared.
     */
    @Override
    public double count() {
        T obj = ref.get();
        return obj != null ? countFunction.applyAsDouble(obj) : Double.NaN;
    }

    @Override
    public Iterable<Measurement> measure() {
        return Collections.singletonList(new Measurement(this::count, Statistic.COUNT));
    }

    /**
     * Returns the initial {@code NTScalar double} structure used when registering this PV
     * with the {@code PVAServer}.  The same instance is updated in-place on every poll tick.
     */
    public PVAScalar<PVADouble> getInitialData() {
        return data;
    }

    /**
     * Reads the current count, updates the NTScalar structure (value, alarm, timestamp),
     * and pushes the result to subscribed PVA clients.
     *
     * @param serverPV the PVA server PV to update; must have been created with the structure
     *                 returned by {@link #getInitialData()}
     * @throws Exception if {@code serverPV.update()} fails
     */
    public void updatePv(ServerPV serverPV) throws Exception {
        try {
            double current = count();
            valueField.set(current);
            if (Double.isNaN(current)) {
                alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER,
                        "Object reference garbage collected");
            } else {
                alarmField.set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Error reading function counter";
            alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, msg);
        }
        PVATimeStamp.set(data, Instant.now());
        serverPV.update(data);
    }
}
