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

package org.apache.hudi.io.storage;

import org.apache.hudi.avro.HoodieAvroWriteSupport;
import org.apache.hudi.client.TaskContextSupplier;
import org.apache.hudi.common.bloom.BloomFilter;
import org.apache.hudi.common.bloom.BloomFilterFactory;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.table.HoodieTable;

import org.apache.avro.Schema;
import org.apache.avro.generic.IndexedRecord;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroSchemaConverter;

import java.io.IOException;

import static org.apache.hudi.common.model.HoodieFileFormat.PARQUET;
import static org.apache.hudi.common.model.HoodieFileFormat.HFILE;

public class HoodieFileWriterFactory {

  public static <T extends HoodieRecordPayload, R extends IndexedRecord, I, K, O, P> HoodieFileWriter<R> getFileWriter(
      String instantTime, Path path, HoodieTable<T, I, K, O, P> hoodieTable, HoodieWriteConfig config, Schema schema,
      TaskContextSupplier taskContextSupplier) throws IOException {
    final String extension = FSUtils.getFileExtension(path.getName());
    if (PARQUET.getFileExtension().equals(extension)) {
      return newParquetFileWriter(instantTime, path, config, schema, hoodieTable, taskContextSupplier);
    }
    if (HFILE.getFileExtension().equals(extension)) {
      return newHFileFileWriter(instantTime, path, config, schema, hoodieTable, taskContextSupplier);
    }
    throw new UnsupportedOperationException(extension + " format not supported yet.");
  }

  private static <T extends HoodieRecordPayload, R extends IndexedRecord> HoodieFileWriter<R> newParquetFileWriter(
      String instantTime, Path path, HoodieWriteConfig config, Schema schema, HoodieTable hoodieTable,
      TaskContextSupplier taskContextSupplier) throws IOException {
    BloomFilter filter = createBloomFilter(config);
    HoodieAvroWriteSupport writeSupport =
        new HoodieAvroWriteSupport(new AvroSchemaConverter().convert(schema), schema, filter);

    HoodieAvroParquetConfig parquetConfig = new HoodieAvroParquetConfig(writeSupport, config.getParquetCompressionCodec(),
        config.getParquetBlockSize(), config.getParquetPageSize(), config.getParquetMaxFileSize(),
        hoodieTable.getHadoopConf(), config.getParquetCompressionRatio());

    return new HoodieParquetWriter<>(instantTime, path, parquetConfig, schema, taskContextSupplier);
  }

  private static <T extends HoodieRecordPayload, R extends IndexedRecord> HoodieFileWriter<R> newHFileFileWriter(
      String instantTime, Path path, HoodieWriteConfig config, Schema schema, HoodieTable hoodieTable,
      TaskContextSupplier taskContextSupplier) throws IOException {

    BloomFilter filter = createBloomFilter(config);
    HoodieHFileConfig hfileConfig = new HoodieHFileConfig(hoodieTable.getHadoopConf(),
        config.getHFileCompressionAlgorithm(), config.getHFileBlockSize(), config.getHFileMaxFileSize(), filter);

    return new HoodieHFileWriter<>(instantTime, path, hfileConfig, schema, taskContextSupplier);
  }

  private static BloomFilter createBloomFilter(HoodieWriteConfig config) {
    return BloomFilterFactory.createBloomFilter(config.getBloomFilterNumEntries(), config.getBloomFilterFPP(),
            config.getDynamicBloomFilterMaxNumEntries(),
            config.getBloomFilterType());
  }
}
