package org.phoebus.pva.micrometer.internal;

import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;
import org.phoebus.pva.micrometer.Health;
import org.phoebus.pva.micrometer.HealthIndicator;

import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the {@code <prefix>.health} NTScalar string ServerPV.
 *
 * <p>On each poll tick, aggregates all {@link HealthIndicator}s using worst-wins
 * semantics and updates alarm severity accordingly.
 */
public final class HealthPv implements PvaMeter {

    private static final Logger logger = Logger.getLogger(HealthPv.class.getName());

    private final ServerPV serverPV;
    private final PVAString valueField;
    private final PVAAlarm alarmField;
    private final PVATimeStamp timeStampField;
    private final PVAStructure localStruct;
    private final List<HealthIndicator> indicators;

    public HealthPv(String pvName, List<HealthIndicator> indicators, PVAServer server)
            throws Exception {
        this.indicators = List.copyOf(indicators);
        valueField = new PVAString("value", "UP");
        alarmField = new PVAAlarm();
        timeStampField = new PVATimeStamp(Instant.now());
        localStruct = PvaScalarHelper.stringStruct(pvName, valueField, alarmField, timeStampField);
        serverPV = server.createPV(pvName, localStruct);
    }

    @Override
    public void tick(boolean alwaysPublish) {
        try {
            Health aggregate = aggregate();
            valueField.set(aggregate.status().name());
            alarmField.set(toAlarmSeverity(aggregate.status()),
                    PVAAlarm.AlarmStatus.NO_STATUS, aggregate.message());
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error evaluating health for " + serverPV.getName(), e);
        }
    }

    @Override
    public void close() {
        PvaScalarHelper.safeClose(serverPV);
    }

    private Health aggregate() {
        Health worst = Health.up();
        for (HealthIndicator indicator : indicators) {
            try {
                Health h = indicator.check();
                if (h.status().ordinal() > worst.status().ordinal()) {
                    worst = h;
                }
            } catch (Exception e) {
                worst = Health.down("Indicator threw: " + e.getMessage());
            }
        }
        return worst;
    }

    private static PVAAlarm.AlarmSeverity toAlarmSeverity(Health.Status status) {
        return switch (status) {
            case UP -> PVAAlarm.AlarmSeverity.NO_ALARM;
            case DEGRADED -> PVAAlarm.AlarmSeverity.MINOR;
            case DOWN -> PVAAlarm.AlarmSeverity.MAJOR;
        };
    }
}
