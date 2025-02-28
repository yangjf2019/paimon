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

package org.apache.paimon.rest;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.Database;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.catalog.PropertyChange;
import org.apache.paimon.catalog.RenamingSnapshotCommit;
import org.apache.paimon.catalog.TableMetadata;
import org.apache.paimon.operation.Lock;
import org.apache.paimon.options.CatalogOptions;
import org.apache.paimon.options.Options;
import org.apache.paimon.partition.Partition;
import org.apache.paimon.rest.auth.BearTokenAuthProvider;
import org.apache.paimon.rest.requests.AlterDatabaseRequest;
import org.apache.paimon.rest.requests.AlterPartitionsRequest;
import org.apache.paimon.rest.requests.AlterTableRequest;
import org.apache.paimon.rest.requests.CommitTableRequest;
import org.apache.paimon.rest.requests.CreateBranchRequest;
import org.apache.paimon.rest.requests.CreateDatabaseRequest;
import org.apache.paimon.rest.requests.CreatePartitionsRequest;
import org.apache.paimon.rest.requests.CreateTableRequest;
import org.apache.paimon.rest.requests.CreateViewRequest;
import org.apache.paimon.rest.requests.DropPartitionsRequest;
import org.apache.paimon.rest.requests.ForwardBranchRequest;
import org.apache.paimon.rest.requests.MarkDonePartitionsRequest;
import org.apache.paimon.rest.requests.RenameTableRequest;
import org.apache.paimon.rest.responses.AlterDatabaseResponse;
import org.apache.paimon.rest.responses.CommitTableResponse;
import org.apache.paimon.rest.responses.CreateDatabaseResponse;
import org.apache.paimon.rest.responses.ErrorResponse;
import org.apache.paimon.rest.responses.ErrorResponseResourceType;
import org.apache.paimon.rest.responses.GetDatabaseResponse;
import org.apache.paimon.rest.responses.GetTableResponse;
import org.apache.paimon.rest.responses.GetTableSnapshotResponse;
import org.apache.paimon.rest.responses.GetTableTokenResponse;
import org.apache.paimon.rest.responses.GetViewResponse;
import org.apache.paimon.rest.responses.ListBranchesResponse;
import org.apache.paimon.rest.responses.ListDatabasesResponse;
import org.apache.paimon.rest.responses.ListPartitionsResponse;
import org.apache.paimon.rest.responses.ListTablesResponse;
import org.apache.paimon.rest.responses.ListViewsResponse;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FormatTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.types.DataField;
import org.apache.paimon.utils.BranchManager;
import org.apache.paimon.view.View;
import org.apache.paimon.view.ViewImpl;
import org.apache.paimon.view.ViewSchema;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.core.JsonProcessingException;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.paimon.rest.RESTObjectMapper.OBJECT_MAPPER;

/** Mock REST server for testing. */
public class RESTCatalogServer {

    private static final String PREFIX = "paimon";
    private static final String DATABASE_URI = String.format("/v1/%s/databases", PREFIX);

    private final MetadataInMemoryFileSystemCatalog catalog;
    private final Dispatcher dispatcher;
    private final MockWebServer server;
    private final String authToken;

    public final Map<String, Database> databaseStore = new HashMap<>();
    public final Map<String, TableMetadata> tableMetadataStore = new HashMap<>();
    public final Map<String, List<Partition>> tablePartitionsStore = new HashMap<>();
    public final Map<String, View> viewStore = new HashMap<>();
    public final Map<String, Snapshot> tableSnapshotStore = new HashMap<>();
    Map<String, RESTToken> dataTokenStore = new HashMap<>();

    public RESTCatalogServer(String warehouse, String initToken) {
        authToken = initToken;
        Options conf = new Options();
        conf.setString("warehouse", warehouse);
        this.catalog =
                MetadataInMemoryFileSystemCatalog.create(
                        CatalogContext.create(conf),
                        databaseStore,
                        tableMetadataStore,
                        tableSnapshotStore,
                        tablePartitionsStore,
                        viewStore,
                        dataTokenStore);
        this.dispatcher = initDispatcher(catalog, warehouse, authToken);
        MockWebServer mockWebServer = new MockWebServer();
        mockWebServer.setDispatcher(dispatcher);
        server = mockWebServer;
    }

    public void start() throws IOException {
        server.start();
    }

    public String getUrl() {
        return server.url("").toString();
    }

    public void shutdown() throws IOException {
        server.shutdown();
    }

