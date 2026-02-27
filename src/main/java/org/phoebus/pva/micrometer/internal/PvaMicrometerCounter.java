package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.function.ToDoubleFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PVA wrapper for Micrometer {@link Counter} and {@link FunctionCounter} meters.
 *
 * <p>Publishes an {@code epics:nt/NTScalar:1.0} double PV with the cumulative count.
 */
public final class PvaMicrometerCounter implements PvaMeter {

    private static final Logger logger = Logger.getLogger(PvaMicrometerCounter.class.getName());

    private final ServerPV serverPV;
    private final PVADouble valueField;
    private final PVAAlarm alarmField;
    private final PVATimeStamp timeStampField;
    private final PVAStructure localStruct;
    private final java.util.function.DoubleSupplier countSupplier;

    private PvaMicrometerCounter(String pvName, java.util.function.DoubleSupplier countSupplier,
            PVAServer server) throws Exception {
        this.countSupplier = countSupplier;
        valueField = new PVADouble("value", 0.0);
        alarmField = new PVAAlarm();
        timeStampField = new PVATimeStamp(Instant.now());
        localStruct = PvaScalarHelper.doubleStruct(pvName, valueField, alarmField, timeStampField);
        serverPV = server.createPV(pvName, localStruct);
    }

    /** Create a PV wrapper for a Counter (cumulative count). */
    public static PvaMicrometerCounter forCounter(String pvName, Counter counter,
            PVAServer server) throws Exception {
        return new PvaMicrometerCounter(pvName, counter::count, server);
    }

    /** Create a PV wrapper for a FunctionCounter. */
    public static <T> PvaMicrometerCounter forFunctionCounter(String pvName, T obj,
            ToDoubleFunction<T> fn, PVAServer server) throws Exception {
        return new PvaMicrometerCounter(pvName, () -> fn.applyAsDouble(obj), server);
    }

    @Override
    public void tick(boolean alwaysPublish) {
        try {
            double val = countSupplier.getAsDouble();
            valueField.set(val);
            PvaScalarHelper.clearAlarm(alarmField);
            PvaScalarHelper.stampNow(timeStampField);
            PvaScalarHelper.safeUpdate(serverPV, localStruct);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error reading counter value for " + serverPV.getName(), e);
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
