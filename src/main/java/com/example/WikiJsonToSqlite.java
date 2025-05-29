package com.example;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * Standalone tool to convert Wikipedia JSON dumps (in Elasticsearch bulk format)
 * into an SQLite database suitable for input into the NLP Pipeline (`Pipeline.java`).
 *
 * <p>This serves as an example converter. Users processing different data sources
 * should create similar dedicated converters.</p>
 *
 * <p>Input Format: Expects a JSON file where each line is a JSON object,
 * typically obtained from Wikimedia CirrusSearch dumps:
 * <a href="https://dumps.wikimedia.org/other/cirrussearch/">https://dumps.wikimedia.org/other/cirrussearch/</a>.
 * The tool extracts 'title', 'text', and 'timestamp' fields from objects containing text.</p>
 *
 * <p>Output Schema: Creates an SQLite database with a 'documents' table containing:
 * <ul>
 *   <li>document_id INTEGER PRIMARY KEY</li>
 *   <li>title TEXT</li>
 *   <li>text TEXT</li>
 *   <li>timestamp TEXT</li>
 * </ul></p>
 */
public class WikiJsonToSqlite {
    private static final Logger logger = LoggerFactory.getLogger(WikiJsonToSqlite.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static class ExtractionResult {
        public final Path outputDb;
        public final long totalEntries;

        public ExtractionResult(Path outputDb, long totalEntries) {
            this.outputDb = outputDb;
            this.totalEntries = totalEntries;
        }
    }

    /**
     * Extract Wikipedia JSON dump to SQLite database
     * @param inputFile Path to input JSON file
     * @param outputDbPath Output database path
     * @param recreate Whether to recreate the table
     * @param limit Maximum number of entries to extract
     * @return Extraction result with output path and count
     * @throws SQLException If database operations fail
     * @throws IOException If file reading fails
     */
    public static ExtractionResult extractToSqlite(Path inputFile, Path outputDbPath, boolean recreate, Integer limit) throws SQLException, IOException {
        // Generate output database name based on input file if not specified
        Path outputDb = outputDbPath;
        if (outputDb == null) {
            outputDb = inputFile.resolveSibling(inputFile.getFileName().toString().replaceFirst("[.][^.]+$", ".db"));
        }

        // 1. Check input file readability first
        if (!Files.isReadable(inputFile)) {
            throw new FileNotFoundException("Input file not found or not readable: " + inputFile.toAbsolutePath());
        }

        // 2. Ensure parent directory for the output database exists
        if (outputDb.getParent() != null) {
            Files.createDirectories(outputDb.getParent());
        }

        // Removed the initial line counting block for performance with large files.
        // Progress will be based on processed entries or the specified limit.
        logger.info("Starting conversion for input file {}{}", inputFile,
            limit != null ? String.format(" (will process up to %d entries)", limit) : " (processing all entries)");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + outputDb.toString())) {
            // Enable WAL mode and other optimizations for better performance
            try (Statement pragma = conn.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA synchronous=NORMAL");
                pragma.execute("PRAGMA temp_store=MEMORY");
                pragma.execute("PRAGMA cache_size=-2000"); // Use 2GB cache
            }

            try (Statement stmt = conn.createStatement()) {
                if (recreate) {
                    stmt.execute("DROP TABLE IF EXISTS documents");
                }
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS documents (
                        document_id INTEGER PRIMARY KEY,
                        title TEXT,
                        text TEXT,
                        timestamp TEXT
                    )
                """);
            }

            conn.setAutoCommit(false);
            long totalEntries = 0;
            long lineCount = 0;

            String insertSql = "INSERT INTO documents (title, text, timestamp) VALUES (?, ?, ?)";

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql);
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new FileInputStream(inputFile.toFile()), StandardCharsets.UTF_8));
                 ProgressBar pb = new ProgressBarBuilder()
                         .setTaskName("Converting Wiki Dump")
                         .setInitialMax(limit != null ? (long)limit : -1) // Use limit or indeterminate progress
                         .build())
            {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonNode item = objectMapper.readTree(line);

                        // Skip the index information object
                        if (item.has("_type") && item.get("_type").asText().equals("_doc")) {
                            continue;
                        }

                        if (item.has("text")) {
                            pstmt.setString(1, getTextValue(item, "title"));
                            pstmt.setString(2, getTextValue(item, "text"));
                            pstmt.setString(3, getTextValue(item, "timestamp"));
                            pstmt.addBatch();
                            totalEntries++;
                            pb.step();

                            // Check if we've hit the limit
                            if (limit != null && totalEntries >= limit) {
                                // Execute final batch and break
                                pstmt.executeBatch();
                                conn.commit();
                                logger.info("Reached limit of {} entries", limit);
                                break;
                            }
                        }

                        if (++lineCount % 100_000 == 0) {  // Reduced batch size for more frequent updates
                            pstmt.executeBatch();
                            conn.commit();
                            logger.debug("Processed {} lines, {} entries added", lineCount, totalEntries);
                        }

                    } catch (Exception e) {
                        logger.error("Error processing line {}: {}", lineCount + 1, e.getMessage());
                    }
                }

                // Insert any remaining batch
                pstmt.executeBatch();
                conn.commit();
                logger.info("Completed processing {} lines, total {} entries added", lineCount, totalEntries);
            }

            return new ExtractionResult(outputDb, totalEntries);
        }
    }

    private static String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    public static void main(String[] args) {
        // Parse command line arguments
        ArgumentParser parser = ArgumentParsers.newFor("WikiJsonToSqlite").build()
                .defaultHelp(true)
                .description("Convert Wikipedia JSON dump to SQLite database");

        parser.addArgument("-f", "--file")
                .required(true)
                .help("Input JSON file path");

        parser.addArgument("-d", "--db")
                .required(true)
                .help("Output SQLite database file path");

        parser.addArgument("-r", "--recreate")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Drop and recreate the documents table if it exists");

        parser.addArgument("-l", "--limit")
                .type(Integer.class)
                .help("Maximum number of entries to extract");

        try {
            Namespace ns = parser.parseArgs(args);
            Path inputFile = Path.of(ns.getString("file"));
            Path outputDb = Path.of(ns.getString("db")); // Now required
            boolean recreate = ns.getBoolean("recreate");
            Integer limit = ns.getInt("limit");

            ExtractionResult result = extractToSqlite(inputFile, outputDb, recreate, limit);
            System.out.printf("Extraction complete. %d entries added to database: %s%n",
                    result.totalEntries, result.outputDb);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error extracting data: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}