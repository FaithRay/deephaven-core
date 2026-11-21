package io.deephaven.db;

import io.deephaven.api.SortColumn;
import io.deephaven.engine.rowset.RowSetFactory;
import io.deephaven.engine.table.BasicDataIndex;
import io.deephaven.engine.table.ColumnSource;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.impl.locations.ColumnLocation;
import io.deephaven.engine.table.impl.locations.TableDataException;
import io.deephaven.engine.table.impl.locations.impl.AbstractTableLocation;
import io.deephaven.engine.table.impl.locations.impl.StandaloneTableKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class IntradayLocation extends AbstractTableLocation {
    private static final String IMPLEMENTATION_NAME = "IntradayLocation";

    final Path directory;
    final String date;
    private final Object lock = new Object();
    private final List<Batch> batches = new ArrayList<>();
    private boolean ready;
    private long visibleRows;

    IntradayLocation(final Path directory, final DateLocationKey key, final boolean subscriptions) {
        super(StandaloneTableKey.getInstance(), key, subscriptions);
        this.directory = directory;
        this.date = key.date;
    }

    @Override
    public String getImplementationName() {
        return IMPLEMENTATION_NAME;
    }

    @Override
    protected void initializeState() {
        if (ready) {
            return;
        }
        ready = true;
        pull(true);
    }

    @Override
    public void refresh() {
        pull(false);
    }

    long visibleRows() {
        return visibleRows;
    }

    Batch find(final long row) {
        synchronized (lock) {
            int low = 0;
            int high = batches.size() - 1;
            while (low <= high) {
                final int mid = (low + high) >>> 1;
                final Batch batch = batches.get(mid);
                if (row < batch.startRow) {
                    high = mid - 1;
                } else if (row >= batch.startRow + batch.rowCount) {
                    low = mid + 1;
                } else {
                    return batch;
                }
            }
        }
        throw new TableDataException("Row " + row + " is not in " + directory);
    }

    private void pull(final boolean initializing) {
        final IntradayLog.Commit commit;
        final List<IntradayLog.Record> records;
        try {
            commit = IntradayLog.readCommit(directory);
            records = IntradayLog.readIndex(directory);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        synchronized (lock) {
            if (records.size() < batches.size()) {
                throw new TableDataException("Intraday log shrank: " + directory);
            }
            for (int i = batches.size(); i < records.size(); i++) {
                final IntradayLog.Record record = records.get(i);
                final byte[] payload;
                try {
                    payload = IntradayLog.readPayload(directory, record);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
                batches.add(new Batch(record.startRow, record.rowCount, payload));
            }
            visibleRows = commit.rowCount;
        }
        if (initializing) {
            handleUpdateInternal(RowSetFactory.flat(commit.rowCount), System.currentTimeMillis());
        } else {
            handleUpdate(RowSetFactory.flat(commit.rowCount), System.currentTimeMillis());
        }
    }

    @Override
    @NotNull
    public List<SortColumn> getSortedColumns() {
        return List.of();
    }

    @Override
    @NotNull
    public List<String[]> getDataIndexColumns() {
        return List.of();
    }

    @Override
    public boolean hasDataIndex(@NotNull final String... columns) {
        return false;
    }

    @Override
    @Nullable
    public BasicDataIndex loadDataIndex(@NotNull final String... columns) {
        return null;
    }

    @Override
    @NotNull
    protected ColumnLocation makeColumnLocation(@NotNull final String name) {
        return new IntradayColumnLocation(this, name);
    }

    @Override
    protected void activateUnderlyingDataSource() {
        refresh();
        activationSuccessful(this);
    }

    @Override
    protected void deactivateUnderlyingDataSource() {}

    @Override
    protected <T> boolean matchSubscriptionToken(final T token) {
        return token == this;
    }

    static final class Batch {
        final long startRow;
        final int rowCount;
        final byte[] payload;
        private Table table;

        Batch(final long startRow, final int rowCount, final byte[] payload) {
            this.startRow = startRow;
            this.rowCount = rowCount;
            this.payload = payload;
        }

        Table table() {
            if (table == null) {
                table = ParquetBytes.read(payload);
            }
            return table;
        }

        <T> ColumnSource<T> column(final String name) {
            return table().getColumnSource(name);
        }

        long rowKey(final long localRow) {
            return table().getRowSet().get(localRow);
        }
    }
}
