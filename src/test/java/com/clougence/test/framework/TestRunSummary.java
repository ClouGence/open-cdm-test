package com.clougence.test.framework;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record TestRunSummary(long submitted, long passed, long failed, long elapsedMillis, List<TestFailure> failures, List<ScenarioSummary> scenarios) {

    private static final List<String> DOMAINS = List.of("behavior", "lineage", "split");

    public boolean successful() {
        return failed == 0;
    }

    public String failureMessage() {
        StringBuilder message = new StringBuilder("failed ").append(failed).append(" of ").append(submitted).append(" cases");
        for (TestFailure failure : failures) {
            message.append(System.lineSeparator()).append(failure.id()).append(": ").append(failure.error().getClass().getName()).append(": ").append(failure.error().getMessage());
        }
        return message.toString();
    }

    public String dataSourceReport() {
        Map<String, Map<String, ScenarioSummary>> dataSources = new TreeMap<>();
        for (ScenarioSummary scenario : scenarios) {
            dataSources.computeIfAbsent(scenario.datasource(), ignored -> new TreeMap<>()).put(scenario.domain(), scenario);
        }
        String prefix = "[open-cdm-test] ";
        int datasourceWidth = Math.max("DATASOURCE".length(), dataSources.keySet().stream().mapToInt(String::length).max().orElse(0));
        Map<String, ScenarioSummary> totals = new TreeMap<>();
        Map<String, Integer> domainWidths = new TreeMap<>();
        for (String domain : DOMAINS) {
            ScenarioSummary total = aggregate(domain, dataSources);
            totals.put(domain, total);
            int valueWidth = dataSources.values().stream().map(values -> formatCounts(values.get(domain))).mapToInt(String::length).max().orElse(0);
            domainWidths.put(domain, Math.max(domain.toUpperCase().length(), Math.max(valueWidth, formatCounts(total).length())));
        }

        String rowFormat = "%s%-" + datasourceWidth + "s";
        for (String domain : DOMAINS) {
            rowFormat += "  %" + domainWidths.get(domain) + "s";
        }
        rowFormat += "  %-6s%n";
        String separator = prefix + "-".repeat(datasourceWidth) + "  "
                           + DOMAINS.stream().map(domain -> "-".repeat(domainWidths.get(domain))).reduce((left, right) -> left + "  " + right).orElse("") + "  " + "-".repeat(6)
                           + System.lineSeparator();

        StringBuilder report = new StringBuilder("[open-cdm-test][DATASOURCE-SUMMARY] ").append("counts=total/passed/failed").append(System.lineSeparator());
        report.append(separator);
        report.append(String.format(rowFormat, prefix, "DATASOURCE", "BEHAVIOR", "LINEAGE", "SPLIT", "RESULT"));
        report.append(separator);
        for (Map.Entry<String, Map<String, ScenarioSummary>> entry : dataSources.entrySet()) {
            Map<String, ScenarioSummary> values = entry.getValue();
            long rowFailures = values.values().stream().mapToLong(ScenarioSummary::failed).sum();
            report.append(String.format(rowFormat, prefix, entry
                .getKey(), formatCounts(values.get("behavior")), formatCounts(values.get("lineage")), formatCounts(values.get("split")), rowFailures == 0 ? "PASS" : "FAIL"));
        }
        report.append(separator);
        report.append(String.format(rowFormat, prefix, "ALL", formatCounts(totals.get("behavior")), formatCounts(totals.get("lineage")), formatCounts(totals
            .get("split")), failed == 0 ? "PASS" : "FAIL"));
        return report.toString();
    }

    private static ScenarioSummary aggregate(String domain, Map<String, Map<String, ScenarioSummary>> dataSources) {
        long total = 0;
        long passed = 0;
        long failed = 0;
        for (Map<String, ScenarioSummary> values : dataSources.values()) {
            ScenarioSummary scenario = values.get(domain);
            if (scenario != null) {
                total += scenario.total();
                passed += scenario.passed();
                failed += scenario.failed();
            }
        }
        return new ScenarioSummary("ALL", domain, total, passed, failed);
    }

    private static String formatCounts(ScenarioSummary scenario) {
        if (scenario == null) {
            return "0/0/0";
        }
        return scenario.total() + "/" + scenario.passed() + "/" + scenario.failed();
    }

    public record TestFailure(String id, String source, Throwable error) {
    }

    public record ScenarioSummary(String datasource, String domain, long total, long passed, long failed) implements Comparable<ScenarioSummary> {

        @Override
        public int compareTo(ScenarioSummary other) {
            int datasourceOrder = datasource.compareTo(other.datasource);
            return datasourceOrder != 0 ? datasourceOrder : domain.compareTo(other.domain);
        }
    }
}
