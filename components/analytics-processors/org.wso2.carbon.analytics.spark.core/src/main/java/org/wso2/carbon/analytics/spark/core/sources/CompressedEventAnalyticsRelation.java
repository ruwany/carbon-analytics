/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.analytics.spark.core.sources;

import static org.wso2.carbon.analytics.spark.core.util.AnalyticsCommonUtils.extractFieldsFromColumns;
import static org.wso2.carbon.analytics.spark.core.util.AnalyticsCommonUtils.isEmptyAnalyticsSchema;
import static org.wso2.carbon.analytics.spark.core.util.AnalyticsCommonUtils.isEmptySchema;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.sources.BaseRelation;
import org.apache.spark.sql.sources.InsertableRelation;
import org.apache.spark.sql.sources.TableScan;
import org.apache.spark.sql.types.StructType;
import org.wso2.carbon.analytics.dataservice.core.AnalyticsDataService;
import org.wso2.carbon.analytics.datasource.commons.AnalyticsSchema;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsException;
import org.wso2.carbon.analytics.spark.core.internal.ServiceHolder;
import org.wso2.carbon.analytics.spark.core.rdd.CompressedEventAnalyticsRDD;
import org.wso2.carbon.analytics.spark.core.util.CarbonScalaUtils;

import scala.reflect.ClassTag$;

/**
 * This class represents a Spark SQL relation with respect to the Analytics Data Service.
 */
public class CompressedEventAnalyticsRelation extends BaseRelation implements TableScan,
                                                               InsertableRelation, Serializable {
    private static final long serialVersionUID = -6621212018440626281L;
    private static final Log log = LogFactory.getLog(CompressedEventAnalyticsRelation.class);
    private SQLContext sqlContext;
    private StructType schema;
    private int tenantId;
    private String tableName;
    private boolean schemaMerge;
    private String recordStore;
    
    public CompressedEventAnalyticsRelation() {
    }

    /**
     * Creates a relation between the spark table and physical DB table.
     * 
     * @param tenantId      Tenant ID
     * @param recordStore   Record Store name
     * @param tableName     Name of the table
     * @param sqlContext    Spark SQl Context
     */
    public CompressedEventAnalyticsRelation(int tenantId, String recordStore, String tableName,
            boolean schemaMerge, SQLContext sqlContext) {
        this.tenantId = tenantId;
        this.recordStore = recordStore;
        this.tableName = tableName;
        this.sqlContext = sqlContext;
        this.schemaMerge = schemaMerge;

        try {
            AnalyticsSchema analyticsSchema = ServiceHolder.getAnalyticsDataService().getTableSchema(
                    tenantId, tableName);
            if (isEmptyAnalyticsSchema(analyticsSchema)) {
                log.warn(this.tableName + " table created with an empty schema. Aborting creating the relation");
                throw new RuntimeException("Analytics Relation created with an empty schema for " +
                                           "table" + this.tableName);
            } else {
                this.schema = new StructType(extractFieldsFromColumns(analyticsSchema.getColumns()));
            }
        } catch (AnalyticsException e) {
            String msg = "Failed to load the schema for table " + tableName + " : " + e.getMessage();
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    /**
     * Creates a relation between the spark table and physical DB table.
     * 
     * @param tenantId      Tenant ID
     * @param recordStore   Record Store name
     * @param tableName     Table name
     * @param sqlContext    Spark SQL Context
     * @param schema        Schema of the Table
     */
    public CompressedEventAnalyticsRelation(int tenantId, String recordStore, String tableName,
            boolean schemaMerge, SQLContext sqlContext, StructType schema) {
        this.tenantId = tenantId;
        this.tableName = tableName;
        this.recordStore = recordStore;
        this.sqlContext = sqlContext;
        this.schema = schema;
        this.schemaMerge = schemaMerge;
    }

    @SuppressWarnings("unchecked")
    @Override
    public RDD<Row> buildScan() {
        if (isEmptySchema(this.schema)) {
            String msg = "Unable to scan through the table as the schema is unavailable for " + this.tableName;
            log.error(msg);
            throw new RuntimeException(msg);
        }
        return new CompressedEventAnalyticsRDD(this.tenantId, this.tableName, new ArrayList<>(Arrays.asList(this.schema
            .fieldNames())), this.schemaMerge, this.sqlContext.sparkContext(),
            scala.collection.Seq$.MODULE$.empty(), ClassTag$.MODULE$.<Row> apply(Row.class));
    }

    @Override
    public SQLContext sqlContext() {
        return this.sqlContext;
    }

    @Override
    public StructType schema() {
        if (isEmptySchema(this.schema)) {
            log.warn("No schema is available for table " + this.tableName);
        }
        return schema;
    }

    @Override
    public void insert(final DataFrame data, boolean overwrite) {
        AnalyticsDataService dataService = ServiceHolder.getAnalyticsDataService();
        try {
            AnalyticsSchema tempSchema = dataService.getTableSchema(this.tenantId, this.tableName);
            if (isEmptyAnalyticsSchema(tempSchema)) {
                throw new RuntimeException("Unable to insert data to the table as the AnalyticsSchema is unavailable" +
                    " for " + this.tableName);
            }
            if (overwrite && dataService.tableExists(this.tenantId, this.tableName)) {
                dataService.deleteTable(this.tenantId, this.tableName);
                if (!dataService.listRecordStoreNames().contains(this.recordStore)) {
                    throw new RuntimeException("Unknown data store name " + this.recordStore);
                }
                dataService.createTable(this.tenantId, this.recordStore, this.tableName);
                dataService.setTableSchema(this.tenantId, this.tableName, tempSchema);
            }
            writeDataFrameToDAL(data);
        } catch (AnalyticsException e) {
            String msg = "Error while inserting data into table " + this.tableName + " : " + e.getMessage();
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    private void writeDataFrameToDAL(DataFrame data) {
        for (int i = 0; i < data.rdd().partitions().length; i++) {
            data.sqlContext().sparkContext().runJob(data.rdd(), new AnalyticsWritingFunction(tenantId, tableName,
                data.schema()), CarbonScalaUtils.getNumberSeq(i, i + 1), false, ClassTag$.MODULE$.Unit());
        }
    }
}
