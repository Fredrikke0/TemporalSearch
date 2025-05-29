package com.example.logging.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogAnalysisTest {

    @Test
    void testLogAnalysis(@TempDir Path tempDir) throws IOException {
        // Create a sample log file
        Path logFile = tempDir.resolve("test.log");
        List<String> logLines = List.of(
            "{\"event\":\"batch_complete\",\"timestamp\":\"2025-01-10 14:40:03.998\",\"phase\":\"unigram\",\"total_documents\":100,\"avg_processing_time_ms\":2.5,\"heap_used_mb\":1024}",
            "{\"event\":\"indexing_metrics\",\"phase\":\"unigram\",\"ngrams_generated\":500,\"state_verifications\":{\"ngram_generation_passed\":95,\"ngram_generation_failed\":5}}",
            "{\"event\":\"batch_error\",\"message\":\"Failed to process document\",\"batch_size\":10}",
            "{\"event\":\"batch_complete\",\"timestamp\":\"2025-01-10 14:41:03.998\",\"phase\":\"unigram\",\"total_documents\":200,\"avg_processing_time_ms\":2.8,\"heap_used_mb\":1124}"
        );
        Files.write(logFile, logLines);

        // Analyze logs
        LogAnalyzer analyzer = new LogAnalyzer();
        Map<String, Object> results = analyzer.analyzeLogs(logFile);

        // Verify analysis results
        assertNotNull(results);
        assertTrue(results.containsKey("processing_summary"));
        assertTrue(results.containsKey("performance_metrics"));
        assertTrue(results.containsKey("error_patterns"));
        assertTrue(results.containsKey("state_verification_summary"));
        assertTrue(results.containsKey("memory_usage_trend"));

        // Verify processing summary
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) results.get("processing_summary");
        assertEquals(300L, summary.get("total_documents_processed"));
        assertEquals(500L, summary.get("total_ngrams_generated"));

        // Verify error patterns
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) results.get("error_patterns");
        assertEquals(1L, errors.get("total_errors"));

        // Verify state verifications
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> verifications =
            (Map<String, Map<String, Object>>) results.get("state_verification_summary");
        assertTrue(verifications.containsKey("ngram_generation"));
        assertEquals(95.0, verifications.get("ngram_generation").get("pass_rate"));

        // Generate and verify text report
        Path textReport = tempDir.resolve("report.txt");
        LogSummarizer summarizer = new LogSummarizer();
        summarizer.generateReport(results, textReport, "text");
        assertTrue(Files.exists(textReport));
        String textContent = Files.readString(textReport);
        assertTrue(textContent.contains("Total Documents Processed: 300"));
        assertTrue(textContent.contains("Total N-grams Generated: 500"));

        // Generate and verify HTML report
        Path htmlReport = tempDir.resolve("report.html");
        summarizer.generateReport(results, htmlReport, "html");
        assertTrue(Files.exists(htmlReport));
        String htmlContent = Files.readString(htmlReport);
        assertTrue(htmlContent.contains("<!DOCTYPE html>"));
        assertTrue(htmlContent.contains("<title>N-gram Index Generator Analysis Report</title>"));
        assertTrue(htmlContent.contains("Total Documents Processed: <strong>300</strong>"));
    }

    @Test
    void testEmptyLogFile(@TempDir Path tempDir) throws IOException {
        // Create an empty log file
        Path logFile = tempDir.resolve("empty.log");
        Files.createFile(logFile);

        // Analyze empty log
        LogAnalyzer analyzer = new LogAnalyzer();
        Map<String, Object> results = analyzer.analyzeLogs(logFile);

        // Verify results contain all expected sections but with empty/zero values
        assertNotNull(results);
        assertTrue(results.containsKey("processing_summary"));
        assertTrue(results.containsKey("performance_metrics"));
        assertTrue(results.containsKey("error_patterns"));

        // Verify processing summary shows zero documents
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) results.get("processing_summary");
        assertEquals(0L, summary.get("total_documents_processed"));
        assertEquals(0L, summary.get("total_ngrams_generated"));

        // Generate reports from empty analysis
        LogSummarizer summarizer = new LogSummarizer();
        Path textReport = tempDir.resolve("empty_report.txt");
        summarizer.generateReport(results, textReport, "text");
        assertTrue(Files.exists(textReport));

        Path htmlReport = tempDir.resolve("empty_report.html");
        summarizer.generateReport(results, htmlReport, "html");
        assertTrue(Files.exists(htmlReport));
    }

    @Test
    void testInvalidLogLines(@TempDir Path tempDir) throws IOException {
        // Create a log file with some invalid JSON lines
        Path logFile = tempDir.resolve("invalid.log");
        List<String> logLines = List.of(
            "This is not JSON",
            "{\"event\":\"batch_complete\",\"total_documents\":100}",
            "}{invalid json}{",
            "{\"event\":\"indexing_metrics\",\"ngrams_generated\":500}"
        );
        Files.write(logFile, logLines);

        // Analyze logs with invalid lines
        LogAnalyzer analyzer = new LogAnalyzer();
        Map<String, Object> results = analyzer.analyzeLogs(logFile);

        // Verify valid entries were processed
        assertNotNull(results);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) results.get("processing_summary");
        assertEquals(100L, summary.get("total_documents_processed"));
        assertEquals(500L, summary.get("total_ngrams_generated"));

        // Generate reports from partial analysis
        LogSummarizer summarizer = new LogSummarizer();
        Path textReport = tempDir.resolve("partial_report.txt");
        summarizer.generateReport(results, textReport, "text");
        assertTrue(Files.exists(textReport));
    }
}