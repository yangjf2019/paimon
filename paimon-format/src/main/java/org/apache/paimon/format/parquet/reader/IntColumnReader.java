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

package org.apache.paimon.format.parquet.reader;

import org.apache.paimon.data.columnar.writable.WritableIntVector;

import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.schema.PrimitiveType;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Int {@link ColumnReader}. */
public class IntColumnReader extends AbstractColumnReader<WritableIntVector> {

    public IntColumnReader(ColumnDescriptor descriptor, PageReadStore pageReadStore)
            throws IOException {
        super(descriptor, pageReadStore);
        checkTypeName(PrimitiveType.PrimitiveTypeName.INT32);
    }

    @Override
    protected void readBatch(int rowId, int num, WritableIntVector column) {
        int left = num;
        while (left > 0) {
            if (runLenDecoder.currentCount == 0) {
                runLenDecoder.readNextGroup();
            }
            int n = Math.min(left, runLenDecoder.currentCount);
            switch (runLenDecoder.mode) {
                case RLE:
                    if (runLenDecoder.currentValue == maxDefLevel) {
                        readIntegers(n, column, rowId);
                    } else {
                        column.setNulls(rowId, n);
                    }
                    break;
                case PACKED:
                    for (int i = 0; i < n; ++i) {
                        if (runLenDecoder.currentBuffer[runLenDecoder.currentBufferIdx++]
                                == maxDefLevel) {
                            column.setInt(rowId + i, readInteger());
                        } else {
                            column.setNullAt(rowId + i);
                        }
                    }
                    break;
            }
            rowId += n;
            left -= n;
            runLenDecoder.currentCount -= n;
        }
    }

    @Override
    protected void skipBatch(int num) {
        int left = num;
        while (left > 0) {
            if (runLenDecoder.currentCount == 0) {
                runLenDecoder.readNextGroup();
            }
            int n = Math.min(left, runLenDecoder.currentCount);
            switch (runLenDecoder.mode) {
                case RLE:
                    if (runLenDecoder.currentValue == maxDefLevel) {
                        skipInteger(n);
                    }
                    break;
                case PACKED:
                    for (int i = 0; i < n; ++i) {
                        if (runLenDecoder.currentBuffer[runLenDecoder.currentBufferIdx++]
                                == maxDefLevel) {
                            skipInteger(1);
                        }
                    }
                    break;
            }
            left -= n;
            runLenDecoder.currentCount -= n;
        }
    }

    private void skipInteger(int num) {
        skipDataBuffer(4 * num);
    }

    @Override
    protected void readBatchFromDictionaryIds(
            int rowId, int num, WritableIntVector column, WritableIntVector dictionaryIds) {
        for (int i = rowId; i < rowId + num; ++i) {
            if (!column.isNullAt(i)) {
                column.setInt(i, dictionary.decodeToInt(dictionaryIds.getInt(i)));
            }
        }
    }

    private int readInteger() {
        return readDataBuffer(4).getInt();
    }

    private void readIntegers(int total, WritableIntVector c, int rowId) {
        int requiredBytes = total * 4;
        ByteBuffer buffer = readDataBuffer(requiredBytes);

        if (buffer.hasArray()) {
            int offset = buffer.arrayOffset() + buffer.position();
            c.setIntsFromBinary(rowId, total, buffer.array(), offset);
        } else {
            for (int i = 0; i < total; i += 1) {
                c.setInt(rowId + i, buffer.getInt());
            }
        }
    }
}
