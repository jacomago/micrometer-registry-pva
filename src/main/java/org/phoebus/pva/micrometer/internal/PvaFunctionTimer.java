package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.FunctionTimer;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PVA wrapper for Micrometer {@link FunctionTimer} meters.
 *
 * <p>Publishes a custom {@code micrometer:FunctionTimer:1.0} structure with fields:
 * {@code count} (double), {@code totalTime} (double, seconds), {@code timeStamp}.
 */
public final class PvaFunctionTimer implements PvaMeter {

    private static final Logger logger = Logger.getLogger(PvaFunctionTimer.class.getName());

    private final ServerPV serverPV;
    private final PVADouble countField;
    private final PVADouble totalTimeField;
    private final PVATimeStamp timeStampField;
    private final PVAStructure localStruct;
    private final FunctionTimer ft;

    public PvaFunctionTimer(String pvName, FunctionTimer ft, PVAServer server) throws Exception {
        this.ft = ft;
        countField = new PVADouble("count", 0.0);
        totalTimeField = new PVADouble("totalTime", 0.0);
        timeStampField = new PVATimeStamp(Instant.now());
        localStruct = new PVAStructure(pvName, "micrometer:FunctionTimer:1.0",
                countField, totalTimeField, timeStampField);
        serverPV = server.createPV(pvName, localStruct);
    }

    @Override
    public void tick(boolean alwaysPublish) {
        try {
            countField.set(ft.count());
            totalTimeField.set(ft.totalTime(TimeUnit.SECONDS));
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Error reading function timer value for " + serverPV.getName(), e);
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        }
    }

    @Override
    public void close() {
        PvaScalarHelper.safeClose(serverPV);
    }
}
