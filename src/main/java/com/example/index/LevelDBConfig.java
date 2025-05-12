package com.example.index;

import org.iq80.leveldb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized configuration for LevelDB settings optimized for high-throughput index generation.
 * Provides consistent configuration across different index generator implementations.
 */
public class LevelDBConfig {
    private static final Logger logger = LoggerFactory.getLogger(LevelDBConfig.class);

    // Default values from optimization plan
    public static final int WRITE_BUFFER_SIZE = 256 * 1024 * 1024; // 256MB
    public static final int BLOCK_CACHE_SIZE = 1024 * 1024 * 1024; // 1GB
    public static final int BLOOM_FILTER_BITS = 10; // Bits per key for Bloom filter
    public static final int BATCH_SIZE = 1_000; // Reduced from 10_000 to prevent NegativeArraySizeException
    
    /**
     * Creates an optimized Options instance for LevelDB configuration.
     * Settings are tuned for high-throughput index generation with reduced write amplification.
     *
     * @return Configured Options instance
     */
    public static Options createOptimizedOptions() {
        Options options = new Options();
        options.createIfMissing(true);
        options.writeBufferSize(WRITE_BUFFER_SIZE);
        options.cacheSize(BLOCK_CACHE_SIZE);
        options.compressionType(CompressionType.SNAPPY);
        
        // Enhanced logging configuration for debugging
        logger.info("LevelDB configuration:" +
                   "\n- Write buffer: {}MB" +
                   "\n- Block cache: {}GB" +
                   "\n- Compression: {}",
                   WRITE_BUFFER_SIZE / (1024 * 1024),
                   BLOCK_CACHE_SIZE / (1024 * 1024 * 1024),
                   options.compressionType());
        
        return options;
    }

    /**
     * Collects and logs LevelDB statistics for monitoring and debugging.
     * Includes metrics like write amplification and batch write duration.
     *
     * @param db The LevelDB database instance
     */
    public static void collectLevelDbStats(DB db) {
        // Get LevelDB stats
        String stats = db.getProperty("leveldb.stats");
        if (stats != null) {
            logger.debug("LevelDB Stats:\n{}", stats);
        }

        // Get additional properties
        String sstables = db.getProperty("leveldb.sstables");
        if (sstables != null) {
            logger.debug("SSTable Information:\n{}", sstables);
        }

        String numFiles = db.getProperty("leveldb.num-files-at-level0");
        if (numFiles != null) {
            logger.debug("Number of files at level 0: {}", numFiles);
        }

        String memTableSize = db.getProperty("leveldb.approximate-memory-usage");
        if (memTableSize != null) {
            logger.debug("Approximate memory usage: {} bytes", memTableSize);
        }
    }
} 