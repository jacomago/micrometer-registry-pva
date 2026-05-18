package org.phoebus.pva.micrometer.internal;

import org.epics.pva.data.PVADouble;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVADisplay;
import org.epics.pva.data.nt.PVAScalar;
import org.epics.pva.data.nt.PVATimeStamp;

/**
 * Shared factory helpers for PVA structures used by internal meter wrappers.
 */
final class PvStructures {

    /** Alarm message set when a weak reference backing a meter has been garbage collected. */
    static final String GC_ALARM_MESSAGE = "Object reference garbage collected";

    private PvStructures() {}

    /**
     * Builds a new {@code NTScalar double} structure (initial value {@code 0.0}) with
     * standard EPICS alarm, timestamp, and display sub-structures.
     *
     * <p>The {@code display.description} field is populated from the meter's description
     * (if any) and {@code display.units} is populated from the meter's base unit (if any),
     * making the channel self-describing for EPICS clients such as Phoebus and {@code pvget}.
     *
     * <p>Used by all scalar meter wrappers ({@link PvaGauge}, {@link PvaMicrometerCounter},
     * {@link PvaFunctionCounter}, {@link PvaTimeGauge}).
     *
     * @param description human-readable description for the {@code display.description} field;
     *                    {@code null} is treated as an empty string
     * @param units       engineering-unit string for the {@code display.units} (EGU) field;
     *                    {@code null} is treated as an empty string
     */
    static PVAScalar<PVADouble> buildDoubleScalar(String description, String units) {
        try {
            return PVAScalar.doubleScalarBuilder(0.0)
                    .name("")
                    .alarm(new PVAAlarm())
                    .timeStamp(new PVATimeStamp())
                    .display(new PVADisplay(0.0, 0.0,
                            description != null ? description : "",
                            units != null ? units : "",
                            0, PVADisplay.Form.DEFAULT))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build NTScalar structure", e);
        }
    }
}
