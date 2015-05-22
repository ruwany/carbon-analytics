/*
 *  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.analytics.spark.core.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.deploy.master.Master;
import org.apache.spark.deploy.master.MasterArguments;
import org.apache.spark.deploy.worker.Worker;
import org.apache.spark.deploy.worker.WorkerArguments;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.util.Utils;
import org.wso2.carbon.analytics.dataservice.AnalyticsDataService;
import org.wso2.carbon.analytics.dataservice.AnalyticsServiceHolder;
import org.wso2.carbon.analytics.dataservice.clustering.AnalyticsClusterException;
import org.wso2.carbon.analytics.dataservice.clustering.AnalyticsClusterManager;
import org.wso2.carbon.analytics.dataservice.clustering.GroupEventListener;
import org.wso2.carbon.analytics.datasource.commons.Record;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsException;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsTableNotAvailableException;
import org.wso2.carbon.analytics.datasource.core.util.GenericUtils;
import org.wso2.carbon.analytics.spark.core.AnalyticsExecutionCall;
import org.wso2.carbon.analytics.spark.core.exception.AnalyticsExecutionException;
import org.wso2.carbon.analytics.spark.core.util.AnalyticsConstants;
import org.wso2.carbon.analytics.spark.core.util.AnalyticsQueryResult;
import org.wso2.carbon.analytics.spark.core.util.AnalyticsRelation;
import org.wso2.carbon.utils.CarbonUtils;
import scala.None$;
import scala.Option;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This class represents the analytics query execution context.
 */
public class SparkAnalyticsExecutor implements GroupEventListener {

    private static final int BASE_WORKER_UI_PORT = 8090;

    private static final int BASE_WORKER_PORT = 4501;

    private static final String MASTER_PORT_GROUP_PROP = "MASTER_PORT";

    private static final String MASTER_HOST_GROUP_PROP = "MASTER_HOST";

    private static final int BASE_WEBUI_PORT = 8081;

    private static final int BASE_MASTER_PORT = 7077;

    private static final String CLUSTER_GROUP_NAME = "CARBON_ANALYTICS_EXECUTION";

    private static final String LOCAL_MASTER_URL = "local";

    private static final String CARBON_ANALYTICS_SPARK_APP_NAME = "CarbonAnalytics";

    private static final String WORKER_CORES = "1";

    private static final String WORKER_MEMORY = "1g";

    private static final String WORK_DIR = "work";

    private static final Log log = LogFactory.getLog(SparkAnalyticsExecutor.class);

    private SparkConf sparkConf;

    private JavaSparkContext javaSparkCtx;

    private SQLContext sqlCtx;

    private String myHost;
    
    private int portOffset;
    
    private int workerCount = 1;
    
    private Object workerActorSystem;
    private Object masterActorSystem;

    public SparkAnalyticsExecutor(String myHost, int portOffset) throws AnalyticsClusterException {
        this.myHost = myHost;
        this.portOffset = portOffset;
        AnalyticsClusterManager acm = AnalyticsServiceHolder.getAnalyticsClusterManager();
        if (acm.isClusteringEnabled()) {
            this.initSparkDataListener();
            acm.joinGroup(CLUSTER_GROUP_NAME, this);
        } else {
            this.initLocalClient();
        }
    }

    private void initClient(String masterUrl, String appName) {
        this.sparkConf = initSparkConf(masterUrl, appName);
        this.javaSparkCtx = new JavaSparkContext(this.sparkConf);
        this.sqlCtx = new SQLContext(this.javaSparkCtx);
    }

    private void initLocalClient() {
        this.sparkConf = new SparkConf();
        this.sparkConf.setMaster(LOCAL_MASTER_URL).setAppName(CARBON_ANALYTICS_SPARK_APP_NAME);
        this.javaSparkCtx = new JavaSparkContext(this.sparkConf);
        this.sqlCtx = new SQLContext(this.javaSparkCtx);
    }

