package com.clougence.test.framework;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.clougence.test.framework.TestResourceLoader.SourceSpec;
import com.clougence.test.framework.resource.TextCaseSupport;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TestRunner {

    private static final String PLAN_RESOURCE = "config/test-plan.json";
    private static final String ANTLR_CACHE_SLOTS_PROPERTY = "com.clougence.sql.antlr.cacheSlots";
    private static final String ANTLR_MAX_SLOTS_PER_KEY_PROPERTY = "com.clougence.sql.antlr.maxSlotsPerKey";
    private static final String ANTLR_MAX_DFA_STATES_PROPERTY = "com.clougence.sql.antlr.maxDfaStatesPerSlot";
    private static final TestTask STOP = new TestTask("<stop>", "<framework>", () -> { });

    public TestRunSummary run(RunOptions options) {
        TestPlan plan = loadPlan();
        int workers = options.workers() == null ? plan.runtime().workers() : options.workers();
        if (workers < 1) {
            workers = Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        int configuredProducers = options.producers() == null ? plan.runtime().producers() : options.producers();
        if (configuredProducers < 1) {
            throw new IllegalArgumentException("producers must be greater than zero: " + configuredProducers);
        }
        int capacity = options.queueCapacity() == null ? plan.runtime().queueCapacity() : options.queueCapacity();
        int antlrCacheSlots = options.antlrCacheSlots() == null ?
                plan.runtime().antlrCacheSlots() : options.antlrCacheSlots();
        int antlrMaxSlotsPerKey = options.antlrMaxSlotsPerKey() == null ?
                plan.runtime().antlrMaxSlotsPerKey() : options.antlrMaxSlotsPerKey();
        int antlrMaxDfaStates = options.antlrMaxDfaStatesPerSlot() == null ?
                plan.runtime().antlrMaxDfaStatesPerSlot() : options.antlrMaxDfaStatesPerSlot();
        configurePositive(ANTLR_CACHE_SLOTS_PROPERTY, antlrCacheSlots);
        configurePositive(ANTLR_MAX_SLOTS_PER_KEY_PROPERTY, antlrMaxSlotsPerKey);
        configurePositive(ANTLR_MAX_DFA_STATES_PROPERTY, antlrMaxDfaStates);
        TestQueue queue = new TestQueue(capacity);
        TestCollector collector = new TestCollector();
        System.out.println("[open-cdm-test] failure-report=" + collector.failureReport());
        TestResourceLoader loader = new TestResourceLoader();
        List<SourceSpec> sources = loader.sources(plan, options);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("No test resources matched: " + options);
        }
        collector.totalSources(sources.size());
        sources.forEach(source -> collector.registerScenario(
                source.dialect().config().id(), source.suite().domain()));
        int producers = Math.min(configuredProducers, sources.size());

        ExecutorService executors = Executors.newFixedThreadPool(workers + producers);
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-progress");
            thread.setDaemon(true);
            return thread;
        });
        reporter.scheduleAtFixedRate(() -> System.out.println(collector.progress(queue)),
                1, 1, TimeUnit.SECONDS);

        AtomicInteger nextSource = new AtomicInteger();
        Future<?>[] producerFutures = new Future<?>[producers];
        for (int i = 0; i < producers; i++) {
            producerFutures[i] = executors.submit(() -> produce(sources, loader, queue, collector, nextSource));
        }
        Future<?>[] consumers = new Future<?>[workers];
        for (int i = 0; i < workers; i++) {
            consumers[i] = executors.submit(() -> consume(queue, collector));
        }
        try {
            for (Future<?> producer : producerFutures) {
                producer.get();
            }
            for (int i = 0; i < workers; i++) {
                queue.put(STOP);
            }
            for (Future<?> consumer : consumers) {
                consumer.get();
            }
        } catch (Exception e) {
            throw new IllegalStateException("test framework execution failed", e);
        } finally {
            reporter.shutdownNow();
            executors.shutdownNow();
        }
        System.out.println(collector.progress(queue));
        TestRunSummary summary = collector.summary();
        System.out.print(summary.dataSourceReport());
        System.out.flush();
        return summary;
    }

    private static void configurePositive(String property, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(property + " must be greater than zero: " + value);
        }
        System.setProperty(property, Integer.toString(value));
    }

    private static void produce(List<SourceSpec> sources, TestResourceLoader loader, TestQueue queue,
                                TestCollector collector, AtomicInteger nextSource) {
        try {
            int sourceIndex;
            while ((sourceIndex = nextSource.getAndIncrement()) < sources.size()) {
                SourceSpec source = sources.get(sourceIndex);
                try {
                    List<TestTask> tasks = loader.load(source);
                    collector.casesDiscovered(tasks);
                    for (TestTask task : tasks) {
                        queue.put(task);
                        collector.submitted(task);
                    }
                } catch (Throwable error) {
                    TestTask failure = new TestTask(source.resourcePath(), source.resourcePath(),
                            readFailureSql(source.resourcePath()), () -> {
                        if (error instanceof Exception exception) {
                            throw exception;
                        }
                        throw new RuntimeException(error);
                    }).withContext(source.suite().domain(), source.dialect().config().id());
                    collector.caseDiscovered(failure);
                    queue.put(failure);
                    collector.submitted(failure);
                } finally {
                    collector.sourceLoaded();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test resource loading interrupted", e);
        }
    }

    private static String readFailureSql(String resourcePath) {
        try {
            return TextCaseSupport.readResource(resourcePath);
        } catch (RuntimeException ignored) {
            return "<unavailable: resource could not be read>";
        }
    }

    private static void consume(TestQueue queue, TestCollector collector) {
        try {
            while (true) {
                TestTask task = queue.take();
                if (task == STOP) {
                    return;
                }
                try {
                    task.executable().run();
                    collector.passed(task);
                } catch (Throwable error) {
                    collector.failed(task, error);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static TestPlan loadPlan() {
        try (InputStream input = TestRunner.class.getClassLoader().getResourceAsStream(PLAN_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing test plan: " + PLAN_RESOURCE);
            }
            return new ObjectMapper().readValue(input, TestPlan.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load test plan: " + PLAN_RESOURCE, e);
        }
    }
}
