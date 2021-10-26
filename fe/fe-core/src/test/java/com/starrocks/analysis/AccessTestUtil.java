// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/analysis/AccessTestUtil.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.analysis;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.catalog.BrokerMgr;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.FakeEditLog;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndex.IndexState;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.RandomDistributionInfo;
import com.starrocks.catalog.SinglePartitionInfo;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.load.Load;
import com.starrocks.mysql.privilege.Auth;
import com.starrocks.mysql.privilege.PrivPredicate;
import com.starrocks.persist.EditLog;
import com.starrocks.qe.ConnectContext;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TStorageType;
import mockit.Expectations;

import java.util.LinkedList;
import java.util.List;

public class AccessTestUtil {
    private static FakeEditLog fakeEditLog;

    public static SystemInfoService fetchSystemInfoService() {
        SystemInfoService clusterInfo = new SystemInfoService();
        return clusterInfo;
    }

    public static Auth fetchAdminAccess() {
        Auth auth = new Auth();
        try {
            new Expectations(auth) {
                {
                    auth.checkGlobalPriv((ConnectContext) any, (PrivPredicate) any);
                    minTimes = 0;
                    result = true;

                    auth.checkDbPriv((ConnectContext) any, anyString, (PrivPredicate) any);
                    minTimes = 0;
                    result = true;

                    auth.checkTblPriv((ConnectContext) any, anyString, anyString, (PrivPredicate) any);
                    minTimes = 0;
                    result = true;

                    auth.setPassword((SetPassVar) any);
                    minTimes = 0;
                }
            };
        } catch (DdlException e) {
            e.printStackTrace();
        }
        return auth;
    }

    public static Catalog fetchAdminCatalog() {
        try {
            Catalog catalog = Deencapsulation.newInstance(Catalog.class);

            Auth auth = fetchAdminAccess();

            fakeEditLog = new FakeEditLog();
            EditLog editLog = new EditLog("name");
            catalog.setEditLog(editLog);

            Database db = new Database(50000L, "testCluster:testDb");
            MaterializedIndex baseIndex = new MaterializedIndex(30001, IndexState.NORMAL);
            RandomDistributionInfo distributionInfo = new RandomDistributionInfo(10);
            Partition partition = new Partition(20000L, "testTbl", baseIndex, distributionInfo);
            List<Column> baseSchema = new LinkedList<Column>();
            Column column = new Column();
            baseSchema.add(column);
            OlapTable table = new OlapTable(30000, "testTbl", baseSchema,
                    KeysType.AGG_KEYS, new SinglePartitionInfo(), distributionInfo, catalog.getClusterId(), null);
            table.setIndexMeta(baseIndex.getId(), "testTbl", baseSchema, 0, 1, (short) 1,
                    TStorageType.COLUMN, KeysType.AGG_KEYS);
            table.addPartition(partition);
            table.setBaseIndexId(baseIndex.getId());
            db.createTable(table);

            new Expectations(catalog) {
                {
                    catalog.getAuth();
                    minTimes = 0;
                    result = auth;

                    catalog.getDb(50000L);
                    minTimes = 0;
                    result = db;

                    catalog.getDb("testCluster:testDb");
                    minTimes = 0;
                    result = db;

                    catalog.getDb("testCluster:emptyDb");
                    minTimes = 0;
                    result = null;

                    catalog.getDb(anyString);
                    minTimes = 0;
                    result = new Database();

                    catalog.getDbNames();
                    minTimes = 0;
                    result = Lists.newArrayList("testCluster:testDb");

                    catalog.getEditLog();
                    minTimes = 0;
                    result = editLog;

                    catalog.getLoadInstance();
                    minTimes = 0;
                    result = new Load();

                    catalog.getClusterDbNames("testCluster");
                    minTimes = 0;
                    result = Lists.newArrayList("testCluster:testDb");

                    catalog.changeDb((ConnectContext) any, "blockDb");
                    minTimes = 0;
                    result = new DdlException("failed");

                    catalog.changeDb((ConnectContext) any, anyString);
                    minTimes = 0;

                    catalog.getBrokerMgr();
                    minTimes = 0;
                    result = new BrokerMgr();
                }
            };
            return catalog;
        } catch (DdlException e) {
            return null;
        } catch (AnalysisException e) {
            return null;
        }
    }

