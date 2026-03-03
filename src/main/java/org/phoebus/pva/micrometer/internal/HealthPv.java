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
 * <p>On every poll tick (via a
 * {@link PvaMeterRegistry#registerTickListener(Runnable) tick listener}) all registered
 * indicators are called.  The <em>worst</em> status among them determines the published
 * status and alarm severity; all non-empty messages are joined with {@code "; "}.
 *
 * <p>Status-to-alarm mapping:
 * <ul>
 *   <li>{@link Status#UP}       → {@code NO_ALARM}</li>
 *   <li>{@link Status#DEGRADED} → {@code MINOR}</li>
 *   <li>{@link Status#DOWN}     → {@code MAJOR}</li>
 * </ul>
 *
 * <p>If any indicator throws, that indicator is treated as {@link Status#DOWN} with the
 * exception message recorded in the channel's {@code message} field.
 *
 * <p>The PVA structure has type name {@code service:Health:1.0} and contains:
 * <ul>
 *   <li>{@code status}  — {@code "UP"}, {@code "DEGRADED"}, or {@code "DOWN"}</li>
 *   <li>{@code message} — concatenated messages from all indicators</li>
 *   <li>{@code alarm}   — EPICS alarm with severity derived from status</li>
 *   <li>{@code timeStamp} — EPICS timestamp updated on every tick</li>
 * </ul>
 *
 * <p>This class is package-internal; use {@link org.phoebus.pva.micrometer.PvaServiceBinder}
 * to register health indicators.
 */
public final class HealthPv {

    private static final Logger logger = Logger.getLogger(HealthPv.class.getName());

    private final String pvName;
    private final List<HealthIndicator> indicators;

    /** Mutable structure updated in-place on every tick. */
    private PVAStructure data;

    /** Cached field references for efficient in-place updates. */
    private PVAString statusField;
    private PVAString messageField;
    private PVAAlarm alarmField;

    /**
     * Creates a {@code HealthPv}.
     *
     * @param prefix     service prefix used to derive the PV name ({@code <prefix>.health})
     * @param indicators snapshot list of indicators to aggregate (must be non-empty)
     */
    public HealthPv(String prefix, List<HealthIndicator> indicators) {
        this.pvName = prefix + ".health";
        this.indicators = indicators;
    }

    /**
     * Creates the PVA channel and registers a tick listener on the registry.
     * The channel is populated immediately with an initial health check.
     *
     * @param registry the registry whose poll loop will drive health updates
     */
    public void createPv(PvaMeterRegistry registry) {
        data = buildData();
        statusField = data.get("status");
        messageField = data.get("message");
        alarmField = data.get("alarm");

        ServerPV serverPV = registry.createRawPv(pvName, data);

        // Push an immediate update so clients see the current health on connect.
        updateData();
        try {
            serverPV.update(data);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to publish initial health PV '" + pvName + "'", e);
        }

        // Register for subsequent poll ticks.
        registry.registerTickListener(() -> {
            updateData();
            try {
                serverPV.update(data);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to update health PV '" + pvName + "'", e);
            }
        });
    }

    /**
     * Aggregates all indicator results, updates the PVA structure fields in-place,
     * and stamps the current wall-clock time.
     */
    private void updateData() {
        Health aggregate = aggregate();
        statusField.set(aggregate.status().name());
        messageField.set(aggregate.message());
        alarmField.set(toSeverity(aggregate.status()), AlarmStatus.NO_STATUS, "");
        PVATimeStamp.set(data, Instant.now());
    }

    /**
     * Runs every indicator and returns the aggregated {@link Health}.
     * The worst status wins; messages are joined with {@code "; "}.
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

    private static AlarmSeverity toSeverity(Status status) {
        return switch (status) {
            case UP       -> AlarmSeverity.NO_ALARM;
            case DEGRADED -> AlarmSeverity.MINOR;
            case DOWN     -> AlarmSeverity.MAJOR;
        };
    }

    private static PVAStructure buildData() {
        return new PVAStructure("", "service:Health:1.0",
                new PVAString("status", Status.UP.name()),
                new PVAString("message", ""),
                new PVAAlarm(),
                new PVATimeStamp());
    }
}