    private void startMaster(String host, String port, String webUIport, String propsFile,
                             SparkConf sc) {
        String[] argsArray = new String[]{"-h", host,
                                          "-p", port,
                                          "--webui-port", webUIport,
                                          "--properties-file", propsFile //CarbonUtils.getCarbonHome() + File.separator + AnalyticsConstants.SPARK_DEFAULTS_PATH
        };
        MasterArguments args = new MasterArguments(argsArray, sc);
        this.masterActorSystem = Master.startSystemAndActor(args.host(), args.port(), args.webUiPort(), sc)._1();
    }

    private void startWorker(String workerHost, String masterHost, String masterPort,
                             String workerPort,
                             String workerUiPort, String workerCores, String workerMemory,
                             String workerDir,
                             String propFile, SparkConf sc) {
        String master = "spark://" + masterHost + ":" + masterPort;
        String[] argsArray = new String[]{master,
                                          "-h", workerHost,
                                          "-p", workerPort,
                                          "--webui-port", workerUiPort,
                                          "-c", workerCores,
                                          "-m", workerMemory,
                                          "-d", workerDir,
                                          "--properties-file", propFile //CarbonUtils.getCarbonHome() + File.separator + AnalyticsConstants.SPARK_DEFAULTS_PATH
        };
        WorkerArguments args = new WorkerArguments(argsArray, this.sparkConf);
        this.workerActorSystem = Worker.startSystemAndActor(args.host(), args.port(), args.webUiPort(),
                                                            args.cores(), args.memory(), args.masters(),
                                                            args.workDir(), (Option) None$.MODULE$, sc)._1();
    }

    private SparkConf initSparkConf(String masterUrl, String appName) {
        SparkConf conf = new SparkConf();
        conf.setIfMissing("spark.master", masterUrl);
        conf.setIfMissing("spark.app.name", appName);
        return conf;
    }

    private void initSparkDataListener() {
        this.validateSparkScriptPathPermission();
        ExecutorService executor = Executors.newFixedThreadPool(1);
        SparkDataListener listener = new SparkDataListener();
        executor.execute(listener);
    }

    private void validateSparkScriptPathPermission() {
        Set<PosixFilePermission> perms = new HashSet<>();
        //add owners permission
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        //add group permissions
        perms.add(PosixFilePermission.GROUP_READ);
        perms.add(PosixFilePermission.GROUP_WRITE);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        try {
            Files.setPosixFilePermissions(Paths.get(CarbonUtils.getCarbonHome() + File.separator +
                                                    AnalyticsConstants.SPARK_COMPUTE_CLASSPATH_SCRIPT_PATH), perms);
        } catch (IOException e) {
            log.warn("Error while checking the permission for " + AnalyticsConstants.SPARK_COMPUTE_CLASSPATH_SCRIPT_PATH
                     + ". " + e.getMessage());
        }
    }

    public void stop() {
        if (this.sqlCtx != null) {
            this.sqlCtx.sparkContext().stop();
            this.javaSparkCtx.close();
        }
    }

    private void processDefineTable(int tenantId, String query,
                                    String[] tokens) throws AnalyticsExecutionException {
        String tableName = tokens[2].trim();
        String alias = tableName;
        if (tokens[tokens.length - 2].equalsIgnoreCase(AnalyticsConstants.TERM_AS)) {
            alias = tokens[tokens.length - 1];
            query = query.substring(0, query.lastIndexOf(tokens[tokens.length - 2]));
        }
        String schemaString = query.substring(query.indexOf(tableName) + tableName.length()).trim();
        try {
            registerTable(tenantId, tableName, alias, schemaString);
        } catch (AnalyticsException e) {
            throw new AnalyticsExecutionException("Error in registering analytics table: " + e.getMessage(), e);
        }
    }
    
    public int getNumPartitionsHint() {
        /* all workers will not have the same CPU count, this is just an approximation */
        return this.getWorkerCount() * Runtime.getRuntime().availableProcessors();
    }
    
