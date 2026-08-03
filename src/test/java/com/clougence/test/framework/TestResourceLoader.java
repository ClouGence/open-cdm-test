package com.clougence.test.framework;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.clougence.test.framework.resource.TextCaseSupport;
import com.clougence.test.scenario.behavior.BehaviorDomainLoader;
import com.clougence.test.scenario.lineage.LineageDomainLoader;
import com.clougence.test.scenario.split.SplitDomainLoader;

public final class TestResourceLoader {

    public List<SourceSpec> sources(TestPlan plan, RunOptions options) {
        Map<String, TestPlan.DialectConfig> dialects = new HashMap<>();
        plan.dialects().forEach(dialect -> dialects.put(dialect.id(), dialect));
        List<SourceSpec> sources = new ArrayList<>();
        for (TestPlan.SuiteConfig suite : plan.suites()) {
            TestPlan.DialectConfig dialect = dialects.get(suite.dialect());
            if (dialect == null) {
                throw new IllegalArgumentException("Unknown dialect in suite " + suite.id() + ": " + suite.dialect());
            }
            for (TestPlan.VariantConfig variant : suite.variants()) {
                DialectRuntime runtime = new DialectRuntime(dialect, variant.version());
                for (String resourceRoot : variant.resources()) {
                    List<String> paths = TextCaseSupport.resourceFiles(resourceRoot, path -> !excluded(path, variant));
                    for (String path : paths) {
                        if (options.accepts(suite, variant, path)) {
                            sources.add(new SourceSpec(suite, variant, runtime, path));
                        }
                    }
                }
            }
        }
        return sources;
    }

    public List<TestTask> load(SourceSpec source) {
        return loader(source.suite().domain()).load(source.resourcePath(), source.variant(), source.dialect()).stream()
                .map(task -> task.withContext(source.suite().domain(), source.dialect().config().id()))
                .toList();
    }

    private static boolean excluded(String path, TestPlan.VariantConfig variant) {
        if (variant.excludeContains() == null) {
            return false;
        }
        return variant.excludeContains().stream().anyMatch(path::contains);
    }

    private static DomainCaseLoader loader(String domain) {
        return switch (domain) {
            case "split" -> new SplitDomainLoader();
            case "lineage" -> new LineageDomainLoader();
            case "behavior" -> new BehaviorDomainLoader();
            default -> throw new IllegalArgumentException("Unknown test domain: " + domain);
        };
    }

    public record SourceSpec(TestPlan.SuiteConfig suite, TestPlan.VariantConfig variant,
                             DialectRuntime dialect, String resourcePath) {
    }
}
