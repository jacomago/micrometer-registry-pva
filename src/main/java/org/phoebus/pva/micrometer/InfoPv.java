package org.phoebus.pva.micrometer;

import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;

import java.net.InetAddress;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes static build metadata as a one-time PVA channel named
 * {@code <prefix>.info}.
 *
 * <p>The channel is an {@code NTScalar string} whose {@code value} field holds a
 * JSON object:
 * <pre>{"name":"...","version":"...","buildDate":"...","gitCommit":"...","host":"..."}</pre>
 * Null fields are omitted from the JSON.  The host name is resolved from
 * {@link InetAddress#getLocalHost()}.
 *
 * <p>The channel is created via
 * {@link PvaMeterRegistry#createRawPv(String, PVAStructure)} and updated exactly
 * once — in the constructor.  The value never changes after that.
 *
 * <p>This class is package-internal; use {@link PvaServiceBinder}
 * to create build-info PVs.
 */
final class InfoPv {

    private static final Logger logger = Logger.getLogger(InfoPv.class.getName());

    private final ServerPV serverPV;

    /**
     * Creates an {@code InfoPv}, immediately publishing a JSON build-info string to a
     * new {@code NTScalar string} PVA channel.
     *
     * @param registry  registry used to create and track the PVA channel
     * @param pvName    PVA channel name, e.g. {@code "<prefix>.info"}
     * @param name      service name; {@code null} → field omitted from JSON
     * @param version   artifact version; {@code null} → field omitted from JSON
     * @param buildDate ISO-8601 build date; {@code null} → field omitted from JSON
     * @param gitCommit short Git commit hash; {@code null} → field omitted from JSON
     */
    InfoPv(PvaMeterRegistry registry, String pvName, String name,
           String version, String buildDate, String gitCommit) {
        PVAStructure data = buildInitialData();
        this.serverPV = registry.createRawPv(pvName, data);

        String host = null;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not resolve hostname for info PV '" + pvName + "'", e);
        }

        PVAString valueField = data.get("value");
        valueField.set(buildJson(name, version, buildDate, gitCommit, host));
        PVATimeStamp.set(data, Instant.now());

        try {
            serverPV.update(data);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to publish initial info PV '" + pvName + "'", e);
        }
    }

    /**
     * Closes the underlying PVA channel, notifying connected clients.
     */
    void close() {
        serverPV.close();
    }

    private static PVAStructure buildInitialData() {
        return new PVAStructure("", "epics:nt/NTScalar:1.0",
                new PVAString("value", ""),
                new PVAAlarm(),
                new PVATimeStamp());
    }

    /**
     * Builds a JSON object string from the supplied fields, omitting any that are
     * {@code null}.
     */
    static String buildJson(String name, String version, String buildDate,
                            String gitCommit, String host) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        first = appendField(sb, "name", name, first);
        first = appendField(sb, "version", version, first);
        first = appendField(sb, "buildDate", buildDate, first);
        first = appendField(sb, "gitCommit", gitCommit, first);
        appendField(sb, "host", host, first);
        sb.append("}");
        return sb.toString();
    }

    /** Appends {@code ,"key":"value"} (or {@code "key":"value"} if first). */
    private static boolean appendField(StringBuilder sb, String key, String value, boolean first) {
        if (value == null) {
            return first;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
        return false;
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
