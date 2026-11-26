package io.deephaven.db;

import io.deephaven.configuration.Configuration;
import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.rowset.RowSet;
import io.deephaven.engine.rowset.RowSetFactory;
import io.deephaven.engine.rowset.TrackingRowSet;
import io.deephaven.engine.rowset.WritableRowSet;
import io.deephaven.engine.table.ColumnSource;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.impl.PartitionAwareSourceTable;
import io.deephaven.engine.table.impl.QueryTable;
import io.deephaven.engine.table.impl.sources.regioned.RegionedTableComponentFactoryImpl;
import io.deephaven.engine.util.TableTools;
import io.deephaven.parquet.table.ParquetInstructions;
import io.deephaven.parquet.table.ParquetInstructions.ParquetFileLayout;
import io.deephaven.parquet.table.ParquetTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Groovy {@code db} object. {@code db.t} reads static historical parquet. {@code db.i} tails one growing intraday log
 * per date. {@code db.merge} copies a finished date into the historical store.
 */
public final class Database {
    private static volatile Database shared;

    private final Path root;
    private final Map<String, TableWriterLock> locks = new HashMap<>();
    private final Map<String, Table> live = new HashMap<>();

    private Database(final Path root) {
        this.root = root;
    }

    public static Database open(final Path root) {
        try {
            Files.createDirectories(root);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Database(root);
    }

    public Path root() {
        return root;
    }

    public static Database shared() {
        Database local = shared;
        if (local == null) {
            synchronized (Database.class) {
                local = shared;
                if (local == null) {
                    final String configured =
                            Configuration.getInstance().getStringWithDefault("Database.root", "data/deephaven-db");
                    shared = local = open(Path.of(configured));
                }
            }
        }
        return local;
    }

    public Table t(final String namespace, final String table) {
        Names.checkIdentifier("namespace", namespace);
        Names.checkIdentifier("table", table);
        final TableSchema schema = requireSchema(namespace, table);
        final Path directory = historicalDir(namespace, table);
        if (!Files.isDirectory(directory)) {
            return TableTools.newTable(schema.toDefinition());
        }
        final ParquetInstructions instructions = ParquetInstructions.builder()
                .setFileLayout(ParquetFileLayout.KV_PARTITIONED)
                .setTableDefinition(schema.toDefinition())
                .build();
        return ParquetTools.readTable(directory.toString(), instructions);
    }

    public Table i(final String namespace, final String table) {
        return i(namespace, table, true);
    }

    public Table i(final String namespace, final String table, final boolean live) {
        Names.checkIdentifier("namespace", namespace);
        Names.checkIdentifier("table", table);
        final TableSchema schema = requireSchema(namespace, table);
        final String key = key(namespace, table);
        synchronized (lock(namespace, table)) {
            if (live) {
                final Table cached = this.live.get(key);
                if (cached != null) {
                    return cached;
                }
            }
            final IntradayLocationProvider provider = new IntradayLocationProvider(
                    intradayDir(namespace, table), historicalDir(namespace, table), live);
            provider.ensureInitialized();
            final Table result = new PartitionAwareSourceTable(
                    schema.toDefinition(),
                    (live ? "intraday " : "intraday snapshot ") + namespace + "." + table,
                    RegionedTableComponentFactoryImpl.INSTANCE,
                    provider,
                    live ? ExecutionContext.getContext().getUpdateGraph() : null);
            if (live) {
                this.live.put(key, result);
            }
            return result;
        }
    }

    public void add(final String namespace, final String table, final Table source) {
        writeHistorical(namespace, table, source, null);
    }

    public void add(final String namespace, final String table, final String date, final Table source) {
        writeHistorical(namespace, table, source, date);
    }

    public void append(final String namespace, final String table, final Table source) {
        appendDated(namespace, table, source, null);
    }

    public void append(final String namespace, final String table, final String date, final Table source) {
        appendDated(namespace, table, source, date);
    }

    public void merge(final String namespace, final String table, final String date) {
        Names.checkIdentifier("namespace", namespace);
        Names.checkIdentifier("table", table);
        Names.checkDate(date);
        final TableSchema schema = requireSchema(namespace, table);
        synchronized (lock(namespace, table)) {
            lockWriter(namespace, table);
            final Path dateDir = intradayDateDir(namespace, table, date);
            final Path finalDir = historicalDir(namespace, table).resolve("Date=" + date);
            if (Files.isDirectory(finalDir)) {
                markMerged(dateDir);
                live.remove(key(namespace, table));
                return;
            }
            final List<Table> parts = new ArrayList<>();
            try {
                for (final IntradayLog.Record record : IntradayLog.readIndex(dateDir)) {
                    if (record.rowCount == 0) {
                        continue;
                    }
                    final Table data = ParquetBytes.read(IntradayLog.readPayload(dateDir, record));
                    parts.add(data.updateView("Date=`" + date + "`").view(schema.columnNames()));
                }
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            if (!parts.isEmpty()) {
                final Table merged = parts.size() == 1 ? parts.get(0) : TableTools.merge(parts);
                writeHistoricalFiles(schema, merged, historicalDir(namespace, table), true);
            }
            markMerged(dateDir);
            live.remove(key(namespace, table));
        }
    }

    public Table getNamespaces() {
        return TableTools.newTable(TableTools.stringCol("Namespace", namespaces().toArray(String[]::new)));
    }

    public Table getTableNames(final String namespace) {
        Names.checkIdentifier("namespace", namespace);
        return TableTools.newTable(
                TableTools.stringCol("TableName", tableNames(namespace).toArray(String[]::new)));
    }

    public Table getCatalog() {
        final List<String> namespaces = new ArrayList<>();
        final List<String> tables = new ArrayList<>();
        for (final String namespace : namespaces()) {
            for (final String table : tableNames(namespace)) {
                namespaces.add(namespace);
                tables.add(table);
            }
        }
        return TableTools.newTable(
                TableTools.stringCol("Namespace", namespaces.toArray(String[]::new)),
                TableTools.stringCol("TableName", tables.toArray(String[]::new)));
    }

    private void writeHistorical(final String namespace, final String table, final Table source,
            final String stampedDate) {
        Names.checkIdentifier("namespace", namespace);
        Names.checkIdentifier("table", table);
        final Table stamped = stampDate(source, stampedDate);
        synchronized (lock(namespace, table)) {
            lockWriter(namespace, table);
            final TableSchema schema = schemaForWrite(namespace, table, stamped);
            final Table ordered = copy(stamped).view(schema.columnNames());
            writeHistoricalFiles(schema, ordered, historicalDir(namespace, table), false);
        }
    }

    private void appendDated(final String namespace, final String table, final Table source,
            final String stampedDate) {
        Names.checkIdentifier("namespace", namespace);
        Names.checkIdentifier("table", table);
        final Table stamped = stampDate(source, stampedDate);
        synchronized (lock(namespace, table)) {
            lockWriter(namespace, table);
            final TableSchema schema = schemaForWrite(namespace, table, stamped);
            final QueryTable rows = (QueryTable) copy(stamped).coalesce();
            final TrackingRowSet tracking = rows.getRowSet();
            final long current = tracking.size();
            final long prev = tracking.sizePrev();
            final boolean usePrev = current == 0 && prev != 0;
            final long n = usePrev ? prev : current;
            if (n == 0) {
                return;
            }
            try (final RowSet rowSet = usePrev ? tracking.copyPrev() : tracking.copy()) {
                final ColumnSource<?> dateCol = rows.getColumnSource(TableSchema.DATE);
                final Map<String, WritableRowSet> byDate = new LinkedHashMap<>();
                try (final RowSet.Iterator it = rowSet.iterator()) {
                    while (it.hasNext()) {
                        final long key = it.nextLong();
                        final Object raw = usePrev ? dateCol.getPrev(key) : dateCol.get(key);
                        final String date = raw == null ? null : raw.toString();
                        Names.checkDate(date);
                        byDate.computeIfAbsent(date, ignored -> RowSetFactory.empty()).insert(key);
                    }
                }
                final String[] dataCols = schema.dataColumnNames();
                long written = 0;
                for (final Map.Entry<String, WritableRowSet> entry : byDate.entrySet()) {
                    final String date = entry.getKey();
                    if (hidden(namespace, table, date)) {
                        throw new IllegalStateException(
                                "Date " + date + " is already merged for " + namespace + "." + table);
                    }
                    final Table part = rows.getSubTable(entry.getValue().toTracking()).view(dataCols);
                    if (part.size() == 0) {
                        throw new IllegalStateException(
                                "Date " + date + " matched no rows of " + namespace + "." + table);
                    }
                    final Path dateDir = intradayDateDir(namespace, table, date);
                    try {
                        IntradayLog.append(dateDir, ParquetBytes.write(part), Math.toIntExact(part.size()));
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (!Files.isRegularFile(IntradayLog.logFile(dateDir))) {
                        throw new IllegalStateException(
                                "append wrote no log at " + IntradayLog.logFile(dateDir));
                    }
                    written += part.size();
                }
                if (written != n) {
                    throw new IllegalStateException("Wrote " + written + " of " + n + " rows for " + namespace + "."
                            + table);
                }
            }
        }
    }

    private void writeHistoricalFiles(final TableSchema schema, final Table source, final Path destination,
            final boolean stage) {
        final Path writeRoot;
        Path staging = null;
        try {
            if (stage) {
                staging = destination.resolveSibling(destination.getFileName() + ".merge-staging");
                deleteRecursively(staging);
                Files.createDirectories(staging);
                writeRoot = staging;
            } else {
                Files.createDirectories(destination);
                writeRoot = destination;
            }
            final ParquetInstructions instructions = ParquetInstructions.builder()
                    .setTableDefinition(schema.toDefinition())
                    .build();
            ParquetTools.writeKeyValuePartitionedTable(source, writeRoot.toString(), instructions);
            if (stage) {
                try (Stream<Path> children = Files.list(staging)) {
                    for (final Path child : (Iterable<Path>) children::iterator) {
                        final String name = child.getFileName().toString();
                        if (!name.startsWith("Date=")) {
                            continue;
                        }
                        final Path finalDir = destination.resolve(name);
                        if (Files.exists(finalDir)) {
                            throw new IllegalStateException("Historical partition already exists: " + finalDir);
                        }
                        Files.createDirectories(destination);
                        Files.move(child, finalDir, StandardCopyOption.ATOMIC_MOVE);
                    }
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (staging != null) {
                deleteRecursively(staging);
            }
        }
    }

    private static Table stampDate(final Table source, final String date) {
        if (date == null) {
            if (source.getDefinition().getColumn(TableSchema.DATE) == null) {
                throw new IllegalArgumentException("Table must include a String Date column");
            }
            return source;
        }
        Names.checkDate(date);
        return source.updateView("Date=`" + date + "`");
    }

    private TableSchema schemaForWrite(final String namespace, final String table, final Table source) {
        final Path path = schemaFile(namespace, table);
        try {
            if (Files.isRegularFile(path)) {
                final TableSchema existing = TableSchema.read(path);
                existing.checkCompatible(source);
                return existing;
            }
            final TableSchema created = TableSchema.fromTable(source);
            created.write(path);
            return created;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private TableSchema requireSchema(final String namespace, final String table) {
        final Path path = schemaFile(namespace, table);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Unknown table " + namespace + "." + table);
        }
        try {
            return TableSchema.read(path);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Table copy(final Table table) {
        final QueryTable coalesced = (QueryTable) table.coalesce();
        if (coalesced.size() == 0) {
            return coalesced;
        }
        final String[] names = coalesced.getDefinition().getColumnNamesArray();
        final Table copied = coalesced.select(names);
        if (copied.size() != coalesced.size()) {
            throw new IllegalStateException(
                    "Copy produced " + copied.size() + " rows from " + coalesced.size());
        }
        return copied;
    }

    private boolean hidden(final String namespace, final String table, final String date) {
        return IntradayLog.isMerged(intradayDateDir(namespace, table, date))
                || Files.isDirectory(historicalDir(namespace, table).resolve("Date=" + date));
    }

    private static void markMerged(final Path dateDir) {
        try {
            IntradayLog.markMerged(dateDir);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void lockWriter(final String namespace, final String table) {
        final String key = key(namespace, table);
        if (locks.containsKey(key)) {
            return;
        }
        try {
            locks.put(key, TableWriterLock.acquire(intradayDir(namespace, table)));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Object lock(final String namespace, final String table) {
        return (namespace + "\0" + table).intern();
    }

    private static String key(final String namespace, final String table) {
        return namespace + "/" + table;
    }

    private Path schemaFile(final String namespace, final String table) {
        return root.resolve("catalog").resolve(namespace).resolve(table + ".schema");
    }

    private Path historicalDir(final String namespace, final String table) {
        return root.resolve("historical").resolve(namespace).resolve(table);
    }

    private Path intradayDir(final String namespace, final String table) {
        return root.resolve("intraday").resolve(namespace).resolve(table);
    }

    private Path intradayDateDir(final String namespace, final String table, final String date) {
        return intradayDir(namespace, table).resolve("Date=" + date);
    }

    private List<String> namespaces() {
        return childNames(root.resolve("catalog"));
    }

    private List<String> tableNames(final String namespace) {
        return childNames(root.resolve("catalog").resolve(namespace)).stream()
                .filter(name -> name.endsWith(".schema"))
                .map(name -> name.substring(0, name.length() - ".schema".length()))
                .sorted()
                .toList();
    }

    private static List<String> childNames(final Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.map(path -> path.getFileName().toString()).sorted(Comparator.naturalOrder()).toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(final Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            final List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (final Path child : paths) {
                Files.deleteIfExists(child);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
