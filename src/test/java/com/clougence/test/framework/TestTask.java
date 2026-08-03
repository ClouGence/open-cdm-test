package com.clougence.test.framework;

public record TestTask(String id, String source, String sql, int caseCount,
                       String domain, String datasource, CheckedRunnable executable) {

    public TestTask {
        if (caseCount < 1) {
            throw new IllegalArgumentException("caseCount must be greater than zero: " + caseCount);
        }
    }

    public TestTask(String id, String source, CheckedRunnable executable) {
        this(id, source, null, 1, null, null, executable);
    }

    public TestTask(String id, String source, int caseCount, CheckedRunnable executable) {
        this(id, source, null, caseCount, null, null, executable);
    }

    public TestTask(String id, String source, String sql, CheckedRunnable executable) {
        this(id, source, sql, 1, null, null, executable);
    }

    public TestTask(String id, String source, String sql, int caseCount, CheckedRunnable executable) {
        this(id, source, sql, caseCount, null, null, executable);
    }

    public TestTask withContext(String domain, String datasource) {
        return new TestTask(id, source, sql, caseCount, domain, datasource, executable);
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
