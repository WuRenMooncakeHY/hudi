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

package org.apache.hudi.client;

import com.codahale.metrics.Timer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hudi.client.embebbed.BaseEmbeddedTimelineService;
import org.apache.hudi.client.embedded.SparkEmbeddedTimelineService;
import org.apache.hudi.common.HoodieEngineContext;
import org.apache.hudi.common.HoodieSparkEngineContext;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.HoodieTableVersion;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieCompactionConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieCommitException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.index.HoodieIndex;
import org.apache.hudi.index.HoodieSparkIndexFactory;
import org.apache.hudi.table.BaseHoodieTimelineArchiveLog;
import org.apache.hudi.table.BulkInsertPartitioner;
import org.apache.hudi.table.HoodieSparkTable;
import org.apache.hudi.table.HoodieSparkTimelineArchiveLog;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.SparkMarkerFiles;
import org.apache.hudi.table.action.HoodieWriteMetadata;
import org.apache.hudi.table.action.compact.SparkCompactHelpers;
import org.apache.hudi.table.upgrade.SparkUpgradeDowngrade;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;

import java.io.IOException;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

public class HoodieSparkWriteClient<T extends HoodieRecordPayload> extends AbstractHoodieWriteClient<T,
    JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> {

  private static final Logger LOG = LogManager.getLogger(HoodieSparkWriteClient.class);

  public HoodieSparkWriteClient(HoodieEngineContext context, HoodieWriteConfig clientConfig) {
    super(context, clientConfig);
  }

  public HoodieSparkWriteClient(HoodieEngineContext context, HoodieWriteConfig writeConfig, boolean rollbackPending) {
    super(context, writeConfig, rollbackPending);
  }

  public HoodieSparkWriteClient(HoodieEngineContext context, HoodieWriteConfig writeConfig, boolean rollbackPending, Option<BaseEmbeddedTimelineService> timelineService) {
    super(context, writeConfig, rollbackPending, timelineService);
  }

  /**
   * Register hudi classes for Kryo serialization.
   *
   * @param conf instance of SparkConf
   * @return SparkConf
   */
  public static SparkConf registerClasses(SparkConf conf) {
    conf.registerKryoClasses(new Class[]{HoodieWriteConfig.class, HoodieRecord.class, HoodieKey.class});
    return conf;
  }

  @Override
  protected HoodieIndex<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> createIndex(HoodieWriteConfig writeConfig) {
    return HoodieSparkIndexFactory.createIndex(config);
  }

  @Override
  public boolean commit(String instantTime, JavaRDD<WriteStatus> writeStatuses, Option<Map<String, String>> extraMetadata) {
    List<HoodieWriteStat> stats = writeStatuses.map(WriteStatus::getStat).collect();
    return commitStats(instantTime, stats, extraMetadata);
  }

  @Override
  protected HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> createTable(HoodieWriteConfig config, Configuration hadoopConf) {
    return HoodieSparkTable.create(config, context);
  }

  @Override
  public JavaRDD<HoodieRecord<T>> filterExists(JavaRDD<HoodieRecord<T>> hoodieRecords) {
    // Create a Hoodie table which encapsulated the commits and files visible
    HoodieTable table = HoodieSparkTable.create(config, context);
    Timer.Context indexTimer = metrics.getIndexCtx();
    JavaRDD<HoodieRecord<T>> recordsWithLocation = getIndex().tagLocation(hoodieRecords, context, table);
    metrics.updateIndexMetrics(LOOKUP_STR, metrics.getDurationInMs(indexTimer == null ? 0L : indexTimer.stop()));
    return recordsWithLocation.filter(v1 -> !v1.isCurrentLocationKnown());
  }

  /**
   * Main API to run bootstrap to hudi.
   */
  @Override
  public void bootstrap(Option<Map<String, String>> extraMetadata) {
    if (rollbackPending) {
      rollBackInflightBootstrap();
    }
    HoodieSparkTable table = (HoodieSparkTable) getTableAndInitCtx(WriteOperationType.UPSERT, HoodieTimeline.METADATA_BOOTSTRAP_INSTANT_TS);
    table.bootstrap(context, extraMetadata);
  }

  @Override
  public JavaRDD<WriteStatus> upsert(JavaRDD<HoodieRecord<T>> records, String instantTime) {
    HoodieSparkTable table = (HoodieSparkTable) getTableAndInitCtx(WriteOperationType.UPSERT, instantTime);
    table.validateUpsertSchema();
    setOperationType(WriteOperationType.UPSERT);
    startAsyncCleaningIfEnabled(this, instantTime);
    HoodieWriteMetadata<JavaRDD<WriteStatus>> result = table.upsert(context, instantTime, records);
    if (result.getIndexLookupDuration().isPresent()) {
      metrics.updateIndexMetrics(LOOKUP_STR, result.getIndexLookupDuration().get().toMillis());
    }
    return postWrite(result, instantTime, table);
  }

  @Override
  public JavaRDD<WriteStatus> upsertPreppedRecords(JavaRDD<HoodieRecord<T>> preppedRecords, String instantTime) {
    HoodieSparkTable table = (HoodieSparkTable) getTableAndInitCtx(WriteOperationType.UPSERT_PREPPED, instantTime);
    table.validateUpsertSchema();
    setOperationType(WriteOperationType.UPSERT_PREPPED);
    startAsyncCleaningIfEnabled(this, instantTime);
    HoodieWriteMetadata result = table.upsertPrepped(context,instantTime, preppedRecords);
    return postWrite(result, instantTime, table);
  }

  @Override
  public JavaRDD<WriteStatus> insert(JavaRDD<HoodieRecord<T>> records, String instantTime) {
    HoodieSparkTable table = (HoodieSparkTable) getTableAndInitCtx(WriteOperationType.INSERT, instantTime);
    table.validateInsertSchema();
    setOperationType(WriteOperationType.INSERT);
    startAsyncCleaningIfEnabled(this, instantTime);
    HoodieWriteMetadata result = table.insert(context,instantTime, records);
    return postWrite(result, instantTime, table);
  }

  @Override
  public JavaRDD<WriteStatus> insertPreppedRecords(JavaRDD<HoodieRecord<T>> preppedRecords, String instantTime) {
    HoodieSparkTable table = (HoodieSparkTable) getTableAndInitCtx(WriteOperationType.INSERT_PREPPED, instantTime);
    table.validateInsertSchema();
    setOperationType(WriteOperationType.INSERT_PREPPED);
    startAsyncCleaningIfEnabled(this, instantTime);
    HoodieWriteMetadata result = table.insertPrepped(context,instantTime, preppedRecords);
    return postWrite(result, instantTime, table);
  }

  @Override
  public JavaRDD<WriteStatus> bulkInsert(JavaRDD<HoodieRecord<T>> records, String instantTime) {
    return bulkInsert(records, instantTime, Option.empty());
  }

  @Override
  public JavaRDD<WriteStatus> bulkInsert(JavaRDD<HoodieRecord<T>> records, String instantTime, Option<BulkInsertPartitioner<JavaRDD<HoodieRecord<T>>>> userDefinedBulkInsertPartitioner) {
    HoodieSparkTable table = (HoodieSparkTable) getTableAndInitCtx(WriteOperationType.BULK_INSERT, instantTime);
    table.validateInsertSchema();
    setOperationType(WriteOperationType.BULK_INSERT);
    startAsyncCleaningIfEnabled(this, instantTime);
    HoodieWriteMetadata result = table.bulkInsert(context,instantTime, records, userDefinedBulkInsertPartitioner);
    return postWrite(result, instantTime, table);
  }

  @Override
  public JavaRDD<WriteStatus> bulkInsertPreppedRecords(JavaRDD<HoodieRecord<T>> preppedRecords, String instantTime, Option<BulkInsertPartitioner<JavaRDD<HoodieRecord<T>>>> bulkInsertPartitioner) {
    HoodieSparkTable table = (HoodieSparkTable) getTableAndInitCtx(WriteOperationType.BULK_INSERT_PREPPED, instantTime);
    table.validateInsertSchema();
    setOperationType(WriteOperationType.BULK_INSERT_PREPPED);
    startAsyncCleaningIfEnabled(this, instantTime);
    HoodieWriteMetadata result = table.bulkInsertPrepped(context,instantTime, preppedRecords, bulkInsertPartitioner);
    return postWrite(result, instantTime, table);
  }

  @Override
  public JavaRDD<WriteStatus> delete(JavaRDD<HoodieKey> keys, String instantTime) {
    HoodieSparkTable table = (HoodieSparkTable) getTableAndInitCtx(WriteOperationType.DELETE, instantTime);
    setOperationType(WriteOperationType.DELETE);
    HoodieWriteMetadata result = table.delete(context,instantTime, keys);
    return postWrite(result, instantTime, table);
  }

  @Override
  protected JavaRDD<WriteStatus> postWrite(HoodieWriteMetadata<JavaRDD<WriteStatus>> result,
                                           String instantTime,
                                           HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> hoodieTable) {
    if (result.getIndexLookupDuration().isPresent()) {
      metrics.updateIndexMetrics(getOperationType().name(), result.getIndexUpdateDuration().get().toMillis());
    }
    if (result.isCommitted()) {
      // Perform post commit operations.
      if (result.getFinalizeDuration().isPresent()) {
        metrics.updateFinalizeWriteMetrics(result.getFinalizeDuration().get().toMillis(),
            result.getWriteStats().get().size());
      }

      postCommit(hoodieTable, result.getCommitMetadata().get(), instantTime, Option.empty());

      emitCommitMetrics(instantTime, result.getCommitMetadata().get(),
          hoodieTable.getMetaClient().getCommitActionType());
    }
    return result.getWriteStatuses();
  }

  @Override
  protected void postCommit(HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> table, HoodieCommitMetadata metadata, String instantTime, Option<Map<String, String>> extraMetadata) {
    try {

      // Delete the marker directory for the instant.
      new SparkMarkerFiles(table, instantTime).quietDeleteMarkerDir(context, config.getMarkersDeleteParallelism());

      // Do an inline compaction if enabled
      if (config.isInlineCompaction()) {
        runAnyPendingCompactions(table);
        metadata.addMetadata(HoodieCompactionConfig.INLINE_COMPACT_PROP, "true");
        inlineCompact(extraMetadata);
      } else {
        metadata.addMetadata(HoodieCompactionConfig.INLINE_COMPACT_PROP, "false");
      }
      // We cannot have unbounded commit files. Archive commits if we have to archive
      BaseHoodieTimelineArchiveLog archiveLog = new HoodieSparkTimelineArchiveLog(config, context);
      archiveLog.archiveIfRequired(context);
      autoCleanOnCommit(instantTime);
    } catch (IOException ioe) {
      throw new HoodieIOException(ioe.getMessage(), ioe);
    }
  }

  @Override
  public void commitCompaction(String compactionInstantTime, JavaRDD<WriteStatus> writeStatuses, Option<Map<String, String>> extraMetadata) throws IOException {
    HoodieSparkTable table = HoodieSparkTable.create(config, context);
    HoodieCommitMetadata metadata = SparkCompactHelpers.newInstance().createCompactionMetadata(
        table, compactionInstantTime, writeStatuses, config.getSchema());
    extraMetadata.ifPresent(m -> m.forEach(metadata::addMetadata));
    completeCompaction(metadata, writeStatuses, table, compactionInstantTime);
  }

  @Override
  protected void completeCompaction(HoodieCommitMetadata metadata, JavaRDD<WriteStatus> writeStatuses, HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> table, String compactionCommitTime) {
    List<HoodieWriteStat> writeStats = writeStatuses.map(WriteStatus::getStat).collect();
    finalizeWrite(table, compactionCommitTime, writeStats);
    LOG.info("Committing Compaction " + compactionCommitTime + ". Finished with result " + metadata);
    SparkCompactHelpers.newInstance().completeInflightCompaction(table, compactionCommitTime, metadata);

    if (compactionTimer != null) {
      long durationInMs = metrics.getDurationInMs(compactionTimer.stop());
      try {
        metrics.updateCommitMetrics(HoodieActiveTimeline.COMMIT_FORMATTER.parse(compactionCommitTime).getTime(),
            durationInMs, metadata, HoodieActiveTimeline.COMPACTION_ACTION);
      } catch (ParseException e) {
        throw new HoodieCommitException("Commit time is not of valid format. Failed to commit compaction "
            + config.getBasePath() + " at time " + compactionCommitTime, e);
      }
    }
    LOG.info("Compacted successfully on commit " + compactionCommitTime);
  }

  @Override
  protected JavaRDD<WriteStatus> compact(String compactionInstantTime, boolean shouldComplete) {
    HoodieSparkTable table = HoodieSparkTable.create(config, context);
    HoodieTimeline pendingCompactionTimeline = table.getActiveTimeline().filterPendingCompactionTimeline();
    HoodieInstant inflightInstant = HoodieTimeline.getCompactionInflightInstant(compactionInstantTime);
    if (pendingCompactionTimeline.containsInstant(inflightInstant)) {
      rollbackInflightCompaction(inflightInstant, table);
      table.getMetaClient().reloadActiveTimeline();
    }
    compactionTimer = metrics.getCompactionCtx();
    HoodieWriteMetadata compactionMetadata = table.compact(context, compactionInstantTime);
    JavaRDD<WriteStatus> statuses = (JavaRDD<WriteStatus>) compactionMetadata.getWriteStatuses();
    if (shouldComplete && compactionMetadata.getCommitMetadata().isPresent()) {
      completeCompaction((HoodieCommitMetadata) compactionMetadata.getCommitMetadata().get(), statuses, table, compactionInstantTime);
    }
    return statuses;
  }

  @Override
  protected HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> getTableAndInitCtx(WriteOperationType operationType, String instantTime) {
    HoodieTableMetaClient metaClient = createMetaClient(true);
    new SparkUpgradeDowngrade(metaClient, config, context).run(metaClient, HoodieTableVersion.current(), config, context, instantTime);
    return getTableAndInitCtx(metaClient, operationType);
  }

  private HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> getTableAndInitCtx(HoodieTableMetaClient metaClient, WriteOperationType operationType) {
    if (operationType == WriteOperationType.DELETE) {
      setWriteSchemaForDeletes(metaClient);
    }
    // Create a Hoodie table which encapsulated the commits and files visible
    HoodieSparkTable table = HoodieSparkTable.create(config, (HoodieSparkEngineContext) context, metaClient);
    if (table.getMetaClient().getCommitActionType().equals(HoodieTimeline.COMMIT_ACTION)) {
      writeContext = metrics.getCommitCtx();
    } else {
      writeContext = metrics.getDeltaCommitCtx();
    }
    return table;
  }

  @Override
  public AsyncCleanerService startAsyncCleaningIfEnabled(AbstractHoodieWriteClient<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> writeClient, String instantTime) {
    AsyncCleanerService asyncCleanerService = null;
    if (writeClient.getConfig().isAutoClean() && writeClient.getConfig().isAsyncClean()) {
      LOG.info("Auto cleaning is enabled. Running cleaner async to write operation");
      asyncCleanerService = new AsyncCleanerService(writeClient, instantTime);
      asyncCleanerService.start(null);
    } else {
      LOG.info("Auto cleaning is not enabled. Not running cleaner now");
    }
    return asyncCleanerService;
  }

  @Override
  public void startEmbeddedServerView() {
    synchronized (this) {
      if (config.isEmbeddedTimelineServerEnabled()) {
        if (!timelineServer.isPresent()) {
          // Run Embedded Timeline Server
          LOG.info("Starting Timeline service !!");
          timelineServer = Option.of(new SparkEmbeddedTimelineService(context,
              config.getClientSpecifiedViewStorageConfig()));
          try {
            timelineServer.get().startServer();
            // Allow executor to find this newly instantiated timeline service
            config.setViewStorageConfig(timelineServer.get().getRemoteFileSystemViewConfig());
          } catch (IOException e) {
            LOG.warn("Unable to start timeline service. Proceeding as if embedded server is disabled", e);
            stopEmbeddedServerView(false);
          }
        } else {
          LOG.info("Timeline Server already running. Not restarting the service");
        }
      } else {
        LOG.info("Embedded Timeline Server is disabled. Not starting timeline service");
      }
    }
  }
}
