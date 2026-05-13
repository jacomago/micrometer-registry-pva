package org.phoebus.pva.mapping.scraper;

import java.util.Map;

/** One Prometheus time-series sample: metric name, label set, and numeric value. */
public record Sample(String name, Map<String, String> labels, double value) {}
