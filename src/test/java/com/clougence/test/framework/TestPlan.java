package com.clougence.test.framework;

import java.util.List;

public record TestPlan(RuntimeConfig runtime, List<DialectConfig> dialects, List<SuiteConfig> suites) {

    public record RuntimeConfig(int queueCapacity, int producers, int workers,
                                int antlrCacheSlots, int antlrMaxSlotsPerKey,
                                int antlrMaxDfaStatesPerSlot) {
    }

    public record DialectConfig(String id, String engineClass, String dataSourceType,
                                String permissionRegistryClass) {
    }

    public record SuiteConfig(String id, String domain, String dialect, List<VariantConfig> variants) {
    }

    public record VariantConfig(String version, List<String> resources, List<String> excludeContains,
                                boolean rejected, boolean locatedLineage) {
    }
}