    private void processInsertInto(int tenantId, String query, 
            String[] tokens) throws AnalyticsExecutionException {
        String tableName = tokens[2].trim();
        String selectQuery = query.substring(query.indexOf(tableName) + tableName.length()).trim();
        try {
            insertIntoTable(tenantId, tableName, toResult(this.sqlCtx.sql
                    (encodeQueryWithTenantId(tenantId, selectQuery))));
        } catch (AnalyticsException e) {
            throw new AnalyticsExecutionException("Error in executing insert into query: " + e.getMessage(), e);
        }
    }
    
    public AnalyticsQueryResult executeQuery(int tenantId, String query) throws AnalyticsExecutionException {
        AnalyticsClusterManager acm = AnalyticsServiceHolder.getAnalyticsClusterManager();
        if (acm.isClusteringEnabled() && !acm.isLeader(CLUSTER_GROUP_NAME)) {
            try {
                return acm.executeOne(CLUSTER_GROUP_NAME, acm.getLeader(CLUSTER_GROUP_NAME),
                                      new AnalyticsExecutionCall(tenantId, query));
            } catch (AnalyticsClusterException e) {
                throw new AnalyticsExecutionException("Error executing analytics query: " + e.getMessage(), e);
            }
        } else {
            return this.executeQueryLocal(tenantId, query);
        }
    }

    public AnalyticsQueryResult executeQueryLocal(int tenantId, String query)
            throws AnalyticsExecutionException {
        query = query.trim();
        if (query.endsWith(";")) {
            query = query.substring(0, query.length() - 1);
        }
        String[] tokens = query.split(" ");
        if (tokens.length >= 3) {
            if (tokens[0].trim().equalsIgnoreCase(AnalyticsConstants.TERM_DEFINE) &&
                    tokens[1].trim().equalsIgnoreCase(AnalyticsConstants.TERM_TABLE)) {
                this.processDefineTable(tenantId, query, tokens);
                return null;
            } else if (tokens[0].trim().equalsIgnoreCase(AnalyticsConstants.TERM_INSERT) &&
                    tokens[1].trim().equalsIgnoreCase(AnalyticsConstants.TERM_INTO)) {
                this.processInsertInto(tenantId, query, tokens);
                return null;
            }
        }
        return toResult(this.sqlCtx.sql(encodeQueryWithTenantId(tenantId, query)));
    }

    private String encodeQueryWithTenantId(int tenantId, String query) {
        String result = query;
        String[] tokens = query.split("\\s+");
        ArrayList<String> tableNames = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            if ((tokens[i].compareToIgnoreCase(AnalyticsConstants.TERM_FROM) == 0 ||
                 tokens[i].compareToIgnoreCase(AnalyticsConstants.TERM_JOIN) == 0)
                && tokens[i + 1].substring(0, 1).matches("[a-zA-Z]")) {
                tableNames.add(tokens[i + 1]);
                i++;
            }
        }

        for (String name : tableNames) {
            result = result.replaceAll("\\b" + name + "\\b", encodeTableName(tenantId, name));
        }

