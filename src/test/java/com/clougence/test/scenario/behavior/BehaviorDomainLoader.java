package com.clougence.test.scenario.behavior;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.clougence.test.framework.DialectRuntime;
import com.clougence.test.framework.DomainCaseLoader;
import com.clougence.test.framework.TestPlan.VariantConfig;
import com.clougence.test.framework.TestTask;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorAnalysisSpi;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorObject;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorRelation;
import com.clougence.clouddm.sdk.sql.analysis.behavior.ObjectName;

public final class BehaviorDomainLoader implements DomainCaseLoader {

    private final ThreadLocal<Object> permissionRegistry = new ThreadLocal<>();

    @Override
    public List<TestTask> load(String resourcePath, VariantConfig variant, DialectRuntime dialect) {
        List<TestTask> tasks = new ArrayList<>();
        for (BehaviorTextTest.TestCase testCase : BehaviorTextTest.loadCases(resourcePath)) {
            tasks.add(new TestTask(testCase.displayName(), resourcePath, testCase.sql(), () -> {
                BehaviorAnalysisSpi spi = dialect.engine().behaviorAnalysisSpi(dialect.parameters());
                if (spi == null) {
                    throw new IllegalStateException("No BehaviorAnalysisSpi for " + dialect.config().id());
                }
                BehaviorTextTest.assertStrictCase(resourcePath, testCase, spi, permissionVerifier(dialect));
            }));
        }
        return tasks;
    }

    private BehaviorTextTest.PermissionVerifier permissionVerifier(DialectRuntime dialect) {
        String className = dialect.config().permissionRegistryClass();
        if (className == null || className.isBlank()) {
            return null;
        }
        return relation -> invokeRegistry(className, dialect.version(), relation);
    }

    private boolean invokeRegistry(String className, String version, BehaviorRelation relation) {
        try {
            Object registry = permissionRegistry.get();
            if (registry == null || !registry.getClass().getName().equals(className)) {
                registry = Class.forName(className).getConstructor().newInstance();
                permissionRegistry.set(registry);
            }
            BehaviorObject object = relation.getSubject();
            ObjectName name = object.getObjectName();
            if (name == null) {
                return false;
            }
            Method method = registry.getClass()
                .getMethod("isPermissionExempt", relation.getAction().getClass(), object.getObjectType().getClass(), String.class, String.class, String.class, String.class);
            return (Boolean) method.invoke(registry, relation.getAction(), object.getObjectType(), name.getCatalog(), name.getSchema(), name.getObjectName(), version);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot invoke permission registry " + className, e);
        }
    }
}
