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

package com.dtstack.flink.sql.side.mongo;

import com.dtstack.flink.sql.side.BaseAllReqRow;
import com.dtstack.flink.sql.side.FieldInfo;
import com.dtstack.flink.sql.side.JoinInfo;
import com.dtstack.flink.sql.side.AbstractSideTableInfo;
import com.dtstack.flink.sql.side.mongo.table.MongoSideTableInfo;
import com.dtstack.flink.sql.side.mongo.utils.MongoUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.apache.calcite.sql.JoinType;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.table.runtime.types.CRow;
import org.apache.flink.table.typeutils.TimeIndicatorTypeInfo;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reason:
 * Date: 2018/11/6
 *
 * @author xuqianjin
 */
public class MongoAllReqRow extends BaseAllReqRow {

    private static final long serialVersionUID = -675332795591842778L;

    private static final Logger LOG = LoggerFactory.getLogger(MongoAllReqRow.class);

    private static final int CONN_RETRY_NUM = 3;

    private static final int FETCH_SIZE = 1000;

    private MongoClient mongoClient;

    private MongoDatabase db;

    private AtomicReference<Map<String, List<Map<String, Object>>>> cacheRef = new AtomicReference<>();

    public MongoAllReqRow(RowTypeInfo rowTypeInfo, JoinInfo joinInfo, List<FieldInfo> outFieldInfoList, AbstractSideTableInfo sideTableInfo) {
        super(new MongoAllSideInfo(rowTypeInfo, joinInfo, outFieldInfoList, sideTableInfo));
    }

    @Override
    public Row fillData(Row input, Object sideInput) {
        Map<String, Object> cacheInfo = (Map<String, Object>) sideInput;
        Row row = new Row(sideInfo.getOutFieldInfoList().size());
        for (Map.Entry<Integer, Integer> entry : sideInfo.getInFieldIndex().entrySet()) {
            Object obj = input.getField(entry.getValue());
            boolean isTimeIndicatorTypeInfo = TimeIndicatorTypeInfo.class.isAssignableFrom(sideInfo.getRowTypeInfo().getTypeAt(entry.getValue()).getClass());

            //Type information for indicating event or processing time. However, it behaves like a regular SQL timestamp but is serialized as Long.
            if (obj instanceof Timestamp && isTimeIndicatorTypeInfo) {
                obj = ((Timestamp) obj).getTime();
            }
            row.setField(entry.getKey(), obj);
        }

        for (Map.Entry<Integer, String> entry : sideInfo.getSideFieldNameIndex().entrySet()) {
            if (cacheInfo == null) {
                row.setField(entry.getKey(), null);
            } else {
                row.setField(entry.getKey(), cacheInfo.get(entry.getValue()));
            }
        }

        return row;
    }

    @Override
    protected void initCache() throws SQLException {
        Map<String, List<Map<String, Object>>> newCache = Maps.newConcurrentMap();
        cacheRef.set(newCache);
        loadData(newCache);
    }

    @Override
    protected void reloadCache() {
        //reload cacheRef and replace to old cacheRef
        Map<String, List<Map<String, Object>>> newCache = Maps.newConcurrentMap();
        try {
            loadData(newCache);
        } catch (SQLException e) {
            LOG.error("", e);
        }

        cacheRef.set(newCache);
        LOG.info("----- Mongo all cacheRef reload end:{}", Calendar.getInstance());
    }

    @Override
    public void flatMap(CRow input, Collector<CRow> out) throws Exception {
        List<Object> inputParams = Lists.newArrayList();
        for (Integer conValIndex : sideInfo.getEqualValIndex()) {
            Object equalObj = input.row().getField(conValIndex);
            if (equalObj == null) {
                if(sideInfo.getJoinType() == JoinType.LEFT){
                    Row data = fillData(input.row(), null);
                    out.collect(new CRow(data, input.change()));
                }
                return;
            }

            inputParams.add(equalObj);
        }

        String key = buildKey(inputParams);
        List<Map<String, Object>> cacheList = cacheRef.get().get(key);
        if (CollectionUtils.isEmpty(cacheList)) {
            if (sideInfo.getJoinType() == JoinType.LEFT) {
                Row row = fillData(input.row(), null);
                out.collect(new CRow(row, input.change()));
            } else {
                return;
            }

            return;
        }

        for (Map<String, Object> one : cacheList) {
            out.collect(new CRow(fillData(input.row(), one), input.change()));
        }
    }

