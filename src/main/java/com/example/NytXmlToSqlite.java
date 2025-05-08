package com.example;

import com.example.nyt.NYTCorpusDocument;
import com.example.nyt.NYTCorpusDocumentParser;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.io.input.CloseShieldInputStream;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Standalone tool to convert New York Times Corpus XML dumps (within .tar.gz archives)
 * into an SQLite database suitable for input into the NLP Pipeline (`Pipeline.java`).
 *
 * <p>Input Format: Expects a directory containing .tar.gz files.
 * Each archive should contain NITF XML files as described in the NYT Corpus documentation.
 * The tool extracts 'headline' (as title), 'body', and 'publicationDate' (as timestamp).</p>
 *
 * <p>Output Schema: Creates an SQLite database with a 'documents' table containing:
 * <ul>
 *   <li>document_id INTEGER PRIMARY KEY</li>
 *   <li>title TEXT</li>
 *   <li>text TEXT</li>
 *   <li>timestamp TEXT (ISO 8601 format: yyyy-MM-ddTHH:mm:ss)</li>
 * </ul></p>
 */
public class NytXmlToSqlite {
    private static final Logger logger = LoggerFactory.getLogger(NytXmlToSqlite.class);
    // ISO 8601 format for timestamps
    private static final SimpleDateFormat ISO_8601_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    static {
        // Ensure UTC timezone for date formatting
        ISO_8601_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public static class ExtractionResult {
        public final Path outputDb;
        public final long totalEntries;
        public final long totalFilesProcessed;

        public ExtractionResult(Path outputDb, long totalEntries, long totalFilesProcessed) {
            this.outputDb = outputDb;
            this.totalEntries = totalEntries;
            this.totalFilesProcessed = totalFilesProcessed;
        }
    }

    /**
     * Extract NYT Corpus archives to SQLite database.
     *
     * @param inputDir Path to directory containing .tar.gz files.
     * @param outputDbPath Path for the output SQLite database file.
     * @param recreate Whether to recreate the table if it exists.
     * @param limit Maximum number of articles (XML files) to extract.
     * @return Extraction result with output path and counts.
     * @throws SQLException If database operations fail.
     * @throws IOException If file/archive reading fails.
     */
    public static ExtractionResult extractToSqlite(Path inputDir, Path outputDbPath, boolean recreate, Integer limit)
            throws SQLException, IOException {

        if (!Files.isDirectory(inputDir)) {
            throw new IOException("Input path is not a valid directory: " + inputDir);
        }

        logger.info("Starting NYT Corpus extraction...");
        logger.info("Input directory: {}", inputDir.toAbsolutePath());
        logger.info("Output database: {}", outputDbPath.toAbsolutePath());
        logger.info("Recreate table: {}", recreate);
        logger.info("Limit: {}", limit == null ? "none" : limit);

        // Count total XML files first for progress bar (best effort)
        long estimatedXmlFiles = countXmlFilesInArchives(inputDir);
        logger.info("Estimated total XML files across archives: {}", estimatedXmlFiles);
        long maxFilesToProcess = (limit != null && limit < estimatedXmlFiles) ? limit : estimatedXmlFiles;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + outputDbPath.toString())) {
            setupDatabase(conn, recreate);

            conn.setAutoCommit(false);
            long totalEntriesAdded = 0;
            long totalFilesProcessed = 0;
            String insertSql = "INSERT INTO documents (title, text, timestamp) VALUES (?, ?, ?)";

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {

                NYTCorpusDocumentParser parser = new NYTCorpusDocumentParser();

                try (Stream<Path> archiveFiles = Files.list(inputDir).filter(p -> p.toString().toLowerCase().endsWith(".tar.gz"))) {
                    // Process the stream directly using forEachOrdered to maintain processing order if needed
                    // Using AtomicBoolean/Long for exception handling and counts within lambda
                    AtomicBoolean errorOccurred = new AtomicBoolean(false);
                    AtomicLong currentTotalEntries = new AtomicLong(totalEntriesAdded);
                    AtomicLong currentFilesProcessed = new AtomicLong(totalFilesProcessed);

                    archiveFiles.forEachOrdered(archivePath -> {
                        if (errorOccurred.get()) return; // Stop processing if an error happened in a previous archive
                        if (limit != null && currentFilesProcessed.get() >= limit) return; // Stop if limit reached

                        logger.info("Processing archive: {}", archivePath.getFileName());
                        try (InputStream fis = Files.newInputStream(archivePath);
                             BufferedInputStream bis = new BufferedInputStream(fis);
                             GzipCompressorInputStream gis = new GzipCompressorInputStream(bis)) {

                            TarArchiveInputStream tis = new TarArchiveInputStream(gis);

                            TarArchiveEntry entry;
                            while ((entry = tis.getNextTarEntry()) != null) {
                                // Check limit again inside the inner loop
                                if (limit != null && currentFilesProcessed.get() >= limit) {
                                    logger.info("Reached processing limit of {} files.", limit);
                                    errorOccurred.set(true); // Use error flag to break outer stream processing
                                    break; // Break inner while loop
                                }

                                String entryName = entry.getName(); // Get name once
                                String entryNameLower = entryName.toLowerCase();
                                boolean isDirectory = entry.isDirectory();
                                boolean endsWithXml = entryNameLower.endsWith(".xml");
                                boolean endsWithOnelineXml = entryNameLower.endsWith(".xml-oneline.xml");

                                // DEBUG: Log details for every entry before the check
                                // Limit logging to first N files to avoid flooding
                                if (currentFilesProcessed.get() < 50) { // Log details for first 50 potential candidates encountered
                                     logger.debug("Checking entry: Name='{}', IsDir={}, EndsXml={}, EndsOnelineXml={}",
                                                  entryName, isDirectory, endsWithXml, endsWithOnelineXml);
                                }

                                // Process if it's a file and ends with .xml (including .xml-oneline.xml)
                                if (!isDirectory && endsWithXml) {
                                    currentFilesProcessed.incrementAndGet();

                                    try {
                                        InputStream shieldedTis = CloseShieldInputStream.wrap(tis);
                                        NYTCorpusDocument doc = parser.parseNYTCorpusDocumentFromInputStream(shieldedTis, false);

                                        if (doc != null) {
                                            String title = doc.getHeadline();
                                            String text = doc.getBody();
                                            String timestamp = (doc.getPublicationDate() != null)
                                                    ? ISO_8601_FORMAT.format(doc.getPublicationDate())
                                                    : null;

                                            if (title != null && !title.isBlank() && text != null && !text.isBlank()) {
                                                pstmt.setString(1, title);
                                                pstmt.setString(2, text);
                                                pstmt.setString(3, timestamp);
                                                pstmt.addBatch();
                                                long entries = currentTotalEntries.incrementAndGet();

                                                // Increased batch size
                                                if (entries % 100000 == 0) {
                                                    pstmt.executeBatch();
                                                    conn.commit();
                                                    logger.debug("Committed batch. Total entries added: {}", entries);
                                                }
                                            } else {
                                                // Log detailed info only for the first few skipped entries
                                                if (currentFilesProcessed.get() <= 100 && (title == null || title.isBlank() || text == null || text.isBlank())) {
                                                     logger.warn("Skipping entry due to missing title/text. Entry: {}. Title Blank: {}, Text Blank: {}", 
                                                                entry.getName(), 
                                                                (title == null || title.isBlank()), 
                                                                (text == null || text.isBlank()));
                                                    logger.warn("Skipped Document Content Hint: {}", doc.toString().substring(0, Math.min(500, doc.toString().length())));
                                                }
                                            }
                                        } else {
                                            logger.error("Parser returned null for entry: {}", entry.getName());
                                        }
                                    } catch (Exception e) {
                                        logger.error("Error processing entry {} in archive {}: {}",
                                                entry.getName(), archivePath.getFileName(), e.getMessage(), e);
                                        errorOccurred.set(true); // Stop processing on inner error
                                        break; // Break inner while loop
                                    }
                                } // end if xml file
                            } // end while entries
                        } catch (IOException e) {
                            logger.error("Error reading archive {}: {}", archivePath.getFileName(), e.getMessage(), e);
                            errorOccurred.set(true); // Signal error to stop stream processing
                        }
                    }); // end forEachOrdered

                    // Update the main counters after stream processing
                    totalEntriesAdded = currentTotalEntries.get();
                    totalFilesProcessed = currentFilesProcessed.get();

                } // end try-with-resources for file listing

                // Final batch commit
                pstmt.executeBatch();
                conn.commit();

            } // end try-with-resources for PreparedStatement

            // Add debug logging here
            logger.debug("DEBUG: Just before final logs - totalFilesProcessed = {}", totalFilesProcessed);
            logger.debug("DEBUG: Just before final logs - totalEntriesAdded = {}", totalEntriesAdded);

            logger.info("Completed NYT Corpus extraction.");
            logger.info("Total XML files processed: {}", totalFilesProcessed);
            logger.info("Total entries added to database: {}", totalEntriesAdded);

            return new ExtractionResult(outputDbPath, totalEntriesAdded, totalFilesProcessed);

        } // end try-with-resources for Connection
    }

    private static void setupDatabase(Connection conn, boolean recreate) throws SQLException {
        // Enable WAL mode and other optimizations for better performance
        try (Statement pragma = conn.createStatement()) {
            pragma.execute("PRAGMA journal_mode=WAL");
            pragma.execute("PRAGMA synchronous=NORMAL");
            pragma.execute("PRAGMA temp_store=MEMORY");
            pragma.execute("PRAGMA cache_size=-200000"); // Use 200MB cache (adjust as needed)
            logger.debug("Enabled SQLite PRAGMAs (WAL, NORMAL, MEMORY temp, 200MB cache).");
        }

        try (Statement stmt = conn.createStatement()) {
            if (recreate) {
                stmt.execute("DROP TABLE IF EXISTS documents");
                logger.info("Dropped existing 'documents' table.");
            }
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    document_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT,
                    text TEXT,
                    timestamp TEXT
                )
            """);
            logger.info("Ensured 'documents' table exists with schema (document_id, title, text, timestamp).");
        }
    }

    // Helper to estimate total XML files for the progress bar
    private static long countXmlFilesInArchives(Path inputDir) {
        AtomicLong count = new AtomicLong(0);
        try (Stream<Path> archiveFiles = Files.list(inputDir).filter(p -> p.toString().toLowerCase().endsWith(".tar.gz"))) {
            archiveFiles.forEach(archivePath -> {
                try (InputStream fis = Files.newInputStream(archivePath);
                     BufferedInputStream bis = new BufferedInputStream(fis);
                     GzipCompressorInputStream gis = new GzipCompressorInputStream(bis);
                     TarArchiveInputStream tis = new TarArchiveInputStream(gis)) {

                    TarArchiveEntry entry;
                    while ((entry = tis.getNextTarEntry()) != null) {
                        if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".xml")) {
                            count.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    logger.warn("Could not read archive for counting: {} - {}", archivePath.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.error("Could not list archive files for counting in {}: {}", inputDir, e.getMessage());
        }
        return count.get();
    }

    public static void main(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("NytXmlToSqlite").build()
                .defaultHelp(true)
                .description("Convert NYT Corpus XML archives (.tar.gz) to SQLite database.");

        parser.addArgument("-i", "--input-dir")
                .required(true)
                .help("Input directory containing NYT Corpus .tar.gz archives");

        parser.addArgument("-o", "--output-db")
                .required(true)
                .help("Output SQLite database file path");

        parser.addArgument("-r", "--recreate")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Drop and recreate the documents table if it exists");

        parser.addArgument("-l", "--limit")
                .type(Integer.class)
                .help("Maximum number of XML articles to process");

        try {
            Namespace ns = parser.parseArgs(args);
            Path inputDir = Paths.get(ns.getString("input_dir"));
            Path outputDb = Paths.get(ns.getString("output_db"));
            boolean recreate = ns.getBoolean("recreate");
            Integer limit = ns.getInt("limit");

            ExtractionResult result = extractToSqlite(inputDir, outputDb, recreate, limit);
            System.out.printf("Extraction complete. %d files processed, %d entries added to database: %s%n",
                    result.totalFilesProcessed, result.totalEntries, result.outputDb.toAbsolutePath());

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