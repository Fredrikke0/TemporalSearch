package com.example.logging.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates human-readable summary reports from analyzed log data.
 * Supports both plain text and HTML report formats.
 */
public class LogSummarizer {
    private static final Logger logger = LoggerFactory.getLogger(LogSummarizer.class);

    /**
     * Generates a summary report from log analysis results.
     *
     * @param analysisResults The results from LogAnalyzer
     * @param outputPath Path where the report should be saved
     * @param format The output format ("text" or "html")
     * @throws IOException if there's an error writing the report
     */
    public void generateReport(Map<String, Object> analysisResults, Path outputPath, String format)
            throws IOException {
        String report = format.equalsIgnoreCase("html") ?
            generateHtmlReport(analysisResults) :
            generateTextReport(analysisResults);

        Files.writeString(outputPath, report);
        logger.info("Generated {} report at: {}", format, outputPath);
    }

    private String generateTextReport(Map<String, Object> results) {
        StringBuilder report = new StringBuilder();
        report.append("N-gram Index Generator Analysis Report\n");
        report.append("=====================================\n\n");

        // Processing Summary
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) results.get("processing_summary");
        report.append("Processing Summary:\n");
        report.append("-----------------\n");
        report.append(String.format("Total Documents Processed: %d\n",
            summary.get("total_documents_processed")));
        report.append(String.format("Total N-grams Generated: %d\n",
            summary.get("total_ngrams_generated")));
        if (summary.containsKey("avg_ngrams_per_document")) {
            report.append(String.format("Average N-grams per Document: %.2f\n",
                summary.get("avg_ngrams_per_document")));
        }
        report.append("\n");

        // Performance Metrics
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Double>> metrics =
            (Map<String, Map<String, Double>>) results.get("performance_metrics");
        report.append("Performance Metrics:\n");
        report.append("-------------------\n");
        metrics.forEach((phase, stats) -> {
            report.append(String.format("Phase: %s\n", phase));
            report.append(String.format("  Average Processing Time: %.2f ms\n", stats.get("avg_ms")));
            report.append(String.format("  Min Processing Time: %.2f ms\n", stats.get("min_ms")));
            report.append(String.format("  Max Processing Time: %.2f ms\n", stats.get("max_ms")));
            report.append(String.format("  Standard Deviation: %.2f ms\n", stats.get("std_dev_ms")));
            report.append("\n");
        });

