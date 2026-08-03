package com.clougence.test.scenario.split;

import java.util.ArrayList;
import java.util.List;

import com.clougence.test.framework.DialectRuntime;
import com.clougence.test.framework.DomainCaseLoader;
import com.clougence.test.framework.TestPlan.VariantConfig;
import com.clougence.test.framework.TestTask;
import com.clougence.clouddm.sdk.sql.parser.SplitAnalysisSpi;

public final class SplitDomainLoader implements DomainCaseLoader {

    @Override
    public List<TestTask> load(String resourcePath, VariantConfig variant, DialectRuntime dialect) {
        try {
            SplitTextTest.SplitFixture fixture = SplitTextTest.loadFixture(resourcePath);
            if (!variant.rejected()) {
                return List.of(new TestTask(resourcePath, resourcePath, fixture.inputSql(), fixture.expected().size(), () -> {
                    SplitAnalysisSpi spi = dialect.engine().splitAnalysisSpi(dialect.parameters());
                    if (spi == null) {
                        throw new IllegalStateException("No SplitAnalysisSpi for " + dialect.config().id());
                    }
                    SplitTextTest.verifyFixture(fixture, spi, true);
                }));
            }
            List<TestTask> tasks = new ArrayList<>();
            for (SplitTextTest.SplitCase splitCase : fixture.cases()) {
                String id = splitCase.displayName();
                String sql = fixture.expected().get(splitCase.splitIndex()).script();
                tasks.add(new TestTask(id, resourcePath, sql, () -> assertRejected(dialect, sql, id)));
            }
            return tasks;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load split fixture: " + resourcePath, e);
        }
    }

    private static void assertRejected(DialectRuntime dialect, String sql, String id) {
        SplitAnalysisSpi spi = dialect.engine().splitAnalysisSpi(dialect.parameters());
        try {
            spi.splitScript(sql, null, 0, 0);
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected parser rejection: " + id);
    }
}
