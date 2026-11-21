package io.deephaven.db;

import io.deephaven.chunk.WritableChunk;
import io.deephaven.chunk.attributes.Values;
import io.deephaven.engine.table.ColumnDefinition;
import io.deephaven.engine.table.ColumnSource;
import io.deephaven.engine.table.impl.locations.impl.AbstractColumnLocation;
import io.deephaven.engine.table.impl.sources.regioned.ColumnRegionByte;
import io.deephaven.engine.table.impl.sources.regioned.ColumnRegionChar;
import io.deephaven.engine.table.impl.sources.regioned.ColumnRegionDouble;
import io.deephaven.engine.table.impl.sources.regioned.ColumnRegionFloat;
import io.deephaven.engine.table.impl.sources.regioned.ColumnRegionInt;
import io.deephaven.engine.table.impl.sources.regioned.ColumnRegionLong;
import io.deephaven.engine.table.impl.sources.regioned.ColumnRegionObject;
import io.deephaven.engine.table.impl.sources.regioned.ColumnRegionShort;
import io.deephaven.engine.table.impl.sources.regioned.RegionedColumnSource;
import io.deephaven.generic.region.AppendOnlyFixedSizePageRegionByte;
import io.deephaven.generic.region.AppendOnlyFixedSizePageRegionChar;
import io.deephaven.generic.region.AppendOnlyFixedSizePageRegionDouble;
import io.deephaven.generic.region.AppendOnlyFixedSizePageRegionFloat;
import io.deephaven.generic.region.AppendOnlyFixedSizePageRegionInt;
import io.deephaven.generic.region.AppendOnlyFixedSizePageRegionLong;
import io.deephaven.generic.region.AppendOnlyFixedSizePageRegionObject;
import io.deephaven.generic.region.AppendOnlyFixedSizePageRegionShort;
import io.deephaven.generic.region.AppendOnlyRegionAccessor;
import org.jetbrains.annotations.NotNull;

final class IntradayColumnLocation extends AbstractColumnLocation {
    private static final int PAGE_SIZE = 1 << 16;
    private static final long REGION_MASK = RegionedColumnSource.ROW_KEY_TO_SUB_REGION_ROW_INDEX_MASK;

    private final IntradayLocation location;

    IntradayColumnLocation(final IntradayLocation location, final String name) {
        super(location, name);
        this.location = location;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public ColumnRegionChar<Values> makeColumnRegionChar(@NotNull final ColumnDefinition<?> columnDefinition) {
        return new AppendOnlyFixedSizePageRegionChar<>(REGION_MASK, PAGE_SIZE, accessor());
    }

    @Override
    public ColumnRegionByte<Values> makeColumnRegionByte(@NotNull final ColumnDefinition<?> columnDefinition) {
        return new AppendOnlyFixedSizePageRegionByte<>(REGION_MASK, PAGE_SIZE, accessor());
    }

    @Override
    public ColumnRegionShort<Values> makeColumnRegionShort(@NotNull final ColumnDefinition<?> columnDefinition) {
        return new AppendOnlyFixedSizePageRegionShort<>(REGION_MASK, PAGE_SIZE, accessor());
    }

    @Override
    public ColumnRegionInt<Values> makeColumnRegionInt(@NotNull final ColumnDefinition<?> columnDefinition) {
        return new AppendOnlyFixedSizePageRegionInt<>(REGION_MASK, PAGE_SIZE, accessor());
    }

    @Override
    public ColumnRegionLong<Values> makeColumnRegionLong(@NotNull final ColumnDefinition<?> columnDefinition) {
        return new AppendOnlyFixedSizePageRegionLong<>(REGION_MASK, PAGE_SIZE, accessor());
    }

    @Override
    public ColumnRegionFloat<Values> makeColumnRegionFloat(@NotNull final ColumnDefinition<?> columnDefinition) {
        return new AppendOnlyFixedSizePageRegionFloat<>(REGION_MASK, PAGE_SIZE, accessor());
    }

    @Override
    public ColumnRegionDouble<Values> makeColumnRegionDouble(@NotNull final ColumnDefinition<?> columnDefinition) {
        return new AppendOnlyFixedSizePageRegionDouble<>(REGION_MASK, PAGE_SIZE, accessor());
    }

    @Override
    public <TYPE> ColumnRegionObject<TYPE, Values> makeColumnRegionObject(
            @NotNull final ColumnDefinition<TYPE> columnDefinition) {
        return new AppendOnlyFixedSizePageRegionObject<>(REGION_MASK, PAGE_SIZE, accessor());
    }

    private AppendOnlyRegionAccessor<Values> accessor() {
        return new AppendOnlyRegionAccessor<>() {
            @Override
            public void readChunkPage(final long firstRowPosition, final int minimumSize,
                    @NotNull final WritableChunk<Values> destination) {
                destination.setSize(0);
                for (int ii = 0; ii < minimumSize; ii++) {
                    appendValue(destination, firstRowPosition + ii);
                }
            }

            @Override
            public long size() {
                return location.visibleRows();
            }
        };
    }

    private void appendValue(final WritableChunk<Values> destination, final long row) {
        if (TableSchema.DATE.equals(getName())) {
            destination.asWritableObjectChunk().add(location.date);
            return;
        }
        final IntradayLocation.Batch batch = location.find(row);
        final ColumnSource<?> source = batch.column(getName());
        final long key = batch.rowKey(row - batch.startRow);
        final Class<?> type = source.getType();
        if (type == int.class) {
            destination.asWritableIntChunk().add(source.getInt(key));
        } else if (type == long.class) {
            destination.asWritableLongChunk().add(source.getLong(key));
        } else if (type == double.class) {
            destination.asWritableDoubleChunk().add(source.getDouble(key));
        } else if (type == float.class) {
            destination.asWritableFloatChunk().add(source.getFloat(key));
        } else if (type == short.class) {
            destination.asWritableShortChunk().add(source.getShort(key));
        } else if (type == byte.class) {
            destination.asWritableByteChunk().add(source.getByte(key));
        } else if (type == char.class) {
            destination.asWritableCharChunk().add(source.getChar(key));
        } else if (type == boolean.class) {
            destination.asWritableObjectChunk().add(source.getBoolean(key));
        } else {
            destination.asWritableObjectChunk().add(source.get(key));
        }
    }
}
