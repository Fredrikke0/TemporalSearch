package com.example.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class IndexingMetricsTest {
    private IndexingMetrics metrics;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        metrics = new IndexingMetrics();

        Logger logger = (Logger) LoggerFactory.getLogger(IndexingMetrics.class);
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.detachAndStopAllAppenders();
        logger.addAppender(listAppender);
    }

    private List<String> getLogMessages() {
        return listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    @Test
    void testSingleIndexSuccess() throws InterruptedException {
        String indexType = "unigram";
        long items = 12345;

        metrics.startIndexProcessing(indexType);
        TimeUnit.MILLISECONDS.sleep(50); // Simulate work
        metrics.endIndexProcessing(indexType, items);
        metrics.logOverallMetrics();

        List<String> logs = getLogMessages();

        assertTrue(logs.stream().anyMatch(s -> s.contains("Started processing index type: " + indexType)), "Log for start of processing not found");
        assertTrue(logs.stream().anyMatch(s -> s.contains("Finished processing index type: " + indexType) && s.contains("Items processed/written: " + items)), "Log for end of successful processing not found");

        String overallSummaryMarker = "--- Overall Indexing Summary ---";
        String endOfSummaryMarker = "--- End of Indexing Summary ---";
        String unigramMetricLine = String.format("  - Index Type: '%s', Duration: ", indexType);
        String unigramItemLine = String.format("Items: %d", items);

        assertTrue(logs.stream().anyMatch(s -> s.contains(overallSummaryMarker)), "Overall summary marker not found");
        assertTrue(logs.stream().anyMatch(s -> s.contains(endOfSummaryMarker)), "End of summary marker not found");

        boolean foundUnigramSummary = logs.stream().anyMatch(s -> s.contains(unigramMetricLine) && s.contains(unigramItemLine));
        assertTrue(foundUnigramSummary, "Unigram summary line not found or incorrect in logs. Logs: \n" + String.join("\n", logs));
        assertTrue(logs.stream().anyMatch(s -> s.contains("Total indexing duration:")), "Total duration log not found");
    }

    @Test
    void testSingleIndexFailure() throws InterruptedException {
        String indexType = "bigram";
        long errorCode = -1; // Indicates failure

        metrics.startIndexProcessing(indexType);
        TimeUnit.MILLISECONDS.sleep(30); // Simulate work
        metrics.endIndexProcessing(indexType, errorCode);
        metrics.logOverallMetrics();

        List<String> logs = getLogMessages();

        assertTrue(logs.stream().anyMatch(s -> s.contains("Started processing index type: " + indexType)), "Log for start of processing not found");
        assertTrue(logs.stream().anyMatch(s -> s.contains("Processing for index type: " + indexType + " recorded as FAILED/INCOMPLETE")), "Log for failed processing not found");

        String overallSummaryMarker = "--- Overall Indexing Summary ---";
        String bigramMetricLine = String.format("  - Index Type: '%s'", indexType);
        String failureStatus = "Status: FAILED/INCOMPLETE";

        assertTrue(logs.stream().anyMatch(s -> s.contains(overallSummaryMarker)), "Overall summary marker not found");

        boolean foundBigramFailureSummary = logs.stream().anyMatch(s -> s.contains(bigramMetricLine) && s.contains(failureStatus));
        assertTrue(foundBigramFailureSummary, "Bigram failure summary line not found or incorrect in logs. Logs: \n" + String.join("\n", logs));
    }

    @Test
    void testMultipleIndexesMixedSuccessAndFailure() throws InterruptedException {
        String type1 = "unigram";
        long items1 = 1000;
        String type2 = "bigram";
        long items2Fail = -1;
        String type3 = "trigram";
        long items3 = 500;

        metrics.startIndexProcessing(type1);
        TimeUnit.MILLISECONDS.sleep(10);
        metrics.endIndexProcessing(type1, items1);

        metrics.startIndexProcessing(type2);
        TimeUnit.MILLISECONDS.sleep(10);
        metrics.endIndexProcessing(type2, items2Fail);

        metrics.startIndexProcessing(type3);
        TimeUnit.MILLISECONDS.sleep(10);
        metrics.endIndexProcessing(type3, items3);

        metrics.logOverallMetrics();
        List<String> logs = getLogMessages();

        assertTrue(logs.stream().anyMatch(s -> s.contains("--- Overall Indexing Summary ---")), "Overall summary marker not found");
        assertTrue(logs.stream().anyMatch(s -> s.contains("Total indexing duration:")), "Total duration log not found");

        String type1SuccessMarker = String.format("  - Index Type: '%s'", type1);
        String type1ItemMarker = String.format("Items: %d", items1);
        assertTrue(logs.stream().anyMatch(s -> s.contains(type1SuccessMarker) && s.contains(type1ItemMarker) && !s.contains("FAILED/INCOMPLETE")), "Type1 success log not found");

        String type2FailureMarker = String.format("  - Index Type: '%s'", type2);
        String failureStatus = "Status: FAILED/INCOMPLETE";
        assertTrue(logs.stream().anyMatch(s -> s.contains(type2FailureMarker) && s.contains(failureStatus)), "Type2 failure log not found");

        String type3SuccessMarker = String.format("  - Index Type: '%s'", type3);
        String type3ItemMarker = String.format("Items: %d", items3);
        assertTrue(logs.stream().anyMatch(s -> s.contains(type3SuccessMarker) && s.contains(type3ItemMarker) && !s.contains("FAILED/INCOMPLETE")), "Type3 success log not found");

        assertTrue(logs.stream().anyMatch(s -> s.contains("--- End of Indexing Summary ---")), "End of summary marker not found");
    }

    @Test
    void testNoIndexesProcessed() {
        metrics.logOverallMetrics();
        List<String> logs = getLogMessages();

        assertTrue(logs.stream().anyMatch(s -> s.contains("--- Overall Indexing Summary ---")), "Overall summary marker not found");
        assertTrue(logs.stream().anyMatch(s -> s.contains("Total indexing duration:")), "Total duration log not found");
        assertTrue(logs.stream().anyMatch(s -> s.contains("No individual index metrics were recorded for this run.")), "Log for no individual metrics not found");
        assertTrue(logs.stream().anyMatch(s -> s.contains("--- End of Indexing Summary ---")), "End of summary marker not found");
    }

    @Test
    void testEndProcessingWithoutStart() {
        String orphanType = "orphan_index";
        metrics.endIndexProcessing(orphanType, 100);

        List<String> logs = getLogMessages();
        assertTrue(logs.stream().anyMatch(s -> s.contains("endIndexProcessing called for index type: " + orphanType + " without a corresponding start time")), "Warning for orphan endIndexProcessing not found");

        listAppender.list.clear();
        metrics.logOverallMetrics();
        List<String> summaryLogs = getLogMessages();

        assertTrue(summaryLogs.stream().anyMatch(s -> s.contains("--- Overall Indexing Summary ---")), "Overall summary marker not found in second log set");
        assertTrue(summaryLogs.stream().anyMatch(s -> s.contains("No individual index metrics were recorded for this run.")), "Log for no individual metrics not found in second log set");
        assertFalse(summaryLogs.stream().anyMatch(s -> s.contains("Index Type: '" + orphanType + "'")), "Orphan index should not appear in the final summary. Logs: \n" +  String.join("\n", summaryLogs));
    }

    @Test
    void testOverheadOfMetricsCalls() {
        final int ITERATIONS = 1000;
        String testType = "performance_test";

        long startTime = System.nanoTime();

        for (int i = 0; i < ITERATIONS; i++) {
            metrics.startIndexProcessing(testType + "_" + i); // Unique type for each to avoid overwrite issues
            metrics.endIndexProcessing(testType + "_" + i, i);
        }
        metrics.logOverallMetrics();

        long durationNanos = System.nanoTime() - startTime;
        double durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);
        // Each iteration does a start, end. logOverallMetrics is outside the loop.
        double overheadPerOpPairMs = durationMs / ITERATIONS;

        assertTrue(overheadPerOpPairMs < 0.5, "Metrics overhead per operation pair is too high: " + overheadPerOpPairMs + "ms. Total duration: " + durationMs + "ms");
        System.out.println("Metrics overhead per (start + end) operation pair: " + overheadPerOpPairMs + " ms for " + ITERATIONS + " iterations.");
    }
}