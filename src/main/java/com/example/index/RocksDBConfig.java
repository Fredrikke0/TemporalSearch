package com.example.index;

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

    // Load RocksDB native library.
    static {
        RocksDB.loadLibrary();
    }

    // Default values from optimization plan
    public static final long WRITE_BUFFER_SIZE = 256L * 1024 * 1024; // 256MB
    public static final long BLOCK_CACHE_SIZE = 1024L * 1024 * 1024; // 1GB
    public static final int BLOOM_FILTER_BITS_PER_KEY = 10; // Bits per key for Bloom filter
    public static final boolean BLOOM_FILTER_USE_BLOCK_BASED_BUILDER = false; // Typical setting for BloomFilter
    public static final int BATCH_SIZE = 1_000; // Reduced from 10_000 to prevent NegativeArraySizeException

    /**
     * Creates an optimized Options instance for RocksDB configuration.
     * Settings are tuned for high-throughput index generation with reduced write amplification.
     *
     * @return Configured Options instance
     */
    public static Options createOptimizedOptions() {
        Options options = new Options();
        options.setCreateIfMissing(true);
        options.setWriteBufferSize(WRITE_BUFFER_SIZE); // long value

        // Block Cache configuration
        BlockBasedTableConfig tableOptions = new BlockBasedTableConfig();
        LRUCache cache = new LRUCache(BLOCK_CACHE_SIZE); // long value
        tableOptions.setBlockCache(cache);
        // It's important to set if the cache is shared or not.
        // If 'cache' is only used by this Options instance, then it's not shared.
        // If 'cache' is shared across multiple Options instances, then setShared(true) should be called on LRUCache.
        // For now, assuming it's not shared. The plan mentions "Options owns the cache".
        // tableOptions.setCacheIndexAndFilterBlocks(true); // Consider for better cache utilization
        // tableOptions.setPinL0FilterAndIndexBlocksInCache(true); // Consider for better cache utilization

        options.setTableFormatConfig(tableOptions);

        options.setCompressionType(CompressionType.SNAPPY_COMPRESSION); // SNAPPY_COMPRESSION for RocksDB

        // Statistics
        options.setStatistics(new Statistics()); // Enable statistics collection

        // Enhanced logging configuration for debugging
        logger.info("RocksDB configuration:" +
                   "\n- Write buffer: {}MB" +
                   "\n- Block cache: {}GB" +
                   "\n- Compression: {}" +
                   "\n- Statistics enabled: {}",
                   WRITE_BUFFER_SIZE / (1024 * 1024),
                   BLOCK_CACHE_SIZE / (1024 * 1024 * 1024),
                   options.compressionType(),
                   options.statistics() != null);

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
            // The following TickerTypes for block cache might not be available in all RocksDB versions or configurations.
            // Check RocksDB documentation for available TickerType enums for your version.
            // logger.debug("- Block Cache Capacity: {}", statistics.getTickerCount(TickerType.BLOCK_CACHE_CAPACITY));
            // logger.debug("- Block Cache Usage: {}", statistics.getTickerCount(TickerType.BLOCK_CACHE_USAGE));
        } catch (org.rocksdb.RocksDBException e) {
            logger.error("Error getting RocksDB properties", e);
        }
    }
}