package io.deephaven.db;

import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.impl.QueryTable;
import io.deephaven.parquet.table.ParquetTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ParquetBytes {
    private ParquetBytes() {}

    static byte[] write(final Table table) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("deephaven-db-batch", ".parquet");
            ParquetTools.writeTable(table, tmp.toString());
            return Files.readAllBytes(tmp);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(tmp);
        }
    }

    static Table read(final byte[] payload) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("deephaven-db-read", ".parquet");
            Files.write(tmp, payload);
            final Table lazy = ParquetTools.readTable(tmp.toString());
            return ((QueryTable) lazy.coalesce()).snapshot();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(tmp);
        }
    }

    private static void delete(final Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ignored) {
            // The batch is already in memory. A leftover temp file is harmless.
        }
    }
}
