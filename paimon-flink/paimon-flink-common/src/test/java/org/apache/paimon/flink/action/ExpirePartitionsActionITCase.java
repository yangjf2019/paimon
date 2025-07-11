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

package org.apache.paimon.flink.action;

import org.apache.paimon.Snapshot;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.StreamWriteBuilder;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.SnapshotManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.init;
import static org.assertj.core.api.Assertions.assertThat;

/** IT cases for {@link ExpirePartitionsAction}. */
public class ExpirePartitionsActionITCase extends ActionITCaseBase {

    private static final DataType[] FIELD_TYPES =
            new DataType[] {DataTypes.STRING(), DataTypes.STRING(), DataTypes.STRING()};

    private static final RowType ROW_TYPE = RowType.of(FIELD_TYPES, new String[] {"k", "dt", "hm"});

    @BeforeEach
    public void setUp() {
        init(warehouse);
    }

    @Test
    public void testExpirePartitionsAction() throws Exception {
        FileStoreTable table = prepareTable();
        TableScan.Plan plan = table.newReadBuilder().newScan().plan();
        List<String> actual = getResult(table.newReadBuilder().newRead(), plan.splits(), ROW_TYPE);
        List<String> expected;
        expected = Arrays.asList("+I[1, 2024-01-01, 01:00]", "+I[2, 9999-09-20, 02:00]");

        assertThat(actual).isEqualTo(expected);

        createAction(
                        ExpirePartitionsAction.class,
                        "expire_partitions",
                        "--warehouse",
                        warehouse,
                        "--database",
                        database,
                        "--table",
                        tableName,
                        "--expiration_time",
                        "1 d",
                        "--timestamp_formatter",
                        "yyyy-MM-dd")
                .run();
        SnapshotManager snapshotManager = getFileStoreTable(tableName).snapshotManager();
        Snapshot snapshot = snapshotManager.snapshot(snapshotManager.latestSnapshotId());
        assertThat(snapshot.id()).isEqualTo(3);
        assertThat(snapshot.commitKind()).isEqualTo(Snapshot.CommitKind.OVERWRITE);

        plan = table.newReadBuilder().newScan().plan();
        actual = getResult(table.newReadBuilder().newRead(), plan.splits(), ROW_TYPE);

        expected = Arrays.asList("+I[2, 9999-09-20, 02:00]");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void testExpirePartitionsActionWithTimePartition() throws Exception {
        FileStoreTable table = prepareTable();
        TableScan.Plan plan = table.newReadBuilder().newScan().plan();
        List<String> actual = getResult(table.newReadBuilder().newRead(), plan.splits(), ROW_TYPE);
        List<String> expected;
        expected = Arrays.asList("+I[1, 2024-01-01, 01:00]", "+I[2, 9999-09-20, 02:00]");

        assertThat(actual).isEqualTo(expected);

        createAction(
                        ExpirePartitionsAction.class,
                        "expire_partitions",
                        "--warehouse",
                        warehouse,
                        "--database",
                        database,
                        "--table",
                        tableName,
                        "--expiration_time",
                        "1 d",
                        "--timestamp_formatter",
                        "yyyy-MM-dd HH:mm",
                        "--timestamp_pattern",
                        "$dt $hm")
                .run();

        plan = table.newReadBuilder().newScan().plan();
        actual = getResult(table.newReadBuilder().newRead(), plan.splits(), ROW_TYPE);

        expected = Arrays.asList("+I[2, 9999-09-20, 02:00]");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void testExpirePartitionsWithTableConf() throws Exception {
        FileStoreTable table = prepareTable();
        // prepare more data
        writeData(
                rowData(
                        BinaryString.fromString("3"),
                        BinaryString.fromString("2024-01-01"),
                        BinaryString.fromString("02:00")));
        writeData(
                rowData(
                        BinaryString.fromString("4"),
                        BinaryString.fromString("2024-01-02"),
                        BinaryString.fromString("01:00")));

        TableScan.Plan plan = table.newReadBuilder().newScan().plan();
        List<String> actual = getResult(table.newReadBuilder().newRead(), plan.splits(), ROW_TYPE);
        List<String> expected;
        expected =
                Arrays.asList(
                        "+I[1, 2024-01-01, 01:00]",
                        "+I[2, 9999-09-20, 02:00]",
                        "+I[3, 2024-01-01, 02:00]",
                        "+I[4, 2024-01-02, 01:00]");

        assertThat(actual).isEqualTo(expected);

        // only expire one partition
        createAction(
                        ExpirePartitionsAction.class,
                        "expire_partitions",
                        "--warehouse",
                        warehouse,
                        "--database",
                        database,
                        "--table",
                        tableName,
                        "--expiration_time",
                        "1 d",
                        "--timestamp_formatter",
                        "yyyy-MM-dd",
                        "--timestamp_pattern",
                        "$dt",
                        "--table_conf",
                        "partition.expiration-max-num=1",
                        "--table_conf",
                        "partition.expiration-batch-size=1")
                .run();

        plan = table.newReadBuilder().newScan().plan();
        actual = getResult(table.newReadBuilder().newRead(), plan.splits(), ROW_TYPE);

        expected =
                Arrays.asList(
                        "+I[2, 9999-09-20, 02:00]",
                        "+I[3, 2024-01-01, 02:00]",
                        "+I[4, 2024-01-02, 01:00]");

        assertThat(actual).isEqualTo(expected);
    }

    private FileStoreTable prepareTable() throws Exception {
        init(warehouse);

        RowType rowType =
                RowType.of(
                        new DataType[] {DataTypes.STRING(), DataTypes.STRING(), DataTypes.STRING()},
                        new String[] {"k", "dt", "hm"});
        String[] pk = {"k", "dt", "hm"};
        String[] partitions = {"dt", "hm"};
        FileStoreTable table =
                createFileStoreTable(
                        rowType,
                        new ArrayList<>(Arrays.asList(partitions)),
                        new ArrayList<>(Arrays.asList(pk)),
                        Collections.singletonList("k"),
                        Collections.emptyMap());

        StreamWriteBuilder writeBuilder = table.newStreamWriteBuilder().withCommitUser(commitUser);
        write = writeBuilder.newWrite();
        commit = writeBuilder.newCommit();

        // 3 snapshots
        writeData(
                rowData(
                        BinaryString.fromString("1"),
                        BinaryString.fromString("2024-01-01"),
                        BinaryString.fromString("01:00")));
        writeData(
                rowData(
                        BinaryString.fromString("2"),
                        BinaryString.fromString("9999-09-20"),
                        BinaryString.fromString("02:00")));

        return table;
    }
}
