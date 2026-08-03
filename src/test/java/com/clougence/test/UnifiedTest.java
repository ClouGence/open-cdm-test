package com.clougence.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.clougence.test.framework.RunOptions;
import com.clougence.test.framework.TestRunSummary;
import com.clougence.test.framework.TestRunner;

public final class UnifiedTest {

    @Test
    void runConfiguredSuites() {
        TestRunSummary summary = new TestRunner().run(RunOptions.fromSystemProperties());
        assertTrue(summary.successful(), summary.failureMessage());
    }
}
