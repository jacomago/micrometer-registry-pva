package org.phoebus.pva.micrometer.internal;

import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for building NTScalar PVAStructure instances and performing safe ServerPV updates.
 */
final class PvaScalarHelper {

    static final String NT_SCALAR = "epics:nt/NTScalar:1.0";
    private static final Logger logger = Logger.getLogger(PvaScalarHelper.class.getName());

    private PvaScalarHelper() {
    }

    /** Build an NTScalar double structure with the given named fields. */
    static PVAStructure doubleStruct(String pvName, PVADouble value, PVAAlarm alarm, PVATimeStamp ts) {
        return new PVAStructure(pvName, NT_SCALAR, value, alarm, ts);
    }

    /** Build an NTScalar string structure. */
    static PVAStructure stringStruct(String pvName, PVAString value, PVAAlarm alarm, PVATimeStamp ts) {
        return new PVAStructure(pvName, NT_SCALAR, value, alarm, ts);
    }

    /** Safely call serverPV.update(), logging any exception. */
    static void safeUpdate(ServerPV serverPV, PVAStructure struct) {
        try {
            serverPV.update(struct);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to update ServerPV " + serverPV.getName(), e);
        }
    }

    /** Safely close a ServerPV, logging any exception. */
    static void safeClose(ServerPV serverPV) {
        try {
            serverPV.close();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to close ServerPV " + serverPV.getName(), e);
        }
    }

    /** Set alarm to INVALID with the given message. */
    static void setInvalidAlarm(PVAAlarm alarm, String message) {
        alarm.set(PVAAlarm.AlarmSeverity.INVALID, PVAAlarm.AlarmStatus.DRIVER,
                message != null ? message : "");
    }

    /** Set alarm to NO_ALARM. */
    static void clearAlarm(PVAAlarm alarm) {
        alarm.set(PVAAlarm.AlarmSeverity.NO_ALARM, PVAAlarm.AlarmStatus.NO_STATUS, "");
    }

    /** Update timestamp to now. */
    static void stampNow(PVATimeStamp ts) {
        ts.set(Instant.now());
    }
}
