package com.clougence.test.scenario.lineage;

import java.util.ArrayList;
import java.util.List;

import com.clougence.test.framework.DialectRuntime;
import com.clougence.test.framework.DomainCaseLoader;
import com.clougence.test.framework.TestPlan.VariantConfig;
import com.clougence.test.framework.TestTask;
import com.clougence.clouddm.sdk.sql.analysis.lineage.LineageAnalysisSpi;
import com.clougence.clouddm.sdk.sql.analysis.lineage.SourceName;

public final class LineageDomainLoader implements DomainCaseLoader {

    @Override
    public List<TestTask> load(String resourcePath, VariantConfig variant, DialectRuntime dialect) {
        LineageTextTest verifier = verifier(dialect.config().id(), variant.locatedLineage());
        List<TestTask> tasks = new ArrayList<>();
        for (LineageTextTest.TestCase testCase : LineageTextTest.loadCases(resourcePath)) {
            String datasource = testCase.datasource == null ? dialect.config().id() : testCase.datasource;
            String id = testCase.displayName(datasource);
            tasks.add(new TestTask(id, resourcePath, testCase.sql, () -> {
                LineageAnalysisSpi spi = dialect.engine().lineageAnalysisSpi(dialect.parameters());
                if (spi == null) {
                    throw new IllegalStateException("No LineageAnalysisSpi for " + dialect.config().id());
                }
                verifier.assertCase(resourcePath, testCase, spi);
            }));
        }
        return tasks;
    }

    private static LineageTextTest verifier(String datasource, boolean located) {
        return new LineageTextTest() {
            @Override
            protected String datasource() {
                return datasource;
            }

            @Override
            protected String sourcePath(SourceName sourceName) {
                return located ? sourceName.toLocatedDsResPath() : sourceName.toDsResPath();
            }
        };
    }
}
