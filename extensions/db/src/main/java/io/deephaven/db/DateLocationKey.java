package io.deephaven.db;

import io.deephaven.base.log.LogOutput;
import io.deephaven.engine.table.impl.locations.impl.PartitionedTableLocationKey;
import io.deephaven.io.log.impl.LogOutputStringImpl;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

final class DateLocationKey extends PartitionedTableLocationKey {
    final String date;

    DateLocationKey(final String date) {
        super(Map.of(TableSchema.DATE, date));
        this.date = date;
    }

    @Override
    public LogOutput append(@NotNull final LogOutput logOutput) {
        return logOutput.append(getImplementationName()).append('[').append(date).append(']');
    }

    @Override
    public String toString() {
        return new LogOutputStringImpl().append(this).toString();
    }

    @Override
    public String getImplementationName() {
        return "DateLocationKey";
    }
}
