package org.phoebus.pva.mapping.pva;

import org.phoebus.pva.mapping.config.ChannelConfig;
import org.phoebus.pva.mapping.config.MappingProperties;
import org.phoebus.pva.mapping.config.ServiceConfig;
import org.phoebus.pva.mapping.scraper.Scraper;
import org.phoebus.pva.mapping.scraper.ScraperFactory;
import org.phoebus.pva.mapping.scraper.ScraperResult;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Schedules one poll task per configured service, each running at its own interval.
 *
 * <p>Each task scrapes all channels for the service in sequence and writes results
 * into the {@link MappingRegistry}.  A successful scrape calls {@link MappingRegistry#setValue};
 * any failure calls {@link MappingRegistry#clearValue} which sets the gauge to NaN and
 * causes the registry to publish {@code INVALID} alarm.
 */
@Component
public class MappingScheduler implements SchedulingConfigurer {

    private static final Logger log = Logger.getLogger(MappingScheduler.class.getName());

    private final MappingProperties properties;
    private final ScraperFactory scraperFactory;
    private final MappingRegistry mappingRegistry;

    private record PollTarget(String pvName, String url, Scraper scraper) {}

    public MappingScheduler(MappingProperties properties,
                             ScraperFactory scraperFactory,
                             MappingRegistry mappingRegistry) {
        this.properties = properties;
        this.scraperFactory = scraperFactory;
        this.mappingRegistry = mappingRegistry;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (properties.services() == null || properties.services().isEmpty()) {
            log.warning("No services configured — nothing to poll.");
            return;
        }

        registrar.setScheduler(Executors.newScheduledThreadPool(properties.services().size()));
        properties.services().forEach((name, svc) -> registerServiceTask(registrar, name, svc));
    }

    private void registerServiceTask(ScheduledTaskRegistrar registrar,
                                     String name,
                                     ServiceConfig svc) {
        Duration interval = svc.pollInterval() != null
                ? svc.pollInterval()
                : properties.pollInterval();

        List<PollTarget> targets = buildTargets(name, svc);
        if (targets.isEmpty()) {
            log.warning("Service '" + name + "' has no channels configured — skipping.");
            return;
        }

        log.info("Scheduling service '" + name + "' with " + targets.size()
                + " channel(s) every " + interval);
        registrar.addFixedDelayTask(() -> pollService(name, targets), interval.toMillis());
    }

    private List<PollTarget> buildTargets(String serviceName, ServiceConfig svc) {
        List<PollTarget> targets = new ArrayList<>();
        if (svc.channels() == null) return targets;
        for (ChannelConfig ch : svc.channels()) {
            try {
                Scraper scraper = scraperFactory.create(ch.scraper());
                String url = ch.resolvedUrl(svc.baseUrl());
                targets.add(new PollTarget(ch.pv(), url, scraper));
            } catch (Exception e) {
                log.log(Level.SEVERE, "Cannot build scraper for PV '" + ch.pv()
                        + "' in service '" + serviceName + "': " + e.getMessage(), e);
            }
        }
        return targets;
    }

    private void pollService(String serviceName, List<PollTarget> targets) {
        for (PollTarget t : targets) {
            try {
                ScraperResult result = t.scraper().scrape(t.url());
                if (result instanceof ScraperResult.Value v) {
                    mappingRegistry.setValue(t.pvName(), v.value());
                } else {
                    mappingRegistry.clearValue(t.pvName());
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "Unexpected error polling '" + t.pvName()
                        + "' in service '" + serviceName + "'", e);
                mappingRegistry.clearValue(t.pvName());
            }
        }
    }
}