    public void setTableSnapshot(Identifier identifier, Snapshot snapshot) {
        tableSnapshotStore.put(identifier.getFullName(), snapshot);
    }

    public void setDataToken(Identifier identifier, RESTToken token) {
        dataTokenStore.put(identifier.getFullName(), token);
    }

    public static Dispatcher initDispatcher(
            MetadataInMemoryFileSystemCatalog catalog, String warehouse, String authToken) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String token =
                        request.getHeaders().get(BearTokenAuthProvider.AUTHORIZATION_HEADER_KEY);
                RESTResponse response;
                try {
                    if (!("Bearer " + authToken).equals(token)) {
                        return new MockResponse().setResponseCode(401);
                    }
                    if (request.getPath().startsWith("/v1/config")) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setBody(getConfigBody(warehouse));
                    } else if (DATABASE_URI.equals(request.getPath())) {
                        return databasesApiHandler(catalog, request);
                    } else if (request.getPath().startsWith(DATABASE_URI)) {
                        String[] resources =
                                request.getPath()
                                        .substring((DATABASE_URI + "/").length())
                                        .split("/");
                        String databaseName = resources[0];
                        boolean isViews = resources.length == 2 && "views".equals(resources[1]);
                        boolean isTables = resources.length == 2 && "tables".equals(resources[1]);
                        boolean isTableRename =
                                resources.length == 3
                                        && "tables".equals(resources[1])
                                        && "rename".equals(resources[2]);
                        boolean isViewRename =
                                resources.length == 3
                                        && "views".equals(resources[1])
                                        && "rename".equals(resources[2]);
                        boolean isView =
                                resources.length == 3
                                        && "views".equals(resources[1])
                                        && !"rename".equals(resources[2]);
                        boolean isTable =
                                resources.length == 3
                                        && "tables".equals(resources[1])
                                        && !"rename".equals(resources[2])
                                        && !"commit".equals(resources[2]);
                        boolean isTableCommit =
                                resources.length == 3
                                        && "tables".equals(resources[1])
                                        && "commit".equals(resources[2]);
                        boolean isTableToken =
                                resources.length == 4
                                        && "tables".equals(resources[1])
                                        && "token".equals(resources[3]);
                        boolean isTableSnapshot =
                                resources.length == 4
                                        && "tables".equals(resources[1])
                                        && "snapshot".equals(resources[3]);
                        boolean isPartitions =
                                resources.length == 4
                                        && "tables".equals(resources[1])
                                        && "partitions".equals(resources[3]);

                        boolean isDropPartitions =
                                resources.length == 5
                                        && "tables".equals(resources[1])
                                        && "partitions".equals(resources[3])
                                        && "drop".equals(resources[4]);
                        boolean isAlterPartitions =
                                resources.length == 5
                                        && "tables".equals(resources[1])
                                        && "partitions".equals(resources[3])
                                        && "alter".equals(resources[4]);
                        boolean isMarkDonePartitions =
                                resources.length == 5
                                        && "tables".equals(resources[1])
                                        && "partitions".equals(resources[3])
                                        && "mark".equals(resources[4]);

                        boolean isBranches =
                                resources.length >= 4
                                        && "tables".equals(resources[1])
                                        && "branches".equals(resources[3]);
                        if (isDropPartitions) {
                            String tableName = resources[2];
                            Identifier identifier = Identifier.create(databaseName, tableName);
                            Optional<MockResponse> error =
                                    checkTablePartitioned(catalog, identifier);
                            if (error.isPresent()) {
                                return error.get();
                            }
                            DropPartitionsRequest dropPartitionsRequest =
                                    OBJECT_MAPPER.readValue(
                                            request.getBody().readUtf8(),
                                            DropPartitionsRequest.class);
                            catalog.dropPartitions(
                                    identifier, dropPartitionsRequest.getPartitionSpecs());
                            return new MockResponse().setResponseCode(200);
                        } else if (isAlterPartitions) {
                            String tableName = resources[2];
                            Identifier identifier = Identifier.create(databaseName, tableName);
                            Optional<MockResponse> error =
                                    checkTablePartitioned(catalog, identifier);
                            if (error.isPresent()) {
                                return error.get();
                            }
                            AlterPartitionsRequest alterPartitionsRequest =
                                    OBJECT_MAPPER.readValue(
                                            request.getBody().readUtf8(),
                                            AlterPartitionsRequest.class);
                            catalog.alterPartitions(
                                    identifier, alterPartitionsRequest.getPartitions());
                            return new MockResponse().setResponseCode(200);
                        } else if (isMarkDonePartitions) {
                            String tableName = resources[2];
                            Identifier identifier = Identifier.create(databaseName, tableName);
                            Optional<MockResponse> error =
                                    checkTablePartitioned(catalog, identifier);
                            if (error.isPresent()) {
                                return error.get();
                            }
                            MarkDonePartitionsRequest markDonePartitionsRequest =
                                    OBJECT_MAPPER.readValue(
                                            request.getBody().readUtf8(),
                                            MarkDonePartitionsRequest.class);
                            catalog.markDonePartitions(
                                    identifier, markDonePartitionsRequest.getPartitionSpecs());
                            return new MockResponse().setResponseCode(200);
                        } else if (isPartitions) {
                            String tableName = resources[2];
                            Optional<MockResponse> error =
                                    checkTablePartitioned(
                                            catalog, Identifier.create(databaseName, tableName));
                            if (error.isPresent()) {
                                return error.get();
                            }
                            return partitionsApiHandler(catalog, request, databaseName, tableName);
                        } else if (isBranches) {
                            String tableName = resources[2];
                            Identifier identifier = Identifier.create(databaseName, tableName);
                            FileStoreTable table = (FileStoreTable) catalog.getTable(identifier);
                            BranchManager branchManager = table.branchManager();
                            switch (request.getMethod()) {
                                case "DELETE":
                                    String branch = resources[4];
                                    table.deleteBranch(branch);
                                    return new MockResponse().setResponseCode(200);
                                case "GET":
                                    List<String> branches = branchManager.branches();
                                    response = new ListBranchesResponse(branches);
                                    return mockResponse(response, 200);
                                case "POST":
                                    if (resources.length == 5) {
                                        ForwardBranchRequest requestBody =
                                                OBJECT_MAPPER.readValue(
                                                        request.getBody().readUtf8(),
                                                        ForwardBranchRequest.class);
                                        branchManager.fastForward(requestBody.branch());
                                    } else {
                                        CreateBranchRequest requestBody =
                                                OBJECT_MAPPER.readValue(
                                                        request.getBody().readUtf8(),
                                                        CreateBranchRequest.class);
                                        if (requestBody.fromTag() == null) {
                                            branchManager.createBranch(requestBody.branch());
                                        } else {
                                            branchManager.createBranch(
                                                    requestBody.branch(), requestBody.fromTag());
                                        }
                                    }
                                    return new MockResponse().setResponseCode(200);
                                default:
                                    return new MockResponse().setResponseCode(404);
                            }
                        } else if (isTableToken) {
                            RESTToken dataToken =
                                    catalog.getToken(Identifier.create(databaseName, resources[2]));
                            GetTableTokenResponse getTableTokenResponse =
                                    new GetTableTokenResponse(
                                            dataToken.token(), dataToken.expireAtMillis());
                            return new MockResponse()
                                    .setResponseCode(200)
                                    .setBody(
                                            OBJECT_MAPPER.writeValueAsString(
                                                    getTableTokenResponse));
                        } else if (isTableSnapshot) {
                            String tableName = resources[2];
                            Optional<Snapshot> snapshotOptional =
                                    catalog.loadSnapshot(
                                            Identifier.create(databaseName, tableName));
                            if (!snapshotOptional.isPresent()) {
                                response =
                                        new ErrorResponse(
                                                ErrorResponseResourceType.SNAPSHOT,
                                                databaseName,
                                                "No Snapshot",
                                                404);
                                return mockResponse(response, 404);
                            }
                            GetTableSnapshotResponse getTableSnapshotResponse =
                                    new GetTableSnapshotResponse(snapshotOptional.get());
                            return new MockResponse()
                                    .setResponseCode(200)
                                    .setBody(
                                            OBJECT_MAPPER.writeValueAsString(
                                                    getTableSnapshotResponse));
                        } else if (isTableRename) {
                            return renameTableApiHandler(catalog, request);
                        } else if (isTableCommit) {
                            return commitTableApiHandler(
                                    catalog, request, databaseName, resources[2]);
                        } else if (isTable) {
                            String tableName = resources[2];
                            return tableApiHandler(catalog, request, databaseName, tableName);
                        } else if (isTables) {
                            return tablesApiHandler(catalog, request, databaseName);
                        } else if (isViews) {
                            return viewsApiHandler(catalog, request, databaseName);
                        } else if (isViewRename) {
                            return renameViewApiHandler(catalog, request);
                        } else if (isView) {
                            String viewName = resources[2];
                            return viewApiHandler(catalog, request, databaseName, viewName);
                        } else {
                            return databaseApiHandler(catalog, request, databaseName);
                        }
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (Catalog.DatabaseNotExistException e) {
                    response =
                            new ErrorResponse(
                                    ErrorResponseResourceType.DATABASE,
                                    e.database(),
                                    e.getMessage(),
                                    404);
                    return mockResponse(response, 404);
                } catch (Catalog.TableNotExistException e) {
                    response =
                            new ErrorResponse(
                                    ErrorResponseResourceType.TABLE,
                                    e.identifier().getTableName(),
                                    e.getMessage(),
                                    404);
                    return mockResponse(response, 404);
                } catch (Catalog.ColumnNotExistException e) {
                    response =
                            new ErrorResponse(
                                    ErrorResponseResourceType.COLUMN,
                                    e.column(),
                                    e.getMessage(),
                                    404);
                    return mockResponse(response, 404);
                } catch (Catalog.DatabaseAlreadyExistException e) {
                    response =
                            new ErrorResponse(
                                    ErrorResponseResourceType.DATABASE,
                                    e.database(),
                                    e.getMessage(),
                                    409);
                    return mockResponse(response, 409);
                } catch (Catalog.TableAlreadyExistException e) {
                    response =
                            new ErrorResponse(
                                    ErrorResponseResourceType.TABLE,
                                    e.identifier().getTableName(),
                                    e.getMessage(),
                                    409);
                    return mockResponse(response, 409);
                } catch (Catalog.ColumnAlreadyExistException e) {
                    response =
                            new ErrorResponse(
                                    ErrorResponseResourceType.COLUMN,
                                    e.column(),
                                    e.getMessage(),
                                    409);
                    return mockResponse(response, 409);
                } catch (Catalog.ViewNotExistException e) {
                    response =
                            new ErrorResponse(
                                    ErrorResponseResourceType.VIEW,
                                    e.identifier().getTableName(),
                                    e.getMessage(),
                                    404);
                    return mockResponse(response, 404);
                } catch (Catalog.ViewAlreadyExistException e) {
                    response =
                            new ErrorResponse(
                                    ErrorResponseResourceType.VIEW,
                                    e.identifier().getTableName(),
                                    e.getMessage(),
                                    409);
                    return mockResponse(response, 409);
                } catch (IllegalArgumentException e) {
                    response = new ErrorResponse(null, null, e.getMessage(), 400);
                    return mockResponse(response, 400);
                } catch (Exception e) {
                    e.printStackTrace();
                    if (e.getCause() instanceof IllegalArgumentException) {
                        response =
                                new ErrorResponse(
                                        null, null, e.getCause().getCause().getMessage(), 400);
                        return mockResponse(response, 400);
                    } else if (e instanceof UnsupportedOperationException) {
                        response = new ErrorResponse(null, null, e.getMessage(), 501);
                        return mockResponse(response, 501);
                    } else if (e instanceof IllegalStateException) {
                        response = new ErrorResponse(null, null, e.getMessage(), 500);
                        return mockResponse(response, 500);
                    }
                    return new MockResponse().setResponseCode(500);
                }
            }
        };
    }

    private static Optional<MockResponse> checkTablePartitioned(
            Catalog catalog, Identifier identifier) {
        Table table;
        try {
            table = catalog.getTable(identifier);
        } catch (Catalog.TableNotExistException e) {
            return Optional.of(
                    mockResponse(
                            new ErrorResponse(ErrorResponseResourceType.TABLE, null, "", 404),
                            404));
        }
        boolean partitioned = CoreOptions.fromMap(table.options()).partitionedTableInMetastore();
        if (!partitioned) {
            return Optional.of(mockResponse(new ErrorResponse(null, null, "", 501), 501));
        }
        return Optional.empty();
    }

    private static MockResponse commitTableApiHandler(
            Catalog catalog, RecordedRequest request, String databaseName, String tableName)
            throws Exception {
        CommitTableRequest requestBody =
                OBJECT_MAPPER.readValue(request.getBody().readUtf8(), CommitTableRequest.class);
        FileStoreTable table =
                (FileStoreTable) catalog.getTable(Identifier.create(databaseName, tableName));
        RenamingSnapshotCommit commit =
                new RenamingSnapshotCommit(table.snapshotManager(), Lock.empty());
        String branchName = requestBody.getIdentifier().getBranchName();
        if (branchName == null) {
            branchName = "main";
        }
        boolean success =
                commit.commit(requestBody.getSnapshot(), branchName, Collections.emptyList());
        CommitTableResponse response = new CommitTableResponse(success);
        return mockResponse(response, 200);
    }

    private static MockResponse databasesApiHandler(Catalog catalog, RecordedRequest request)
            throws Exception {
        RESTResponse response;
        switch (request.getMethod()) {
            case "GET":
                List<String> databaseNameList = catalog.listDatabases();
                response = new ListDatabasesResponse(databaseNameList);
                return mockResponse(response, 200);
            case "POST":
                CreateDatabaseRequest requestBody =
                        OBJECT_MAPPER.readValue(
                                request.getBody().readUtf8(), CreateDatabaseRequest.class);
                String databaseName = requestBody.getName();
                catalog.createDatabase(databaseName, false);
                response = new CreateDatabaseResponse(databaseName, requestBody.getOptions());
                return mockResponse(response, 200);
            default:
                return new MockResponse().setResponseCode(404);
        }
    }

    private static MockResponse databaseApiHandler(
            Catalog catalog, RecordedRequest request, String databaseName) throws Exception {
        RESTResponse response;
        switch (request.getMethod()) {
            case "GET":
                Database database = catalog.getDatabase(databaseName);
                response =
                        new GetDatabaseResponse(
                                UUID.randomUUID().toString(), database.name(), database.options());
                return mockResponse(response, 200);
            case "DELETE":
                catalog.dropDatabase(databaseName, false, true);
                return new MockResponse().setResponseCode(200);
            case "POST":
                AlterDatabaseRequest requestBody =
                        OBJECT_MAPPER.readValue(
                                request.getBody().readUtf8(), AlterDatabaseRequest.class);
                List<PropertyChange> changes = new ArrayList<>();
                for (String property : requestBody.getRemovals()) {
                    changes.add(PropertyChange.removeProperty(property));
                }
                for (Map.Entry<String, String> entry : requestBody.getUpdates().entrySet()) {
                    changes.add(PropertyChange.setProperty(entry.getKey(), entry.getValue()));
                }
                catalog.alterDatabase(databaseName, changes, false);
                AlterDatabaseResponse alterDatabaseResponse =
                        new AlterDatabaseResponse(
                                requestBody.getRemovals(),
                                requestBody.getUpdates().keySet().stream()
                                        .collect(Collectors.toList()),
                                Collections.emptyList());
                return mockResponse(alterDatabaseResponse, 200);
            default:
                return new MockResponse().setResponseCode(404);
        }
    }

    private static MockResponse tablesApiHandler(
            Catalog catalog, RecordedRequest request, String databaseName) throws Exception {
        RESTResponse response;
        switch (request.getMethod()) {
            case "GET":
                response = new ListTablesResponse(catalog.listTables(databaseName));
                return mockResponse(response, 200);
            case "POST":
                CreateTableRequest requestBody =
                        OBJECT_MAPPER.readValue(
                                request.getBody().readUtf8(), CreateTableRequest.class);
                catalog.createTable(requestBody.getIdentifier(), requestBody.getSchema(), false);
                return new MockResponse().setResponseCode(200);
            default:
                return new MockResponse().setResponseCode(404);
        }
    }

    private static MockResponse tableApiHandler(
            Catalog catalog, RecordedRequest request, String databaseName, String tableName)
            throws Exception {
        RESTResponse response;
        Identifier identifier = Identifier.create(databaseName, tableName);
        switch (request.getMethod()) {
            case "GET":
                response = getTable(catalog, databaseName, tableName);
                return mockResponse(response, 200);
            case "POST":
                AlterTableRequest requestBody =
                        OBJECT_MAPPER.readValue(
                                request.getBody().readUtf8(), AlterTableRequest.class);
                catalog.alterTable(identifier, requestBody.getChanges(), false);
                return new MockResponse().setResponseCode(200);
            case "DELETE":
                catalog.dropTable(identifier, false);
                return new MockResponse().setResponseCode(200);
            default:
                return new MockResponse().setResponseCode(404);
        }
    }

    private static MockResponse renameTableApiHandler(Catalog catalog, RecordedRequest request)
            throws Exception {
        RenameTableRequest requestBody =
                OBJECT_MAPPER.readValue(request.getBody().readUtf8(), RenameTableRequest.class);
        catalog.renameTable(requestBody.getSource(), requestBody.getDestination(), false);
        return new MockResponse().setResponseCode(200);
    }

    private static MockResponse partitionsApiHandler(
            Catalog catalog, RecordedRequest request, String databaseName, String tableName)
            throws Exception {
        RESTResponse response;
        Identifier identifier = Identifier.create(databaseName, tableName);
        switch (request.getMethod()) {
            case "GET":
                List<Partition> partitions = catalog.listPartitions(identifier);
                response = new ListPartitionsResponse(partitions);
                return mockResponse(response, 200);
            case "POST":
                CreatePartitionsRequest requestBody =
                        OBJECT_MAPPER.readValue(
                                request.getBody().readUtf8(), CreatePartitionsRequest.class);
                catalog.createPartitions(identifier, requestBody.getPartitionSpecs());
                return new MockResponse().setResponseCode(200);
            default:
                return new MockResponse().setResponseCode(404);
        }
    }

    private static MockResponse viewsApiHandler(
            Catalog catalog, RecordedRequest request, String databaseName) throws Exception {
        RESTResponse response;
        switch (request.getMethod()) {
            case "GET":
                response = new ListViewsResponse(catalog.listViews(databaseName));
                return mockResponse(response, 200);
            case "POST":
                CreateViewRequest requestBody =
                        OBJECT_MAPPER.readValue(
                                request.getBody().readUtf8(), CreateViewRequest.class);
                ViewSchema schema = requestBody.getSchema();
                ViewImpl view =
                        new ViewImpl(
                                requestBody.getIdentifier(),
                                schema.fields(),
                                schema.query(),
                                schema.dialects(),
                                schema.comment(),
                                schema.options());
                catalog.createView(requestBody.getIdentifier(), view, false);
                return new MockResponse().setResponseCode(200);
            default:
                return new MockResponse().setResponseCode(404);
        }
    }

    private static MockResponse viewApiHandler(
            Catalog catalog, RecordedRequest request, String databaseName, String viewName)
            throws Exception {
        RESTResponse response;
        Identifier identifier = Identifier.create(databaseName, viewName);
        switch (request.getMethod()) {
            case "GET":
                View view = catalog.getView(identifier);
                ViewSchema schema =
                        new ViewSchema(
                                view.rowType().getFields(),
                                view.query(),
                                view.dialects(),
                                view.comment().orElse(null),
                                view.options());
                response = new GetViewResponse("id", identifier.getTableName(), schema);
                return mockResponse(response, 200);
            case "DELETE":
                catalog.dropView(identifier, false);
                return new MockResponse().setResponseCode(200);
            default:
                return new MockResponse().setResponseCode(404);
        }
    }

    private static MockResponse renameViewApiHandler(Catalog catalog, RecordedRequest request)
            throws Exception {
        RenameTableRequest requestBody =
                OBJECT_MAPPER.readValue(request.getBody().readUtf8(), RenameTableRequest.class);
        catalog.renameView(requestBody.getSource(), requestBody.getDestination(), false);
        return new MockResponse().setResponseCode(200);
    }

    private static GetTableResponse getTable(Catalog catalog, String databaseName, String tableName)
            throws Exception {
        Identifier identifier = Identifier.create(databaseName, tableName);
        Table table = catalog.getTable(identifier);
        Schema schema;
        Long schemaId = 1L;
        if (table instanceof FileStoreTable) {
            FileStoreTable fileStoreTable = (FileStoreTable) table;
            schema = fileStoreTable.schema().toSchema();
            schemaId = fileStoreTable.schema().id();
        } else {
            FormatTable formatTable = (FormatTable) table;
            List<DataField> fields = formatTable.rowType().getFields();
            schema =
                    new Schema(
                            fields,
                            table.partitionKeys(),
                            table.primaryKeys(),
                            table.options(),
                            table.comment().orElse(null));
        }
        return new GetTableResponse(table.uuid(), table.name(), false, schemaId, schema);
    }

    private static MockResponse mockResponse(RESTResponse response, int httpCode) {
        try {
            return new MockResponse()
                    .setResponseCode(httpCode)
                    .setBody(OBJECT_MAPPER.writeValueAsString(response))
                    .addHeader("Content-Type", "application/json");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getConfigBody(String warehouseStr) {
        return String.format(
                "{\"defaults\": {\"%s\": \"%s\", \"%s\": \"%s\", \"%s\": \"%s\"}}",
                RESTCatalogInternalOptions.PREFIX.key(),
                PREFIX,
                CatalogOptions.WAREHOUSE.key(),
                warehouseStr,
                "header.test-header",
                "test-value");
    }
}
