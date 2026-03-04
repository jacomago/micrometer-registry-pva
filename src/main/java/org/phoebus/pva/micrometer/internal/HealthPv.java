package org.phoebus.pva.micrometer.internal;

import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAAlarm.AlarmStatus;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;
import org.phoebus.pva.micrometer.Health;
import org.phoebus.pva.micrometer.Health.Status;
import org.phoebus.pva.micrometer.HealthIndicator;
import org.phoebus.pva.micrometer.PvaMeterRegistry;

import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes the aggregated health of one or more {@link HealthIndicator}s as a PVA
 * channel named {@code <prefix>.health}.
 *
 * <p>The channel is an {@code NTScalar string} where:
 * <ul>
 *   <li>{@code value} — {@code "UP"}, {@code "DEGRADED"}, or {@code "DOWN"}</li>
 *   <li>{@code alarm.severity} — {@code 0} (UP), {@code 1} (DEGRADED), {@code 2} (DOWN)</li>
 *   <li>{@code alarm.message} — concatenated messages from all indicators</li>
 *   <li>{@code timeStamp} — EPICS timestamp updated on every tick</li>
 * </ul>
 *
 * <p>The {@link #tick()} method implements <em>worst-wins</em> aggregation:
 * {@code DOWN > DEGRADED > UP}.  If any indicator throws, that indicator is treated
 * as {@link Status#DOWN} with the exception message included in the alarm message.
 *
 * <p>Register {@code healthPv::tick} as a tick listener on the registry so that health
 * status is refreshed on every poll interval:
 * <pre>{@code
 * HealthPv healthPv = new HealthPv(registry, prefix + ".health", indicators);
 * registry.registerTickListener(healthPv::tick);
 * }</pre>
 *
 * <p>This class is package-internal; use {@link org.phoebus.pva.micrometer.PvaServiceBinder}
 * to register health indicators.
 */
public final class HealthPv {

    private static final Logger logger = Logger.getLogger(HealthPv.class.getName());

    private final ServerPV serverPV;
    private final List<HealthIndicator> indicators;

    /** Mutable structure updated in-place on every tick. */
    private final PVAStructure data;

    /** Cached field references for efficient in-place updates. */
    private final PVAString valueField;
    private final PVAAlarm alarmField;

    /**
     * Creates a {@code HealthPv}, immediately performing an initial health check.
     *
     * <p>The PVA channel is created via {@code registry.createRawPv()} and an initial
     * {@link #tick()} is performed so clients see the current health on first connect.
     * Register {@code healthPv::tick} as a tick listener to keep the channel updated.
     *
     * @param registry   registry used to create and track the PVA channel
     * @param pvName     PVA channel name, e.g. {@code "<prefix>.health"}
     * @param indicators snapshot list of health indicators to aggregate (must be non-empty)
     */
    public HealthPv(PvaMeterRegistry registry, String pvName, List<HealthIndicator> indicators) {
        this.indicators = indicators;
        this.data = buildInitialData();
        this.valueField = data.get("value");
        this.alarmField = data.get("alarm");
        this.serverPV = registry.createRawPv(pvName, data);
        tick();
    }

    /**
     * Polls all indicators, aggregates the results using worst-wins logic, and pushes
     * the updated status to connected PVA clients.
     *
     * <p>Called by the poll loop via a tick listener registered in
     * {@link org.phoebus.pva.micrometer.PvaServiceBinder#bindTo}.
     */
    public void tick() {
        Health aggregate = aggregate();
        valueField.set(aggregate.status().name());
        alarmField.set(toSeverity(aggregate.status()), AlarmStatus.NO_STATUS, aggregate.message());
        PVATimeStamp.set(data, Instant.now());
        try {
            serverPV.update(data);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to update health PV", e);
        }
    }

    /**
     * Closes the underlying PVA channel, notifying connected clients.
     */
    public void close() {
        serverPV.close();
    }

    /**
     * Runs every indicator and returns the aggregated {@link Health}.
     * The worst status wins; non-empty messages are joined with {@code "; "}.
     */
    private Health aggregate() {
        Status worst = Status.UP;
        StringJoiner messages = new StringJoiner("; ");

        for (HealthIndicator indicator : indicators) {
            Health h;
            try {
                h = indicator.check();
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Health check threw exception";
                h = Health.down(msg);
            }
            if (h.status().ordinal() > worst.ordinal()) {
                worst = h.status();
            }
            if (h.message() != null && !h.message().isEmpty()) {
                messages.add(h.message());
            }
        }
        return new Health(worst, messages.toString());
    }

    /**
     * Maps a {@link Status} to its EPICS alarm severity.
     * {@code UP → 0 (NO_ALARM)}, {@code DEGRADED → 1 (MINOR)}, {@code DOWN → 2 (MAJOR)}.
     */
    private static AlarmSeverity toSeverity(Status status) {
        return switch (status) {
            case UP       -> AlarmSeverity.NO_ALARM;
            case DEGRADED -> AlarmSeverity.MINOR;
            case DOWN     -> AlarmSeverity.MAJOR;
        };
    }

    private static PVAStructure buildInitialData() {
        return new PVAStructure("", "epics:nt/NTScalar:1.0",
                new PVAString("value", Status.UP.name()),
                new PVAAlarm(),
                new PVATimeStamp());
    }
}
