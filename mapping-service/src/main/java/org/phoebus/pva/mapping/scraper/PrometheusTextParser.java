package org.phoebus.pva.mapping.scraper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal parser for the Prometheus text exposition format (version 0.0.4).
 *
 * <p>Only the data needed for the mapping service is extracted: metric name, label set,
 * and numeric value.  {@code # HELP} and {@code # TYPE} comment lines are skipped.
 * Malformed lines are silently ignored so a single bad line never breaks an entire scrape.
 */
public final class PrometheusTextParser {

    // Matches one label pair inside {...}: key="value" (handles \" and \\ escapes)
    private static final Pattern LABEL_PAIR =
            Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private PrometheusTextParser() {}

    /**
     * Parses a Prometheus text-format body into a flat list of samples.
     * One {@link Sample} is produced per non-comment, non-blank line.
     */
    public static List<Sample> parse(String body) {
        if (body == null || body.isBlank()) return Collections.emptyList();

        List<Sample> samples = new ArrayList<>();
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // Format: metricName[{labels}] value [timestamp]
            // Find end of the metric+labels portion (before the first unquoted space)
            int labelEnd = line.lastIndexOf('}');
            String namePart;
            String valuePart;

            if (labelEnd >= 0) {
                // Has labels: "name{...} value [ts]"
                int spaceAfterLabels = line.indexOf(' ', labelEnd);
                if (spaceAfterLabels < 0) continue;
                namePart = line.substring(0, labelEnd + 1);
                String rest = line.substring(spaceAfterLabels + 1).trim();
                // Value is the first token; ignore optional timestamp
                int spaceAfterValue = rest.indexOf(' ');
                valuePart = spaceAfterValue < 0 ? rest : rest.substring(0, spaceAfterValue);
            } else {
                // No labels: "name value [ts]"
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 2) continue;
                namePart = parts[0];
                valuePart = parts[1];
            }

            double value;
            try {
                // Prometheus uses +Inf / -Inf; Java's Double.parseDouble needs "Infinity"
                String normalized = valuePart.replace("+Inf", "Infinity")
                                             .replace("-Inf", "-Infinity");
                value = Double.parseDouble(normalized);
            } catch (NumberFormatException e) {
                continue;
            }

            int braceOpen = namePart.indexOf('{');
            String name;
            Map<String, String> labels;

            if (braceOpen >= 0) {
                name = namePart.substring(0, braceOpen);
                String labelStr = namePart.substring(braceOpen + 1, namePart.length() - 1);
                labels = parseLabels(labelStr);
            } else {
                name = namePart;
                labels = Collections.emptyMap();
            }

            samples.add(new Sample(name, labels, value));
        }
        return samples;
    }

    /**
     * Finds the first sample whose name equals {@code metric} and whose labels contain
     * all entries in {@code requiredLabels} (other labels on the sample are permitted).
     *
     * @param requiredLabels label filter; {@code null} matches any label set
     */
    public static Optional<Sample> find(List<Sample> samples,
                                        String metric,
                                        Map<String, String> requiredLabels) {
        return samples.stream()
                .filter(s -> s.name().equals(metric))
                .filter(s -> requiredLabels == null
                        || s.labels().entrySet().containsAll(requiredLabels.entrySet()))
                .findFirst();
    }

    private static Map<String, String> parseLabels(String labelStr) {
        Map<String, String> labels = new LinkedHashMap<>();
        Matcher m = LABEL_PAIR.matcher(labelStr);
        while (m.find()) {
            String value = m.group(2)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n");
            labels.put(m.group(1), value);
        }
        return Collections.unmodifiableMap(labels);
    }
}
