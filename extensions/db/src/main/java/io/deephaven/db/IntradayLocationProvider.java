package io.deephaven.db;

import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.table.impl.TableUpdateMode;
import io.deephaven.engine.table.impl.locations.TableLocation;
import io.deephaven.engine.table.impl.locations.TableLocationKey;
import io.deephaven.engine.table.impl.locations.impl.AbstractTableLocationProvider;
import io.deephaven.engine.table.impl.locations.impl.StandaloneTableKey;
import io.deephaven.engine.updategraph.UpdateGraph;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

final class IntradayLocationProvider extends AbstractTableLocationProvider {
    private final Path tableDirectory;
    private final Path historicalDirectory;
    private final boolean live;
    private Runnable token;

    IntradayLocationProvider(final Path tableDirectory, final Path historicalDirectory, final boolean live) {
        super(StandaloneTableKey.getInstance(), live, live ? TableUpdateMode.APPEND_ONLY : TableUpdateMode.STATIC,
                live ? TableUpdateMode.APPEND_ONLY : TableUpdateMode.STATIC);
        this.tableDirectory = tableDirectory;
        this.historicalDirectory = historicalDirectory;
        this.live = live;
    }

    @Override
    public String getImplementationName() {
        return "IntradayLocationProvider";
    }

    @Override
    public void refresh() {
        discover();
        if (live) {
            getTableLocationKeys(supplier -> {
                final TableLocation location = getTableLocationIfPresent(supplier.get());
                if (location != null) {
                    location.refresh();
                }
            }, key -> true);
        }
        setInitialized();
    }

    @Override
    @NotNull
    protected TableLocation makeTableLocation(@NotNull final TableLocationKey locationKey) {
        final DateLocationKey key = (DateLocationKey) locationKey;
        return new IntradayLocation(tableDirectory.resolve("Date=" + key.date), key, live);
    }

    @Override
    protected void activateUnderlyingDataSource() {
        final UpdateGraph updateGraph = ExecutionContext.getContext().getUpdateGraph();
        token = this::refresh;
        updateGraph.addSource(token);
        refresh();
        activationSuccessful(token);
    }

    @Override
    protected void deactivateUnderlyingDataSource() {
        if (token != null) {
            ExecutionContext.getContext().getUpdateGraph().removeSource(token);
            token = null;
        }
    }

    @Override
    protected <T> boolean matchSubscriptionToken(final T token) {
        return token == this.token;
    }

    private void discover() {
        if (!Files.isDirectory(tableDirectory)) {
            return;
        }
        try (Stream<Path> children = Files.list(tableDirectory)) {
            children.filter(Files::isDirectory).forEach(directory -> {
                final String name = directory.getFileName().toString();
                if (!name.startsWith("Date=")) {
                    return;
                }
                final String date = name.substring("Date=".length());
                if (hidden(date)) {
                    return;
                }
                handleTableLocationKeyAdded(new DateLocationKey(date));
            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean hidden(final String date) {
        return IntradayLog.isMerged(tableDirectory.resolve("Date=" + date))
                || Files.isDirectory(historicalDirectory.resolve("Date=" + date));
    }
}
