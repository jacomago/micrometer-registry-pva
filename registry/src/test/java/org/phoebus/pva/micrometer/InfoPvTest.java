package org.phoebus.pva.micrometer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the package-private {@link InfoPv#buildJson} method.
 *
 * <p>These tests verify field-selection logic (which fields are included or
 * omitted) and the expected JSON key names.  String escaping is delegated to
 * Jackson and is not tested here.
 */
class InfoPvTest {

    @Test
    void buildJson_allNullFields_returnsEmptyJson() {
        String json = InfoPv.buildJson(null, null, null, null, null);
        assertEquals("{}", json, "All-null inputs must produce an empty JSON object");
    }

    @Test
    void buildJson_allFieldsPresent() {
        String json = InfoPv.buildJson("svc", "1.0", "2024-01-15", "abc123", "myhost");
        assertEquals(
                "{\"name\":\"svc\",\"version\":\"1.0\",\"buildDate\":\"2024-01-15\","
                        + "\"gitCommit\":\"abc123\",\"host\":\"myhost\"}",
                json);
    }

    @Test
    void buildJson_partialFields_omitsNullFields() {
        String json = InfoPv.buildJson("svc", null, null, null, "myhost");
        assertEquals("{\"name\":\"svc\",\"host\":\"myhost\"}", json);
    }

    @Test
    void buildJson_onlyVersion_omitsOtherFields() {
        String json = InfoPv.buildJson(null, "2.3.1", null, null, null);
        assertEquals("{\"version\":\"2.3.1\"}", json);
    }
}
