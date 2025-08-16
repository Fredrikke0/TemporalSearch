package com.example;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.nyt.NYTCorpusDocument;
import com.example.nyt.NYTCorpusDocumentParser;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

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
    private static final SimpleDateFormat ISO_8601_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    static {
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
     * @param globalLimit Maximum number of articles (XML files) to extract in total. This limit will be distributed among available years.
     * @return Extraction result with output path and counts.
     * @throws SQLException If database operations fail.
     * @throws IOException If file/archive reading fails.
     */
    public static ExtractionResult extractToSqlite(Path inputDir, Path outputDbPath, boolean recreate, Integer globalLimit)
            throws SQLException, IOException {

        if (!Files.isDirectory(inputDir)) {
            throw new IOException("Input path is not a valid directory: " + inputDir);
        }

        logger.info("Starting NYT Corpus extraction...");
        logger.info("Input directory: {}", inputDir.toAbsolutePath());
        logger.info("Output database: {}", outputDbPath.toAbsolutePath());
        logger.info("Recreate table: {}", recreate);
        logger.info("Global article limit: {}", globalLimit == null ? "none (process all)" : globalLimit);

        // Count total XML files first for progress bar (best effort)
        long estimatedXmlFiles = countXmlFilesInArchives(inputDir);
        logger.info("Estimated total XML files across archives: {}", estimatedXmlFiles);
        long maxFilesToProcessProgressBar = (globalLimit != null && globalLimit < estimatedXmlFiles) ? globalLimit : estimatedXmlFiles; // For logging estimate

        List<Path> allArchivePathsInOrder;
        try (Stream<Path> stream = Files.list(inputDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".tar.gz"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))) { // Sort by filename (year)
            allArchivePathsInOrder = stream.collect(Collectors.toList());
        }

        if (allArchivePathsInOrder.isEmpty()) {
            logger.warn("No .tar.gz archives found in input directory: {}. Exiting.", inputDir);
            return new ExtractionResult(outputDbPath, 0, 0);
        }

        Map<String, Long> limitPerYearMap = new HashMap<>();
        Map<String, Long> processedCountPerYearMap = new HashMap<>();
        Set<String> availableYears = new TreeSet<>();

        for (Path archivePath : allArchivePathsInOrder) {
            String fileName = archivePath.getFileName().toString();
            // Assuming filenames like "1987.tar.gz"
            if (fileName.matches("\\d{4}\\.tar\\.gz")) {
                availableYears.add(fileName.substring(0, 4));
            }
        }

        if (globalLimit != null && !availableYears.isEmpty()) {
            logger.info("Distributing global limit of {} articles across {} available years: {}", globalLimit, availableYears.size(), availableYears);
            long baseLimitPerYear = globalLimit / availableYears.size();
            long remainder = globalLimit % availableYears.size();
            List<String> sortedYears = new ArrayList<>(availableYears); // Iterating in sorted order for consistent remainder distribution

            for (int i = 0; i < sortedYears.size(); i++) {
                String year = sortedYears.get(i);
                long yearSpecificLimit = baseLimitPerYear;
                if (i < remainder) { // Distribute remainder to the first few years
                    yearSpecificLimit++;
                }
                if (yearSpecificLimit > 0) {
                    limitPerYearMap.put(year, yearSpecificLimit);
                    processedCountPerYearMap.put(year, 0L); // Initialize processed count for this year
                    logger.info("Year {}: Will aim to process up to {} articles.", year, yearSpecificLimit);
                } else {
                    logger.info("Year {}: Will process 0 articles for this year due to limit distribution.", year);
                }
            }
        } else if (globalLimit != null) {
            logger.info("Global limit of {} articles will be applied without per-year distribution (no year-patterned archives found or none available).", globalLimit);
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + outputDbPath.toString())) {
            setupDatabase(conn, recreate);

            conn.setAutoCommit(false);
            AtomicLong totalEntriesAddedToDb = new AtomicLong(0);
            AtomicLong actualFilesProcessedCounter = new AtomicLong(0); // Counts files meeting criteria & attempted for parsing
            String insertSql = "INSERT INTO documents (title, text, timestamp) VALUES (?, ?, ?)";

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                NYTCorpusDocumentParser parser = new NYTCorpusDocumentParser(); // Ensure parser is declared
                AtomicBoolean globalLimitReachedSignal = new AtomicBoolean(false);

                for (Path archivePath : allArchivePathsInOrder) {
                    if (globalLimitReachedSignal.get()) {
                        logger.info("Global limit reached signal received, skipping remaining archives.");
                        break;
                    }

                    // Check global limit before processing a new archive
                    if (globalLimit != null && actualFilesProcessedCounter.get() >= globalLimit) {
                         logger.info("Global article limit ({}) reached before processing archive {}. Stopping.", globalLimit, archivePath.getFileName());
                         globalLimitReachedSignal.set(true);
                         break;
                    }

                    String archiveFileName = archivePath.getFileName().toString();
                    String currentArchiveYear = null;
                    if (archiveFileName.matches("\\d{4}\\.tar\\.gz")) {
                        currentArchiveYear = archiveFileName.substring(0, 4);
                    }

                    try { // Outer try for archive-level IO errors
                        logger.info("Processing archive: {}", archivePath.getFileName());
                        try (InputStream fis = Files.newInputStream(archivePath);
                             BufferedInputStream bis = new BufferedInputStream(fis);
                             GzipCompressorInputStream gis = new GzipCompressorInputStream(bis)) {

                            TarArchiveInputStream tis = new TarArchiveInputStream(gis);

                            TarArchiveEntry entry;
                            while ((entry = tis.getNextEntry()) != null) {
                                if (globalLimitReachedSignal.get()) break; // Check if global limit was hit in a previous iteration

                                // 1. Check global limit: Have we processed enough files overall?
                                if (globalLimit != null && actualFilesProcessedCounter.get() >= globalLimit) {
                                    logger.info("Global article limit ({}) reached. Halting further processing.", globalLimit);
                                    globalLimitReachedSignal.set(true);
                                    break; // Break inner while loop
                                }

                                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".xml")) {
                                    continue; // Skip non-XML files or directories
                                }
                                // At this point, 'entry' is an XML file candidate.

                                // 2. Check year-specific limit (if globalLimit was set and this archive has a year):
                                if (globalLimit != null && currentArchiveYear != null && limitPerYearMap.containsKey(currentArchiveYear)) {
                                    long yearTarget = limitPerYearMap.get(currentArchiveYear);
                                    long yearProcessed = processedCountPerYearMap.getOrDefault(currentArchiveYear, 0L);
                                    if (yearProcessed >= yearTarget) {
                                        logger.debug("Year {} target of {} met for archive {}. Skipping further XMLs from this specific archive.",
                                                   currentArchiveYear, yearTarget, archivePath.getFileName());
                                        break; // Stop processing XML entries from *this archive* for this year.
                                    }
                                }

                                // If we are here, we will attempt to process this file.
                                actualFilesProcessedCounter.incrementAndGet();
                                if (globalLimit != null && currentArchiveYear != null && processedCountPerYearMap.containsKey(currentArchiveYear)) {
                                    processedCountPerYearMap.computeIfPresent(currentArchiveYear, (k, v) -> v + 1);
                                }

                                String entryName = entry.getName(); // Get name once
                                if (actualFilesProcessedCounter.get() <= 50) {
                                     logger.debug("Processing entry: Name='{}'", entryName);
                                }

                                try (InputStream shieldedTis = CloseShieldInputStream.wrap(tis)) { // Shield TIS for this entry
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
                                            long currentDbEntries = totalEntriesAddedToDb.incrementAndGet();

                                            if (currentDbEntries % 100000 == 0) { // Commit batch
                                                pstmt.executeBatch();
                                                conn.commit();
                                                logger.debug("Committed batch. Total DB entries: {}", currentDbEntries);
                                            }
                                        } else {
                                            // Log skipped entries (missing title/text)
                                            if (actualFilesProcessedCounter.get() <= 100) { // Log details for early skips
                                                 logger.warn("Skipping entry due to missing title/text. Entry: {}. Title Blank: {}, Text Blank: {}",
                                                            entry.getName(),
                                                            (title == null || title.isBlank()),
                                                            (text == null || text.isBlank()));
                                                logger.warn("Skipped Document Content Hint (first 500 chars): {}", doc.toString().substring(0, Math.min(500, doc.toString().length())));
                                            }
                                        }
                                    } else {
                                        logger.warn("Parser returned null for entry: {}", entry.getName());
                                    }
                                } catch (Exception e) { // Catch parsing errors for a specific entry
                                    logger.error("Error parsing XML entry {} in archive {}: {}",
                                            entry.getName(), archivePath.getFileName(), e.getMessage(), e);
                                }
                            } // end while entries
                        } catch (IOException e) {
                            logger.error("Error reading archive {}: {}", archivePath.getFileName(), e.getMessage(), e);
                        }
                    } catch (Exception e) { // Catch any other exception during archive processing
                        logger.error("Unhandled exception processing archive {}: {}", archivePath.getFileName(), e.getMessage(), e);
                    }
                } // end for each archivePath

                pstmt.executeBatch();
                conn.commit();

                if (!processedCountPerYearMap.isEmpty()) {
                    logger.info("--- Per-Year Processing Summary ---");
                    processedCountPerYearMap.forEach((year, count) ->
                        logger.info("Year {}: {} articles processed (target: {}).",
                                    year, count, limitPerYearMap.getOrDefault(year, -1L) == -1L ? "N/A (no global limit or year not targeted)" : limitPerYearMap.get(year).toString())
                    );
                    logger.info("-----------------------------------");
                }

                logger.info("Completed NYT Corpus extraction.");
                logger.info("Total XML files meeting criteria and processed: {}", actualFilesProcessedCounter.get());
                logger.info("Total entries added to database: {}", totalEntriesAddedToDb.get());

                return new ExtractionResult(outputDbPath, totalEntriesAddedToDb.get(), actualFilesProcessedCounter.get());

            }

        }
    }

    private static void setupDatabase(Connection conn, boolean recreate) throws SQLException {
        try (Statement pragma = conn.createStatement()) {
            pragma.execute("PRAGMA journal_mode=WAL");
            pragma.execute("PRAGMA synchronous=NORMAL");
            pragma.execute("PRAGMA temp_store=MEMORY");
            pragma.execute("PRAGMA cache_size=-200000");
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

    private static long countXmlFilesInArchives(Path inputDir) {
        AtomicLong count = new AtomicLong(0);
        try (Stream<Path> archiveFiles = Files.list(inputDir).filter(p -> p.toString().toLowerCase().endsWith(".tar.gz"))) {
            archiveFiles.forEach(archivePath -> {
                try (InputStream fis = Files.newInputStream(archivePath);
                     BufferedInputStream bis = new BufferedInputStream(fis);
                     GzipCompressorInputStream gis = new GzipCompressorInputStream(bis);
                     TarArchiveInputStream tis = new TarArchiveInputStream(gis)) {

                    TarArchiveEntry entry;
                    while ((entry = tis.getNextEntry()) != null) {
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
            Integer globalLimit = ns.getInt("limit");

            ExtractionResult result = extractToSqlite(inputDir, outputDb, recreate, globalLimit);
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