    public static Auth fetchBlockAccess() {
        Auth auth = new Auth();
        new Expectations(auth) {
            {
                auth.checkGlobalPriv((ConnectContext) any, (PrivPredicate) any);
                minTimes = 0;
                result = false;

                auth.checkDbPriv((ConnectContext) any, anyString, (PrivPredicate) any);
                minTimes = 0;
                result = false;

                auth.checkTblPriv((ConnectContext) any, anyString, anyString, (PrivPredicate) any);
                minTimes = 0;
                result = false;
            }
        };
        return auth;
    }

    public static OlapTable mockTable(String name) {
        Column column1 = new Column("col1", Type.BIGINT);
        Column column2 = new Column("col2", Type.DOUBLE);

        MaterializedIndex index = new MaterializedIndex();
        new Expectations(index) {
            {
                index.getId();
                minTimes = 0;
                result = 30000L;
            }
        };

        Partition partition = Deencapsulation.newInstance(Partition.class);
        new Expectations(partition) {
            {
                partition.getBaseIndex();
                minTimes = 0;
                result = index;

                partition.getIndex(30000L);
                minTimes = 0;
                result = index;
            }
        };

        OlapTable table = new OlapTable();
        new Expectations(table) {
            {
                table.getBaseSchema();
                minTimes = 0;
                result = Lists.newArrayList(column1, column2);

                table.getPartition(40000L);
                minTimes = 0;
                result = partition;
            }
        };
        return table;
    }

    public static Database mockDb(String name) {
        Database db = new Database();
        OlapTable olapTable = mockTable("testTable");

        new Expectations(db) {
            {
                db.getTable("testTable");
                minTimes = 0;
                result = olapTable;

                db.getTable("emptyTable");
                minTimes = 0;
                result = null;

                db.getTableNamesWithLock();
                minTimes = 0;
                result = Sets.newHashSet("testTable");

                db.getTables();
                minTimes = 0;
                result = Lists.newArrayList(olapTable);

                db.readLock();
                minTimes = 0;

                db.readUnlock();
                minTimes = 0;

                db.getFullName();
                minTimes = 0;
                result = name;
            }
        };
        return db;
    }

    public static Catalog fetchBlockCatalog() {
        try {
            Catalog catalog = Deencapsulation.newInstance(Catalog.class);

            Auth auth = fetchBlockAccess();
            Database db = mockDb("testCluster:testDb");

            new Expectations(catalog) {
                {
                    catalog.getAuth();
                    minTimes = 0;
                    result = auth;

                    catalog.changeDb((ConnectContext) any, anyString);
                    minTimes = 0;
                    result = new DdlException("failed");

                    catalog.getDb("testCluster:testDb");
                    minTimes = 0;
                    result = db;

                    catalog.getDb("testCluster:emptyDb");
                    minTimes = 0;
                    result = null;

                    catalog.getDb(anyString);
                    minTimes = 0;
                    result = new Database();

                    catalog.getDbNames();
                    minTimes = 0;
                    result = Lists.newArrayList("testCluster:testDb");

                    catalog.getClusterDbNames("testCluster");
                    minTimes = 0;
                    result = Lists.newArrayList("testCluster:testDb");

                    catalog.getDb("emptyCluster");
                    minTimes = 0;
                    result = null;
                }
            };
            return catalog;
        } catch (DdlException e) {
            return null;
        } catch (AnalysisException e) {
            return null;
        }
    }

    public static Analyzer fetchAdminAnalyzer(boolean withCluster) {
        final String prefix = "testCluster:";

        Analyzer analyzer = new Analyzer(fetchAdminCatalog(), new ConnectContext(null));
        new Expectations(analyzer) {
            {
                analyzer.getDefaultDb();
                minTimes = 0;
                result = withCluster ? prefix + "testDb" : "testDb";

                analyzer.getQualifiedUser();
                minTimes = 0;
                result = withCluster ? prefix + "testUser" : "testUser";

                analyzer.getClusterName();
                minTimes = 0;
                result = "testCluster";

                analyzer.incrementCallDepth();
                minTimes = 0;
                result = 1;

                analyzer.decrementCallDepth();
                minTimes = 0;
                result = 0;

                analyzer.getCallDepth();
                minTimes = 0;
                result = 1;
            }
        };
        return analyzer;
    }

