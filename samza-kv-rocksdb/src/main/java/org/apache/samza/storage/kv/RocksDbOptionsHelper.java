/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.storage.kv;

import java.io.File;
import org.apache.commons.lang3.StringUtils;
import org.apache.samza.config.Config;
import org.apache.samza.storage.StorageEngineFactory;
import org.apache.samza.storage.StorageManagerUtil;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.CompactionOptionsUniversal;
import org.rocksdb.CompactionStopStyle;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.WALRecoveryMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A helper class to help construct the <code>Options</code> for RocksDb.
 */
public class RocksDbOptionsHelper {
  private static final Logger log = LoggerFactory.getLogger(RocksDbOptionsHelper.class);

  // TODO HIGH pmaheshw Add these to RockdDBTableDescriptor
  public static final String ROCKSDB_WAL_ENABLED = "rocksdb.wal.enabled";
  private static final String ROCKSDB_COMPRESSION = "rocksdb.compression";
  private static final String ROCKSDB_BLOCK_SIZE_BYTES = "rocksdb.block.size.bytes";

  private static final String ROCKSDB_COMPACTION_NUM_LEVELS = "rocksdb.compaction.num.levels";
  private static final String ROCKSDB_COMPACTION_LEVEL0_FILE_NUM_COMPACTION_TRIGGER =
      "rocksdb.compaction.level0.file.num.compaction.trigger";
  private static final String ROCKSDB_COMPACTION_MAX_BACKGROUND_COMPACTIONS = "rocksdb.compaction.max.background.compactions";
  private static final String ROCKSDB_COMPACTION_TARGET_FILE_SIZE_BASE = "rocksdb.compaction.target.file.size.base";
  private static final String ROCKSDB_COMPACTION_TARGET_FILE_SIZE_MULTIPLIER = "rocksdb.compaction.target.file.size.multiplier";

  private static final String ROCKSDB_COMPACTION_STYLE = "rocksdb.compaction.style";
  private static final String ROCKSDB_COMPACTION_UNIVERSAL_MAX_SIZE_AMPLIFICATION_PERCENT =
      "rocksdb.compaction.universal.max.size.amplification.percent";
  private static final String ROCKSDB_COMPACTION_UNIVERSAL_SIZE_RATIO =
      "rocksdb.compaction.universal.size.ratio";
  private static final String ROCKSDB_COMPACTION_UNIVERSAL_MIN_MERGE_WIDTH =
      "rocksdb.compaction.universal.min.merge.width";
  private static final String ROCKSDB_COMPACTION_UNIVERSAL_MAX_MERGE_WIDTH =
      "rocksdb.compaction.universal.max.merge.width";
  private static final String ROCKSDB_COMPACTION_UNIVERSAL_COMPACTION_STOP_STYLE =
      "rocksdb.compaction.universal.compaction.stop.style";

  private static final String ROCKSDB_NUM_WRITE_BUFFERS = "rocksdb.num.write.buffers";
  private static final String ROCKSDB_MAX_LOG_FILE_SIZE_BYTES = "rocksdb.max.log.file.size.bytes";
  private static final String ROCKSDB_KEEP_LOG_FILE_NUM = "rocksdb.keep.log.file.num";
  private static final String ROCKSDB_DELETE_OBSOLETE_FILES_PERIOD_MICROS = "rocksdb.delete.obsolete.files.period.micros";
  private static final String ROCKSDB_MAX_MANIFEST_FILE_SIZE = "rocksdb.max.manifest.file.size";
  private static final String ROCKSDB_MAX_OPEN_FILES = "rocksdb.max.open.files";
  private static final String ROCKSDB_MAX_FILE_OPENING_THREADS = "rocksdb.max.file.opening.threads";

  private static final String ROCKSDB_MAX_BACKGROUND_JOBS = "rocksdb.max.background.jobs";

  /**
   * RocksDB default for {@link RocksDbOptionsHelper#ROCKSDB_COMPACTION_MAX_BACKGROUND_COMPACTIONS} is 1.
   */
  private static final int DEFAULT_ROCKSDB_COMPACTION_MAX_BACKGROUND_COMPACTIONS = 4;

  /**
   * RocksDB default for {@link RocksDbOptionsHelper#ROCKSDB_MAX_BACKGROUND_JOBS} is 2
   * Note: {@link RocksDbOptionsHelper#ROCKSDB_COMPACTION_MAX_BACKGROUND_COMPACTIONS} is deprecated and is decided by
   * ROCKSDB_MAX_BACKGROUND_JOBS now.
   */
  private static final int DEFAULT_ROCKSDB_MAX_BACKGROUND_JOBS = 4;

