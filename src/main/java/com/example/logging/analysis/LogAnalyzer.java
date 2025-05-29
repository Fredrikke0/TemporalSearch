package com.example.logging.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Analyzes JSON-formatted logs to extract performance metrics, error patterns,
 * and system health indicators.
 */
public class LogAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(LogAnalyzer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Map<String, Long> errorCounts = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> processingTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> stateVerifications = new ConcurrentHashMap<>();
    private final Map<LocalDateTime, Double> memoryUsage = new TreeMap<>();
    private long totalDocumentsProcessed = 0;
    private long totalNgramsGenerated = 0;

    /**
     * Analyzes a log file to extract metrics and patterns.
     *
     * @param logFile Path to the log file to analyze
     * @return Map containing analysis results
     * @throws IOException if there's an error reading the log file
     */
    public Map<String, Object> analyzeLogs(Path logFile) throws IOException {
        try (Stream<String> lines = Files.lines(logFile)) {
            lines.forEach(this::processLogLine);
        }

        Map<String, Object> results = new HashMap<>();
        results.put("error_patterns", analyzeErrorPatterns());
        results.put("performance_metrics", analyzePerformanceMetrics());
        results.put("state_verification_summary", analyzeStateVerifications());
        results.put("memory_usage_trend", analyzeMemoryUsage());
        results.put("processing_summary", createProcessingSummary());

        return results;
    }

    private void processLogLine(String line) {
        try {
            // Skip empty lines or non-JSON lines
            if (line == null || line.trim().isEmpty() || !line.trim().startsWith("{")) {
                return;
            }

            JsonNode json = MAPPER.readTree(line);
            String event = json.path("event").asText("");

            switch (event) {
                case "batch_complete":
                    processBatchMetrics(json);
                    break;
                case "indexing_metrics":
                    processIndexingMetrics(json);
                    break;
                case "batch_error":
                    processError(json);
                    break;
            }
        } catch (Exception e) {
            logger.warn("Failed to process log line: {}", e.getMessage());
        }
    }

    private void processBatchMetrics(JsonNode json) {
        String phase = json.path("phase").asText("unknown");
        processingTimes.computeIfAbsent(phase, k -> new ArrayList<>())
            .add(json.path("avg_processing_time_ms").asDouble());

        totalDocumentsProcessed += json.path("total_documents").asLong();

        // Track memory usage over time
        LocalDateTime timestamp = LocalDateTime.parse(
            json.path("timestamp").asText(), DATE_FORMAT);
        memoryUsage.put(timestamp, json.path("heap_used_mb").asDouble());
    }

    private void processIndexingMetrics(JsonNode json) {
        totalNgramsGenerated += json.path("ngrams_generated").asLong();

        // Process state verifications
        JsonNode verificationsNode = json.path("state_verifications");
        if (verificationsNode.isObject()) {
            // Modern way to iterate over ObjectNode properties
            ((ObjectNode) verificationsNode).properties().forEach(entry ->
                stateVerifications.merge(entry.getKey(), entry.getValue().asLong(), Long::sum));
        }
    }

    private void processError(JsonNode json) {
        String errorType = json.path("message").asText("unknown");
        errorCounts.merge(errorType, 1L, Long::sum);
    }

    private Map<String, Object> analyzeErrorPatterns() {
        Map<String, Object> patterns = new HashMap<>();
        patterns.put("total_errors", errorCounts.values().stream().mapToLong(Long::longValue).sum());
        patterns.put("error_distribution", errorCounts);
        return patterns;
    }

    private Map<String, Object> analyzePerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        processingTimes.forEach((phase, times) -> {
            DoubleSummaryStatistics stats = times.stream()
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();

            Map<String, Double> phaseStats = new HashMap<>();
            phaseStats.put("avg_ms", stats.getAverage());
            phaseStats.put("min_ms", stats.getMin());
            phaseStats.put("max_ms", stats.getMax());
            phaseStats.put("std_dev_ms", calculateStdDev(times, stats.getAverage()));

            metrics.put(phase, phaseStats);
        });

        return metrics;
    }

    private Map<String, Object> analyzeStateVerifications() {
        Map<String, Object> summary = new HashMap<>();

        // Group verifications by state type (removing _passed/_failed suffix)
        Map<String, Map<String, Long>> grouped = stateVerifications.entrySet().stream()
            .collect(Collectors.groupingBy(
                e -> e.getKey().substring(0, e.getKey().lastIndexOf('_')),
                Collectors.toMap(
                    e -> e.getKey().endsWith("_passed") ? "passed" : "failed",
                    Map.Entry::getValue,
                    Long::sum,
                    HashMap::new
                )
            ));

        grouped.forEach((state, counts) -> {
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            double passRate = counts.getOrDefault("passed", 0L) * 100.0 / total;

            Map<String, Object> stateStats = new HashMap<>();
            stateStats.put("total_checks", total);
            stateStats.put("pass_rate", passRate);
            stateStats.putAll(counts);

            summary.put(state, stateStats);
        });

        return summary;
    }

    private Map<String, Object> analyzeMemoryUsage() {
        Map<String, Object> memoryAnalysis = new HashMap<>();

        if (!memoryUsage.isEmpty()) {
            DoubleSummaryStatistics stats = memoryUsage.values().stream()
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();

            memoryAnalysis.put("avg_usage_mb", stats.getAverage());
            memoryAnalysis.put("peak_usage_mb", stats.getMax());
            memoryAnalysis.put("min_usage_mb", stats.getMin());

            // Calculate growth rate (MB/hour)
            if (memoryUsage.size() > 1) {
                List<LocalDateTime> timestamps = new ArrayList<>(memoryUsage.keySet());
                LocalDateTime first = timestamps.get(0);
                LocalDateTime last = timestamps.get(timestamps.size() - 1);
                double firstValue = memoryUsage.get(first);
                double lastValue = memoryUsage.get(last);
                double hoursDiff = java.time.Duration.between(first, last).toHours();

                if (hoursDiff > 0) {
                    double growthRate = (lastValue - firstValue) / hoursDiff;
                    memoryAnalysis.put("growth_rate_mb_per_hour", growthRate);
                }
            }
        }

        return memoryAnalysis;
    }

    private Map<String, Object> createProcessingSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_documents_processed", totalDocumentsProcessed);
        summary.put("total_ngrams_generated", totalNgramsGenerated);

        if (totalDocumentsProcessed > 0) {
            summary.put("avg_ngrams_per_document",
                (double) totalNgramsGenerated / totalDocumentsProcessed);
        }

        return summary;
    }

    private double calculateStdDev(List<Double> values, double mean) {
        return Math.sqrt(values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average()
            .orElse(0.0));
    }
}