    public static Analyzer fetchBlockAnalyzer() throws AnalysisException {
        Analyzer analyzer = new Analyzer(fetchBlockCatalog(), new ConnectContext(null));
        new Expectations(analyzer) {
            {
                analyzer.getDefaultDb();
                minTimes = 0;
                result = "testCluster:testDb";

                analyzer.getQualifiedUser();
                minTimes = 0;
                result = "testCluster:testUser";

                analyzer.getClusterName();
                minTimes = 0;
                result = "testCluster";
            }
        };
        return analyzer;
    }

    public static Analyzer fetchEmptyDbAnalyzer() {
        Analyzer analyzer = new Analyzer(fetchBlockCatalog(), new ConnectContext(null));
        new Expectations(analyzer) {
            {
                analyzer.getDefaultDb();
                minTimes = 0;
                result = "";

                analyzer.getQualifiedUser();
                minTimes = 0;
                result = "testCluster:testUser";

                analyzer.getClusterName();
                minTimes = 0;
                result = "testCluster";
            }
        };
        return analyzer;
    }

    public static Analyzer fetchTableAnalyzer() {
        Column column1 = new Column("k1", Type.VARCHAR);
        Column column2 = new Column("k2", Type.VARCHAR);
        Column column3 = new Column("k3", Type.VARCHAR);
        Column column4 = new Column("k4", Type.BIGINT);

        MaterializedIndex index = new MaterializedIndex();
        new Expectations(index) {
            {
                index.getId();
                minTimes = 0;
                result = 30000L;
            }
        };

        Partition partition = Deencapsulation.newInstance(Partition.class);
        new Expectations(partition) {
            {
                partition.getBaseIndex();
                minTimes = 0;
                result = index;

                partition.getIndex(30000L);
                minTimes = 0;
                result = index;
            }
        };

        OlapTable table = new OlapTable();
        new Expectations(table) {
            {
                table.getBaseSchema();
                minTimes = 0;
                result = Lists.newArrayList(column1, column2, column3, column4);

                table.getPartition(40000L);
                minTimes = 0;
                result = partition;

                table.getColumn("k1");
                minTimes = 0;
                result = column1;

                table.getColumn("k2");
                minTimes = 0;
                result = column2;

                table.getColumn("k3");
                minTimes = 0;
                result = column3;

                table.getColumn("k4");
                minTimes = 0;
                result = column4;
            }
        };

        Database db = new Database();

        new Expectations(db) {
            {
                db.getTable("t");
                minTimes = 0;
                result = table;

                db.getTable("emptyTable");
                minTimes = 0;
                result = null;

                db.getTableNamesWithLock();
                minTimes = 0;
                result = Sets.newHashSet("t");

                db.getTables();
                minTimes = 0;
                result = Lists.newArrayList(table);

                db.readLock();
                minTimes = 0;

                db.readUnlock();
                minTimes = 0;

                db.getFullName();
                minTimes = 0;
                result = "testDb";
            }
        };
        Catalog catalog = fetchBlockCatalog();
        Analyzer analyzer = new Analyzer(catalog, new ConnectContext(null));
        new Expectations(analyzer) {
            {
                analyzer.getDefaultDb();
                minTimes = 0;
                result = "testDb";

                analyzer.getTable((TableName) any);
                minTimes = 0;
                result = table;

                analyzer.getQualifiedUser();
                minTimes = 0;
                result = "testUser";

                analyzer.getCatalog();
                minTimes = 0;
                result = catalog;

                analyzer.getClusterName();
                minTimes = 0;
                result = "testCluster";

                analyzer.incrementCallDepth();
                minTimes = 0;
                result = 1;

                analyzer.decrementCallDepth();
                minTimes = 0;
                result = 0;

                analyzer.getCallDepth();
                minTimes = 0;
                result = 1;

                analyzer.getContext();
                minTimes = 0;
                result = new ConnectContext(null);

            }
        };
        return analyzer;
    }
}

