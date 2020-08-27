/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.action.rollback;

import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.HoodieRollbackStat;
import org.apache.hudi.common.HoodieSparkEngineContext;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.SparkMarkerFiles;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;

import java.util.List;

public class SparkCopyOnWriteRollbackActionExecutor<T extends HoodieRecordPayload> extends BaseCopyOnWriteRollbackActionExecutor<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> {
  public SparkCopyOnWriteRollbackActionExecutor(HoodieSparkEngineContext context,
                                                HoodieWriteConfig config,
                                                HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> table,
                                                String instantTime,
                                                HoodieInstant commitInstant,
                                                boolean deleteInstants) {
    super(context, config, table, instantTime, commitInstant, deleteInstants);
  }

  public SparkCopyOnWriteRollbackActionExecutor(HoodieSparkEngineContext context,
                                                HoodieWriteConfig config,
                                                HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> table,
                                                String instantTime,
                                                HoodieInstant commitInstant,
                                                boolean deleteInstants,
                                                boolean skipTimelinePublish,
                                                boolean useMarkerBasedStrategy) {
    super(context, config, table, instantTime, commitInstant, deleteInstants, skipTimelinePublish, useMarkerBasedStrategy);
  }

  @Override
  protected RollbackStrategy getRollbackStrategy() {
    if (useMarkerBasedStrategy) {
      return new SparkMarkerBasedRollbackStrategy(table, context, config, instantTime);
    } else {
      return this::executeRollbackUsingFileListing;
    }
  }

  @Override
  protected List<HoodieRollbackStat> executeRollbackUsingFileListing(HoodieInstant instantToRollback) {
    List<ListingBasedRollbackRequest> rollbackRequests = RollbackUtils.generateRollbackRequestsByListingCOW(table.getMetaClient().getFs(), table.getMetaClient().getBasePath(),
        config.shouldAssumeDatePartitioning());
    return new ListingBasedRollbackHelper(table.getMetaClient(), config).performRollback(HoodieSparkEngineContext.getSparkContext(context), instantToRollback, rollbackRequests);
  }

  @Override
  protected void quietDeleteMarkerDir(HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> table) {
    new SparkMarkerFiles(table, instantToRollback.getTimestamp()).quietDeleteMarkerDir(context, config.getMarkersDeleteParallelism());
  }

}
