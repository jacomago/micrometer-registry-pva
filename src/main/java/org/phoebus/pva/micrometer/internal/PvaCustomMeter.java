package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import org.epics.pva.data.PVAData;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PVA wrapper for catch-all custom Micrometer {@link Meter} instances.
 *
 * <p>Publishes a custom {@code micrometer:Meter:1.0} structure with one {@code double} field
 * per {@link Measurement}, named by the {@link Statistic} enum (lower-cased).
 */
public final class PvaCustomMeter implements PvaMeter {

    private static final Logger logger = Logger.getLogger(PvaCustomMeter.class.getName());

    private final ServerPV serverPV;
    private final List<PVADouble> measurementFields;
    private final PVATimeStamp timeStampField;
    private final PVAStructure localStruct;
    private final Meter meter;

    public PvaCustomMeter(String pvName, Meter meter, PVAServer server) throws Exception {
        this.meter = meter;

        measurementFields = new ArrayList<>();
        List<PVAData> fields = new ArrayList<>();

        for (Measurement m : meter.measure()) {
            String fieldName = m.getStatistic().name().toLowerCase();
            PVADouble field = new PVADouble(fieldName, m.getValue());
            measurementFields.add(field);
            fields.add(field);
        }

        timeStampField = new PVATimeStamp(Instant.now());
        fields.add(timeStampField);

        localStruct = new PVAStructure(pvName, "micrometer:Meter:1.0", fields);
        serverPV = server.createPV(pvName, localStruct);
    }

    @Override
    public void tick(boolean alwaysPublish) {
        try {
            int i = 0;
            for (Measurement m : meter.measure()) {
                if (i < measurementFields.size()) {
                    measurementFields.get(i).set(m.getValue());
                }
                i++;
            }
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Error reading custom meter value for " + serverPV.getName(), e);
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        }
    }

    @Override
    public void close() {
        PvaScalarHelper.safeClose(serverPV);
    }
}
