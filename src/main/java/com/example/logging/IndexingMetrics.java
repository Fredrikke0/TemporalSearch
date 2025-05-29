package com.example.logging;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;


/**
 * Unified metrics tracking for the indexing process.
 * Handles both high-level indexing metrics and batch-level processing details.
 * Uses sampling to reduce log volume while maintaining visibility.
 */
public class IndexingMetrics {
    private static final Logger logger = LoggerFactory.getLogger(IndexingMetrics.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    // Sampling configuration
    private final LogSampler batchMetricsSampler;

    // Overall metrics
    private final long startTime;
    private final long initialHeapUsed;
    private final AtomicLong totalProcessingTimeNanos;
    private final AtomicInteger totalEntriesProcessed;
    private final AtomicInteger uniqueDocumentsProcessed;
    private final AtomicInteger totalBatchesProcessed;

    // Batch-level metrics
    private final AtomicInteger nullCount;
    private final AtomicInteger errorCount;
    private final AtomicLong maxProcessingTimeNanos;
    private final AtomicLong minProcessingTimeNanos;
    private final List<Long> recentProcessingTimes;

    // Stage-level metrics (new)
    private final AtomicLong totalFetchTimeNanos;
    private final AtomicLong totalProcessTimeNanos;
    private final AtomicLong totalWriteTempTimeNanos;
    private final AtomicLong totalOutputItems;

    private final AtomicLong maxFetchTimeNanos;
    private final AtomicLong minFetchTimeNanos;
    private final AtomicLong maxProcessTimeNanos;
    private final AtomicLong minProcessTimeNanos;
    private final AtomicLong maxWriteTempTimeNanos;
    private final AtomicLong minWriteTempTimeNanos;

    // For storing arbitrary named timings
    private final Map<String, Long> customTimingsMs = new ConcurrentHashMap<>();

    // Current batch tracking
    private long currentBatchStartTime;
    private int currentBatchSize;
    private String currentIndexType;

    public IndexingMetrics() {
        this.startTime = System.nanoTime();
        this.initialHeapUsed = MEMORY_BEAN.getHeapMemoryUsage().getUsed();

        // Initialize samplers
        this.batchMetricsSampler = new LogSampler(0.05);    // 5% sampling for batch metrics

        // Initialize counters
        this.totalProcessingTimeNanos = new AtomicLong(0);
        this.totalEntriesProcessed = new AtomicInteger(0);
        this.uniqueDocumentsProcessed = new AtomicInteger(0);
        this.totalBatchesProcessed = new AtomicInteger(0);
        this.nullCount = new AtomicInteger(0);
        this.errorCount = new AtomicInteger(0);
        this.maxProcessingTimeNanos = new AtomicLong(0);
        this.minProcessingTimeNanos = new AtomicLong(Long.MAX_VALUE);
        this.recentProcessingTimes = new ArrayList<>();

        // Initialize new stage-level metrics
        this.totalFetchTimeNanos = new AtomicLong(0);
        this.totalProcessTimeNanos = new AtomicLong(0);
        this.totalWriteTempTimeNanos = new AtomicLong(0);
        this.totalOutputItems = new AtomicLong(0);

        this.maxFetchTimeNanos = new AtomicLong(0);
        this.minFetchTimeNanos = new AtomicLong(Long.MAX_VALUE);
        this.maxProcessTimeNanos = new AtomicLong(0);
        this.minProcessTimeNanos = new AtomicLong(Long.MAX_VALUE);
        this.maxWriteTempTimeNanos = new AtomicLong(0);
        this.minWriteTempTimeNanos = new AtomicLong(Long.MAX_VALUE);
    }

    /**
     * Stores a custom named timing value.
     * @param key The name of the timing metric (e.g., "stitch_date_total_ms")
     * @param durationMs The duration in milliseconds.
     */
    public void addTiming(String key, long durationMs) {
        customTimingsMs.put(key, durationMs);
    }

    /**
     * Start tracking a new batch of documents.
     * @param batchSize The number of documents in this batch
     * @param indexType The type of index being processed (e.g., "unigram", "bigram")
     */
    public void startBatch(int batchSize, String indexType) {
        this.currentBatchStartTime = System.nanoTime();
        this.currentBatchSize = batchSize;
        this.currentIndexType = indexType;
    }

    /**
     * Update the current batch size, used when a batch is truncated due to limits.
     * @param newBatchSize The new size of the current batch
     */
    public void updateCurrentBatchSize(int newBatchSize) {
        this.currentBatchSize = newBatchSize;
    }

    /**
     * Record successful processing of the current batch.
     */
    public void recordBatchSuccess() {
        long duration = System.nanoTime() - currentBatchStartTime;
        recordBatchCompletion(duration, true);
    }

    /**
     * Record successful processing of the current batch with unique document count.
     * @param uniqueDocsInBatch The number of unique documents in this batch
     */
    public void recordBatchSuccess(int uniqueDocsInBatch) {
        long duration = System.nanoTime() - currentBatchStartTime;
        uniqueDocumentsProcessed.addAndGet(uniqueDocsInBatch);
        recordBatchCompletion(duration, true);
    }

    /**
     * Record a failed batch processing attempt.
     */
    public void recordBatchFailure() {
        long duration = System.nanoTime() - currentBatchStartTime;
        recordBatchCompletion(duration, false);
        errorCount.incrementAndGet();
    }

    /**
     * Record a null or empty batch result.
     */
    public void recordNullBatch() {
        nullCount.incrementAndGet();
    }

    /**
     * Records the durations of individual stages within a successful batch processing.
     * This method is called INSTEAD of recordBatchSuccess() when stage timings are available.
     *
     * @param fetchNanos Duration of the fetch stage in nanoseconds.
     * @param processNanos Duration of the process stage in nanoseconds.
     * @param writeTempNanos Duration of the write temporary file stage in nanoseconds.
     * @param itemsInBatchOutput Number of output items generated by processBatch.
     * @param rawEntriesInBatch Number of raw entries processed in this batch (input to fetchBatch).
     */
    public synchronized void recordBatchStageDurations(long fetchNanos, long processNanos, long writeTempNanos, int itemsInBatchOutput, int rawEntriesInBatch) {
        long batchDurationNanos = fetchNanos + processNanos + writeTempNanos;

        // Update overall batch timing stats (using the sum of stages)
        totalProcessingTimeNanos.addAndGet(batchDurationNanos);
        updateMinMaxForStat(batchDurationNanos, minProcessingTimeNanos, maxProcessingTimeNanos);
        recentProcessingTimes.add(batchDurationNanos);
        if (recentProcessingTimes.size() > 100) { // Keep last 100 batches
            recentProcessingTimes.remove(0);
        }

        // Update stage-specific timing stats
        totalFetchTimeNanos.addAndGet(fetchNanos);
        totalProcessTimeNanos.addAndGet(processNanos);
        totalWriteTempTimeNanos.addAndGet(writeTempNanos);

        updateMinMaxForStat(fetchNanos, minFetchTimeNanos, maxFetchTimeNanos);
        updateMinMaxForStat(processNanos, minProcessTimeNanos, maxProcessTimeNanos);
        updateMinMaxForStat(writeTempNanos, minWriteTempTimeNanos, maxWriteTempTimeNanos);

        // Update document/item counts
        totalEntriesProcessed.addAndGet(rawEntriesInBatch); // Raw entries from input
        totalOutputItems.addAndGet(itemsInBatchOutput);     // Output items from processBatch
        // uniqueDocumentsProcessed is typically updated based on input, might need specific handling if tied to output items

        totalBatchesProcessed.incrementAndGet();

        // Log batch metrics if sampled
        if (batchMetricsSampler.shouldLog()) {
            logBatchMetrics(batchDurationNanos, true, fetchNanos, processNanos, writeTempNanos, itemsInBatchOutput, rawEntriesInBatch);
        }
    }

    private synchronized void recordBatchCompletion(long durationNanos, boolean success) {
        // This method is now primarily for simple success/failure without detailed stage breakdown
        // or for cases where stage timings are not available.
        // For detailed stage timings, recordBatchStageDurations should be used.

        totalProcessingTimeNanos.addAndGet(durationNanos);
        updateMinMaxForStat(durationNanos, minProcessingTimeNanos, maxProcessingTimeNanos);

        if (success) {
            // If called directly (e.g. from old code path), we don't have itemsInBatchOutput or rawEntriesInBatch here
            // totalEntriesProcessed might be incremented by currentBatchSize if this path is taken.
            // Consider this a less detailed success recording.
             totalEntriesProcessed.addAndGet(currentBatchSize); // Fallback if not using stage durations
        }
        totalBatchesProcessed.incrementAndGet();

        recentProcessingTimes.add(durationNanos);
        if (recentProcessingTimes.size() > 100) {
            recentProcessingTimes.remove(0);
        }

        if (batchMetricsSampler.shouldLog() || !success) {
            // Log with -1 or null for stage-specific timings if not available
            logBatchMetrics(durationNanos, success, -1, -1, -1, -1, currentBatchSize);
        }
    }

    private void updateMinMaxForStat(long timeNanos, AtomicLong minStat, AtomicLong maxStat) {
        while (true) {
            long currentMax = maxStat.get();
            if (timeNanos <= currentMax || maxStat.compareAndSet(currentMax, timeNanos)) {
                break;
            }
        }
        while (true) {
            long currentMin = minStat.get();
            if (timeNanos >= currentMin || minStat.compareAndSet(currentMin, timeNanos)) {
                break;
            }
        }
    }

    // Overload logBatchMetrics to include stage details
    private void logBatchMetrics(long durationNanos, boolean success, long fetchNanos, long processNanos, long writeTempNanos, int itemsInOutput, int rawItemsInBatch) {
        try {
            double avgProcessingTime = recentProcessingTimes.stream()
                .mapToLong(Long::valueOf)
                .average()
                .orElse(0.0);

            ObjectNode json = MAPPER.createObjectNode()
                .put("event", "batch_complete")
                .put("index_type", currentIndexType)
                .put("success", success)
                .put("batch_input_size", rawItemsInBatch) // Renamed from batch_size for clarity
                .put("batch_output_items", itemsInOutput)
                .put("batch_duration_ms", durationNanos / 1_000_000.0)
                .put("avg_overall_batch_duration_ms", avgProcessingTime / 1_000_000.0); // Avg of sum of stages

            if (fetchNanos >= 0) { // Only add if stage timings are valid
                json.put("fetch_stage_ms", fetchNanos / 1_000_000.0);
                json.put("process_stage_ms", processNanos / 1_000_000.0);
                json.put("write_temp_stage_ms", writeTempNanos / 1_000_000.0);
            }

            json.put("total_raw_entries_processed", totalEntriesProcessed.get()) // Total raw entries from input
                .put("total_output_items_generated", totalOutputItems.get()) // Total items for index
                .put("unique_documents", uniqueDocumentsProcessed.get()) // This count needs careful updating logic
                .put("total_batches", totalBatchesProcessed.get())
                .put("errors", errorCount.get())
                .put("nulls", nullCount.get())
                .put("heap_used_mb", MEMORY_BEAN.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0));

            if (success) {
                logger.info(json.toString());
            } else {
                logger.warn(json.toString());
            }
        } catch (Exception e) {
            logger.warn("Failed to log batch metrics", e);
        }
    }

