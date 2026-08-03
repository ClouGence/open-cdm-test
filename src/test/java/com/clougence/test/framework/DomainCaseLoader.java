package com.clougence.test.framework;

import java.util.List;

public interface DomainCaseLoader {

    List<TestTask> load(String resourcePath, TestPlan.VariantConfig variant, DialectRuntime dialect);
}
