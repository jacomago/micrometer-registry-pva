package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.AbstractMeter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import org.epics.pva.data.PVAData;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAAlarm.AlarmStatus;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Catch-all wrapper for custom Micrometer {@link Meter} types that do not map to a
 * standard meter type.  Published as a custom PVA structure with type name
 * {@code micrometer:Meter:1.0}.
 *
 * <p>The PV structure contains one {@code double} field per {@link Measurement}, named
 * by the measurement's {@link io.micrometer.core.instrument.Statistic} enum constant
 * (lower-cased, e.g.&nbsp;{@code "value"}, {@code "count"}, {@code "total_time"}).
 * Standard EPICS metadata fields ({@code alarm}, {@code timeStamp}) are appended.
 *
 * <p>The structure layout is fixed at construction time and never changes, satisfying
 * the {@code ServerPV.update()} immutability requirement.
 *
 * <p>This class is package-internal; use
 * {@link org.phoebus.pva.micrometer.PvaMeterRegistry} to register custom meters.
 */
public final class PvaMeter extends AbstractMeter implements Meter {

    /** Snapshot of measurements at construction time — iterated on every poll tick. */
    private final List<Measurement> measurements;

    /** The mutable structure updated in-place on every poll tick. */
    private final PVAStructure data;

    /** Cached field references aligned with {@link #measurements} by index. */
    private final List<PVADouble> measurementFields;

    private final PVAAlarm alarmField;

    /**
     * Creates a catch-all meter wrapper.
     *
     * <p>The {@code measurements} iterable is consumed once during construction and
     * stored internally.  It is not re-iterated; instead, the cached {@link Measurement}
     * objects are invoked on every poll tick.
     *
     * @param id           meter identity (name + tags)
     * @param measurements measurements provided by the Micrometer registry SPI;
     *                     each must have a unique {@link io.micrometer.core.instrument.Statistic}
     */
    public PvaMeter(Meter.Id id, Iterable<Measurement> measurements) {
        super(id);
        this.measurements = new ArrayList<>();
        measurements.forEach(this.measurements::add);
        this.data = buildInitialData(this.measurements);
        this.measurementFields = buildFieldCache(this.measurements, this.data);
        this.alarmField = data.get("alarm");
    }

    private static PVAStructure buildInitialData(List<Measurement> measurements) {
        List<PVAData> fields = new ArrayList<>(measurements.size() + 2);
        for (Measurement m : measurements) {
            fields.add(new PVADouble(fieldName(m), 0.0));
        }
        fields.add(new PVAAlarm());
        fields.add(new PVATimeStamp());
        return new PVAStructure("", "micrometer:Meter:1.0", fields);
    }

    private static List<PVADouble> buildFieldCache(List<Measurement> measurements,
            PVAStructure data) {
        List<PVADouble> cache = new ArrayList<>(measurements.size());
        for (Measurement m : measurements) {
            cache.add(data.get(fieldName(m)));
        }
        return cache;
    }

    /**
     * Derives a PVA field name from a measurement's statistic.
     * {@link io.micrometer.core.instrument.Statistic} names are converted to lower-case,
     * e.g.&nbsp;{@code TOTAL_TIME → "total_time"}.
     */
    static String fieldName(Measurement m) {
        return m.getStatistic().name().toLowerCase();
    }

    // -------------------------------------------------------------------------
    // Meter implementation
    // -------------------------------------------------------------------------

    @Override
    public Iterable<Measurement> measure() {
        return measurements;
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
     * Reads each measurement's current value, updates the PVA structure, and pushes to clients.
     *
     * <p>If any measurement supplier throws, the alarm is set to {@code INVALID}.
     *
     * @param serverPV the PVA channel to update
     * @throws Exception if {@code serverPV.update()} fails
     */
    public void updatePv(ServerPV serverPV) throws Exception {
        try {
            for (int i = 0; i < measurements.size(); i++) {
                measurementFields.get(i).set(measurements.get(i).getValue());
            }
            alarmField.set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage()
                    : "Error reading custom meter value";
            alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, msg);
        }
        PVATimeStamp.set(data, Instant.now());
        serverPV.update(data);
    }
}