    /**
     * Log overall indexing metrics. Called periodically or at completion.
     */
    public void logIndexingMetrics() {
        try {
            long elapsedNanos = System.nanoTime() - startTime;
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            long totalBatches = totalBatchesProcessed.get();
            long currentTotalOutputItems = totalOutputItems.get();
            long currentTotalRawEntries = totalEntriesProcessed.get();

            ObjectNode json = MAPPER.createObjectNode()
                .put("event", "indexing_summary") // Changed event name for clarity
                .put("index_type", currentIndexType != null ? currentIndexType : "overall") // Add index type if available
                .put("total_output_items_generated", currentTotalOutputItems)
                .put("total_raw_entries_processed", currentTotalRawEntries)
                .put("unique_documents_processed", uniqueDocumentsProcessed.get())
                .put("total_batches_processed", totalBatches)
                .put("total_errors", errorCount.get())
                .put("total_nulls", nullCount.get())
                .put("elapsed_seconds", elapsedSeconds);

            if (elapsedSeconds > 0) {
                json.put("output_items_per_second", currentTotalOutputItems / elapsedSeconds);
                json.put("raw_entries_per_second", currentTotalRawEntries / elapsedSeconds);
            } else {
                json.put("output_items_per_second", 0);
                json.put("raw_entries_per_second", 0);
            }

            json.put("avg_batch_input_size", totalBatches > 0 ? (double) currentTotalRawEntries / totalBatches : 0.0)
                .put("avg_batch_output_items", totalBatches > 0 ? (double) currentTotalOutputItems / totalBatches : 0.0)
                .put("min_overall_batch_duration_ms", minProcessingTimeNanos.get() != Long.MAX_VALUE ? minProcessingTimeNanos.get() / 1_000_000.0 : 0)
                .put("max_overall_batch_duration_ms", maxProcessingTimeNanos.get() / 1_000_000.0);

            if (totalBatches > 0) { // Average stage timings
                json.put("avg_fetch_stage_ms", (totalFetchTimeNanos.get() / totalBatches) / 1_000_000.0);
                json.put("avg_process_stage_ms", (totalProcessTimeNanos.get() / totalBatches) / 1_000_000.0);
                json.put("avg_write_temp_stage_ms", (totalWriteTempTimeNanos.get() / totalBatches) / 1_000_000.0);

                json.put("min_fetch_stage_ms", minFetchTimeNanos.get() != Long.MAX_VALUE ? minFetchTimeNanos.get() / 1_000_000.0 : 0);
                json.put("max_fetch_stage_ms", maxFetchTimeNanos.get() / 1_000_000.0);
                json.put("min_process_stage_ms", minProcessTimeNanos.get() != Long.MAX_VALUE ? minProcessTimeNanos.get() / 1_000_000.0 : 0);
                json.put("max_process_stage_ms", maxProcessTimeNanos.get() / 1_000_000.0);
                json.put("min_write_temp_stage_ms", minWriteTempTimeNanos.get() != Long.MAX_VALUE ? minWriteTempTimeNanos.get() / 1_000_000.0 : 0);
                json.put("max_write_temp_stage_ms", maxWriteTempTimeNanos.get() / 1_000_000.0);
            }

            json.put("heap_used_mb", MEMORY_BEAN.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0))
                .put("heap_change_mb", (MEMORY_BEAN.getHeapMemoryUsage().getUsed() - initialHeapUsed) / (1024.0 * 1024.0));

            // Add custom timings
            if (!customTimingsMs.isEmpty()) {
                ObjectNode customTimingsNode = MAPPER.createObjectNode();
                for (Map.Entry<String, Long> entry : customTimingsMs.entrySet()) {
                    customTimingsNode.put(entry.getKey(), entry.getValue());
                }
                json.set("custom_timings_ms", customTimingsNode);
            }

            // Only log at INFO level when called at completion (i.e. when total_output_items > 0)
            if (currentTotalOutputItems > 0 || currentTotalRawEntries > 0) {
                logger.info(json.toString());
            } else {
                logger.debug(json.toString()); // Log as debug if nothing was processed
            }
        } catch (Exception e) {
            logger.warn("Failed to log indexing metrics", e);
        }
    }

    // Getters for testing and verification
    public int getTotalEntries() {
        return totalEntriesProcessed.get(); // This now refers to raw input entries
    }

    public int getUniqueDocuments() {
        return uniqueDocumentsProcessed.get();
    }

    public int getTotalBatches() {
        return totalBatchesProcessed.get();
    }

    public int getErrorCount() {
        return errorCount.get();
    }

    public int getNullCount() {
        return nullCount.get();
    }

    public long getTotalProcessingTimeNanos() {
        return totalProcessingTimeNanos.get();
    }

    public long getTotalOutputItems() { // New getter
        return totalOutputItems.get();
    }
}