package io.deephaven.db;

import com.google.auto.service.AutoService;
import io.deephaven.engine.util.GroovyDeephavenSession;

@AutoService(GroovyDeephavenSession.InitScript.class)
public final class DatabaseInitScript implements GroovyDeephavenSession.InitScript {
    @Override
    public String getScriptPath() {
        return "groovy/1-db.groovy";
    }

    @Override
    public int priority() {
        return 1;
    }
}