        return result.trim();
    }

    private void insertIntoTable(int tenantId, String tableName,
                                 AnalyticsQueryResult data)
            throws AnalyticsException {
        AnalyticsDataService ads = ServiceHolder.getAnalyticsDataService();
        List<Record> records = this.generateInsertRecordsForTable(tenantId, tableName, data);
        ads.put(records);
    }
    
    private Integer[] generateTableKeyIndices(String[] keys, String[] columns) {
        List<Integer> result = new ArrayList<>();
        for (String key : keys) {
            for (int i = 0; i < columns.length; i++) {
                if (key.equals(columns[i])) {
                    result.add(i);
                    break;
                }
            }
        }
        return result.toArray(new Integer[result.size()]);
    }
    
    private String generateInsertRecordId(List<Object> row, Integer[] keyIndices) {
        StringBuilder builder = new StringBuilder();
        Object obj;
        for (int index : keyIndices) {
            obj = row.get(index);
            if (obj != null) {
                builder.append(obj.toString());
            }
        }
        /* to make sure, we don't have an empty string */
        builder.append("X");
        try {
            byte[] data = builder.toString().getBytes(AnalyticsConstants.DEFAULT_CHARSET);
            return UUID.nameUUIDFromBytes(data).toString();
        } catch (UnsupportedEncodingException e) {
            /* this wouldn't happen */
            throw new RuntimeException(e);
        }
    }

    private List<Record> generateInsertRecordsForTable(int tenantId, String tableName,
                                                       AnalyticsQueryResult data)
            throws AnalyticsException {
        String[] keys = loadTableKeys(tenantId, tableName);
        boolean primaryKeysExists = keys.length > 0;
        List<List<Object>> rows = data.getRows();
        String[] columns = data.getColumns();
        Integer[] keyIndices = this.generateTableKeyIndices(keys, columns);
        List<Record> result = new ArrayList<>(rows.size());
        Record record;
        for (List<Object> row : rows) {
            if (primaryKeysExists) {
                record = new Record(this.generateInsertRecordId(row, keyIndices), tenantId, tableName,
                                    extractValuesFromRow(row, columns));
            } else {
                record = new Record(tenantId, tableName, extractValuesFromRow(row, columns));
            }
            result.add(record);
        }
        return result;
    }
    
    private static Map<String, Object> extractValuesFromRow(List<Object> row, String[] columns) {
        Map<String, Object> result = new HashMap<>(row.size());
        for (int i = 0; i < row.size(); i++) {
            result.put(columns[i], row.get(i));
        }
        return result;
    }

    private static AnalyticsQueryResult toResult(DataFrame dataFrame)
            throws AnalyticsExecutionException {
        return new AnalyticsQueryResult(dataFrame.schema().fieldNames(),
                                        convertRowsToObjects(dataFrame.collect()));
    }

    private static List<List<Object>> convertRowsToObjects(Row[] rows) {
        List<List<Object>> result = new ArrayList<>();
        List<Object> objects;
        for (Row row : rows) {
            objects = new ArrayList<>();
            for (int i = 0; i < row.length(); i++) {
                objects.add(row.get(i));
            }
            result.add(objects);
        }
        return result;
    }
    
    private static void throwInvalidDefineTableQueryException() throws AnalyticsException {
        throw new AnalyticsException("Invalid define table query, must be in the format of "
                + "'define table <table> (name1 type1, name2 type2, name3 type3,... primary key(name1, name2..))'");
    }
    
    private static String generateTableKeysId(int tenantId, String tableName) {
        return tenantId + "_" + tableName;
    }
    
    private static byte[] tableKeysToBinary(String[] keys) throws AnalyticsException {
        return GenericUtils.serializeObject(keys);
    }
    
    private static String[] binaryToTableKeys(byte[] data) throws AnalyticsException {
        return (String[]) GenericUtils.deserializeObject(data);
    }

    private static String[] loadTableKeys(int tenantId, String tableName)
            throws AnalyticsException {
        AnalyticsDataService ads = ServiceHolder.getAnalyticsDataService();
        List<String> ids = new ArrayList<>(1);
        ids.add(generateTableKeysId(tenantId, tableName));
        List<Record> records = GenericUtils.listRecords(ads, ads.get(
                AnalyticsConstants.TABLE_INFO_TENANT_ID,
                AnalyticsConstants.TABLE_INFO_TABLE_NAME, 1, null, ids));
        if (records.size() == 0) {
            throw new AnalyticsException("Table keys cannot be found for tenant: " + tenantId + " table: " + tableName);
        }
        Record record = records.get(0);
        byte[] data = (byte[]) record.getValue(AnalyticsConstants.OBJECT);
        if (data == null) {
            throw new AnalyticsException("Corrupted table keys for tenant: " + tenantId + " table: " + tableName);
        }
        return binaryToTableKeys(data);
    }

    private static void registerTableKeys(int tenantId, String tableName,
                                          String[] keys) throws AnalyticsException {
        AnalyticsDataService ads = ServiceHolder.getAnalyticsDataService();
        Map<String, Object> values = new HashMap<>();
        values.put(AnalyticsConstants.OBJECT, tableKeysToBinary(keys));
        Record record = new Record(generateTableKeysId(tenantId, tableName), 
                AnalyticsConstants.TABLE_INFO_TENANT_ID, AnalyticsConstants.TABLE_INFO_TABLE_NAME, values);
        List<Record> records = new ArrayList<>(1);
        records.add(record);
        try {
            ads.put(records);
        } catch (AnalyticsTableNotAvailableException e) {
            ads.createTable(AnalyticsConstants.TABLE_INFO_TENANT_ID, AnalyticsConstants.TABLE_INFO_TABLE_NAME);
            ads.put(records);
        }
    }

    private static String processPrimaryKeyAndReturnSchema(int tenantId, String tableName,
                                                           String schemaString)
            throws AnalyticsException {
        int index = schemaString.toLowerCase().lastIndexOf(AnalyticsConstants.TERM_PRIMARY);
        String lastSection = "";
        if (index != -1) {
            index = schemaString.lastIndexOf(',', index);
            lastSection = schemaString.substring(index + 1).trim();
        }
        String[] lastTokens = lastSection.split(" ");
        if (lastTokens.length >= 2 && lastTokens[1].trim().toLowerCase().startsWith(AnalyticsConstants.TERM_KEY)) {
            String keysSection = lastSection.substring(lastSection.toLowerCase().indexOf(
                    AnalyticsConstants.TERM_KEY) + 3).trim();
            if (!(keysSection.startsWith("(") && keysSection.endsWith(")"))) {
                throwInvalidDefineTableQueryException();
            }
            keysSection = keysSection.substring(1, keysSection.length() - 1).trim();
            String keys[] = keysSection.split(",");
            for (int i = 0; i < keys.length; i++) {
                keys[i] = keys[i].trim();
            }
            registerTableKeys(tenantId, tableName, keys);
            return schemaString.substring(0, index).trim();
        } else {
            registerTableKeys(tenantId, tableName, new String[0]);
            return schemaString;
        }
    }
    
    private void registerTable(int tenantId, String tableName, String alias,
            String schemaString) throws AnalyticsException {
        if (!(schemaString.startsWith("(") && schemaString.endsWith(")"))) {
            throwInvalidDefineTableQueryException();
        }
        schemaString = schemaString.substring(1, schemaString.length() - 1).trim();
        schemaString = processPrimaryKeyAndReturnSchema(tenantId, tableName, schemaString);
        AnalyticsDataService ads = ServiceHolder.getAnalyticsDataService();
        if (!ads.tableExists(tenantId, tableName)) {
            ads.createTable(tenantId, tableName);
        }
        AnalyticsRelation table = new AnalyticsRelation(tenantId, tableName, this.sqlCtx, schemaString);
        DataFrame dataFrame = this.sqlCtx.baseRelationToDataFrame(table);
        dataFrame.registerTempTable(encodeTableName(tenantId, alias));
    }

    private String encodeTableName(int tenantId, String tableName) {
        String tenantStr;
        String delimiter = "_";
        if (tenantId < 0) {
            tenantStr = "X" + String.valueOf(-tenantId);
        } else {
            tenantStr = "T" + String.valueOf(tenantId);
        }
        return tenantStr + delimiter + tableName;
    }

    @Override
    public void onBecomingLeader() {
        String propsFile = CarbonUtils.getCarbonHome() + File.separator
                           + AnalyticsConstants.SPARK_DEFAULTS_PATH;
        Utils.loadDefaultSparkProperties(new SparkConf(), propsFile);
        log.info("Spark defaults loaded from " + propsFile);

        String masterPort = System.getProperty(AnalyticsConstants.SPARK_MASTER_PORT);
        if (masterPort == null) {
            masterPort = Integer.toString(BASE_MASTER_PORT + this.portOffset);
        }
        String webuiPort = System.getProperty(AnalyticsConstants.SPARK_MASTER_WEBUI_PORT);
        if (webuiPort == null) {
            webuiPort = Integer.toString(BASE_WEBUI_PORT + this.portOffset);
        }

        String master = System.getProperty(AnalyticsConstants.SPARK_MASTER);
        if (master == null) {
            master = "spark://" + this.myHost + ":" + masterPort;
        }
        String appName = System.getProperty(AnalyticsConstants.SPARK_APP_NAME);
        if (appName == null) {
            appName = CARBON_ANALYTICS_SPARK_APP_NAME;
        }
        this.sparkConf = initSparkConf(master, appName);

        this.startMaster(this.myHost, masterPort, webuiPort, propsFile, this.sparkConf);

        AnalyticsClusterManager acm = AnalyticsServiceHolder.getAnalyticsClusterManager();
        acm.setProperty(CLUSTER_GROUP_NAME, MASTER_HOST_GROUP_PROP, this.myHost);
        acm.setProperty(CLUSTER_GROUP_NAME, MASTER_PORT_GROUP_PROP, masterPort);
        log.info("Analytics master started: [" + master + "]");
    }

    @Override
    public void onLeaderUpdate() {
        String propsFile = CarbonUtils.getCarbonHome() + File.separator
                           + AnalyticsConstants.SPARK_DEFAULTS_PATH;
        Utils.loadDefaultSparkProperties(new SparkConf(), propsFile);
        log.info("Spark defaults loaded from " + propsFile);

        AnalyticsClusterManager acm = AnalyticsServiceHolder.getAnalyticsClusterManager();
        //take master information from the cluster
        String masterHost = (String) acm.getProperty(CLUSTER_GROUP_NAME, MASTER_HOST_GROUP_PROP);
        String masterPort = (String) acm.getProperty(CLUSTER_GROUP_NAME, MASTER_PORT_GROUP_PROP);

        String workerPort = System.getProperty(AnalyticsConstants.SPARK_WORKER_PORT);
        if (workerPort == null) {
            workerPort = Integer.toString(BASE_WORKER_PORT + this.portOffset);
        }

        String workerUiPort = System.getProperty(AnalyticsConstants.SPARK_WORKER_WEBUI_PORT);
        if (workerUiPort == null) {
            workerUiPort = Integer.toString(BASE_WORKER_UI_PORT + this.portOffset);
        }

        String workerCores = System.getProperty(AnalyticsConstants.SPARK_WORKER_CORES);
        if (workerCores == null) {
            workerCores = WORKER_CORES;
        }

        String workerMem = System.getProperty(AnalyticsConstants.SPARK_WORKER_MEMORY);
        if (workerMem == null) {
            workerMem = WORKER_MEMORY;
        }

        String workerDir = System.getProperty(AnalyticsConstants.SPARK_WORKER_DIR);
        if (workerDir == null) {
            workerDir = CarbonUtils.getCarbonHome() + File.separator + WORK_DIR;
        }

        String appName = System.getProperty(AnalyticsConstants.SPARK_APP_NAME);
        if (appName == null) {
            appName = CARBON_ANALYTICS_SPARK_APP_NAME;
        }

        this.startWorker(this.myHost, masterHost, masterPort, workerPort, workerUiPort, workerCores,
                         workerMem, workerDir, propsFile, this.sparkConf);

        if (acm.isLeader(CLUSTER_GROUP_NAME)) {
            this.initClient("spark://" + masterHost + ":" + masterPort, appName);
        }

        log.info("Analytics worker started: [" + this.myHost + ":" + workerPort + ":" + workerUiPort + "] "
                 + "Master [" + masterHost + ":" + masterPort + "]");
    }

    public int getWorkerCount() {
        return workerCount;
    }

    @Override
    public void onMembersChangeForLeader() {
        try {
            this.workerCount = AnalyticsServiceHolder.getAnalyticsClusterManager().getMembers(CLUSTER_GROUP_NAME).size();
            log.info("Analytics worker updated, total count: " + this.getWorkerCount());
        } catch (AnalyticsClusterException e) {
            log.error("Error in extracting the worker count: " + e.getMessage(), e);
        }
    }

}