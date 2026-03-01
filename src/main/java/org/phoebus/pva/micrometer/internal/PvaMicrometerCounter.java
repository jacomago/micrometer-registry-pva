package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.AbstractMeter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Statistic;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAAlarm.AlarmStatus;
import org.epics.pva.data.nt.PVAScalar;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Wraps a Micrometer {@link Counter} as a PVA {@code NTScalar double} channel.
 *
 * <p>The channel value is the cumulative count since the counter was created.  It only
 * ever increases (or stays the same), consistent with the Micrometer counter contract.
 *
 * <p>Increments are applied atomically via a {@link DoubleAdder}, which provides
 * thread-safe, contention-friendly accumulation without global locking.
 *
 * <p>This class is package-internal; use {@link PvaMeterRegistry} to register counters.
 */
public final class PvaMicrometerCounter extends AbstractMeter implements Counter {

    private final DoubleAdder count = new DoubleAdder();

    /** The mutable NTScalar structure updated in-place on every poll tick. */
    private final PVAScalar<PVADouble> data;

    /** Cached reference to the "value" field for efficient in-place updates. */
    private final PVADouble valueField;

    /** Cached reference to the "alarm" sub-structure for efficient in-place updates. */
    private final PVAAlarm alarmField;

    /**
     * Creates a counter wrapper.
     *
     * @param id meter identity (name + tags)
     */
    public PvaMicrometerCounter(Meter.Id id) {
        super(id);
        this.data = PVAScalar.doubleScalarBuilder(0.0)
                .name("")
                .alarm(new PVAAlarm())
                .timeStamp(new PVATimeStamp())
                .build();
        this.valueField = data.get("value");
        this.alarmField = data.get("alarm");
    }

    /**
     * Adds {@code amount} to the cumulative counter value.
     *
     * @param amount the amount to add; must be non-negative
     */
    @Override
    public void increment(double amount) {
        count.add(amount);
    }

    /** Returns the cumulative count since this counter was created. */
    @Override
    public double count() {
        return count.sum();
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
     * Reads the current cumulative count, updates the NTScalar structure (value, alarm,
     * timestamp), and pushes the result to subscribed PVA clients.
     *
     * @param serverPV the PVA server PV to update; must have been created with the structure
     *                 returned by {@link #getInitialData()}
     * @throws Exception if {@code serverPV.update()} fails
     */
    public void updatePv(ServerPV serverPV) throws Exception {
        try {
            valueField.set(count());
            alarmField.set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Error reading counter value";
            alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, msg);
        }
        PVATimeStamp.set(data, Instant.now());
        serverPV.update(data);
    }
}

