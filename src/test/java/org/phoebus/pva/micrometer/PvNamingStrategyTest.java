package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PvNamingStrategy} built-in strategies.
 */
class PvNamingStrategyTest {

    private static Meter.Id id(String name, Tag... tags) {
        return new Meter.Id(name, io.micrometer.core.instrument.Tags.of(tags),
                null, null, Meter.Type.GAUGE);
    }

    // --- DOTS_WITH_BRACE_TAGS ---

    @Test
    void dotsStrategy_noTags_returnsName() {
        Meter.Id mid = id("archiver.engine.pv.total");
        assertEquals("archiver.engine.pv.total",
                PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(mid));
    }

    @Test
    void dotsStrategy_oneTag_appendedInBraces() {
        Meter.Id mid = id("archiver.engine.pv.total", Tag.of("state", "connected"));
        assertEquals("archiver.engine.pv.total{state=\"connected\"}",
                PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(mid));
    }

    @Test
    void dotsStrategy_multipleTags_sortedAlphabetically() {
        Meter.Id mid = id("archiver.engine.pv.total",
                Tag.of("state", "connected"),
                Tag.of("appliance", "a0"));
        // appliance < state alphabetically
        assertEquals("archiver.engine.pv.total{appliance=\"a0\",state=\"connected\"}",
                PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(mid));
    }

    @Test
    void dotsStrategy_tagsAlreadyAlphabetical_orderPreserved() {
        Meter.Id mid = id("my.metric",
                Tag.of("aaa", "1"),
                Tag.of("bbb", "2"),
                Tag.of("ccc", "3"));
        assertEquals("my.metric{aaa=\"1\",bbb=\"2\",ccc=\"3\"}",
                PvNamingStrategy.DOTS_WITH_BRACE_TAGS.pvName(mid));
    }

    // --- COLONS ---

    @Test
    void colonsStrategy_noTags_dotsReplacedByColons() {
        Meter.Id mid = id("archiver.engine.pv.total");
        assertEquals("archiver:engine:pv:total",
                PvNamingStrategy.COLONS.pvName(mid));
    }

    @Test
    void colonsStrategy_withTags_colonSeparated() {
        Meter.Id mid = id("archiver.engine.pv.total",
                Tag.of("appliance", "a0"),
                Tag.of("state", "connected"));
        // tags sorted alphabetically: appliance before state
        assertEquals("archiver:engine:pv:total:appliance:a0:state:connected",
                PvNamingStrategy.COLONS.pvName(mid));
    }

    @Test
    void colonsStrategy_oneTag() {
        Meter.Id mid = id("http.requests", Tag.of("method", "GET"));
        assertEquals("http:requests:method:GET",
                PvNamingStrategy.COLONS.pvName(mid));
    }

    // --- NAME_ONLY ---

    @Test
    void nameOnlyStrategy_noTags() {
        Meter.Id mid = id("my.metric");
        assertEquals("my.metric", PvNamingStrategy.NAME_ONLY.pvName(mid));
    }

    @Test
    void nameOnlyStrategy_tagsDropped() {
        Meter.Id mid = id("my.metric", Tag.of("foo", "bar"), Tag.of("baz", "qux"));
        assertEquals("my.metric", PvNamingStrategy.NAME_ONLY.pvName(mid));
    }
}
