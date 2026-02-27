package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.LongTaskTimer;
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
 * PVA wrapper for Micrometer {@link LongTaskTimer} meters.
 *
 * <p>Publishes a custom {@code micrometer:LongTaskTimer:1.0} structure with fields:
 * {@code activeTasks} (long), {@code duration} (double, seconds), {@code timeStamp}.
 */
public final class PvaLongTaskTimer implements PvaMeter {

    private static final Logger logger = Logger.getLogger(PvaLongTaskTimer.class.getName());

    private final ServerPV serverPV;
    private final PVALong activeTasksField;
    private final PVADouble durationField;
    private final PVATimeStamp timeStampField;
    private final PVAStructure localStruct;
    private final LongTaskTimer ltt;

    public PvaLongTaskTimer(String pvName, LongTaskTimer ltt, PVAServer server) throws Exception {
        this.ltt = ltt;
        activeTasksField = new PVALong("activeTasks", false, 0L);
        durationField = new PVADouble("duration", 0.0);
        timeStampField = new PVATimeStamp(Instant.now());
        localStruct = new PVAStructure(pvName, "micrometer:LongTaskTimer:1.0",
                activeTasksField, durationField, timeStampField);
        serverPV = server.createPV(pvName, localStruct);
    }

    @Override
    public void tick(boolean alwaysPublish) {
        try {
            activeTasksField.set(ltt.activeTasks());
            durationField.set(ltt.duration(TimeUnit.SECONDS));
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Error reading long task timer value for " + serverPV.getName(), e);
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        }
    }

    @Override
    public void close() {
        PvaScalarHelper.safeClose(serverPV);
    }
}
