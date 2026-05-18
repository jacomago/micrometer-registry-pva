package org.phoebus.pva.mapping.pva;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PreDestroy;
import org.epics.pva.server.PVAServer;
import org.phoebus.pva.micrometer.PvaMeterRegistry;
import org.phoebus.pva.micrometer.PvaMeterRegistryConfig;
import org.phoebus.pva.micrometer.PvNamingStrategy;
import org.phoebus.pva.mapping.config.ChannelConfig;
import org.phoebus.pva.mapping.config.MappingProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Creates a {@link PvaMeterRegistry} (backed by a caller-managed {@link PVAServer}) and
 * registers one Micrometer {@link Gauge} per configured channel, each backed by an
 * {@link AtomicReference}{@code <Double>}.
 *
 * <p>The registry handles all PVA mechanics: NTScalar structure, alarm, and timestamp.
 * A gauge returning {@link Double#NaN} is automatically published with {@code INVALID} alarm;
 * a normal value gets {@code NO_ALARM}.
 *
 * <p>{@link MappingScheduler} calls {@link #setValue} / {@link #clearValue} on each poll
 * tick to feed scraped results into the gauges.
 *
 * <p>PV names are passed through verbatim via {@link PvNamingStrategy#NAME_ONLY}.
 */
@Component
public class MappingRegistry {

    private static final Logger log = Logger.getLogger(MappingRegistry.class.getName());

    private final PVAServer pvaServer;
    private final PvaMeterRegistry registry;

    /** Strong references to the gauge state, keyed by PV name. */
    private final Map<String, AtomicReference<Double>> gaugeValues = new LinkedHashMap<>();

    public MappingRegistry(MappingProperties properties) throws Exception {
        this.pvaServer = new PVAServer();
        Duration step = properties.pollInterval();
        this.registry = new PvaMeterRegistry(new PvaMeterRegistryConfig() {
            @Override public String get(String key) { return null; }
            @Override public Duration step() { return step; }
            @Override public PvNamingStrategy namingStrategy() { return PvNamingStrategy.NAME_ONLY; }
        }, Clock.SYSTEM, pvaServer);

        if (properties.services() != null) {
            properties.services().forEach((svcName, svc) -> {
                if (svc.channels() == null) return;
                for (ChannelConfig ch : svc.channels()) {
                    AtomicReference<Double> ref = new AtomicReference<>(Double.NaN);
                    gaugeValues.put(ch.pv(), ref);
                    Gauge.builder(ch.pv(), ref, AtomicReference::get)
                            .description(ch.description())
                            .baseUnit(ch.unit())
                            .register(registry);
                }
            });
        }

        log.info("PVA mapping registry started with " + gaugeValues.size() + " gauge(s).");
    }

    /** Sets the gauge to {@code value}; the registry publishes {@code NO_ALARM} on the next tick. */
    public void setValue(String pvName, double value) {
        AtomicReference<Double> ref = gaugeValues.get(pvName);
        if (ref != null) {
            ref.set(value);
        } else {
            log.warning("setValue: no gauge registered for PV '" + pvName + "'");
        }
    }

    /** Sets the gauge to NaN; the registry publishes {@code INVALID} alarm on the next tick. */
    public void clearValue(String pvName) {
        setValue(pvName, Double.NaN);
    }

    /** Returns the TCP port the embedded {@link PVAServer} is listening on (used by tests). */
    public int getServerPort() throws Exception {
        return pvaServer.getTCPAddress(false).getPort();
    }

    @PreDestroy
    public void close() {
        registry.close();
        try { pvaServer.close(); } catch (Exception ignored) {}
    }
}