    private String buildKey(List<Object> equalValList) {
        StringBuilder sb = new StringBuilder("");
        for (Object equalVal : equalValList) {
            sb.append(equalVal).append("_");
        }

        return sb.toString();
    }

    private String buildKey(Map<String, Object> val, List<String> equalFieldList) {
        StringBuilder sb = new StringBuilder("");
        for (String equalField : equalFieldList) {
            sb.append(val.get(equalField)).append("_");
        }

        return sb.toString();
    }

    private MongoCollection getConn(String host, String userName, String password, String database, String tableName) {

        MongoCollection dbCollection;
        mongoClient = new MongoClient(new MongoClientURI(getConnectionUrl(host, userName, password)));
        db = mongoClient.getDatabase(database);
        dbCollection = db.getCollection(tableName, Document.class);
        return dbCollection;

    }

    private void loadData(Map<String, List<Map<String, Object>>> tmpCache) throws SQLException {
        MongoSideTableInfo tableInfo = (MongoSideTableInfo) sideInfo.getSideTableInfo();
        MongoCollection dbCollection = null;

        try {
            for (int i = 0; i < CONN_RETRY_NUM; i++) {
                try {
                    dbCollection = getConn(tableInfo.getAddress(), tableInfo.getUserName(), tableInfo.getPassword(),
                            tableInfo.getDatabase(), tableInfo.getTableName());
                    break;
                } catch (Exception e) {
                    if (i == CONN_RETRY_NUM - 1) {
                        throw new RuntimeException("", e);
                    }

                    try {
                        String connInfo = "url:" + tableInfo.getAddress() + ";userName:" + tableInfo.getUserName() + ",pwd:" + tableInfo.getPassword();
                        LOG.warn("get conn fail, wait for 5 sec and try again, connInfo:" + connInfo);
                        Thread.sleep(LOAD_DATA_ERROR_SLEEP_TIME);
                    } catch (InterruptedException e1) {
                        LOG.error("", e1);
                    }
                }
            }

            //load data from table
            String[] sideFieldNames = StringUtils.split(sideInfo.getSideSelectFields(), ",");
            BasicDBObject basicDBObject = new BasicDBObject();
            for (String selectField : sideFieldNames) {
                basicDBObject.append(selectField, 1);
            }
            BasicDBObject filterObject = new BasicDBObject();
            try {
                // 填充谓词
                sideInfo.getSideTableInfo().getPredicateInfoes().stream().map(info -> {
                    BasicDBObject filterCondition = MongoUtil.buildFilterObject(info);
                    if (null != filterCondition) {
                        filterObject.append(info.getFieldName(), filterCondition);
                    }
                    return info;
                }).count();
            } catch (Exception e) {
                LOG.info("add predicate infoes error ", e);
            }


            FindIterable<Document> findIterable = dbCollection.find(filterObject).projection(basicDBObject).limit(FETCH_SIZE);
            MongoCursor<Document> mongoCursor = findIterable.iterator();
            while (mongoCursor.hasNext()) {
                Document doc = mongoCursor.next();
                Map<String, Object> oneRow = Maps.newHashMap();
                for (String fieldName : sideFieldNames) {
                    oneRow.put(fieldName.trim(), doc.get(fieldName.trim()));
                }
                String cacheKey = buildKey(oneRow, sideInfo.getEqualFieldList());
                List<Map<String, Object>> list = tmpCache.computeIfAbsent(cacheKey, key -> Lists.newArrayList());
                list.add(oneRow);
            }
        } catch (Exception e) {
            LOG.error("", e);
        } finally {
            if (mongoClient != null) {
                mongoClient.close();
            }
        }
    }
    private String getConnectionUrl(String address, String userName, String password){
        if(address.startsWith("mongodb://") || address.startsWith("mongodb+srv://")){
            return  address;
        }
        if (StringUtils.isNotBlank(userName) && StringUtils.isNotBlank(password)) {
            return String.format("mongodb://%s:%s@%s", userName, password, address);
        }
        return String.format("mongodb://%s", address);
    }

}
