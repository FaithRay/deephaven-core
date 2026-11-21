package io.deephaven.db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * One growing log per date. Readers trust only the commit file. Bytes past that length are a torn tail and are
 * discarded the next time a writer opens the log.
 */
final class IntradayLog {
    static final byte[] LOG_MAGIC = "DHIDBLOG".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final byte[] COMMIT_MAGIC = "DHCOMMIT".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final int RECORD_MAGIC = 0x31424449;

    final Path directory;

    private IntradayLog(final Path directory) {
        this.directory = directory;
    }

    static Path logFile(final Path directory) {
        return directory.resolve("log");
    }

    static Path commitFile(final Path directory) {
        return directory.resolve("commit");
    }

    static Path mergedFile(final Path directory) {
        return directory.resolve("merged");
    }

    static final class Commit {
        final long byteLength;
        final long rowCount;

        Commit(final long byteLength, final long rowCount) {
            this.byteLength = byteLength;
            this.rowCount = rowCount;
        }
    }

    static final class Record {
        final long payloadPosition;
        final int payloadLength;
        final int rowCount;
        final long startRow;

        Record(final long payloadPosition, final int payloadLength, final int rowCount, final long startRow) {
            this.payloadPosition = payloadPosition;
            this.payloadLength = payloadLength;
            this.rowCount = rowCount;
            this.startRow = startRow;
        }
    }

    static void append(final Path directory, final byte[] payload, final int rowCount) throws IOException {
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount");
        }
        Files.createDirectories(directory);
        final Commit commit = readCommit(directory);
        try (FileChannel channel = FileChannel.open(logFile(directory), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            if (channel.size() > commit.byteLength) {
                channel.truncate(commit.byteLength);
            }
            long position = commit.byteLength;
            if (position == 0) {
                writeFully(channel, ByteBuffer.wrap(LOG_MAGIC));
                position = LOG_MAGIC.length;
            } else if (position < LOG_MAGIC.length) {
                throw new IOException("Intraday log is shorter than its magic header: " + directory);
            }
            final CRC32 crc = new CRC32();
            crc.update(payload);
            final ByteBuffer header = ByteBuffer.allocate(16);
            header.putInt(RECORD_MAGIC);
            header.putInt(rowCount);
            header.putInt(payload.length);
            header.putInt((int) crc.getValue());
            header.flip();
            channel.position(position);
            writeFully(channel, header);
            writeFully(channel, ByteBuffer.wrap(payload));
            channel.force(true);
            final long newLength = position + 16L + payload.length;
            writeCommit(directory, newLength, commit.rowCount + rowCount);
        }
    }

    static Commit readCommit(final Path directory) throws IOException {
        final Path commit = commitFile(directory);
        if (!Files.isRegularFile(commit)) {
            return new Commit(0, 0);
        }
        final byte[] bytes = Files.readAllBytes(commit);
        if (bytes.length != COMMIT_MAGIC.length + 16 + 4) {
            throw new IOException("Bad intraday commit length: " + commit);
        }
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final byte[] magic = new byte[COMMIT_MAGIC.length];
        buffer.get(magic);
        if (!java.util.Arrays.equals(magic, COMMIT_MAGIC)) {
            throw new IOException("Bad intraday commit magic: " + commit);
        }
        final long byteLength = buffer.getLong();
        final long rowCount = buffer.getLong();
        final int storedCrc = buffer.getInt();
        final CRC32 crc = new CRC32();
        final ByteBuffer lengths = ByteBuffer.allocate(16);
        lengths.putLong(byteLength);
        lengths.putLong(rowCount);
        crc.update(lengths.array());
        if (storedCrc != (int) crc.getValue()) {
            throw new IOException("Bad intraday commit checksum: " + commit);
        }
        return new Commit(byteLength, rowCount);
    }

    static List<Record> readIndex(final Path directory) throws IOException {
        final Commit commit = readCommit(directory);
        final List<Record> records = new ArrayList<>();
        if (commit.byteLength == 0) {
            return records;
        }
        try (FileChannel channel = FileChannel.open(logFile(directory), StandardOpenOption.READ)) {
            if (channel.size() < commit.byteLength) {
                throw new IOException("Intraday log is shorter than its commit: " + directory);
            }
            final ByteBuffer magic = ByteBuffer.allocate(LOG_MAGIC.length);
            readFully(channel, magic);
            magic.flip();
            final byte[] magicBytes = new byte[LOG_MAGIC.length];
            magic.get(magicBytes);
            if (!java.util.Arrays.equals(magicBytes, LOG_MAGIC)) {
                throw new IOException("Bad intraday log magic: " + directory);
            }
            long position = LOG_MAGIC.length;
            long row = 0;
            while (position < commit.byteLength) {
                final ByteBuffer header = ByteBuffer.allocate(16);
                readFully(channel, header);
                header.flip();
                final int recordMagic = header.getInt();
                final int rowCount = header.getInt();
                final int payloadLength = header.getInt();
                header.getInt();
                if (recordMagic != RECORD_MAGIC) {
                    throw new IOException("Bad intraday record magic at " + position + " in " + directory);
                }
                final long payloadPosition = position + 16;
                records.add(new Record(payloadPosition, payloadLength, rowCount, row));
                channel.position(payloadPosition + payloadLength);
                position = payloadPosition + payloadLength;
                row += rowCount;
            }
            if (position != commit.byteLength || row != commit.rowCount) {
                throw new IOException("Intraday commit does not match the log records: " + directory);
            }
        }
        return records;
    }

    static byte[] readPayload(final Path directory, final Record record) throws IOException {
        try (FileChannel channel = FileChannel.open(logFile(directory), StandardOpenOption.READ)) {
            channel.position(record.payloadPosition);
            final ByteBuffer payload = ByteBuffer.allocate(record.payloadLength);
            readFully(channel, payload);
            return payload.array();
        }
    }

    static void markMerged(final Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(mergedFile(directory), "merged\n");
    }

    static boolean isMerged(final Path directory) {
        return Files.isRegularFile(mergedFile(directory));
    }

    private static void writeCommit(final Path directory, final long byteLength, final long rowCount)
            throws IOException {
        final CRC32 crc = new CRC32();
        final ByteBuffer lengths = ByteBuffer.allocate(16);
        lengths.putLong(byteLength);
        lengths.putLong(rowCount);
        crc.update(lengths.array());
        final ByteBuffer commit = ByteBuffer.allocate(COMMIT_MAGIC.length + 16 + 4);
        commit.put(COMMIT_MAGIC);
        commit.putLong(byteLength);
        commit.putLong(rowCount);
        commit.putInt((int) crc.getValue());
        commit.flip();
        final Path tmp = directory.resolve("commit.tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(channel, commit);
            channel.force(true);
        }
        Files.move(tmp, commitFile(directory), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeFully(final FileChannel channel, final ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void readFully(final FileChannel channel, final ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("Unexpected end of intraday log");
            }
        }
    }
}
