package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Strategy for converting a Micrometer {@link Meter.Id} to a PV name string.
 *
 * <p>Tag order in the PV name is alphabetical by tag key for determinism.
 */
@FunctionalInterface
public interface PvNamingStrategy {

    /**
     * Convert a meter ID to a PV name string.
     *
     * @param id the meter identity (name + tags)
     * @return the PV name to use for the corresponding {@code ServerPV}
     */
    String pvName(Meter.Id id);

    /**
     * Default strategy: dots for name separators, tags appended in
     * {@code {key="value",key2="value2"}} notation.
     *
     * <p>Example: {@code archiver.engine.pv.total{appliance="a0",state="connected"}}
     */
    PvNamingStrategy DOTS_WITH_BRACE_TAGS = id -> {
        List<Tag> tags = id.getTags().stream()
                .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                .collect(Collectors.toList());
        if (tags.isEmpty()) {
            return id.getName();
        }
        String tagStr = tags.stream()
                .map(t -> t.getKey() + "=\"" + t.getValue() + "\"")
                .collect(Collectors.joining(","));
        return id.getName() + "{" + tagStr + "}";
    };

    /**
     * Colon-separated strategy: name parts and tag key/value pairs separated by colons.
     *
     * <p>Example: {@code archiver:engine:pv:total:appliance:a0:state:connected}
     */
    PvNamingStrategy COLONS = id -> {
        String name = id.getName().replace('.', ':');
        List<Tag> tags = id.getTags().stream()
                .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                .collect(Collectors.toList());
        if (tags.isEmpty()) {
            return name;
        }
        String tagStr = tags.stream()
                .map(t -> t.getKey() + ":" + t.getValue())
                .collect(Collectors.joining(":"));
        return name + ":" + tagStr;
    };

    /**
     * Name-only strategy: tags are dropped. Only safe if meter names are already unique.
     *
     * <p>Example: {@code archiver.engine.pv.total}
     */
    PvNamingStrategy NAME_ONLY = id -> id.getName();
}
