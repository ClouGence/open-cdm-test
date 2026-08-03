package com.clougence.test.framework;

import java.util.HashMap;
import java.util.Map;

public record RunOptions(String domain, String datasource, String version, String resource,
                         Integer producers, Integer workers, Integer queueCapacity,
                         Integer antlrCacheSlots, Integer antlrMaxSlotsPerKey,
                         Integer antlrMaxDfaStatesPerSlot) {

    public static RunOptions parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("arguments must use --name=value: " + arg);
            }
            int split = arg.indexOf('=');
            values.put(arg.substring(2, split), arg.substring(split + 1));
        }
        return new RunOptions(values.get("domain"), values.get("datasource"), values.get("version"),
                values.get("resource"), integer(values.get("producers")), integer(values.get("workers")),
                integer(values.get("queue-capacity")), integer(values.get("antlr-cache-slots")),
                integer(values.get("antlr-max-slots-per-key")),
                integer(values.get("antlr-max-dfa-states-per-slot")));
    }

    public static RunOptions fromSystemProperties() {
        return new RunOptions(System.getProperty("test.domain"), System.getProperty("test.datasource"),
                System.getProperty("test.version"), System.getProperty("test.resource"),
                integer(System.getProperty("test.producers")), integer(System.getProperty("test.workers")),
                integer(System.getProperty("test.queueCapacity")),
                integer(System.getProperty("test.antlrCacheSlots")),
                integer(System.getProperty("test.antlrMaxSlotsPerKey")),
                integer(System.getProperty("test.antlrMaxDfaStatesPerSlot")));
    }

    public boolean accepts(TestPlan.SuiteConfig suite, TestPlan.VariantConfig variant, String resourcePath) {
        return matches(domain, suite.domain()) && matches(datasource, suite.dialect()) &&
                matches(version, variant.version()) && matches(resource, resourcePath);
    }

    private static boolean matches(String filter, String value) {
        return filter == null || filter.isBlank() || value != null && value.contains(filter);
    }

    private static Integer integer(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }
}
