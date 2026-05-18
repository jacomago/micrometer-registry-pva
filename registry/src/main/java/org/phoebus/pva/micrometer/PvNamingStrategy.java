package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Strategy for converting a Micrometer {@link Meter.Id} (name + tags) into a PV name string.
 *
 * <p>Tag order in PV names is always alphabetical by tag key for determinism.
 *
 * <p>Built-in strategies are provided as constants. Callers may supply any lambda
 * that satisfies this interface.
 *
 * <pre>{@code
 * PvaMeterRegistry registry = new PvaMeterRegistry(
 *     new PvaMeterRegistryConfig() {
 *         public PvNamingStrategy namingStrategy() {
 *             return PvNamingStrategy.COLONS;
 *         }
 *     }, Clock.SYSTEM);
 * }</pre>
 */
@FunctionalInterface
public interface PvNamingStrategy {

    /**
     * Converts a {@link Meter.Id} to a PV name string.
     *
     * @param id the meter identity (name + tags)
     * @return the PV name to use with the PVA server
     */
    String pvName(Meter.Id id);

    /**
     * Default strategy: meter name (dots preserved) followed by tags in
     * Prometheus-style brace notation.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code archiver.engine.pv.total} — no tags</li>
     *   <li>{@code archiver.engine.pv.total{appliance="a0",state="connected"}} — two tags</li>
     * </ul>
     *
     * <p>Tags are sorted alphabetically by key.
     */
    PvNamingStrategy DOTS_WITH_BRACE_TAGS = id -> {
        List<Tag> tags = sortedTags(id);
        if (tags.isEmpty()) {
            return id.getName();
        }
        StringBuilder sb = new StringBuilder(id.getName());
        sb.append('{');
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(tags.get(i).getKey())
              .append("=\"")
              .append(tags.get(i).getValue())
              .append('"');
        }
        sb.append('}');
        return sb.toString();
    };

    /**
     * Colon-separated strategy: dots in the meter name are replaced by colons,
     * and tags are appended as {@code :key:value} pairs.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code archiver:engine:pv:total} — no tags</li>
     *   <li>{@code archiver:engine:pv:total:appliance:a0:state:connected} — two tags</li>
     * </ul>
     *
     * <p>Tags are sorted alphabetically by key.
     */
    PvNamingStrategy COLONS = id -> {
        StringBuilder sb = new StringBuilder(id.getName().replace('.', ':'));
        for (Tag tag : sortedTags(id)) {
            sb.append(':').append(tag.getKey()).append(':').append(tag.getValue());
        }
        return sb.toString();
    };

    /**
     * Name-only strategy: tags are dropped entirely.
     *
     * <p><strong>Warning:</strong> only safe if all meter names are unique without tags.
     * Using this when meters share a name but differ by tags will cause PV name
     * collisions.
     *
     * <p>Example: {@code archiver.engine.pv.total}
     */
    PvNamingStrategy NAME_ONLY = Meter.Id::getName;

    /** Returns the meter's tags sorted alphabetically by key for deterministic PV names. */
    private static List<Tag> sortedTags(Meter.Id id) {
        return id.getTags().stream()
                .sorted(Comparator.comparing(Tag::getKey))
                .collect(Collectors.toList());
    }
}
