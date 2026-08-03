package com.clougence.test;

import com.clougence.test.framework.RunOptions;
import com.clougence.test.framework.TestRunSummary;
import com.clougence.test.framework.TestRunner;

public final class TestMain {
    public static void main(String[] args) {
        TestRunSummary summary = new TestRunner().run(RunOptions.parse(args));
        if (!summary.successful()) {
            System.err.println(summary.failureMessage());
            System.exit(1);
        }
    }
}