        // Memory Usage
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) results.get("memory_usage_trend");
        report.append("Memory Usage:\n");
        report.append("-------------\n");
        report.append(String.format("Average Usage: %.2f MB\n", memory.get("avg_usage_mb")));
        report.append(String.format("Peak Usage: %.2f MB\n", memory.get("peak_usage_mb")));
        report.append(String.format("Minimum Usage: %.2f MB\n", memory.get("min_usage_mb")));
        if (memory.containsKey("growth_rate_mb_per_hour")) {
            report.append(String.format("Memory Growth Rate: %.2f MB/hour\n",
                memory.get("growth_rate_mb_per_hour")));
        }
        report.append("\n");

        // Error Patterns
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) results.get("error_patterns");
        report.append("Error Analysis:\n");
        report.append("--------------\n");
        report.append(String.format("Total Errors: %d\n", errors.get("total_errors")));
        @SuppressWarnings("unchecked")
        Map<String, Long> distribution = (Map<String, Long>) errors.get("error_distribution");
        if (!distribution.isEmpty()) {
            report.append("Error Distribution:\n");
            distribution.forEach((type, count) ->
                report.append(String.format("  %s: %d\n", type, count)));
        }
        report.append("\n");

        // State Verifications
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> verifications =
            (Map<String, Map<String, Object>>) results.get("state_verification_summary");
        report.append("State Verifications:\n");
        report.append("-------------------\n");
        verifications.forEach((state, stats) -> {
            report.append(String.format("State: %s\n", state));
            report.append(String.format("  Total Checks: %d\n", stats.get("total_checks")));
            report.append(String.format("  Pass Rate: %.2f%%\n", stats.get("pass_rate")));
            report.append(String.format("  Passed: %d\n", stats.get("passed")));
            report.append(String.format("  Failed: %d\n", stats.get("failed")));
            report.append("\n");
        });

        return report.toString();
    }

    private String generateHtmlReport(Map<String, Object> results) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n")
            .append("<html><head>\n")
            .append("<title>N-gram Index Generator Analysis Report</title>\n")
            .append("<style>\n")
            .append("body { font-family: Arial, sans-serif; margin: 40px; }\n")
            .append("h1 { color: #2c3e50; }\n")
            .append("h2 { color: #34495e; margin-top: 30px; }\n")
            .append(".metric { margin: 10px 0; }\n")
            .append(".error { color: #e74c3c; }\n")
            .append(".success { color: #27ae60; }\n")
            .append("table { border-collapse: collapse; width: 100%; margin: 20px 0; }\n")
            .append("th, td { border: 1px solid #bdc3c7; padding: 8px; text-align: left; }\n")
            .append("th { background-color: #f5f6fa; }\n")
            .append("</style>\n")
            .append("</head><body>\n");

        html.append("<h1>N-gram Index Generator Analysis Report</h1>\n");

        // Processing Summary
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) results.get("processing_summary");
        html.append("<h2>Processing Summary</h2>\n");
        html.append("<div class='metric'>")
            .append(String.format("Total Documents Processed: <strong>%d</strong><br>\n",
                summary.get("total_documents_processed")))
            .append(String.format("Total N-grams Generated: <strong>%d</strong><br>\n",
                summary.get("total_ngrams_generated")));
        if (summary.containsKey("avg_ngrams_per_document")) {
            html.append(String.format("Average N-grams per Document: <strong>%.2f</strong><br>\n",
                summary.get("avg_ngrams_per_document")));
        }
        html.append("</div>\n");

        // Performance Metrics
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Double>> metrics =
            (Map<String, Map<String, Double>>) results.get("performance_metrics");
        html.append("<h2>Performance Metrics</h2>\n");
        html.append("<table>\n")
            .append("<tr><th>Phase</th><th>Avg (ms)</th><th>Min (ms)</th>")
            .append("<th>Max (ms)</th><th>Std Dev (ms)</th></tr>\n");
        metrics.forEach((phase, stats) -> {
            html.append("<tr>")
                .append(String.format("<td>%s</td>", phase))
                .append(String.format("<td>%.2f</td>", stats.get("avg_ms")))
                .append(String.format("<td>%.2f</td>", stats.get("min_ms")))
                .append(String.format("<td>%.2f</td>", stats.get("max_ms")))
                .append(String.format("<td>%.2f</td>", stats.get("std_dev_ms")))
                .append("</tr>\n");
        });
        html.append("</table>\n");

        // Memory Usage
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) results.get("memory_usage_trend");
        html.append("<h2>Memory Usage</h2>\n");
        html.append("<div class='metric'>")
            .append(String.format("Average Usage: <strong>%.2f MB</strong><br>\n",
                memory.get("avg_usage_mb")))
            .append(String.format("Peak Usage: <strong>%.2f MB</strong><br>\n",
                memory.get("peak_usage_mb")))
            .append(String.format("Minimum Usage: <strong>%.2f MB</strong><br>\n",
                memory.get("min_usage_mb")));
        if (memory.containsKey("growth_rate_mb_per_hour")) {
            html.append(String.format("Memory Growth Rate: <strong>%.2f MB/hour</strong><br>\n",
                memory.get("growth_rate_mb_per_hour")));
        }
        html.append("</div>\n");

        // Error Analysis
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) results.get("error_patterns");
        html.append("<h2>Error Analysis</h2>\n");
        html.append(String.format("<div class='metric error'>Total Errors: <strong>%d</strong></div>\n",
            errors.get("total_errors")));
        @SuppressWarnings("unchecked")
        Map<String, Long> distribution = (Map<String, Long>) errors.get("error_distribution");
        if (!distribution.isEmpty()) {
            html.append("<table>\n")
                .append("<tr><th>Error Type</th><th>Count</th></tr>\n");
            distribution.forEach((type, count) ->
                html.append(String.format("<tr><td>%s</td><td>%d</td></tr>\n", type, count)));
            html.append("</table>\n");
        }

        // State Verifications
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> verifications =
            (Map<String, Map<String, Object>>) results.get("state_verification_summary");
        html.append("<h2>State Verifications</h2>\n");
        html.append("<table>\n")
            .append("<tr><th>State</th><th>Total Checks</th><th>Pass Rate</th>")
            .append("<th>Passed</th><th>Failed</th></tr>\n");
        verifications.forEach((state, stats) -> {
            html.append("<tr>")
                .append(String.format("<td>%s</td>", state))
                .append(String.format("<td>%d</td>", stats.get("total_checks")))
                .append(String.format("<td>%.2f%%</td>", stats.get("pass_rate")))
                .append(String.format("<td class='success'>%d</td>", stats.get("passed")))
                .append(String.format("<td class='error'>%d</td>", stats.get("failed")))
                .append("</tr>\n");
        });
        html.append("</table>\n");

        html.append("</body></html>");
        return html.toString();
    }
}