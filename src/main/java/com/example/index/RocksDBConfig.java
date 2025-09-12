package com.example.index;

import java.lang.management.ManagementFactory;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.CompressionType;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.Statistics;
import org.rocksdb.TickerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized configuration for RocksDB settings optimized for high-throughput index generation.
 * Provides consistent configuration across different index generator implementations.
 */
public class RocksDBConfig {
    private static final Logger logger = LoggerFactory.getLogger(RocksDBConfig.class);

    static {
        RocksDB.loadLibrary();
    }

    public static final long WRITE_BUFFER_SIZE = 256L * 1024 * 1024;
    public static final long BLOCK_CACHE_SIZE = (long) (Runtime.getRuntime().maxMemory() * 0.3);
    public static final int BLOOM_FILTER_BITS_PER_KEY = 10;
    public static final boolean BLOOM_FILTER_USE_BLOCK_BASED_BUILDER = false;

    private static final long MAX_MANIFEST_FILE_SIZE = 64L * 1024 * 1024; // 64MB
    private static final long MAX_TOTAL_WAL_SIZE = 512L * 1024 * 1024; // 512MB

    /**
     * Creates an optimized Options instance for RocksDB configuration.
     * Settings are tuned for high-throughput index generation with reduced write amplification.
     *
     * @return Configured Options instance
     */
    public static Options createOptimizedOptions() {
        Options options = new Options();
        options.setCreateIfMissing(true);
        options.setWriteBufferSize(WRITE_BUFFER_SIZE);

        // DB Open Optimizations from https://github.com/facebook/rocksdb/wiki/Speed-Up-DB-Open
        options.setSkipStatsUpdateOnDbOpen(true);
        options.setMaxFileOpeningThreads(Runtime.getRuntime().availableProcessors());
        options.setMaxManifestFileSize(MAX_MANIFEST_FILE_SIZE);
        options.setMaxTotalWalSize(MAX_TOTAL_WAL_SIZE);
        options.setSkipCheckingSstFileSizesOnDbOpen(true);

        // Block Cache configuration
        BlockBasedTableConfig tableOptions = new BlockBasedTableConfig();
        LRUCache cache = new LRUCache(BLOCK_CACHE_SIZE);
        tableOptions.setBlockCache(cache);

        // Bloom Filter Configuration (+ partitioned index/filters)
        org.rocksdb.Filter bloomFilter = new org.rocksdb.BloomFilter(BLOOM_FILTER_BITS_PER_KEY, BLOOM_FILTER_USE_BLOCK_BASED_BUILDER);
        tableOptions.setFilterPolicy(bloomFilter);
        tableOptions.setPartitionFilters(true);
        tableOptions.setIndexType(org.rocksdb.IndexType.kTwoLevelIndexSearch);
        tableOptions.setPinL0FilterAndIndexBlocksInCache(true);
        tableOptions.setCacheIndexAndFilterBlocks(true);
        tableOptions.setCacheIndexAndFilterBlocksWithHighPriority(true);
        tableOptions.setPinTopLevelIndexAndFilter(true);

        options.setTableFormatConfig(tableOptions);

        options.setCompressionType(CompressionType.SNAPPY_COMPRESSION);

        options.setStatistics(new Statistics());

        logger.info("RocksDB configuration:" +
                   "\n- Write buffer: {}MB" +
                   "\n- Block cache: {}GB" +
                   "\n- Compression: {}" +
                   "\n- Statistics enabled: {}" +
                   "\n- Skip stats update on DB open: {}" +
                   "\n- Max file opening threads: {}" +
                   "\n- Max manifest file size: {}MB" +
                   "\n- Max total WAL size: {}MB" +
                   "\n- Skip checking SST file sizes on DB open: {}",
                   WRITE_BUFFER_SIZE / (1024 * 1024),
                   BLOCK_CACHE_SIZE / (1024 * 1024 * 1024),
                   options.compressionType(),
                   options.statistics() != null,
                   true,
                   Runtime.getRuntime().availableProcessors(),
                   MAX_MANIFEST_FILE_SIZE / (1024 * 1024),
                   MAX_TOTAL_WAL_SIZE / (1024 * 1024),
                   true);

        return options;
    }

    /**
     * Collects and logs RocksDB statistics for monitoring and debugging.
     *
     * @param db The RocksDB database instance
     * @param statistics The Statistics object associated with the DB options
     */
    public static void collectRocksDBStats(RocksDB db, Statistics statistics) {
        if (statistics == null) {
            logger.warn("Statistics object is null, cannot collect RocksDB stats.");
            return;
        }
        logger.debug("RocksDB Statistics:");
        logger.debug("- Bytes Written: {}", statistics.getTickerCount(TickerType.BYTES_WRITTEN));
        logger.debug("- Bytes Read: {}", statistics.getTickerCount(TickerType.BYTES_READ));
        logger.debug("- Compaction Bytes Written: {}", statistics.getTickerCount(TickerType.COMPACT_WRITE_BYTES));
        logger.debug("- Compaction Bytes Read: {}", statistics.getTickerCount(TickerType.COMPACT_READ_BYTES));

        try {
            String numFilesL0 = db.getProperty("rocksdb.num-files-at-level0");
            if (numFilesL0 != null) {
                logger.debug("- Number of SST files at level 0: {}", numFilesL0);
            }
            String sstables = db.getProperty("rocksdb.sstables");
            if (sstables != null) {
                logger.debug("SSTable Information:\n{}", sstables);
            }
        } catch (org.rocksdb.RocksDBException e) {
            logger.error("Error getting RocksDB properties", e);
        }
    }
}