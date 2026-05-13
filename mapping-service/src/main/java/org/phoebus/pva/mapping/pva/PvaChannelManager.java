package org.phoebus.pva.mapping.pva;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAAlarm.AlarmStatus;
import org.epics.pva.data.nt.PVADisplay;
import org.epics.pva.data.nt.PVAScalar;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;
import org.phoebus.pva.mapping.config.ChannelConfig;
import org.phoebus.pva.mapping.config.MappingProperties;
import org.phoebus.pva.mapping.scraper.ScraperResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the {@link PVAServer} and manages the lifecycle of all PVA channels declared in
 * configuration.  Channels are created once at startup and never removed; the PV set is
 * fixed for the lifetime of the service so OPI screens can rely on stable channel names.
 *
 * <p>All channels are {@code NTScalar double} — the same structure used by the sibling
 * {@code micrometer-registry-pva} library — with alarm, timestamp, and display sub-structures.
 */
@Component
public class PvaChannelManager {

    private static final Logger log = Logger.getLogger(PvaChannelManager.class.getName());

    private final MappingProperties properties;
    private final PVAServer server;
    private final Map<String, ManagedChannel> channels = new LinkedHashMap<>();

    record ManagedChannel(
            ServerPV serverPV,
            PVAScalar<PVADouble> structure,
            PVADouble valueField,
            PVAAlarm alarmField
    ) {}

    public PvaChannelManager(MappingProperties properties) throws Exception {
        this.properties = properties;
        this.server = new PVAServer();
    }

    @PostConstruct
    void init() {
        if (properties.services() == null) return;
        properties.services().forEach((serviceName, svc) -> {
            if (svc.channels() == null) return;
            for (ChannelConfig ch : svc.channels()) {
                try {
                    createChannel(ch);
                } catch (Exception e) {
                    log.log(Level.SEVERE, "Failed to create PV '" + ch.pv() + "'", e);
                }
            }
        });
        log.info("PVA mapping service started with " + channels.size() + " channel(s).");
    }

    private void createChannel(ChannelConfig ch) throws Exception {
        PVAScalar<PVADouble> structure;
        try {
            structure = PVAScalar.doubleScalarBuilder(0.0)
                    .name("")
                    .alarm(new PVAAlarm())
                    .timeStamp(new PVATimeStamp())
                    .display(new PVADisplay(0.0, 0.0,
                            ch.description() != null ? ch.description() : "",
                            ch.unit() != null ? ch.unit() : "",
                            0, PVADisplay.Form.DEFAULT))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build NTScalar structure for '" + ch.pv() + "'", e);
        }

        PVADouble valueField = structure.get("value");
        PVAAlarm alarmField  = structure.get("alarm");

        // Start in INVALID/UDF state until the first successful scrape
        alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, "No data yet");
        PVATimeStamp.set(structure, Instant.now());

        ServerPV serverPV = server.createPV(ch.pv(), structure);
        channels.put(ch.pv(), new ManagedChannel(serverPV, structure, valueField, alarmField));
        log.fine("Created PV: " + ch.pv());
    }

    /**
     * Applies a scrape result to the named PV: updates the value and alarm fields,
     * stamps the current time, and pushes to connected clients.
     *
     * <p>On {@link ScraperResult.Unavailable}, the previous numeric value is retained
     * (value field is not changed) so that OPI screens can still display the last known
     * reading alongside the alarm state.
     */
    public void applyResult(String pvName, ScraperResult result) {
        ManagedChannel ch = channels.get(pvName);
        if (ch == null) {
            log.warning("applyResult: no channel registered for PV '" + pvName + "'");
            return;
        }
        try {
            if (result instanceof ScraperResult.Value v) {
                ch.valueField().set(v.value());
                ch.alarmField().set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
            } else if (result instanceof ScraperResult.Unavailable u) {
                AlarmSeverity epics = switch (u.severity()) {
                    case MINOR -> AlarmSeverity.MINOR;
                    case MAJOR -> AlarmSeverity.MAJOR;
                };
                ch.alarmField().set(epics, AlarmStatus.DRIVER, u.reason());
            }
            PVATimeStamp.set(ch.structure(), Instant.now());
            ch.serverPV().update(ch.structure());
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to update PV '" + pvName + "'", e);
        }
    }

    /** Returns the TCP port the embedded {@link PVAServer} is listening on (used by tests). */
    public int getServerPort() throws Exception {
        return server.getTCPAddress(false).getPort();
    }

    @PreDestroy
    public void close() {
        channels.values().forEach(ch -> {
            try { ch.serverPV().close(); } catch (Exception ignored) {}
        });
        try { server.close(); } catch (Exception ignored) {}
    }
}
