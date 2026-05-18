package org.phoebus.pva.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.pva.mapping.config.ScraperConfig;
import org.phoebus.pva.mapping.scraper.HttpStatusScraper;
import org.phoebus.pva.mapping.scraper.JsonScraper;
import org.phoebus.pva.mapping.scraper.PrometheusScraper;
import org.phoebus.pva.mapping.scraper.Scraper;
import org.phoebus.pva.mapping.scraper.ScraperFactory;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScraperFactoryTest {

    private ScraperFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ScraperFactory(new ObjectMapper());
    }

    @Test
    void httpStatus_createsHttpStatusScraper() {
        ScraperConfig config = new ScraperConfig("http-status", null, null, null, null);
        Scraper scraper = factory.create(config);
        assertInstanceOf(HttpStatusScraper.class, scraper);
    }

    @Test
    void prometheus_createsPrometheusScraper() {
        ScraperConfig config = new ScraperConfig("prometheus", "my_metric", null, null, null);
        Scraper scraper = factory.create(config);
        assertInstanceOf(PrometheusScraper.class, scraper);
    }

    @Test
    void json_createsJsonScraper() {
        ScraperConfig config = new ScraperConfig("json", null, null, "status", null);
        Scraper scraper = factory.create(config);
        assertInstanceOf(JsonScraper.class, scraper);
    }

    @Test
    void unknownType_throwsIllegalArgumentException() {
        ScraperConfig config = new ScraperConfig("graphql", null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> factory.create(config));
    }

    @Test
    void nullConfig_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(null));
    }
}
