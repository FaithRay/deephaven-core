package io.deephaven.db;

import io.deephaven.engine.table.ColumnDefinition;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.TableDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class TableSchema {
    static final String DATE = "Date";

    final List<ColumnDefinition<?>> columns;

    private TableSchema(final List<ColumnDefinition<?>> columns) {
        this.columns = List.copyOf(columns);
    }

    static TableSchema fromTable(final Table table) {
        final List<ColumnDefinition<?>> columns = new ArrayList<>();
        columns.add(ColumnDefinition.ofString(DATE).withPartitioning());
        boolean sawDate = false;
        for (final ColumnDefinition<?> column : table.getDefinition().getColumns()) {
            if (DATE.equals(column.getName())) {
                sawDate = true;
                if (column.getDataType() != String.class) {
                    throw new IllegalArgumentException("Date column must be a String");
                }
                continue;
            }
            columns.add(ColumnDefinition.fromGenericType(column.getName(), column.getDataType(),
                    column.getComponentType()));
        }
        if (!sawDate) {
            throw new IllegalArgumentException("Table must include a String Date column");
        }
        if (columns.size() < 2) {
            throw new IllegalArgumentException("Table must contain Date and at least one data column");
        }
        return new TableSchema(columns);
    }

    static TableSchema read(final Path path) throws IOException {
        final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !"1".equals(lines.get(0))) {
            throw new IOException("Unsupported catalog schema: " + path);
        }
        final List<ColumnDefinition<?>> columns = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            final String[] parts = line.split("\t", -1);
            if (parts.length != 3) {
                throw new IOException("Bad catalog line in " + path + ": " + line);
            }
            final Class<?> dataType = classForName(parts[1]);
            final ColumnDefinition<?> column = ColumnDefinition.fromGenericType(parts[0], dataType, null,
                    "partition".equals(parts[2])
                            ? ColumnDefinition.ColumnType.Partitioning
                            : ColumnDefinition.ColumnType.Normal);
            columns.add(column);
        }
        return new TableSchema(columns);
    }

    void write(final Path path) throws IOException {
        Files.createDirectories(path.getParent());
        final StringBuilder text = new StringBuilder("1\n");
        for (final ColumnDefinition<?> column : columns) {
            text.append(column.getName()).append('\t')
                    .append(column.getDataType().getName()).append('\t')
                    .append(column.getColumnType() == ColumnDefinition.ColumnType.Partitioning
                            ? "partition"
                            : "normal")
                    .append('\n');
        }
        final Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, text.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    void checkCompatible(final Table table) {
        final TableSchema other = fromTable(table);
        if (columns.size() != other.columns.size()) {
            throw new IllegalArgumentException("Column set does not match the catalog schema");
        }
        for (final ColumnDefinition<?> column : other.columns) {
            final ColumnDefinition<?> expected = find(column.getName());
            if (expected == null || expected.getDataType() != column.getDataType()
                    || expected.getColumnType() != column.getColumnType()) {
                throw new IllegalArgumentException(
                        "Column " + column.getName() + " does not match the catalog schema");
            }
        }
    }

    TableDefinition toDefinition() {
        return TableDefinition.of(columns);
    }

    String[] columnNames() {
        return columns.stream().map(ColumnDefinition::getName).toArray(String[]::new);
    }

    String[] dataColumnNames() {
        return columns.stream()
                .map(ColumnDefinition::getName)
                .filter(name -> !DATE.equals(name))
                .toArray(String[]::new);
    }

    ColumnDefinition<?> find(final String name) {
        for (final ColumnDefinition<?> column : columns) {
            if (column.getName().equals(name)) {
                return column;
            }
        }
        return null;
    }

    private static Class<?> classForName(final String name) throws IOException {
        switch (name) {
            case "boolean":
                return boolean.class;
            case "char":
                return char.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            default:
                try {
                    return Class.forName(name);
                } catch (final ClassNotFoundException e) {
                    throw new IOException("Unknown column type " + name, e);
                }
        }
    }
}
