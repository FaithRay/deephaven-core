package io.deephaven.db;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class TableWriterLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private TableWriterLock(final FileChannel channel, final FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static TableWriterLock acquire(final Path tableDirectory) throws IOException {
        Files.createDirectories(tableDirectory);
        final FileChannel channel = FileChannel.open(tableDirectory.resolve("writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            final FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new IllegalStateException("Another writer already holds " + tableDirectory);
            }
            return new TableWriterLock(channel, lock);
        } catch (final IOException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
