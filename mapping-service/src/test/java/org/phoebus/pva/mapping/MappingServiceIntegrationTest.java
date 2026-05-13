package org.phoebus.pva.mapping;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.epics.pva.PVASettings;
import org.epics.pva.client.PVAClient;
import org.epics.pva.client.PVAChannel;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAInt;
import org.epics.pva.data.PVAStructure;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.phoebus.pva.mapping.pva.PvaChannelManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end integration test: real PVAServer + MockWebServer HTTP stubs.
 *
 * <p>The Spring context starts with a short poll interval (500 ms) and all scrape URLs
 * pointing at a MockWebServer dispatcher.  After each scenario we wait for at least one
 * poll cycle, then use a PVAClient to read values and alarm severities back.
 *
 * <p>Alarm severity integers match standard EPICS: 0=NO_ALARM, 1=MINOR, 2=MAJOR, 3=INVALID.
 * (Same convention as the library's {@code PvaServiceBinderTest}.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MappingServiceIntegrationTest {

    static {
        // Restrict PVAServer and PVAClient to IPv4 only; environments without
        // IPv6 support throw UnsupportedOperationException from DatagramChannel.open(INET6).
        PVASettings.EPICS_PVAS_INTF_ADDR_LIST = "127.0.0.1";
        PVASettings.EPICS_PVA_ENABLE_IPV6 = false;
    }

    static final int ALARM_NO_ALARM = 0;
    static final int ALARM_MINOR    = 1;
    static final int ALARM_MAJOR    = 2;

    private static final long POLL_MS = 500;

    // Mutable state shared by the MockWebServer dispatcher
    static final AtomicInteger  healthStatus  = new AtomicInteger(200);
    static final AtomicReference<String> prometheusBody = new AtomicReference<>("");
    static final AtomicReference<String> healthBody     = new AtomicReference<>("{\"status\":\"UP\"}");

    private static MockWebServer mockServer;

    @Autowired
    PvaChannelManager channelManager;

    // ── Dynamic Spring configuration ──────────────────────────────────────────

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws Exception {
        mockServer = new MockWebServer();
        mockServer.setDispatcher(new Dispatcher() {
            @Override
            public @NotNull MockResponse dispatch(@NotNull RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                if (path.startsWith("/health")) {
                    int code = healthStatus.get();
                    if (code >= 200 && code < 300) {
                        return new MockResponse().setResponseCode(code)
                                .setBody(healthBody.get());
                    }
                    return new MockResponse().setResponseCode(code);
                }
                if (path.startsWith("/prometheus")) {
                    return new MockResponse().setBody(prometheusBody.get());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        mockServer.start();

        String rawBase = mockServer.url("").toString();
        final String baseUrl = rawBase.endsWith("/") ? rawBase.substring(0, rawBase.length() - 1) : rawBase;

        registry.add("pva-mapping.poll-interval", () -> "PT0.5S");
        registry.add("pva-mapping.services.svc.base-url", () -> baseUrl);

        // TEST:up  — http-status scraper against /health
        registry.add("pva-mapping.services.svc.channels[0].pv",          () -> "TEST:up");
        registry.add("pva-mapping.services.svc.channels[0].path",         () -> "/health");
        registry.add("pva-mapping.services.svc.channels[0].scraper.type", () -> "http-status");
        registry.add("pva-mapping.services.svc.channels[0].description",  () -> "Service reachable");

        // TEST:heap — prometheus scraper against /prometheus
        registry.add("pva-mapping.services.svc.channels[1].pv",                      () -> "TEST:heap");
        registry.add("pva-mapping.services.svc.channels[1].path",                    () -> "/prometheus");
        registry.add("pva-mapping.services.svc.channels[1].scraper.type",            () -> "prometheus");
        registry.add("pva-mapping.services.svc.channels[1].scraper.metric",          () -> "jvm_memory_used_bytes");
        registry.add("pva-mapping.services.svc.channels[1].scraper.labels[area]",    () -> "heap");

        // TEST:status — json scraper against /health
        registry.add("pva-mapping.services.svc.channels[2].pv",                         () -> "TEST:status");
        registry.add("pva-mapping.services.svc.channels[2].path",                       () -> "/health");
        registry.add("pva-mapping.services.svc.channels[2].scraper.type",               () -> "json");
        registry.add("pva-mapping.services.svc.channels[2].scraper.json-path",          () -> "status");
        registry.add("pva-mapping.services.svc.channels[2].scraper.value-map.UP",       () -> "1.0");
        registry.add("pva-mapping.services.svc.channels[2].scraper.value-map.DOWN",     () -> "0.0");
        registry.add("pva-mapping.services.svc.channels[2].scraper.value-map.DEGRADED", () -> "0.5");
    }

    // ── PVA client (shared across all tests) ─────────────────────────────────

    static PVAClient pvaClient;
    static Map<String, PVAChannel> channelCache = new HashMap<>();
    static String savedNameServers;
    static boolean savedAutoAddrList;

    @BeforeAll
    static void setUpPvaClient(@Autowired PvaChannelManager mgr) throws Exception {
        int port = mgr.getServerPort();
        savedNameServers   = PVASettings.EPICS_PVA_NAME_SERVERS;
        savedAutoAddrList  = PVASettings.EPICS_PVA_AUTO_ADDR_LIST;
        PVASettings.EPICS_PVA_NAME_SERVERS  = "127.0.0.1:" + port;
        PVASettings.EPICS_PVA_AUTO_ADDR_LIST = false;
        pvaClient = new PVAClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        channelCache.values().forEach(ch -> { try { ch.close(); } catch (Exception ignored) {} });
        if (pvaClient != null) pvaClient.close();
        PVASettings.EPICS_PVA_NAME_SERVERS  = savedNameServers;
        PVASettings.EPICS_PVA_AUTO_ADDR_LIST = savedAutoAddrList;
        if (mockServer != null) mockServer.shutdown();
    }

    private PVAStructure readPv(String pvName) throws Exception {
        PVAChannel ch = channelCache.computeIfAbsent(pvName, pvaClient::getChannel);
        ch.connect().get(5, TimeUnit.SECONDS);
        return ch.read("").get(5, TimeUnit.SECONDS);
    }

    private double readDouble(String pvName) throws Exception {
        return ((PVADouble) readPv(pvName).get("value")).get();
    }

    private int readAlarmSeverity(String pvName) throws Exception {
        PVAStructure alarm = readPv(pvName).get("alarm");
        return ((PVAInt) alarm.get("severity")).get();
    }

    // ── Scenarios ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void httpStatus_serviceUp_value1AndNoAlarm() throws Exception {
        healthStatus.set(200);
        prometheusBody.set(prometheusBody(12345678.0));
        healthBody.set("{\"status\":\"UP\"}");

        Thread.sleep(POLL_MS * 3);

        assertEquals(1.0, readDouble("TEST:up"), 1e-9);
        assertEquals(ALARM_NO_ALARM, readAlarmSeverity("TEST:up"));
    }

    @Test
    @Order(2)
    void prometheusScraper_metricFound_correctValueAndNoAlarm() throws Exception {
        healthStatus.set(200);
        prometheusBody.set(prometheusBody(99999.0));
        healthBody.set("{\"status\":\"UP\"}");

        Thread.sleep(POLL_MS * 3);

        assertEquals(99999.0, readDouble("TEST:heap"), 1e-9);
        assertEquals(ALARM_NO_ALARM, readAlarmSeverity("TEST:heap"));
    }

    @Test
    @Order(3)
    void jsonScraper_statusUp_mappedTo1AndNoAlarm() throws Exception {
        healthStatus.set(200);
        prometheusBody.set(prometheusBody(1.0));
        healthBody.set("{\"status\":\"UP\"}");

        Thread.sleep(POLL_MS * 3);

        assertEquals(1.0, readDouble("TEST:status"), 1e-9);
        assertEquals(ALARM_NO_ALARM, readAlarmSeverity("TEST:status"));
    }

    @Test
    @Order(4)
    void prometheusScraper_metricAbsent_minorAlarm() throws Exception {
        healthStatus.set(200);
        prometheusBody.set("other_metric 1.0\n");   // no jvm_memory_used_bytes
        healthBody.set("{\"status\":\"UP\"}");

        Thread.sleep(POLL_MS * 3);

        assertEquals(ALARM_MINOR, readAlarmSeverity("TEST:heap"));
    }

    @Test
    @Order(5)
    void httpStatus_service503_majorAlarm() throws Exception {
        healthStatus.set(503);
        prometheusBody.set(prometheusBody(1.0));
        healthBody.set("{\"status\":\"DOWN\"}");

        Thread.sleep(POLL_MS * 3);

        assertEquals(ALARM_MAJOR, readAlarmSeverity("TEST:up"));
    }

    @Test
    @Order(6)
    void serviceRecovery_alarmClearsAfterRecovery() throws Exception {
        // Restore healthy state after the 503 scenario
        healthStatus.set(200);
        prometheusBody.set(prometheusBody(12345678.0));
        healthBody.set("{\"status\":\"UP\"}");

        Thread.sleep(POLL_MS * 3);

        assertEquals(ALARM_NO_ALARM, readAlarmSeverity("TEST:up"));
        assertEquals(1.0, readDouble("TEST:up"), 1e-9);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String prometheusBody(double heapValue) {
        return "# TYPE jvm_memory_used_bytes gauge\n"
             + "jvm_memory_used_bytes{area=\"heap\",id=\"G1 Old Gen\"} " + heapValue + "\n"
             + "jvm_memory_used_bytes{area=\"nonheap\",id=\"Metaspace\"} 55000.0\n";
    }
}