  public static Options options(Config storeConfig, int numTasksForContainer, long defaultMaxManifestFileSize,
      File storeDir, StorageEngineFactory.StoreMode storeMode) {
    Options options = new Options();

    if (storeConfig.getBoolean(ROCKSDB_WAL_ENABLED, false)) {
      options.setManualWalFlush(true); // store.flush() will flushWAL(sync = true) instead
      options.setWalRecoveryMode(WALRecoveryMode.AbsoluteConsistency);
    }

    Long writeBufSize = storeConfig.getLong("container.write.buffer.size.bytes", 32 * 1024 * 1024);
    // Cache size and write buffer size are specified on a per-container basis.
    options.setWriteBufferSize((int) (writeBufSize / numTasksForContainer));

    CompressionType compressionType = CompressionType.SNAPPY_COMPRESSION;
    String compressionInConfig = storeConfig.get(ROCKSDB_COMPRESSION, "snappy");
    switch (compressionInConfig) {
      case "snappy":
        compressionType = CompressionType.SNAPPY_COMPRESSION;
        break;
      case "bzip2":
        compressionType = CompressionType.BZLIB2_COMPRESSION;
        break;
      case "zlib":
        compressionType = CompressionType.ZLIB_COMPRESSION;
        break;
      case "lz4":
        compressionType = CompressionType.LZ4_COMPRESSION;
        break;
      case "lz4hc":
        compressionType = CompressionType.LZ4HC_COMPRESSION;
        break;
      case "none":
        compressionType = CompressionType.NO_COMPRESSION;
        break;
      default:
        log.warn("Unknown rocksdb.compression codec " + compressionInConfig +
            ", overwriting to " + compressionType.name());
    }
    options.setCompressionType(compressionType);

    long blockCacheSize = getBlockCacheSize(storeConfig, numTasksForContainer);
    int blockSize = storeConfig.getInt(ROCKSDB_BLOCK_SIZE_BYTES, 4096);
    BlockBasedTableConfig tableOptions = new BlockBasedTableConfig();
    tableOptions.setBlockCacheSize(blockCacheSize).setBlockSize(blockSize);
    options.setTableFormatConfig(tableOptions);

    setCompactionOptions(storeConfig, options);

    options.setMaxWriteBufferNumber(storeConfig.getInt(ROCKSDB_NUM_WRITE_BUFFERS, 3));
    options.setCreateIfMissing(true);
    options.setErrorIfExists(false);

    options.setMaxLogFileSize(storeConfig.getLong(ROCKSDB_MAX_LOG_FILE_SIZE_BYTES, 64 * 1024 * 1024L));
    options.setKeepLogFileNum(storeConfig.getLong(ROCKSDB_KEEP_LOG_FILE_NUM, 2));
    options.setDeleteObsoleteFilesPeriodMicros(storeConfig.getLong(ROCKSDB_DELETE_OBSOLETE_FILES_PERIOD_MICROS, 21600000000L));
    options.setMaxOpenFiles(storeConfig.getInt(ROCKSDB_MAX_OPEN_FILES, -1));
    options.setMaxFileOpeningThreads(storeConfig.getInt(ROCKSDB_MAX_FILE_OPENING_THREADS, 16));
    // The default for rocksdb is 1GB (1024*1024*1024 bytes)
    options.setMaxManifestFileSize(storeConfig.getLong(ROCKSDB_MAX_MANIFEST_FILE_SIZE, defaultMaxManifestFileSize));
    // use prepareForBulk load only when i. the store is being requested in BulkLoad mode
    // and ii. the storeDirectory does not exist (fresh restore), because bulk load does not work seamlessly with
    // existing stores : https://github.com/facebook/rocksdb/issues/2734
    StorageManagerUtil storageManagerUtil = new StorageManagerUtil();
    if (storeMode.equals(StorageEngineFactory.StoreMode.BulkLoad) && !storageManagerUtil.storeExists(storeDir)) {
      log.info("Using prepareForBulkLoad for restore to " + storeDir);
      options.prepareForBulkLoad();
    }

    return options;
  }

