package com.clougence.test.framework;

import java.lang.reflect.Constructor;

import com.clougence.test.framework.metadata.VirtualMetaService;
import com.clougence.clouddm.sdk.service.execute.MetaService;
import com.clougence.clouddm.sdk.sql.SqlEngineSpi;
import com.clougence.clouddm.sdk.sql.SqlParserParameters;

public final class DialectRuntime {

    private static final MetaService META_SERVICE = new VirtualMetaService();

    private final TestPlan.DialectConfig config;
    private final String version;
    private final ThreadLocal<SqlEngineSpi> engines;

    public DialectRuntime(TestPlan.DialectConfig config, String version) {
        this.config = config;
        this.version = version;
        this.engines = ThreadLocal.withInitial(this::newEngine);
    }

    public TestPlan.DialectConfig config() {
        return config;
    }

    public String version() {
        return version;
    }

    public SqlEngineSpi engine() {
        return engines.get();
    }

    public SqlParserParameters parameters() {
        if (version == null || version.isBlank()) {
            return SqlParserParameters.empty();
        }
        return SqlParserParameters.ofVersion(version);
    }

    private SqlEngineSpi newEngine() {
        try {
            Class<?> type = Class.forName(config.engineClass());
            for (Constructor<?> constructor : type.getConstructors()) {
                if (constructor.getParameterCount() == 1 &&
                        MetaService.class.isAssignableFrom(constructor.getParameterTypes()[0])) {
                    return (SqlEngineSpi) constructor.newInstance(META_SERVICE);
                }
            }
            return (SqlEngineSpi) type.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create SqlEngineSpi for " + config.id() +
                    " using " + config.engineClass(), e);
        }
    }
}
