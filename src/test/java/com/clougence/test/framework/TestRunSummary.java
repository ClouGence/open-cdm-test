package com.clougence.test.framework;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record TestRunSummary(long submitted, long passed, long failed, long elapsedMillis,
                             List<TestFailure> failures, List<ScenarioSummary> scenarios) {

    private static final List<String> DOMAINS = List.of("behavior", "lineage", "split");

    public boolean successful() {
        return failed == 0;
    }

    public String failureMessage() {
        StringBuilder message = new StringBuilder("failed ").append(failed)
                .append(" of ").append(submitted).append(" cases");
        for (TestFailure failure : failures) {
            message.append(System.lineSeparator()).append(failure.id()).append(": ")
                    .append(failure.error().getClass().getName()).append(": ")
                    .append(failure.error().getMessage());
        }
        return message.toString();
    }

    public String dataSourceReport() {
        Map<String, Map<String, ScenarioSummary>> dataSources = new TreeMap<>();
        for (ScenarioSummary scenario : scenarios) {
            dataSources.computeIfAbsent(scenario.datasource(), ignored -> new TreeMap<>())
                    .put(scenario.domain(), scenario);
        }
        StringBuilder report = new StringBuilder("[open-cdm-test][DATASOURCE-SUMMARY]")
                .append(System.lineSeparator());
        for (Map.Entry<String, Map<String, ScenarioSummary>> entry : dataSources.entrySet()) {
            report.append("[open-cdm-test][DATASOURCE] datasource=").append(entry.getKey());
            for (String domain : DOMAINS) {
                ScenarioSummary scenario = entry.getValue().getOrDefault(domain,
                        new ScenarioSummary(entry.getKey(), domain, 0, 0, 0));
                report.append(' ').append(domain).append("(total=").append(scenario.total())
                        .append(",passed=").append(scenario.passed())
                        .append(",failed=").append(scenario.failed()).append(')');
            }
            report.append(System.lineSeparator());
        }
        return report.toString();
    }

    public record TestFailure(String id, String source, Throwable error) {
    }

    public record ScenarioSummary(String datasource, String domain, long total, long passed, long failed)
            implements Comparable<ScenarioSummary> {

        @Override
        public int compareTo(ScenarioSummary other) {
            int datasourceOrder = datasource.compareTo(other.datasource);
            return datasourceOrder != 0 ? datasourceOrder : domain.compareTo(other.domain);
        }
    }
}
