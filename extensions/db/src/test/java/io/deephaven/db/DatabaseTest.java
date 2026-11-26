package io.deephaven.db;

import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.impl.SourceTable;
import io.deephaven.engine.testutil.ControlledUpdateGraph;
import io.deephaven.engine.testutil.junit4.EngineCleanup;
import io.deephaven.engine.util.TableTools;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DatabaseTest {
    @Rule
    public final EngineCleanup framework = new EngineCleanup();

    @Test
    public void appendGrowsOneLogAndSnapshotStaysStill() throws Exception {
        final Path root = Files.createTempDirectory("deephaven-db");
        try {
            final Database db = Database.open(root);
            db.append("Market", "Trades", "2024-01-01", rows("AAPL", 1, "MSFT", 2));

            final Table live = db.i("Market", "Trades");
            final Table same = db.i("Market", "Trades", true);
            final Table snapshot = db.i("Market", "Trades", false);
            assertTrue(live == same);
            assertEquals(2, live.size());
            assertEquals(2, snapshot.size());

            db.append("Market", "Trades", "2024-01-01", rows("GOOG", 3, "IBM", 4));
            final SourceTable liveSource = (SourceTable) live;
            final ControlledUpdateGraph updateGraph = ExecutionContext.getContext().getUpdateGraph().cast();
            updateGraph.startCycleForUnitTests(false);
            try {
                updateGraph.refreshUpdateSourceForUnitTests(() -> {
                    liveSource.tableLocationProvider().refresh();
                    liveSource.refresh();
                });
                updateGraph.markSourcesRefreshedForUnitTests();
            } finally {
                updateGraph.completeCycleForUnitTests();
            }
            assertEquals(4, live.size());
            assertEquals(2, snapshot.size());

            final Path dateDir = root.resolve("intraday/Market/Trades/Date=2024-01-01");
            try (Stream<Path> children = Files.list(dateDir)) {
                assertEquals(2, children.count());
            }
            assertTrue(Files.isRegularFile(dateDir.resolve("log")));
            assertEquals(1, db.getNamespaces().size());
            assertEquals("Market", db.getNamespaces().getColumnSource("Namespace", String.class).get(0));
            assertEquals(1, db.getCatalog().size());
        } finally {
            delete(root);
        }
    }

    @Test
    public void appendUsesDateColumnAlreadyOnTheTable() throws Exception {
        final Path root = Files.createTempDirectory("deephaven-db");
        try {
            final Database db = Database.open(root);
            db.append("Market", "Trades", TableTools.newTable(
                    TableTools.stringCol("Date", "2024-01-01", "2024-01-01"),
                    TableTools.stringCol("Sym", "AAPL", "MSFT"),
                    TableTools.intCol("Qty", 1, 2)));
            assertEquals(2, db.i("Market", "Trades", false).size());
            assertTrue(Files.isRegularFile(root.resolve("intraday/Market/Trades/Date=2024-01-01/log")));
        } finally {
            delete(root);
        }
    }

    @Test
    public void appendWritesSnapshotThenUpdateView() throws Exception {
        final Path root = Files.createTempDirectory("deephaven-db");
        try {
            final Database db = Database.open(root);
            final Table src = TableTools.newTable(
                    TableTools.stringCol("cur_time", "2024-01-01 09:30:00", "2024-01-01 09:30:01"),
                    TableTools.stringCol("Sym", "AAPL", "MSFT"),
                    TableTools.intCol("Qty", 1, 2));
            final Table stamped = src.snapshot()
                    .updateView("Date = cur_time.substring(0, 10)")
                    .view("Date", "cur_time", "Sym", "Qty");
            db.append("Market", "Trades", stamped);
            assertTrue(Files.isRegularFile(root.resolve("intraday/Market/Trades/Date=2024-01-01/log")));
            assertEquals(2, db.i("Market", "Trades", false).size());
        } finally {
            delete(root);
        }
    }

    @Test
    public void historicalFilterAndMerge() throws Exception {
        final Path root = Files.createTempDirectory("deephaven-db");
        try {
            final Database db = Database.open(root);
            db.add("Market", "Trades", TableTools.newTable(
                    TableTools.stringCol("Date", "2024-01-01", "2024-01-02"),
                    TableTools.stringCol("Sym", "AAPL", "MSFT"),
                    TableTools.intCol("Qty", 10, 20)));
            assertEquals(1, db.t("Market", "Trades").where("Date=`2024-01-01`").size());
            assertEquals(2, db.t("Market", "Trades").size());

            db.append("Market", "Quotes", "2024-01-03", rows("IBM", 5, "ORCL", 6));
            assertEquals(2, db.i("Market", "Quotes", false).size());
            db.merge("Market", "Quotes", "2024-01-03");
            assertEquals(2, db.t("Market", "Quotes").where("Date=`2024-01-03`").size());
            assertEquals(0, db.i("Market", "Quotes").size());
        } finally {
            delete(root);
        }
    }

    @Test
    public void rejectsUnsafeNamesAndLoadsInitScript() throws Exception {
        final Path root = Files.createTempDirectory("deephaven-db-names");
        try {
            final Database db = Database.open(root);
            assertThrows(IllegalArgumentException.class, () -> db.t("..", "Trades"));
            assertThrows(IllegalArgumentException.class, () -> db.t("Market", "a/b"));
            assertNotNull(DatabaseInitScript.class.getClassLoader().getResource("groovy/1-db.groovy"));
        } finally {
            delete(root);
        }
    }

    private static Table rows(final String sym1, final int qty1, final String sym2, final int qty2) {
        return TableTools.newTable(
                TableTools.stringCol("Sym", sym1, sym2),
                TableTools.intCol("Qty", qty1, qty2));
    }

    private static void delete(final Path directory) throws Exception {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (final Path path : walk.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
