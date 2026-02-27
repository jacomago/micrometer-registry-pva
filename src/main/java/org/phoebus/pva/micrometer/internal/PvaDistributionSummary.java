package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.DistributionSummary;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVALong;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PVA wrapper for Micrometer {@link DistributionSummary} meters.
 *
 * <p>Publishes a custom {@code micrometer:Summary:1.0} structure with fields:
 * {@code count} (long), {@code total} (double), {@code max} (double), {@code timeStamp}.
 */
public final class PvaDistributionSummary implements PvaMeter {

    private static final Logger logger = Logger.getLogger(PvaDistributionSummary.class.getName());

    private final ServerPV serverPV;
    private final PVALong countField;
    private final PVADouble totalField;
    private final PVADouble maxField;
    private final PVATimeStamp timeStampField;
    private final PVAStructure localStruct;
    private final DistributionSummary summary;

    public PvaDistributionSummary(String pvName, DistributionSummary summary,
            PVAServer server) throws Exception {
        this.summary = summary;
        countField = new PVALong("count", false, 0L);
        totalField = new PVADouble("total", 0.0);
        maxField = new PVADouble("max", 0.0);
        timeStampField = new PVATimeStamp(Instant.now());
        localStruct = new PVAStructure(pvName, "micrometer:Summary:1.0",
                countField, totalField, maxField, timeStampField);
        serverPV = server.createPV(pvName, localStruct);
    }

    @Override
    public void tick(boolean alwaysPublish) {
        try {
            countField.set(summary.count());
            totalField.set(summary.totalAmount());
            maxField.set(summary.max());
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Error reading distribution summary value for " + serverPV.getName(), e);
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        }
    }

    @Override
    public void close() {
        PvaScalarHelper.safeClose(serverPV);
    }
}
