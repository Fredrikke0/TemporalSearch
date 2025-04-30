package com.example;

import com.example.annotation.Annotations;
import com.example.logging.analysis.LogAnalyzer;
import com.example.logging.analysis.LogSummarizer;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Pipeline {
    private static final Logger logger = LoggerFactory.getLogger(Pipeline.class);
    private static final String DEFAULT_PROJECT = "default";

    public static void main(String[] args) {
        try {
            runPipeline(args);
        } catch (ArgumentParserException e) {
            System.err.println("Argument Error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            logger.error("Error running pipeline", e);
            System.err.println("Error running pipeline: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void runPipeline(String[] args) throws Exception {
        // Create argument parser
        ArgumentParser parser = ArgumentParsers.newFor("Pipeline").build()
                .defaultHelp(true)
                .description("Process and index text data through multiple pipeline stages: conversion, annotation, indexing, and analysis.")
                .usage("${prog} [-h] <stage-specific-arguments>\n\n" +
                       "Example usage for each stage:\n" +
                       "  Convert:   ${prog} -s convert -f wiki.json -p project_name\n" +
                       "  Annotate:  ${prog} -s annotate -p project_name -b 1000 -t 8\n" +
                       "  Index:     ${prog} -s index -p project_name -y all\n" +
                       "  Analyze:   ${prog} -s analyze -g pipeline.log -o reports\n" +
                       "  All:       ${prog} -s all -f wiki.json -p project_name");

        // Common arguments group
        var commonGroup = parser.addArgumentGroup("Common arguments");
        commonGroup.addArgument("-s", "--stage")
                .choices("all", "convert", "annotate", "index", "analyze")
                .setDefault("all")
                .help("Pipeline stage to run:\n" +
                      "  all      - Run all stages (conversion, annotation, indexing)\n" +
                      "  convert  - Convert Wikipedia dump to SQLite database\n" +
                      "  annotate - Add linguistic annotations to documents\n" +
                      "  index    - Generate searchable indexes\n" +
                      "  analyze  - Analyze processing logs");

        commonGroup.addArgument("-p", "--project")
                .setDefault(DEFAULT_PROJECT)
                .help("Project name for organizing indexes and database (default: '" + DEFAULT_PROJECT + "')");

        commonGroup.addArgument("-d", "--db")
                .required(false)
                .help("Path to SQLite database file (optional: auto-generated within project directory)");

        commonGroup.addArgument("--debug")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Enable detailed debug logging to console");

        commonGroup.addArgument("-l", "--limit")
                .type(Integer.class)
                .help("Maximum documents to process in ANY stage (applies globally)");

        // Conversion stage group
        var convertGroup = parser.addArgumentGroup("Conversion stage arguments (required for 'convert' stage)");
        convertGroup.addArgument("-f", "--file")
                .help("Path to Wikipedia dump file");

        convertGroup.addArgument("-r", "--recreate")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Drop and recreate the documents table if it exists");

        // Annotation stage group
        var annotateGroup = parser.addArgumentGroup("Annotation stage arguments (used in 'annotate' stage)");
        annotateGroup.addArgument("-b", "--batch-size")
                .setDefault(1000)
                .type(Integer.class)
                .help("Number of documents to process in each batch (default: 1000)");

        annotateGroup.addArgument("-t", "--threads")
                .setDefault(8)
                .type(Integer.class)
                .help("Number of parallel threads for CoreNLP processing (default: 8)");

        // Index stage group
        var indexGroup = parser.addArgumentGroup("Index stage arguments (used in 'index' stage)");
        indexGroup.addArgument("-i", "--index-dir")
                .help("Directory for storing generated indexes (optional: auto-generated within project directory)");

        indexGroup.addArgument("-w", "--stopwords")
                .setDefault("stopwords.txt")
                .help("Path to file containing stopwords to exclude (default: stopwords.txt)");

        indexGroup.addArgument("-y", "--index-type")
                .choices("unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos", "hypernym", "stitch", "all")
                .setDefault("all")
                .help("Type of index to generate:\n" +
                      "  unigram    - Single word index\n" +
                      "  bigram     - Two word phrases\n" +
                      "  trigram    - Three word phrases\n" +
                      "  dependency - Grammatical dependencies\n" +
                      "  ner_date   - Named entity dates\n" +
                      "  ner        - Named entity recognition\n" +
                      "  pos        - Part-of-speech tagging\n" +
                      "  hypernym   - Word hypernyms\n" +
                      "  stitch     - Connects unigrams with their associated dates\n" +
                      "  all        - Generate all index types (default)");

        indexGroup.addArgument("-k", "--preserve-index")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Keep existing index data instead of regenerating");

        // Analysis stage group
        var analysisGroup = parser.addArgumentGroup("Analysis stage arguments (required for 'analyze' stage)");
        analysisGroup.addArgument("-g", "--log-file")
                .help("Path to log file to analyze");

        analysisGroup.addArgument("-o", "--report-dir")
                .setDefault("reports")
                .help("Directory for storing analysis reports (default: 'reports')");

        analysisGroup.addArgument("-m", "--report-format")
                .choices("text", "html", "both")
                .setDefault("both")
                .help("Format for analysis reports:\n" +
                      "  text - Plain text report\n" +
                      "  html - HTML formatted report\n" +
                      "  both - Generate both formats (default)");

        // Parse arguments
        Namespace ns = parser.parseArgs(args);
        
        // Set debug mode
        if (ns.getBoolean("debug")) {
            System.setProperty("DEBUG_MODE", "true");
            logger.info("DEBUG mode enabled via command line.");
        }

        String stage = ns.getString("stage");
        String projectName = ns.getString("project");
        String dbPathStr = ns.getString("db");
        String wikiDumpPath = ns.getString("file");
        String indexDirStr = ns.getString("index_dir");
        
        // Create project directories and resolve paths
        Path projectBasePath = setupProjectDirectories(projectName);
        
        // Resolve paths: Use explicit if provided, otherwise default within project dir
        Path dbPath = (dbPathStr != null) ? Path.of(dbPathStr) : projectBasePath.resolve(projectName + ".db");
        Path indexDir = (indexDirStr != null) ? Path.of(indexDirStr) : projectBasePath;
        
        logger.info("Starting Pipeline for project '{}' (Stage: {})", projectName, stage);
        logger.info("Using Database: {}", dbPath.toAbsolutePath());
        logger.info("Using Index Directory: {}", indexDir.toAbsolutePath());

        // Validate required arguments based on stage
        if (stage.equals("convert") || stage.equals("all")) {
            if (wikiDumpPath == null) {
                throw new ArgumentParserException("--file is required for conversion stage", parser);
            }
        }

        if (stage.equals("analyze") && ns.getString("log_file") == null) {
            throw new ArgumentParserException("--log-file is required for analysis stage", parser);
        }

        // Run selected pipeline stages
        if (stage.equals("all") || stage.equals("convert")) {
            logger.info("Running conversion stage...");
            WikiJsonToSqlite.ExtractionResult result = WikiJsonToSqlite.extractToSqlite(
                Path.of(wikiDumpPath),
                dbPath,
                stage.equals("all") || ns.getBoolean("recreate"),
                ns.getInt("limit")
            );
            logger.info("Conversion complete. {} entries added to database: {}",
                result.totalEntries, result.outputDb);
        }

        if (stage.equals("all") || stage.equals("annotate")) {
            logger.info("Running annotation stage...");
            // Build command arguments list
            List<String> annotationArgs = new ArrayList<>();
            annotationArgs.add("-d");
            annotationArgs.add(dbPath.toString());
            annotationArgs.add("-t");
            annotationArgs.add(ns.getInt("threads").toString());
            
            // Add global limit if specified
            Integer limit = ns.getInt("limit");
            if (limit != null) {
                annotationArgs.add("-l");
                annotationArgs.add(limit.toString());
            }
            
            // Add overwrite flag if specified or if we're running the 'all' stage
            if (ns.getBoolean("recreate") || stage.equals("all")) {
                annotationArgs.add("-o");
            }
            
            Annotations.main(annotationArgs.toArray(new String[0]));
        }

        if (stage.equals("all") || stage.equals("index")) {
            logger.info("Running indexing stage...");
            IndexRunner.runIndexing(
                    dbPath.toString(),
                    indexDir.toString(),
                    ns.getString("stopwords"),
                    ns.getInt("batch_size"),
                    ns.getString("index_type"),
                    ns.getBoolean("preserve_index"),
                    ns.getInt("limit"));
        }

        if (stage.equals("analyze")) {
            logger.info("Running log analysis...");
            runAnalysis(
                ns.getString("log_file"),
                ns.getString("report_dir"),
                ns.getString("report_format"));
        }

        logger.info("Pipeline completed successfully!");
    }

    private static void runAnalysis(String logFile, String reportDir, String format) throws Exception {
        // Create report directory if it doesn't exist
        Path reportPath = Path.of(reportDir);
        if (!reportPath.toFile().exists()) {
            reportPath.toFile().mkdirs();
        }

        // Analyze logs
        LogAnalyzer analyzer = new LogAnalyzer();
        Map<String, Object> results = analyzer.analyzeLogs(Path.of(logFile));

        // Generate reports
        LogSummarizer summarizer = new LogSummarizer();
        if (format.equals("text") || format.equals("both")) {
            Path textReport = reportPath.resolve("analysis_report.txt");
            summarizer.generateReport(results, textReport, "text");
            logger.info("Generated text report: {}", textReport);
        }
        
        if (format.equals("html") || format.equals("both")) {
            Path htmlReport = reportPath.resolve("analysis_report.html");
            summarizer.generateReport(results, htmlReport, "html");
            logger.info("Generated HTML report: {}", htmlReport);
        }

        // Print summary to console
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) results.get("processing_summary");
        logger.info("\nAnalysis Summary:");
        logger.info("----------------");
        logger.info("Total Documents Processed: {}", summary.get("total_documents_processed"));
        logger.info("Total N-grams Generated: {}", summary.get("total_ngrams_generated"));
        if (summary.containsKey("avg_ngrams_per_document")) {
            logger.info("Average N-grams per Document: {}", 
                summary.get("avg_ngrams_per_document"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) results.get("error_patterns");
        logger.info("Total Errors: {}", errors.get("total_errors"));
    }

    /**
     * Sets up the project directory structure and returns the base path
     * @param projectName Name of the project
     * @return Path to the project directory
     * @throws IOException If directory creation fails
     */
    private static Path setupProjectDirectories(String projectName) throws IOException {
        // Create main project structure
        Path currentDir = Path.of(System.getProperty("user.dir"));
        Path indexesDir = currentDir.resolve("indexes");
        Path projectDir = indexesDir.resolve(projectName);
        
        // Ensure indexes directory exists
        if (!Files.exists(indexesDir)) {
            Files.createDirectories(indexesDir);
        }
        
        // Ensure project directory exists
        if (!Files.exists(projectDir)) {
            Files.createDirectories(projectDir);
        }
        
        return projectDir;
    }
}
