package org.phoebus.pva.micrometer.internal;

import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;
import org.phoebus.pva.micrometer.PvaMeterRegistry;

import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes static build metadata as a one-time PVA channel named
 * {@code <prefix>.build}.
 *
 * <p>The channel is created once via
 * {@link PvaMeterRegistry#createRawPv(String, PVAStructure)} and immediately
 * stamped with the current wall-clock time.  The value never changes after
 * creation — no tick listener is registered.
 *
 * <p>The PVA structure has type name {@code service:BuildInfo:1.0} and contains:
 * <ul>
 *   <li>{@code version}   — artifact version string</li>
 *   <li>{@code buildDate} — ISO-8601 build date</li>
 *   <li>{@code gitCommit} — short Git commit hash</li>
 *   <li>{@code timeStamp} — EPICS timestamp set at creation time</li>
 * </ul>
 *
 * <p>This class is package-internal; use {@link org.phoebus.pva.micrometer.PvaServiceBinder}
 * to create build-info PVs.
 */
public final class InfoPv {

    private static final Logger logger = Logger.getLogger(InfoPv.class.getName());

    private final String pvName;
    private final String version;
    private final String buildDate;
    private final String gitCommit;

    /**
     * Creates an {@code InfoPv} with the given build metadata.
     * Null values are coerced to empty strings.
     *
     * @param prefix    service prefix used to derive the PV name ({@code <prefix>.build})
     * @param version   artifact version, e.g. {@code "1.0.0"}
     * @param buildDate ISO-8601 build date, e.g. {@code "2024-01-15"}
     * @param gitCommit short Git commit hash, e.g. {@code "abc1234"}
     */
    public InfoPv(String prefix, String version, String buildDate, String gitCommit) {
        this.pvName = prefix + ".build";
        this.version = version != null ? version : "";
        this.buildDate = buildDate != null ? buildDate : "";
        this.gitCommit = gitCommit != null ? gitCommit : "";
    }

    /**
     * Creates the PVA channel on the registry's server and performs a single update
     * to populate the build-info fields.
     *
     * @param registry the registry whose underlying PVAServer will host this channel
     */
    public void createPv(PvaMeterRegistry registry) {
        PVAStructure data = buildData();
        ServerPV serverPV = registry.createRawPv(pvName, data);
        PVATimeStamp.set(data, Instant.now());
        try {
            serverPV.update(data);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to publish initial build-info PV '" + pvName + "'", e);
        }
    }

    private PVAStructure buildData() {
        return new PVAStructure("", "service:BuildInfo:1.0",
                new PVAString("version", version),
                new PVAString("buildDate", buildDate),
                new PVAString("gitCommit", gitCommit),
                new PVATimeStamp());
    }
}
