package org.phoebus.pva.micrometer.internal;

import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;

import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates and populates the static {@code <prefix>.info} NTScalar string ServerPV.
 *
 * <p>The PV is set once at creation time and never updated by the poll loop.
 * Its value is a JSON string containing service identity fields.
 */
public final class InfoPv {

    private static final Logger logger = Logger.getLogger(InfoPv.class.getName());

    private final ServerPV serverPV;

    public InfoPv(String pvName, String serviceName, String version, String buildDate,
            String gitCommit, PVAServer server) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        if (serviceName != null && !serviceName.isBlank()) {
            fields.put("name", serviceName);
        }
        if (version != null && !version.isBlank()) {
            fields.put("version", version);
        }
        if (buildDate != null && !buildDate.isBlank()) {
            fields.put("buildDate", buildDate);
        }
        if (gitCommit != null && !gitCommit.isBlank()) {
            fields.put("gitCommit", gitCommit);
        }
        fields.put("host", resolveHostname());

        String json = toJson(fields);

        PVAString valueField = new PVAString("value", json);
        PVAAlarm alarmField = new PVAAlarm();
        PVATimeStamp timeStampField = new PVATimeStamp(Instant.now());

        PVAStructure struct = PvaScalarHelper.stringStruct(pvName, valueField, alarmField, timeStampField);
        serverPV = server.createPV(pvName, struct);
    }

    /** Close the underlying ServerPV. */
    public void close() {
        PvaScalarHelper.safeClose(serverPV);
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Cannot resolve local hostname", e);
            return "unknown";
        }
    }

    private static String toJson(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(escape(entry.getKey())).append("\":");
            sb.append("\"").append(escape(entry.getValue())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
