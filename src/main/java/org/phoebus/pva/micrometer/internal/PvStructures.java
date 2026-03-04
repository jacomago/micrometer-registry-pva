package org.phoebus.pva.micrometer.internal;

import org.epics.pva.data.PVADouble;
import org.epics.pva.data.nt.PVAAlarm;
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
     * standard EPICS alarm and timestamp sub-structures.
     *
     * <p>Used by all scalar meter wrappers ({@link PvaGauge}, {@link PvaMicrometerCounter},
     * {@link PvaFunctionCounter}, {@link PvaTimeGauge}).
     */
    static PVAScalar<PVADouble> buildDoubleScalar() {
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
}
