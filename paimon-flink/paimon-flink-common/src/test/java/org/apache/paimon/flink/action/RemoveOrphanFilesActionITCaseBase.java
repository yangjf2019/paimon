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

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.SchemaChange;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.StreamWriteBuilder;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.DateTimeUtils;

import org.apache.paimon.shade.guava30.com.google.common.collect.ImmutableList;

import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.paimon.CoreOptions.SCAN_FALLBACK_BRANCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** IT cases base for {@link RemoveOrphanFilesAction}. */
public abstract class RemoveOrphanFilesActionITCaseBase extends ActionITCaseBase {

    private static final String ORPHAN_FILE_1 = "bucket-0/orphan_file1";
    private static final String ORPHAN_FILE_2 = "bucket-0/orphan_file2";

    private FileStoreTable createTableAndWriteData(String tableName) throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {DataTypes.BIGINT(), DataTypes.STRING()},
                        new String[] {"k", "v"});

        FileStoreTable table =
                createFileStoreTable(
                        tableName,
                        rowType,
                        Collections.emptyList(),
                        Collections.singletonList("k"),
                        Collections.emptyList(),
                        Collections.emptyMap());

        StreamWriteBuilder writeBuilder = table.newStreamWriteBuilder().withCommitUser(commitUser);
        write = writeBuilder.newWrite();
        commit = writeBuilder.newCommit();

        writeData(rowData(1L, BinaryString.fromString("Hi")));

        Path orphanFile1 = getOrphanFilePath(table, ORPHAN_FILE_1);
        Path orphanFile2 = getOrphanFilePath(table, ORPHAN_FILE_2);

        FileIO fileIO = table.fileIO();
        fileIO.writeFile(orphanFile1, "a", true);
        fileIO.writeFile(orphanFile2, "b", true);
        Thread.sleep(2000);

        return table;
    }

    private Path getOrphanFilePath(FileStoreTable table, String orphanFile) {
        return new Path(table.location(), orphanFile);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testRunWithoutException(boolean isNamedArgument) throws Exception {
        assumeTrue(!isNamedArgument || supportNamedArgument());

        FileStoreTable table = createTableAndWriteData(tableName);

        List<String> args =
                new ArrayList<>(
                        Arrays.asList(
                                "remove_orphan_files",
                                "--warehouse",
                                warehouse,
                                "--database",
                                database,
                                "--table",
                                tableName));
        RemoveOrphanFilesAction action1 = createAction(RemoveOrphanFilesAction.class, args);
        assertThatCode(action1::run).doesNotThrowAnyException();

        args.add("--older_than");
        args.add("2023-12-31 23:59:59");
        RemoveOrphanFilesAction action2 = createAction(RemoveOrphanFilesAction.class, args);
        assertThatCode(action2::run).doesNotThrowAnyException();

        String withoutOlderThan =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s')"
                                : "CALL sys.remove_orphan_files('%s.%s')",
                        database,
                        tableName);
        CloseableIterator<Row> withoutOlderThanCollect = executeSQL(withoutOlderThan);
        assertThat(ImmutableList.copyOf(withoutOlderThanCollect)).containsOnly(Row.of("0"));

        String olderThan =
                DateTimeUtils.formatLocalDateTime(
                        DateTimeUtils.toLocalDateTime(System.currentTimeMillis()), 3);
        String withDryRun =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s', older_than => '%s', dry_run => true)"
                                : "CALL sys.remove_orphan_files('%s.%s', '%s', true)",
                        database,
                        tableName,
                        olderThan);
        ImmutableList<Row> actualDryRunDeleteFile = ImmutableList.copyOf(executeSQL(withDryRun));
        assertThat(actualDryRunDeleteFile).containsOnly(Row.of("2"));

        String withOlderThan =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s', older_than => '%s')"
                                : "CALL sys.remove_orphan_files('%s.%s', '%s')",
                        database,
                        tableName,
                        olderThan);
        ImmutableList<Row> actualDeleteFile = ImmutableList.copyOf(executeSQL(withOlderThan));

        assertThat(actualDeleteFile).containsExactlyInAnyOrder(Row.of("2"), Row.of("2"));

        // test clean empty directories
        FileIO fileIO = table.fileIO();
        Path location = table.location();
        Path bucketDir = new Path(location, "bucket-0");

        // delete snapshots and clean orphan files
        fileIO.delete(new Path(location, "snapshot"), true);
        ImmutableList.copyOf(executeSQL(withOlderThan));
        assertThat(fileIO.exists(bucketDir)).isTrue();
        assertThat(fileIO.listDirectories(bucketDir)).isEmpty();

        // clean empty directories
        ImmutableList.copyOf(executeSQL(withOlderThan));
        assertThat(fileIO.exists(bucketDir)).isFalse();
        // table should not be deleted
        assertThat(fileIO.exists(location)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testRemoveDatabaseOrphanFilesITCase(boolean isNamedArgument) throws Exception {
        assumeTrue(!isNamedArgument || supportNamedArgument());

        createTableAndWriteData("tableName1");
        createTableAndWriteData("tableName2");

        List<String> args =
                new ArrayList<>(
                        Arrays.asList(
                                "remove_orphan_files",
                                "--warehouse",
                                warehouse,
                                "--database",
                                database));

        if (ThreadLocalRandom.current().nextBoolean()) {
            args.add("--table");
            args.add("*");
        }

        RemoveOrphanFilesAction action1 = createAction(RemoveOrphanFilesAction.class, args);
        assertThatCode(action1::run).doesNotThrowAnyException();

        args.add("--older_than");
        args.add("2023-12-31 23:59:59");
        RemoveOrphanFilesAction action2 = createAction(RemoveOrphanFilesAction.class, args);
        assertThatCode(action2::run).doesNotThrowAnyException();

        args.add("--parallelism");
        args.add("5");
        RemoveOrphanFilesAction action3 = createAction(RemoveOrphanFilesAction.class, args);
        assertThatCode(action3::run).doesNotThrowAnyException();

        String withoutOlderThan =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s')"
                                : "CALL sys.remove_orphan_files('%s.%s')",
                        database,
                        "*");
        CloseableIterator<Row> withoutOlderThanCollect = executeSQL(withoutOlderThan);
        assertThat(ImmutableList.copyOf(withoutOlderThanCollect)).containsOnly(Row.of("0"));

        String withParallelism =
                String.format("CALL sys.remove_orphan_files('%s.%s','',true,5)", database, "*");
        CloseableIterator<Row> withParallelismCollect = executeSQL(withParallelism);
        assertThat(ImmutableList.copyOf(withParallelismCollect)).containsOnly(Row.of("0"));

        String olderThan =
                DateTimeUtils.formatLocalDateTime(
                        DateTimeUtils.toLocalDateTime(System.currentTimeMillis()), 3);
        String withDryRun =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s', older_than => '%s', dry_run => true)"
                                : "CALL sys.remove_orphan_files('%s.%s', '%s', true)",
                        database,
                        "*",
                        olderThan);
        ImmutableList<Row> actualDryRunDeleteFile = ImmutableList.copyOf(executeSQL(withDryRun));
        assertThat(actualDryRunDeleteFile).containsOnly(Row.of("4"));

        String withOlderThan =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s', older_than => '%s')"
                                : "CALL sys.remove_orphan_files('%s.%s', '%s')",
                        database,
                        "*",
                        olderThan);
        ImmutableList<Row> actualDeleteFile = ImmutableList.copyOf(executeSQL(withOlderThan));

        assertThat(actualDeleteFile).containsOnly(Row.of("4"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testCleanWithBranch(boolean isNamedArgument) throws Exception {
        assumeTrue(!isNamedArgument || supportNamedArgument());

        // create main branch
        FileStoreTable table = createTableAndWriteData(tableName);

        // create first branch and write some data
        table.createBranch("br");
        SchemaManager schemaManager = new SchemaManager(table.fileIO(), table.location(), "br");
        TableSchema branchSchema =
                schemaManager.commitChanges(SchemaChange.addColumn("v2", DataTypes.INT()));
        Options branchOptions = new Options(branchSchema.options());
        branchOptions.set(CoreOptions.BRANCH, "br");
        branchSchema = branchSchema.copy(branchOptions.toMap());
        FileStoreTable branchTable =
                FileStoreTableFactory.create(table.fileIO(), table.location(), branchSchema);

        String commitUser = UUID.randomUUID().toString();
        StreamTableWrite write = branchTable.newWrite(commitUser);
        StreamTableCommit commit = branchTable.newCommit(commitUser);
        write.write(GenericRow.of(2L, BinaryString.fromString("Hello"), 20));
        commit.commit(1, write.prepareCommit(false, 1));
        write.close();
        commit.close();

        // create orphan file in snapshot directory of first branch
        Path orphanFile3 = new Path(table.location(), "branch/branch-br/snapshot/orphan_file3");
        branchTable.fileIO().writeFile(orphanFile3, "x", true);

        // create second branch, which is empty
        table.createBranch("br2");

        // create orphan file in snapshot directory of second branch
        Path orphanFile4 = new Path(table.location(), "branch/branch-br2/snapshot/orphan_file4");
        branchTable.fileIO().writeFile(orphanFile4, "y", true);
        Thread.sleep(2000);

        if (ThreadLocalRandom.current().nextBoolean()) {
            executeSQL(
                    String.format(
                            "ALTER TABLE `%s`.`%s` SET ('%s' = 'br')",
                            database, tableName, SCAN_FALLBACK_BRANCH.key()),
                    false,
                    true);
        }

        String olderThan =
                DateTimeUtils.formatLocalDateTime(
                        DateTimeUtils.toLocalDateTime(System.currentTimeMillis()), 3);
        String procedure =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s', older_than => '%s')"
                                : "CALL sys.remove_orphan_files('%s.%s', '%s')",
                        database,
                        "*",
                        olderThan);
        ImmutableList<Row> actualDeleteFile = ImmutableList.copyOf(executeSQL(procedure));
        assertThat(actualDeleteFile).containsOnly(Row.of("4"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testRunWithMode(boolean isNamedArgument) throws Exception {
        assumeTrue(!isNamedArgument || supportNamedArgument());

        createTableAndWriteData(tableName);

        List<String> args =
                new ArrayList<>(
                        Arrays.asList(
                                "remove_orphan_files",
                                "--warehouse",
                                warehouse,
                                "--database",
                                database,
                                "--table",
                                tableName));
        RemoveOrphanFilesAction action1 = createAction(RemoveOrphanFilesAction.class, args);
        assertThatCode(action1::run).doesNotThrowAnyException();

        args.add("--older_than");
        args.add("2023-12-31 23:59:59");
        RemoveOrphanFilesAction action2 = createAction(RemoveOrphanFilesAction.class, args);
        assertThatCode(action2::run).doesNotThrowAnyException();

        String withoutOlderThan =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s')"
                                : "CALL sys.remove_orphan_files('%s.%s')",
                        database,
                        tableName);
        CloseableIterator<Row> withoutOlderThanCollect = executeSQL(withoutOlderThan);
        assertThat(ImmutableList.copyOf(withoutOlderThanCollect)).containsOnly(Row.of("0"));

        String olderThan =
                DateTimeUtils.formatLocalDateTime(
                        DateTimeUtils.toLocalDateTime(System.currentTimeMillis()), 3);
        String withLocalMode =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s', older_than => '%s', dry_run => true, parallelism => 5, mode => 'local')"
                                : "CALL sys.remove_orphan_files('%s.%s', '%s', true, 5, 'local')",
                        database,
                        tableName,
                        olderThan);
        ImmutableList<Row> actualLocalRunDeleteFile =
                ImmutableList.copyOf(executeSQL(withLocalMode));
        assertThat(actualLocalRunDeleteFile).containsOnly(Row.of("2"));

        String withDistributedMode =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s', older_than => '%s', dry_run => true, parallelism => 5, mode => 'distributed')"
                                : "CALL sys.remove_orphan_files('%s.%s', '%s', true, 5, 'distributed')",
                        database,
                        tableName,
                        olderThan);
        ImmutableList<Row> actualDistributedRunDeleteFile =
                ImmutableList.copyOf(executeSQL(withDistributedMode));
        assertThat(actualDistributedRunDeleteFile).containsOnly(Row.of("2"));

        String withInvalidMode =
                String.format(
                        isNamedArgument
                                ? "CALL sys.remove_orphan_files(`table` => '%s.%s', older_than => '%s', dry_run => true, parallelism => 5, mode => 'unknown')"
                                : "CALL sys.remove_orphan_files('%s.%s', '%s', true, 5, 'unknown')",
                        database,
                        tableName,
                        olderThan);
        assertThatCode(() -> executeSQL(withInvalidMode))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unknown mode");
    }

    protected boolean supportNamedArgument() {
        return true;
    }
}