  private static void setCompactionOptions(Config storeConfig, Options options) {
    if (storeConfig.containsKey(ROCKSDB_COMPACTION_NUM_LEVELS)) {
      options.setNumLevels(storeConfig.getInt(ROCKSDB_COMPACTION_NUM_LEVELS));
    }

    if (storeConfig.containsKey(ROCKSDB_COMPACTION_LEVEL0_FILE_NUM_COMPACTION_TRIGGER)) {
      int level0FileNumCompactionTrigger = storeConfig.getInt(ROCKSDB_COMPACTION_LEVEL0_FILE_NUM_COMPACTION_TRIGGER);
      options.setLevel0FileNumCompactionTrigger(level0FileNumCompactionTrigger);
    }

    if (storeConfig.containsKey(ROCKSDB_COMPACTION_MAX_BACKGROUND_COMPACTIONS)) {
      options.setMaxBackgroundCompactions(
          storeConfig.getInt(ROCKSDB_COMPACTION_MAX_BACKGROUND_COMPACTIONS,
              DEFAULT_ROCKSDB_COMPACTION_MAX_BACKGROUND_COMPACTIONS));
    }

    if (storeConfig.containsKey(ROCKSDB_COMPACTION_TARGET_FILE_SIZE_BASE)) {
      options.setTargetFileSizeBase(storeConfig.getLong(ROCKSDB_COMPACTION_TARGET_FILE_SIZE_BASE));
    }

    if (storeConfig.containsKey(ROCKSDB_COMPACTION_TARGET_FILE_SIZE_MULTIPLIER)) {
      options.setTargetFileSizeMultiplier(storeConfig.getInt(ROCKSDB_COMPACTION_TARGET_FILE_SIZE_MULTIPLIER));
    }

    if (storeConfig.containsKey(ROCKSDB_MAX_BACKGROUND_JOBS)) {
      options.setMaxBackgroundJobs(storeConfig.getInt(ROCKSDB_MAX_BACKGROUND_JOBS, DEFAULT_ROCKSDB_MAX_BACKGROUND_JOBS));
    }

    CompactionStyle compactionStyle = CompactionStyle.UNIVERSAL;
    String compactionStyleInConfig = storeConfig.get(ROCKSDB_COMPACTION_STYLE, "universal");
    switch (compactionStyleInConfig) {
      case "universal":
        compactionStyle = CompactionStyle.UNIVERSAL;
        break;
      case "fifo":
        compactionStyle = CompactionStyle.FIFO;
        break;
      case "level":
        compactionStyle = CompactionStyle.LEVEL;
        break;
      default:
        log.warn("Unknown rocksdb.compaction.style " + compactionStyleInConfig +
            ", overwriting to " + compactionStyle.name());
    }
    options.setCompactionStyle(compactionStyle);

    // Universal compaction options
    if (compactionStyle.equals(CompactionStyle.UNIVERSAL)) {
      CompactionOptionsUniversal compactionOptions = new CompactionOptionsUniversal();

      if (storeConfig.containsKey(ROCKSDB_COMPACTION_UNIVERSAL_MAX_SIZE_AMPLIFICATION_PERCENT)) {
        int val = storeConfig.getInt(ROCKSDB_COMPACTION_UNIVERSAL_MAX_SIZE_AMPLIFICATION_PERCENT);
        compactionOptions.setMaxSizeAmplificationPercent(val);
      }

      if (storeConfig.containsKey(ROCKSDB_COMPACTION_UNIVERSAL_SIZE_RATIO)) {
        int val = storeConfig.getInt(ROCKSDB_COMPACTION_UNIVERSAL_SIZE_RATIO);
        compactionOptions.setSizeRatio(val);
      }

      if (storeConfig.containsKey(ROCKSDB_COMPACTION_UNIVERSAL_MIN_MERGE_WIDTH)) {
        int val = storeConfig.getInt(ROCKSDB_COMPACTION_UNIVERSAL_MIN_MERGE_WIDTH);
        compactionOptions.setMinMergeWidth(val);
      }

      if (storeConfig.containsKey(ROCKSDB_COMPACTION_UNIVERSAL_MAX_MERGE_WIDTH)) {
        int val = storeConfig.getInt(ROCKSDB_COMPACTION_UNIVERSAL_MAX_MERGE_WIDTH);
        compactionOptions.setMaxMergeWidth(val);
      }

      if (storeConfig.containsKey(ROCKSDB_COMPACTION_UNIVERSAL_COMPACTION_STOP_STYLE)) {
        String stopStyle = storeConfig.get(ROCKSDB_COMPACTION_UNIVERSAL_COMPACTION_STOP_STYLE);
        if (StringUtils.isNotBlank(stopStyle)) {
          compactionOptions.setStopStyle(CompactionStopStyle.valueOf(stopStyle));
        }
      }

      options.setCompactionOptionsUniversal(compactionOptions);
    }
  }

  public static Long getBlockCacheSize(Config storeConfig, int numTasksForContainer) {
    long cacheSize = storeConfig.getLong("container.cache.size.bytes", 100 * 1024 * 1024L);
    return cacheSize / numTasksForContainer;
  }
}
