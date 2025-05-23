/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.format.parquet.writer;

import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.InternalArray;
import org.apache.paimon.data.InternalMap;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.data.variant.Variant;
import org.apache.paimon.format.parquet.ParquetSchemaConverter;
import org.apache.paimon.types.ArrayType;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DecimalType;
import org.apache.paimon.types.IntType;
import org.apache.paimon.types.LocalZonedTimestampType;
import org.apache.paimon.types.MapType;
import org.apache.paimon.types.MultisetType;
import org.apache.paimon.types.RowType;
import org.apache.paimon.types.TimestampType;
import org.apache.paimon.types.VariantType;

import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.Type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;
import static org.apache.paimon.format.parquet.ParquetSchemaConverter.computeMinBytesForDecimalPrecision;
import static org.apache.paimon.utils.Preconditions.checkArgument;

/** Writes a record to the Parquet API with the expected schema in order to be written to a file. */
public class ParquetRowDataWriter {

    public static final int JULIAN_EPOCH_OFFSET_DAYS = 2_440_588;
    public static final long MILLIS_IN_DAY = TimeUnit.DAYS.toMillis(1);
    public static final long NANOS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);
    public static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final RowWriter rowWriter;
    private final RecordConsumer recordConsumer;

    public ParquetRowDataWriter(RecordConsumer recordConsumer, RowType rowType, GroupType schema) {
        this.recordConsumer = recordConsumer;
        this.rowWriter = new RowWriter(rowType, schema, false);
    }

    /**
     * It writes a record to Parquet.
     *
     * @param record Contains the record that is going to be written.
     */
    public void write(final InternalRow record) {
        recordConsumer.startMessage();
        rowWriter.write(record);
        recordConsumer.endMessage();
    }

    private FieldWriter createWriter(DataType t, Type type) {
        if (type.isPrimitive()) {
            switch (t.getTypeRoot()) {
                case CHAR:
                case VARCHAR:
                    return new StringWriter(t.isNullable());
                case BOOLEAN:
                    return new BooleanWriter(t.isNullable());
                case BINARY:
                case VARBINARY:
                    return new BinaryWriter(t.isNullable());
                case DECIMAL:
                    DecimalType decimalType = (DecimalType) t;
                    return createDecimalWriter(
                            decimalType.getPrecision(), decimalType.getScale(), t.isNullable());
                case TINYINT:
                    return new ByteWriter(t.isNullable());
                case SMALLINT:
                    return new ShortWriter(t.isNullable());
                case DATE:
                case TIME_WITHOUT_TIME_ZONE:
                case INTEGER:
                    return new IntWriter(t.isNullable());
                case BIGINT:
                    return new LongWriter(t.isNullable());
                case FLOAT:
                    return new FloatWriter(t.isNullable());
                case DOUBLE:
                    return new DoubleWriter(t.isNullable());
                case TIMESTAMP_WITHOUT_TIME_ZONE:
                    TimestampType timestampType = (TimestampType) t;
                    return createTimestampWriter(timestampType.getPrecision(), t.isNullable());
                case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                    LocalZonedTimestampType localZonedTimestampType = (LocalZonedTimestampType) t;
                    return createTimestampWriter(
                            localZonedTimestampType.getPrecision(), t.isNullable());
                default:
                    throw new UnsupportedOperationException("Unsupported type: " + type);
            }
        } else {
            GroupType groupType = type.asGroupType();
            LogicalTypeAnnotation annotation = type.getLogicalTypeAnnotation();

            if (t instanceof ArrayType
                    && annotation instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation) {
                return new ArrayWriter(((ArrayType) t).getElementType(), groupType, t.isNullable());
            } else if (t instanceof MapType
                    && annotation instanceof LogicalTypeAnnotation.MapLogicalTypeAnnotation) {
                return new MapWriter(
                        ((MapType) t).getKeyType(),
                        ((MapType) t).getValueType(),
                        groupType,
                        t.isNullable());
            } else if (t instanceof MultisetType
                    && annotation instanceof LogicalTypeAnnotation.MapLogicalTypeAnnotation) {
                return new MapWriter(
                        ((MultisetType) t).getElementType(),
                        new IntType(false),
                        groupType,
                        t.isNullable());
            } else if (t instanceof RowType && type instanceof GroupType) {
                return new RowWriter((RowType) t, groupType, t.isNullable());
            } else if (t instanceof VariantType && type instanceof GroupType) {
                return new VariantWriter(t.isNullable());
            } else {
                throw new UnsupportedOperationException("Unsupported type: " + type);
            }
        }
    }

    private FieldWriter createTimestampWriter(int precision, boolean isNullable) {
        if (precision <= 3) {
            return new TimestampMillsWriter(precision, isNullable);
        } else if (precision > 6) {
            return new TimestampInt96Writer(precision, isNullable);
        } else {
            return new TimestampMicrosWriter(precision, isNullable);
        }
    }

    private abstract static class FieldWriter {

        private final boolean isNullable;

        public FieldWriter(boolean isNullable) {
            this.isNullable = isNullable;
        }

        abstract void write(InternalRow row, int ordinal);

        abstract void write(InternalArray arrayData, int ordinal);

        public boolean isNullable() {
            return isNullable;
        }
    }

    private class BooleanWriter extends FieldWriter {

        public BooleanWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeBoolean(row.getBoolean(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeBoolean(arrayData.getBoolean(ordinal));
        }

        private void writeBoolean(boolean value) {
            recordConsumer.addBoolean(value);
        }
    }

    private class ByteWriter extends FieldWriter {

        public ByteWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeByte(row.getByte(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeByte(arrayData.getByte(ordinal));
        }

        private void writeByte(byte value) {
            recordConsumer.addInteger(value);
        }
    }

    private class ShortWriter extends FieldWriter {

        public ShortWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeShort(row.getShort(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeShort(arrayData.getShort(ordinal));
        }

        private void writeShort(short value) {
            recordConsumer.addInteger(value);
        }
    }

    private class LongWriter extends FieldWriter {

        public LongWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeLong(row.getLong(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeLong(arrayData.getLong(ordinal));
        }

        private void writeLong(long value) {
            recordConsumer.addLong(value);
        }
    }

    private class FloatWriter extends FieldWriter {

        public FloatWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeFloat(row.getFloat(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeFloat(arrayData.getFloat(ordinal));
        }

        private void writeFloat(float value) {
            recordConsumer.addFloat(value);
        }
    }

    private class DoubleWriter extends FieldWriter {

        public DoubleWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeDouble(row.getDouble(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeDouble(arrayData.getDouble(ordinal));
        }

        private void writeDouble(double value) {
            recordConsumer.addDouble(value);
        }
    }

    private class StringWriter extends FieldWriter {

        public StringWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeString(row.getString(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeString(arrayData.getString(ordinal));
        }

        private void writeString(BinaryString value) {
            recordConsumer.addBinary(Binary.fromReusedByteArray(value.toBytes()));
        }
    }

    private class BinaryWriter extends FieldWriter {

        public BinaryWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeBinary(row.getBinary(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeBinary(arrayData.getBinary(ordinal));
        }

        private void writeBinary(byte[] value) {
            recordConsumer.addBinary(Binary.fromReusedByteArray(value));
        }
    }

    private class IntWriter extends FieldWriter {

        public IntWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeInt(row.getInt(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeInt(arrayData.getInt(ordinal));
        }

        private void writeInt(int value) {
            recordConsumer.addInteger(value);
        }
    }

    private class TimestampMillsWriter extends FieldWriter {

        private final int precision;

        private TimestampMillsWriter(int precision, boolean isNullable) {
            super(isNullable);
            checkArgument(precision <= 3);
            this.precision = precision;
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeTimestamp(row.getTimestamp(ordinal, precision));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeTimestamp(arrayData.getTimestamp(ordinal, precision));
        }

        private void writeTimestamp(Timestamp value) {
            recordConsumer.addLong(value.getMillisecond());
        }
    }

    private class TimestampMicrosWriter extends FieldWriter {

        private final int precision;

        private TimestampMicrosWriter(int precision, boolean isNullable) {
            super(isNullable);
            checkArgument(precision > 3);
            checkArgument(precision <= 6);
            this.precision = precision;
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeTimestamp(row.getTimestamp(ordinal, precision));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeTimestamp(arrayData.getTimestamp(ordinal, precision));
        }

        private void writeTimestamp(Timestamp value) {
            recordConsumer.addLong(value.toMicros());
        }
    }

    private class TimestampInt96Writer extends FieldWriter {

        private final int precision;

        private TimestampInt96Writer(int precision, boolean isNullable) {
            super(isNullable);
            checkArgument(precision > 6);
            this.precision = precision;
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeTimestamp(row.getTimestamp(ordinal, precision));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeTimestamp(arrayData.getTimestamp(ordinal, precision));
        }

        private void writeTimestamp(Timestamp value) {
            recordConsumer.addBinary(timestampToInt96(value));
        }
    }

    /** It writes a map field to parquet, both key and value are nullable. */
    private class MapWriter extends FieldWriter {

        private final String repeatedGroupName;
        private final String keyName;
        private final String valueName;
        private final FieldWriter keyWriter;
        private final FieldWriter valueWriter;

        private MapWriter(
                DataType keyType, DataType valueType, GroupType groupType, boolean isNullable) {
            super(isNullable);
            // Get the internal map structure (MAP_KEY_VALUE)
            GroupType repeatedType = groupType.getType(0).asGroupType();
            this.repeatedGroupName = repeatedType.getName();

            // Get key element information
            Type type = repeatedType.getType(0);
            this.keyName = type.getName();
            this.keyWriter = createWriter(keyType, type);

            // Get value element information
            Type valuetype = repeatedType.getType(1);
            this.valueName = valuetype.getName();
            this.valueWriter = createWriter(valueType, valuetype);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeMapData(row.getMap(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeMapData(arrayData.getMap(ordinal));
        }

        private void writeMapData(InternalMap mapData) {
            recordConsumer.startGroup();

            if (mapData != null && mapData.size() > 0) {
                recordConsumer.startField(repeatedGroupName, 0);

                InternalArray keyArray = mapData.keyArray();
                InternalArray valueArray = mapData.valueArray();
                for (int i = 0; i < keyArray.size(); i++) {
                    recordConsumer.startGroup();
                    if (!keyArray.isNullAt(i)) {
                        // write key element
                        recordConsumer.startField(keyName, 0);
                        keyWriter.write(keyArray, i);
                        recordConsumer.endField(keyName, 0);
                    } else {
                        throw new IllegalArgumentException(
                                "Parquet does not support null keys in maps. "
                                        + "See https://github.com/apache/parquet-format/blob/master/LogicalTypes.md#maps "
                                        + "for more details.");
                    }

                    if (!valueArray.isNullAt(i)) {
                        // write value element
                        recordConsumer.startField(valueName, 1);
                        valueWriter.write(valueArray, i);
                        recordConsumer.endField(valueName, 1);
                    }
                    recordConsumer.endGroup();
                }

                recordConsumer.endField(repeatedGroupName, 0);
            }
            recordConsumer.endGroup();
        }
    }

    /** It writes an array type field to parquet. */
    private class ArrayWriter extends FieldWriter {

        private final String elementName;
        private final FieldWriter elementWriter;
        private final String repeatedGroupName;

        private ArrayWriter(DataType t, GroupType groupType, boolean isNullable) {
            super(isNullable);
            // Get the internal array structure
            GroupType repeatedType = groupType.getType(0).asGroupType();
            this.repeatedGroupName = repeatedType.getName();

            Type elementType = repeatedType.getType(0);
            this.elementName = elementType.getName();

            this.elementWriter = createWriter(t, elementType);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeArrayData(row.getArray(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeArrayData(arrayData.getArray(ordinal));
        }

        private void writeArrayData(InternalArray arrayData) {
            recordConsumer.startGroup();
            int listLength = arrayData.size();

            if (listLength > 0) {
                recordConsumer.startField(repeatedGroupName, 0);
                for (int i = 0; i < listLength; i++) {
                    recordConsumer.startGroup();
                    if (!arrayData.isNullAt(i)) {
                        recordConsumer.startField(elementName, 0);
                        elementWriter.write(arrayData, i);
                        recordConsumer.endField(elementName, 0);
                    }
                    recordConsumer.endGroup();
                }

                recordConsumer.endField(repeatedGroupName, 0);
            }
            recordConsumer.endGroup();
        }
    }

    /** It writes a row type field to parquet. */
    private class RowWriter extends FieldWriter {
        private final FieldWriter[] fieldWriters;
        private final String[] fieldNames;

        public RowWriter(RowType rowType, GroupType groupType, boolean isNullable) {
            super(isNullable);
            this.fieldNames = rowType.getFieldNames().toArray(new String[0]);
            List<DataType> fieldTypes = rowType.getFieldTypes();
            this.fieldWriters = new FieldWriter[rowType.getFieldCount()];
            for (int i = 0; i < fieldWriters.length; i++) {
                fieldWriters[i] = createWriter(fieldTypes.get(i), groupType.getType(i));
            }
        }

        public void write(InternalRow row) {
            for (int i = 0; i < fieldWriters.length; i++) {
                if (!row.isNullAt(i)) {
                    String fieldName = fieldNames[i];
                    FieldWriter writer = fieldWriters[i];

                    recordConsumer.startField(fieldName, i);
                    writer.write(row, i);
                    recordConsumer.endField(fieldName, i);
                } else {
                    if (!fieldWriters[i].isNullable()) {
                        throw new IllegalArgumentException(
                                format(
                                        "Parquet does not support null values in non-nullable fields. Field name : %s expected not null but found null",
                                        fieldNames[i]));
                    }
                }
            }
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            recordConsumer.startGroup();
            InternalRow rowData = row.getRow(ordinal, fieldWriters.length);
            write(rowData);
            recordConsumer.endGroup();
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            recordConsumer.startGroup();
            InternalRow rowData = arrayData.getRow(ordinal, fieldWriters.length);
            write(rowData);
            recordConsumer.endGroup();
        }
    }

    private class VariantWriter extends FieldWriter {

        public VariantWriter(boolean isNullable) {
            super(isNullable);
        }

        @Override
        public void write(InternalRow row, int ordinal) {
            writeVariant(row.getVariant(ordinal));
        }

        @Override
        public void write(InternalArray arrayData, int ordinal) {
            writeVariant(arrayData.getVariant(ordinal));
        }

        private void writeVariant(Variant variant) {
            recordConsumer.startGroup();
            recordConsumer.startField(Variant.VALUE, 0);
            recordConsumer.addBinary(Binary.fromReusedByteArray(variant.value()));
            recordConsumer.endField(Variant.VALUE, 0);
            recordConsumer.startField(Variant.METADATA, 1);
            recordConsumer.addBinary(Binary.fromReusedByteArray(variant.metadata()));
            recordConsumer.endField(Variant.METADATA, 1);
            recordConsumer.endGroup();
        }
    }

    private Binary timestampToInt96(Timestamp timestamp) {
        int julianDay;
        long nanosOfDay;
        long mills = timestamp.getMillisecond();
        julianDay = (int) ((mills / MILLIS_IN_DAY) + JULIAN_EPOCH_OFFSET_DAYS);
        nanosOfDay =
                (mills % MILLIS_IN_DAY) * NANOS_PER_MILLISECOND + timestamp.getNanoOfMillisecond();

        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(nanosOfDay);
        buf.putInt(julianDay);
        buf.flip();
        return Binary.fromConstantByteBuffer(buf);
    }

    private FieldWriter createDecimalWriter(int precision, int scale, boolean isNullable) {
        checkArgument(
                precision <= DecimalType.MAX_PRECISION,
                "Decimal precision %s exceeds max precision %s",
                precision,
                DecimalType.MAX_PRECISION);

        class Int32Writer extends FieldWriter {

            public Int32Writer(boolean isNullable) {
                super(isNullable);
            }

            @Override
            public void write(InternalArray arrayData, int ordinal) {
                long unscaledLong =
                        (arrayData.getDecimal(ordinal, precision, scale)).toUnscaledLong();
                addRecord(unscaledLong);
            }

            @Override
            public void write(InternalRow row, int ordinal) {
                long unscaledLong = row.getDecimal(ordinal, precision, scale).toUnscaledLong();
                addRecord(unscaledLong);
            }

            private void addRecord(long unscaledLong) {
                recordConsumer.addInteger((int) unscaledLong);
            }
        }

        class Int64Writer extends FieldWriter {

            public Int64Writer(boolean isNullable) {
                super(isNullable);
            }

            @Override
            public void write(InternalArray arrayData, int ordinal) {
                long unscaledLong =
                        (arrayData.getDecimal(ordinal, precision, scale)).toUnscaledLong();
                addRecord(unscaledLong);
            }

            @Override
            public void write(InternalRow row, int ordinal) {
                long unscaledLong = row.getDecimal(ordinal, precision, scale).toUnscaledLong();
                addRecord(unscaledLong);
            }

            private void addRecord(long unscaledLong) {
                recordConsumer.addLong(unscaledLong);
            }
        }

        class UnscaledBytesWriter extends FieldWriter {
            private final int numBytes;
            private final byte[] decimalBuffer;

            private UnscaledBytesWriter(boolean isNullable) {
                super(isNullable);
                this.numBytes = computeMinBytesForDecimalPrecision(precision);
                this.decimalBuffer = new byte[numBytes];
            }

            @Override
            public void write(InternalArray arrayData, int ordinal) {
                byte[] bytes = (arrayData.getDecimal(ordinal, precision, scale)).toUnscaledBytes();
                addRecord(bytes);
            }

            @Override
            public void write(InternalRow row, int ordinal) {
                byte[] bytes = row.getDecimal(ordinal, precision, scale).toUnscaledBytes();
                addRecord(bytes);
            }

            private void addRecord(byte[] bytes) {
                byte[] writtenBytes;
                if (bytes.length == numBytes) {
                    // Avoid copy.
                    writtenBytes = bytes;
                } else {
                    byte signByte = bytes[0] < 0 ? (byte) -1 : (byte) 0;
                    Arrays.fill(decimalBuffer, 0, numBytes - bytes.length, signByte);
                    System.arraycopy(
                            bytes, 0, decimalBuffer, numBytes - bytes.length, bytes.length);
                    writtenBytes = decimalBuffer;
                }
                recordConsumer.addBinary(Binary.fromReusedByteArray(writtenBytes, 0, numBytes));
            }
        }

        if (ParquetSchemaConverter.is32BitDecimal(precision)) {
            return new Int32Writer(isNullable);
        } else if (ParquetSchemaConverter.is64BitDecimal(precision)) {
            return new Int64Writer(isNullable);
        } else {
            return new UnscaledBytesWriter(isNullable);
        }
    }
}
