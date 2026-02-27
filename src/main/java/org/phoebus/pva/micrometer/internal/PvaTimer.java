package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.Timer;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVALong;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PVA wrapper for Micrometer {@link Timer} meters.
 *
 * <p>Publishes a custom {@code micrometer:Timer:1.0} structure with fields:
 * {@code count} (long), {@code totalTime} (double, seconds), {@code max} (double, seconds),
 * {@code timeStamp}.
 */
public final class PvaTimer implements PvaMeter {

    private static final Logger logger = Logger.getLogger(PvaTimer.class.getName());

    private final ServerPV serverPV;
    private final PVALong countField;
    private final PVADouble totalTimeField;
    private final PVADouble maxField;
    private final PVATimeStamp timeStampField;
    private final PVAStructure localStruct;
    private final Timer timer;

    public PvaTimer(String pvName, Timer timer, PVAServer server) throws Exception {
        this.timer = timer;
        countField = new PVALong("count", false, 0L);
        totalTimeField = new PVADouble("totalTime", 0.0);
        maxField = new PVADouble("max", 0.0);
        timeStampField = new PVATimeStamp(Instant.now());
        localStruct = new PVAStructure(pvName, "micrometer:Timer:1.0",
                countField, totalTimeField, maxField, timeStampField);
        serverPV = server.createPV(pvName, localStruct);
    }

    @Override
    public void tick(boolean alwaysPublish) {
        try {
            countField.set(timer.count());
            totalTimeField.set(timer.totalTime(TimeUnit.SECONDS));
            maxField.set(timer.max(TimeUnit.SECONDS));
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error reading timer value for " + serverPV.getName(), e);
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        }
    }

    @Override
    public void close() {
        PvaScalarHelper.safeClose(serverPV);
    }
}
