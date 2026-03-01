package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PvNamingStrategy} built-in implementations.
 *
 * <p>Covers:
 * <ul>
 *   <li>No tags</li>
 *   <li>One tag</li>
 *   <li>Multiple tags — asserts alphabetical tag ordering</li>
 * </ul>
 */
class PvNamingStrategyTest {

    // -------------------------------------------------------------------------
    // DOTS_WITH_BRACE_TAGS
    // -------------------------------------------------------------------------

    @Test
    void dotsWithBraceTags_noTags() {
        Meter.Id id = meterId("archiver.engine.pv.total", Tags.empty());
        assertEquals("archiver.engine.pv.total",
                PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(id));
    }

    @Test
    void dotsWithBraceTags_oneTag() {
        Meter.Id id = meterId("archiver.engine.pv.total", Tags.of("state", "connected"));
        assertEquals("archiver.engine.pv.total{state=\"connected\"}",
                PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(id));
    }

    @Test
    void dotsWithBraceTags_multipleTagsAlphabeticalOrder() {
        // Tags supplied out of alphabetical order — strategy must sort them.
        Meter.Id id = meterId("archiver.engine.pv.total",
                Tags.of("state", "connected", "appliance", "appliance0"));
        assertEquals("archiver.engine.pv.total{appliance=\"appliance0\",state=\"connected\"}",
                PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(id));
    }

    @Test
    void dotsWithBraceTags_threeTagsAlphabeticalOrder() {
        Meter.Id id = meterId("requests.total",
                Tags.of("zone", "eu-west", "method", "GET", "host", "server01"));
        assertEquals("requests.total{host=\"server01\",method=\"GET\",zone=\"eu-west\"}",
                PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(id));
    }

    // -------------------------------------------------------------------------
    // COLONS
    // -------------------------------------------------------------------------

    @Test
    void colons_noTags() {
        Meter.Id id = meterId("archiver.engine.pv.total", Tags.empty());
        assertEquals("archiver:engine:pv:total",
                PvNamingStrategy.COLONS.pvName(id));
    }

    @Test
    void colons_oneTag() {
        Meter.Id id = meterId("archiver.engine.pv.total", Tags.of("state", "connected"));
        assertEquals("archiver:engine:pv:total:state:connected",
                PvNamingStrategy.COLONS.pvName(id));
    }

    @Test
    void colons_multipleTagsAlphabeticalOrder() {
        // Tags supplied out of alphabetical order — strategy must sort them.
        Meter.Id id = meterId("archiver.engine.pv.total",
                Tags.of("state", "connected", "appliance", "appliance0"));
        assertEquals("archiver:engine:pv:total:appliance:appliance0:state:connected",
                PvNamingStrategy.COLONS.pvName(id));
    }

    @Test
    void colons_threeTagsAlphabeticalOrder() {
        Meter.Id id = meterId("requests.total",
                Tags.of("zone", "eu-west", "method", "GET", "host", "server01"));
        assertEquals("requests:total:host:server01:method:GET:zone:eu-west",
                PvNamingStrategy.COLONS.pvName(id));
    }

    // -------------------------------------------------------------------------
    // NAME_ONLY
    // -------------------------------------------------------------------------

    @Test
    void nameOnly_noTags() {
        Meter.Id id = meterId("archiver.engine.pv.total", Tags.empty());
        assertEquals("archiver.engine.pv.total",
                PvNamingStrategy.NAME_ONLY.pvName(id));
    }

    @Test
    void nameOnly_tagsAreDropped() {
        Meter.Id id = meterId("archiver.engine.pv.total",
                Tags.of("state", "connected", "appliance", "appliance0"));
        assertEquals("archiver.engine.pv.total",
                PvNamingStrategy.NAME_ONLY.pvName(id));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static Meter.Id meterId(String name, Tags tags) {
        return new Meter.Id(name, tags, null, null, Meter.Type.GAUGE);
    }
}
