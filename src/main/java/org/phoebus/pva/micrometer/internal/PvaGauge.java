package org.phoebus.pva.micrometer.internal;

import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PVA wrapper for Micrometer {@link io.micrometer.core.instrument.Gauge} and
 * {@link io.micrometer.core.instrument.TimeGauge} meters.
 *
 * <p>Publishes an {@code epics:nt/NTScalar:1.0} double PV.
 */
public final class PvaGauge implements PvaMeter {

    private static final Logger logger = Logger.getLogger(PvaGauge.class.getName());

    private final ServerPV serverPV;
    private final PVADouble valueField;
    private final PVAAlarm alarmField;
    private final PVATimeStamp timeStampField;
    private final PVAStructure localStruct;
    private final java.util.function.DoubleSupplier valueSupplier;

    private PvaGauge(String pvName, java.util.function.DoubleSupplier supplier, PVAServer server)
            throws Exception {
        this.valueSupplier = supplier;
        valueField = new PVADouble("value", 0.0);
        alarmField = new PVAAlarm();
        timeStampField = new PVATimeStamp(Instant.now());
        localStruct = PvaScalarHelper.doubleStruct(pvName, valueField, alarmField, timeStampField);
        serverPV = server.createPV(pvName, localStruct);
    }

    /** Create a PV wrapper for a standard Gauge. */
    public static <T> PvaGauge forGauge(String pvName, T obj, ToDoubleFunction<T> fn,
            PVAServer server) throws Exception {
        return new PvaGauge(pvName, () -> fn.applyAsDouble(obj), server);
    }

    /** Create a PV wrapper for a TimeGauge (normalises to seconds). */
    public static <T> PvaGauge forTimeGauge(String pvName, T obj, TimeUnit unit,
            ToDoubleFunction<T> fn, PVAServer server) throws Exception {
        return new PvaGauge(pvName,
                () -> unit.toNanos((long) fn.applyAsDouble(obj)) / 1_000_000_000.0,
                server);
    }

    @Override
    public void tick(boolean alwaysPublish) {
        try {
            double val = valueSupplier.getAsDouble();
            valueField.set(val);
            PvaScalarHelper.clearAlarm(alarmField);
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error reading gauge value for " + serverPV.getName(), e);
            PvaScalarHelper.setInvalidAlarm(alarmField, e.getMessage());
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        }
    }

    @Override
    public void close() {
        PvaScalarHelper.safeClose(serverPV);
    }
}
