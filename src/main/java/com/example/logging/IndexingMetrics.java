package com.example.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexingMetrics {
    private static final Logger logger = LoggerFactory.getLogger(IndexingMetrics.class);

    private final long overallStartTimeNanos;
    private final ConcurrentHashMap<String, Long> indexStartTimesNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> indexDurationsNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> indexItemCounts = new ConcurrentHashMap<>();

    public IndexingMetrics() {
        this.overallStartTimeNanos = System.nanoTime();
        logger.info("Indexing process initiated.");
    }

    public void startIndexProcessing(String indexType) {
        indexStartTimesNanos.put(indexType, System.nanoTime());
        logger.info("Started processing index type: {}", indexType);
    }

    public void endIndexProcessing(String indexType, long itemCount) {
        Long startTimeNanos = indexStartTimesNanos.get(indexType);
        if (startTimeNanos != null) {
            long durationNanos = System.nanoTime() - startTimeNanos;
            indexDurationsNanos.put(indexType, durationNanos);
            indexItemCounts.put(indexType, itemCount);

            if (itemCount >= 0) {
                logger.info("Finished processing index type: {}. Duration: {} ms. Items processed/written: {}",
                            indexType, TimeUnit.NANOSECONDS.toMillis(durationNanos), itemCount);
            } else {
                 logger.warn("Processing for index type: {} recorded as FAILED/INCOMPLETE. Duration up to this point: {} ms.",
                            indexType, TimeUnit.NANOSECONDS.toMillis(durationNanos));
            }
        } else {
            // This could happen if endIndexProcessing is called multiple times or without a start.
            logger.warn("endIndexProcessing called for index type: {} without a corresponding start time or it was already ended.", indexType);
        }
    }

    public void logOverallMetrics() {
        long overallDurationNanos = System.nanoTime() - overallStartTimeNanos;
        logger.info("--- Overall Indexing Summary ---");
        logger.info("Total indexing duration: {} ms ({} seconds)",
                    TimeUnit.NANOSECONDS.toMillis(overallDurationNanos),
                    TimeUnit.NANOSECONDS.toSeconds(overallDurationNanos));

        if (!indexDurationsNanos.isEmpty()) {
            logger.info("Individual Index Metrics (Duration and Item Count):");
            for (Map.Entry<String, Long> entry : indexDurationsNanos.entrySet()) {
                String indexType = entry.getKey();
                long durationMs = TimeUnit.NANOSECONDS.toMillis(entry.getValue());
                long itemCount = indexItemCounts.getOrDefault(indexType, -1L);

                if (itemCount >= 0) {
                    logger.info("  - Index Type: '{}', Duration: {} ms, Items: {}", indexType, durationMs, itemCount);
                } else {
                    logger.info("  - Index Type: '{}', Duration (up to failure/issue): {} ms, Status: FAILED/INCOMPLETE", indexType, durationMs);
                }
            }
        } else {
            logger.info("No individual index metrics were recorded for this run.");
        }
        logger.info("--- End of Indexing Summary ---");
    }
}