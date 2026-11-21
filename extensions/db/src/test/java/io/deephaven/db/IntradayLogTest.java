package io.deephaven.db;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertEquals;

public class IntradayLogTest {
    @Test
    public void tornTailIsDiscardedOnNextAppend() throws Exception {
        final Path directory = Files.createTempDirectory("deephaven-db-log");
        try {
            IntradayLog.append(directory, new byte[] {1, 2, 3}, 2);
            try (FileChannel channel = FileChannel.open(IntradayLog.logFile(directory), StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                final ByteBuffer junk = ByteBuffer.wrap(new byte[] {9, 9, 9});
                while (junk.hasRemaining()) {
                    channel.write(junk);
                }
            }
            final IntradayLog.Commit before = IntradayLog.readCommit(directory);
            assertEquals(2, before.rowCount);
            assertEquals(1, IntradayLog.readIndex(directory).size());

            IntradayLog.append(directory, new byte[] {4}, 1);
            final IntradayLog.Commit after = IntradayLog.readCommit(directory);
            assertEquals(3, after.rowCount);
            assertEquals(after.byteLength, Files.size(IntradayLog.logFile(directory)));
            assertEquals(2, IntradayLog.readIndex(directory).size());
        } finally {
            delete(directory);
        }
    }

    private static void delete(final Path directory) throws Exception {
        try (var walk = Files.walk(directory)) {
            for (final Path path : walk.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
