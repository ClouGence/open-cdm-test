package com.clougence.test.framework;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.clougence.test.framework.TestRunSummary.TestFailure;
import com.clougence.test.framework.TestRunSummary.ScenarioSummary;

public final class TestCollector {

    private static final Path FAILURE_REPORT = Path.of("build", "reports", "open-cdm-test", "failures.log");

    private final long                         startedNanos = System.nanoTime();
    private final AtomicInteger                totalSources = new AtomicInteger();
    private final AtomicInteger                loadedSources = new AtomicInteger();
    private final AtomicLong                   discoveredCases = new AtomicLong();
    private final AtomicLong                   submitted = new AtomicLong();
    private final AtomicLong                   passed = new AtomicLong();
    private final AtomicLong                   failed = new AtomicLong();
    private final ConcurrentLinkedQueue<TestFailure> failures = new ConcurrentLinkedQueue<>();
    private final Map<ScenarioKey, ScenarioCounters> scenarios = new ConcurrentHashMap<>();
    private long                               lastProgressNanos = startedNanos;
    private long                               lastProgressCompleted;
    private long                               lastRps;

    public TestCollector() {
        try {
            Files.createDirectories(FAILURE_REPORT.getParent());
            Files.writeString(FAILURE_REPORT, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize failure report: " + FAILURE_REPORT.toAbsolutePath(), e);
        }
    }

    public Path failureReport() {
        return FAILURE_REPORT.toAbsolutePath().normalize();
    }

    public void totalSources(int value) {
        totalSources.set(value);
    }

    public void registerScenario(String datasource, String domain) {
        scenarios.computeIfAbsent(new ScenarioKey(datasource, domain), ignored -> new ScenarioCounters());
    }

    public void sourceLoaded() {
        loadedSources.incrementAndGet();
    }

    public void casesDiscovered(List<TestTask> tasks) {
        long count = 0;
        for (TestTask task : tasks) {
            count += task.caseCount();
        }
        discoveredCases.addAndGet(count);
    }

    public void caseDiscovered(TestTask task) {
        discoveredCases.addAndGet(task.caseCount());
    }

    public void submitted(TestTask task) {
        submitted.addAndGet(task.caseCount());
        counters(task).total.addAndGet(task.caseCount());
    }

    public void passed(TestTask task) {
        passed.addAndGet(task.caseCount());
        counters(task).passed.addAndGet(task.caseCount());
    }

    public void failed(TestTask task, Throwable error) {
        failed.addAndGet(task.caseCount());
        counters(task).failed.addAndGet(task.caseCount());
        failures.add(new TestFailure(task.id(), task.source(), error));
        printFailure(task, error);
    }

    private static void printFailure(TestTask task, Throwable error) {
        StringWriter output = new StringWriter(2048);
        PrintWriter writer = new PrintWriter(output);
        writer.println();
        writer.println("[open-cdm-test][FAILED]");
        writer.println("script: " + task.source());
        writer.println("case: " + task.id());
        writer.println("case-count: " + task.caseCount());
        writer.println("sql:");
        writer.println(task.sql() == null ? "<unavailable>" : task.sql());
        writer.println("stacktrace:");
        error.printStackTrace(writer);
        writer.println("[open-cdm-test][FAILED-END]");
        writer.flush();
        synchronized (System.err) {
            System.err.print(output);
            System.err.flush();
            try {
                Files.writeString(FAILURE_REPORT, output.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[open-cdm-test][FAILURE-REPORT-ERROR] path=" +
                        FAILURE_REPORT.toAbsolutePath() + " error=" + e);
            }
        }
    }

    public synchronized String progress(TestQueue queue) {
        long now = System.nanoTime();
        long seconds = Duration.ofNanos(now - startedNanos).toSeconds();
        int loadedSourceCount = loadedSources.get();
        int totalSourceCount = totalSources.get();
        long passedCount = passed.get();
        long failedCount = failed.get();
        long completedCount = passedCount + failedCount;
        long discoveredCaseCount = discoveredCases.get();
        long progressNanos = now - this.lastProgressNanos;
        if (progressNanos >= TimeUnit.MILLISECONDS.toNanos(250)) {
            this.lastRps = Math.round((completedCount - this.lastProgressCompleted) *
                    (double) TimeUnit.SECONDS.toNanos(1) / progressNanos);
            this.lastProgressCompleted = completedCount;
            this.lastProgressNanos = now;
        }
        String totalSuffix = loadedSourceCount < totalSourceCount ? "+" : "";
        return "[open-cdm-test] source=" + loadedSourceCount + "/" + totalSourceCount +
                " cases=" + completedCount + "/" + discoveredCaseCount + totalSuffix +
                " queue=" + queue.size() + "/" + queue.capacity() +
                " passed=" + passedCount + " failed=" + failedCount +
                " rate=" + this.lastRps + "rps" +
                " elapsed=" + seconds + "s";
    }

    public TestRunSummary summary() {
        long elapsed = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        List<ScenarioSummary> scenarioSummaries = scenarios.entrySet().stream()
                .map(entry -> new ScenarioSummary(entry.getKey().datasource(), entry.getKey().domain(),
                        entry.getValue().total.get(), entry.getValue().passed.get(), entry.getValue().failed.get()))
                .sorted()
                .toList();
        return new TestRunSummary(submitted.get(), passed.get(), failed.get(), elapsed,
                List.copyOf(failures), scenarioSummaries);
    }

    private ScenarioCounters counters(TestTask task) {
        String datasource = task.datasource() == null ? "<unknown>" : task.datasource();
        String domain = task.domain() == null ? "<unknown>" : task.domain();
        return scenarios.computeIfAbsent(new ScenarioKey(datasource, domain), ignored -> new ScenarioCounters());
    }

    private record ScenarioKey(String datasource, String domain) {
    }

    private static final class ScenarioCounters {

        private final AtomicLong total = new AtomicLong();
        private final AtomicLong passed = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
    }
}
