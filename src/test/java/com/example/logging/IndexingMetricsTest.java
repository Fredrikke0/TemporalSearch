package com.example.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class IndexingMetricsTest {
    private IndexingMetrics metrics;
    private ListAppender<ILoggingEvent> listAppender;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Dummy stage durations for testing
    private static final long FETCH_NANOS = 5_000_000; // 5ms
    private static final long PROCESS_NANOS = 10_000_000; // 10ms
    private static final long WRITE_TEMP_NANOS = 2_000_000; // 2ms

    @BeforeEach
    void setUp() {
        metrics = new IndexingMetrics();
        
        Logger logger = (Logger) LoggerFactory.getLogger(IndexingMetrics.class);
        // Ensure logger level is low enough to capture INFO/WARN from IndexingMetrics
        // If IndexingMetrics logs batch details at DEBUG, this might need to be DEBUG
        // Based on current IndexingMetrics, sampled batches are INFO, summary is INFO.
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG); 
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.detachAndStopAllAppenders(); // Remove other appenders to avoid duplicate logs in test output
        logger.addAppender(listAppender);
    }

    @Test
    void testSuccessfulBatchProcessingWithStages() throws Exception {
        metrics.startBatch(100, "unigram"); // rawEntriesInBatch = 100 (from currentBatchSize)
        // Simulate one successful batch with stage durations
        metrics.recordBatchStageDurations(FETCH_NANOS, PROCESS_NANOS, WRITE_TEMP_NANOS, 80, 100); // 80 output items, 100 raw

        assertEquals(100, metrics.getTotalEntries()); // Raw entries
        assertEquals(80, metrics.getTotalOutputItems());
        assertEquals(1, metrics.getTotalBatches());
        assertEquals(0, metrics.getErrorCount());
        assertEquals(0, metrics.getNullCount());
        assertTrue(metrics.getTotalProcessingTimeNanos() > 0);
        assertEquals(FETCH_NANOS + PROCESS_NANOS + WRITE_TEMP_NANOS, metrics.getTotalProcessingTimeNanos());

        // Check min/max for stages are set
        metrics.logIndexingMetrics(); // This will populate min/max if only one batch
        JsonNode summary = getLastLogEventJson("indexing_summary");
        assertNotNull(summary);
        assertEquals(FETCH_NANOS / 1_000_000.0, summary.get("min_fetch_stage_ms").asDouble(), 0.001);
        assertEquals(PROCESS_NANOS / 1_000_000.0, summary.get("max_process_stage_ms").asDouble(), 0.001);
    }

    @Test
    void testFailedBatchProcessing() throws Exception {
        metrics.startBatch(100, "unigram");
        metrics.recordBatchFailure(); // This uses the old recordBatchCompletion path

        assertEquals(0, metrics.getTotalEntries()); // No raw entries processed on failure if using simple recordBatchFailure
        assertEquals(0, metrics.getTotalOutputItems());
        assertEquals(1, metrics.getTotalBatches()); // A batch was attempted
        assertEquals(1, metrics.getErrorCount());

        // Verify log
        JsonNode batchLog = getLastLogEventJson("batch_complete");
        assertNotNull(batchLog);
        assertFalse(batchLog.get("success").asBoolean());
        assertEquals(100, batchLog.get("batch_input_size").asInt()); // currentBatchSize
    }

    @Test
    void testNullBatchProcessing() {
        // recordNullBatch does not increment batch count. It's for when fetchBatch returns empty.
        metrics.recordNullBatch();

        assertEquals(0, metrics.getTotalEntries());
        assertEquals(0, metrics.getTotalOutputItems());
        assertEquals(0, metrics.getTotalBatches()); // No batch processed
        assertEquals(0, metrics.getErrorCount());
        assertEquals(1, metrics.getNullCount());
    }

    @Test
    void testMultipleBatchesDifferentTypesWithStages() throws Exception {
        metrics.startBatch(100, "unigram");
        metrics.recordBatchStageDurations(FETCH_NANOS, PROCESS_NANOS, WRITE_TEMP_NANOS, 80, 100);

        metrics.startBatch(150, "bigram"); // Different raw batch size
        metrics.recordBatchStageDurations(FETCH_NANOS + 1, PROCESS_NANOS + 1, WRITE_TEMP_NANOS + 1, 120, 150);

        metrics.startBatch(50, "trigram");
        metrics.recordBatchStageDurations(FETCH_NANOS -1 , PROCESS_NANOS -1, WRITE_TEMP_NANOS -1, 40, 50);

        assertEquals(100 + 150 + 50, metrics.getTotalEntries()); // Sum of raw entries
        assertEquals(80 + 120 + 40, metrics.getTotalOutputItems()); // Sum of output items
        assertEquals(3, metrics.getTotalBatches());
        assertEquals(0, metrics.getErrorCount());

        // Test uniqueDocumentsProcessed - currently not updated by recordBatchStageDurations
        // If we want to test it, we'd need to call metrics.recordBatchSuccess(uniqueDocs) or adapt recordBatchStageDurations
        // For now, we can assert it's 0 if not explicitly set by other means.
        // metrics.recordBatchSuccess(50); // e.g. if unigram generator explicitly tracks this
        // assertEquals(50, metrics.getUniqueDocuments()); 
    }


    @Test
    void testMetricsLoggingWithStages() throws Exception {
        // Increase iterations to improve chance of sampler hitting.
        // For a 5% sample rate, 50 iterations gives a high chance of at least one hit.
        int unigramIterations = 50;
        int bigramIterations = 50;

        for (int i = 0; i < unigramIterations; i++) {
            metrics.startBatch(100, "test-unigram");
            metrics.recordBatchStageDurations(FETCH_NANOS, PROCESS_NANOS, WRITE_TEMP_NANOS, 80, 100);
        }
        
        for (int i = 0; i < bigramIterations; i++) {
            metrics.startBatch(120, "test-bigram");
            metrics.recordBatchStageDurations(FETCH_NANOS + 1000, PROCESS_NANOS + 1000, WRITE_TEMP_NANOS + 1000, 90, 120);
        }

        metrics.startBatch(50, "test-failed");
        metrics.recordBatchFailure(); // This will always log due to !success

        metrics.logIndexingMetrics();

        JsonNode summary = null;
        JsonNode loggedUnigramBatch = null; // To store the first unigram batch we find
        JsonNode loggedBigramBatch = null;  // To store the first bigram batch we find
        JsonNode loggedFailedBatch = null;

        for (ILoggingEvent event : listAppender.list) {
            String msg = event.getMessage();
            JsonNode json = MAPPER.readTree(msg);
            String eventType = json.path("event").asText();
            String indexType = json.path("index_type").asText(); // Get index type from log

            if ("indexing_summary".equals(eventType)) {
                summary = json;
            } else if ("batch_complete".equals(eventType)) {
                if (json.get("success").asBoolean()) {
                    if ("test-unigram".equals(indexType) && loggedUnigramBatch == null) { // Found first unigram
                        loggedUnigramBatch = json;
                    }
                    if ("test-bigram".equals(indexType) && loggedBigramBatch == null) { // Found first bigram
                        loggedBigramBatch = json;
            }
                } else { // Failed batch
                     if ("test-failed".equals(indexType) && loggedFailedBatch == null) {
                        loggedFailedBatch = json;
                    }
                }
            }
        }
        
        assertNotNull(summary, "No indexing_summary event found");
        assertEquals(unigramIterations * 100 + bigramIterations * 120, summary.get("total_raw_entries_processed").asInt());
        assertEquals(unigramIterations * 80 + bigramIterations * 90, summary.get("total_output_items_generated").asInt());
        assertEquals(unigramIterations + bigramIterations + 1, summary.get("total_batches_processed").asInt()); // +1 for the failed batch
        assertEquals(1, summary.get("total_errors").asInt());
        assertTrue(summary.has("elapsed_seconds"));
        assertTrue(summary.has("output_items_per_second"));
        assertTrue(summary.has("raw_entries_per_second"));
        assertTrue(summary.has("avg_fetch_stage_ms"));
        assertTrue(summary.has("avg_process_stage_ms"));
        assertTrue(summary.has("avg_write_temp_stage_ms"));
        assertTrue(summary.has("min_fetch_stage_ms"));
        assertTrue(summary.has("max_process_stage_ms"));

        assertNotNull(loggedUnigramBatch, "No successful 'test-unigram' batch was logged. Increase iterations or check sampler.");
        if (loggedUnigramBatch != null) {
            assertEquals(100, loggedUnigramBatch.get("batch_input_size").asInt());
            assertEquals(80, loggedUnigramBatch.get("batch_output_items").asInt());
            assertEquals(FETCH_NANOS / 1_000_000.0, loggedUnigramBatch.get("fetch_stage_ms").asDouble(), 0.001);
        }

        assertNotNull(loggedBigramBatch, "No successful 'test-bigram' batch was logged. Increase iterations or check sampler.");
         if (loggedBigramBatch != null) {
            assertEquals(120, loggedBigramBatch.get("batch_input_size").asInt());
            assertEquals(90, loggedBigramBatch.get("batch_output_items").asInt());
            assertEquals((FETCH_NANOS + 1000) / 1_000_000.0, loggedBigramBatch.get("fetch_stage_ms").asDouble(), 0.001);
        }

        assertNotNull(loggedFailedBatch, "Failed batch 'test-failed' not logged");
        if (loggedFailedBatch != null) {
            assertEquals(50, loggedFailedBatch.get("batch_input_size").asInt()); 
            assertFalse(loggedFailedBatch.get("success").asBoolean());
            assertFalse(loggedFailedBatch.has("fetch_stage_ms")); // Stage timings are not present for this failure path
        }
    }

    @Test
    void testPerformanceOverheadWithStages() {
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 1000; i++) {
            metrics.startBatch(100, "test-perf");
            metrics.recordBatchStageDurations(1_000_000, 1_000_000, 500_000, 80, 100);
        }
        
        long duration = System.nanoTime() - startTime;
        double durationMs = duration / 1_000_000.0;
        double overheadPerBatchMs = durationMs / 1000.0;

        // Increased the threshold slightly due to more complex logic per call.
        // Still aims for sub-millisecond overhead on average.
        assertTrue(overheadPerBatchMs < 1.0,
            "Metrics overhead too high: " + overheadPerBatchMs + "ms per batch for 1000 batches");
    }

    // Helper to get the last logged event as JSON, matching an event type
    private JsonNode getLastLogEventJson(String eventType) throws Exception {
        JsonNode result = null;
        for (ILoggingEvent event : listAppender.list) {
            JsonNode json = MAPPER.readTree(event.getMessage());
            if (json.path("event").asText().equals(eventType)) {
                result = json; // Keep overwriting to get the last one
            }
        }
        // listAppender.list.clear(); // Clear after use if needed, or per test
        return result;
    }
} 