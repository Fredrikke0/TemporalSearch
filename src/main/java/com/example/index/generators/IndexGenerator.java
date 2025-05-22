package com.example.index.generators;

import com.google.common.collect.ListMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.logging.ProgressTracker;
import com.example.logging.IndexingMetrics;
import com.google.code.externalsorting.ExternalSort;
import com.example.core.PositionList;
import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import org.iq80.leveldb.Options;

import com.example.index.IndexEntry;
import com.example.index.LevelDBConfig;
import org.iq80.leveldb.WriteBatch;

import java.io.*;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/**
 * Abstract base class for streaming index generation that processes large datasets efficiently
 * while maintaining bounded memory usage. Uses external sorting for scalable processing.
 *
 * @param <T> The type of index entry this generator processes
 */
public abstract class IndexGenerator<T extends IndexEntry> implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(IndexGenerator.class);
    public static final String DELIMITER = "\0";
    public static final String TEMP_SUBDIR_NAME = "temp-sort-files";
    public static final char ESCAPE_CHAR = '\u001F';

    protected final IndexAccess indexAccess;
    protected final Connection sqliteConn;
    protected Set<String> stopwords;
    protected final ProgressTracker progress;
    private final Path tempDir;
    private long totalNGramsGenerated = 0;
    protected final int batchSize;

    /**
     * Gets the name of the table to query for entries.
     * @return The table name
     */
    protected abstract String getTableName();

    /**
     * Gets the name of this index for progress tracking and logging.
     * @return The name of the index
     */
    protected abstract String getIndexName();

    /**
     * Fetches a batch of entries from the database.
     * @param lastEntryFromPreviousBatch The last entry processed in the previous overall batch, used for pagination.
     *                                   Null if this is the first batch.
     * @return A list of entries.
     * @throws SQLException if a database error occurs.
     */
    protected abstract List<T> fetchBatch(T lastEntryFromPreviousBatch) throws SQLException;

    /**
     * Processes a raw batch of entries into an intermediate, aggregated form suitable for writing to a temp file.
     * @param batch The list of entries fetched from the database.
     * @return A ListMultimap সেরা<String, PositionList>, where keys are terms and values are their positions.
     */
    protected abstract ListMultimap<String, PositionList> processBatch(List<T> batch);

    /**
     * Estimates or retrieves the total number of documents/items to be processed for this index.
     * This is used for progress tracking.
     * @return Total count of items.
     * @throws SQLException if a database error occurs.
     */
    public abstract long getDocumentCountForIndex() throws SQLException;

    // Slim constructor
    protected IndexGenerator(String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath, String indexNameForLogging) throws IOException {
        this.sqliteConn = sqliteConn;
        this.progress = progress;
        this.batchSize = batchSize;
        this.stopwords = loadStopwordsInternal(stopwordsPath);
        this.tempDir = initializeTempDir(indexNameForLogging, customTempPath);
        this.indexAccess = null; 
        logger.debug("IndexGenerator (slim constructor for [{}]) initialized. Temp dir: {}, Batch size: {}", indexNameForLogging, this.tempDir.toAbsolutePath(), this.batchSize);
        registerShutdownHook();
    }

    // Original full constructor (delegates to the one with customTempPath)
    protected IndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    // Original full constructor with customTempPath
    protected IndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        Options options = LevelDBConfig.createOptimizedOptions();
        try {
            this.indexAccess = new IndexAccess(Path.of(indexBaseDir), getIndexName(), options);
        } catch (IndexAccessException e) {
            throw new IOException("Failed to initialize IndexAccess for " + getIndexName(), e);
        }
        this.sqliteConn = sqliteConn;
        this.progress = progress;
        this.batchSize = batchSize;
        this.stopwords = loadStopwordsInternal(stopwordsPath);
        this.tempDir = initializeTempDir(getIndexName(), customTempPath); // Use getIndexName() here
        
        try {
            long totalDocs = getDocumentCountForIndex();
            this.progress.startIndex(getIndexName(), totalDocs);
        } catch (SQLException e) {
            throw new IOException("Failed to get document count for index: " + getIndexName(), e);
        }
        logger.debug("IndexGenerator for [{}] initialized. Temp dir: {}, Batch size: {}", getIndexName(), this.tempDir.toAbsolutePath(), this.batchSize);
        registerShutdownHook();
    }

    private Path initializeTempDir(String indexNameForTempDir, Path customTempPath) throws IOException {
        Path resolvedTempDir;
        if (customTempPath != null) {
            try {
                if (!Files.exists(customTempPath)) Files.createDirectories(customTempPath);
                if (!Files.isDirectory(customTempPath) || !Files.isWritable(customTempPath)) 
                    throw new IOException("Custom temporary path is not a writable directory: " + customTempPath);
                resolvedTempDir = Files.createTempDirectory(customTempPath, indexNameForTempDir + "-index-temp-");
                logger.info("IndexGenerator for [{}] using custom temp directory: {}", indexNameForTempDir, resolvedTempDir.toAbsolutePath());
            } catch (IOException e) {
                logger.warn("Failed to create or use custom temp directory '{}' for [{}]. Falling back to system default.", customTempPath, indexNameForTempDir, e);
                resolvedTempDir = Files.createTempDirectory(indexNameForTempDir + "-index-temp-");
                logger.info("IndexGenerator for [{}] using system default temp directory: {}", indexNameForTempDir, resolvedTempDir.toAbsolutePath());
            }
        } else {
            resolvedTempDir = Files.createTempDirectory(indexNameForTempDir + "-index-temp-");
            logger.info("IndexGenerator for [{}] using system default temp directory: {}", indexNameForTempDir, resolvedTempDir.toAbsolutePath());
        }
        return resolvedTempDir;
    }

    private Set<String> loadStopwordsInternal(String path) throws IOException { 
        if (path == null || path.trim().isEmpty()) {
            logger.warn("Stopwords path is null or empty. Proceeding without stopwords.");
            return Collections.emptySet();
        }
        Path filePath = Path.of(path);
        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            logger.warn("Stopwords file not found or not readable: {}. Proceeding without stopwords.", filePath.toAbsolutePath());
            return Collections.emptySet();
        }
        try {
            Set<String> loadedStopwords = new HashSet<>(Files.readAllLines(filePath, StandardCharsets.UTF_8));
            logger.info("Loaded {} stopwords from {}", loadedStopwords.size(), filePath.toAbsolutePath());
            return loadedStopwords;
        } catch (IOException e) {
            logger.error("Error loading stopwords from {}. Proceeding without stopwords.", filePath.toAbsolutePath(), e);
            // Still return empty set on error after logging, or rethrow. Current behavior is to proceed without.
            // Rethrowing to make failure explicit, consistent with original throw for this method.
            throw e; 
        }
    }

    private void registerShutdownHook() {
         Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (Files.exists(tempDir)) {
                    Files.walk(tempDir)
                         .sorted((a, b) -> b.compareTo(a))
                         .forEach(path -> {
                             try {
                                 Files.deleteIfExists(path);
                             } catch (IOException e) {
                                 logger.debug("Could not delete temporary file: {} ({})", path, e.getMessage());
                             }
                         });
                } else {
                    logger.debug("Temporary directory already cleaned up: {}", tempDir);
                }
            } catch (IOException e) {
                logger.debug("Failed to cleanup temporary directory: {} ({})", tempDir, e.getMessage());
            }
        }));
    }

    /**
     * Checks if a word is a stopword.
     * The input word is expected to be already lowercased by the caller.
     *
     * @param word The word to check (expected to be in lowercase).
     * @return True if the word is null or a stopword, false otherwise.
     */
    protected boolean isStopword(String word) {
        return word == null || stopwords.contains(word);
    }

    /**
     * Writes a batch of processed entries to a temporary file.
     * @param positions The processed position lists to write
     * @return The temporary file containing the sorted entries
     */
    protected File writeBatchToTempFile(ListMultimap<String, PositionList> positions) throws IOException {
        File tempFile = Files.createTempFile(tempDir, "batch-", ".tmp").toFile();
        // logger.info("Attempting to write batch to temp file: {}. Unique terms in batch: {}. Total PositionLists: {}",
        //    tempFile.getAbsolutePath(), positions.keySet().size(), positions.size());

        try {
            FileStore store = Files.getFileStore(tempDir);
            // logger.debug("Usable space before writing [{}]: {} MB",
            //     tempFile.getName(), store.getUsableSpace() / (1024 * 1024));
        } catch (IOException e) {
            logger.warn("Could not determine usable space before writing temp file [{}]: {}", tempFile.getName(), e.getMessage());
        }

        long bytesWrittenToFile = 0;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
            // Sort the entries by term (key) before writing to ensure each batch file is sorted.
            List<Map.Entry<String, Collection<PositionList>>> sortedEntries =
                new ArrayList<>(positions.asMap().entrySet());
            sortedEntries.sort(Map.Entry.comparingByKey());

            for (Map.Entry<String, Collection<PositionList>> entry : sortedEntries) {
                // Merge all position lists for this term within this batch
                PositionList mergedList = new PositionList();
                for (PositionList list : entry.getValue()) {
                    list.getPositions().forEach(mergedList::add);
                }
                
                String line = String.format("%s\t%s\n", 
                    entry.getKey(), 
                    Base64.getEncoder().encodeToString(mergedList.serialize()));
                if (line.length() > 10 * 1024 * 1024) { // Log if a single line is very large (e.g. >10MB)
                    logger.warn("Very large line being written to temp file {} for key '{}'. Line length: {} bytes",
                        tempFile.getName(), entry.getKey(), line.length());
                }
                writer.write(line);
                bytesWrittenToFile += line.getBytes(StandardCharsets.UTF_8).length; // Approximate byte count
            }
        } catch (IOException e) {
            logger.error("IOException while writing to temp file {}. Bytes written before error (approx): {}. Error: {}",
                tempFile.getAbsolutePath(), bytesWrittenToFile, e.getMessage(), e);
            throw e; // Re-throw the exception
        }
        // logger.info("Successfully wrote batch to temp file: {}. Final size: {} bytes",
        //     tempFile.getAbsolutePath(), tempFile.length());
        return tempFile;
    }

    /**
     * Writes the final merged and sorted entries to the index.
     * @param sortedFile The file containing the sorted entries
     */
    protected void writeToLevelDB(File sortedFile) throws IOException {
        long startTime = System.currentTimeMillis();
        totalNGramsGenerated = 0; // Reset count for this index generation
        WriteBatch batch = null;
        int batchCounter = 0;

        //String debugTerm = System.getProperty("debug.index.term", "shrek"); // Default to shrek if not set

        try (BufferedReader reader = new BufferedReader(new FileReader(sortedFile))) {
            String line;
            String currentTerm = null;
            PositionList mergedPositions = null;
            final int MAX_RETRIES = 3;
            final long RETRY_DELAY_MS = 1000;

            batch = indexAccess.createWriteBatch();

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) continue;
                String term = parts[0];
                PositionList positions = PositionList.deserialize(Base64.getDecoder().decode(parts[1]));

                // if (debugTerm.equals(term)) {
                //     logger.debug("Processing term '{}'. Current mergedPositions size: {}. New positions size: {}.",
                //         debugTerm, (mergedPositions != null ? mergedPositions.size() : "null"), positions.size());
                // }

                if (currentTerm == null) {
                    currentTerm = term;
                    mergedPositions = positions;
                    // if (debugTerm.equals(currentTerm)) {
                    //     logger.debug("Encountered '{}' for the first time in sorted file. Positions size: {}", debugTerm, mergedPositions.size());
                    // }
                    continue;
                }

                if (!currentTerm.equals(term)) {
                    // if (debugTerm.equals(currentTerm)) {
                    //     logger.debug("Finalizing '{}' before switching to term '{}'. Merged positions size: {}", debugTerm, term, mergedPositions.size());
                    // }
                    byte[] keyBytes = bytes(currentTerm);
                    byte[] valueBytes = mergedPositions.serialize();
                    batch.put(keyBytes, valueBytes);
                    batchCounter++;
                    totalNGramsGenerated++;

                    if (batchCounter >= LevelDBConfig.BATCH_SIZE) {
                        writeBatchWithRetry(batch, MAX_RETRIES, RETRY_DELAY_MS, batchCounter);
                        batch.close(); // Close old batch
                        batch = indexAccess.createWriteBatch(); // Start new batch
                        batchCounter = 0;
                    }

                    // if (totalNGramsGenerated % 100000 == 0) {
                    //     long elapsed = System.currentTimeMillis() - startTime;
                    //     logger.debug("Write progress: {} terms, {} terms/sec",
                    //         totalNGramsGenerated,
                    //         String.format("%.2f", totalNGramsGenerated * 1000.0 / elapsed));
                    // }

                    currentTerm = term;
                    mergedPositions = positions;
                    // if (debugTerm.equals(currentTerm)) {
                    //     logger.debug("Switched to new term '{}'. Initial positions size: {}", debugTerm, mergedPositions.size());
                    // }
                } else { // Same term as before, merge positions
                    // if (debugTerm.equals(currentTerm)) {
                    //     logger.debug("Merging additional positions for '{}'. Before merge size: {}. Adding {} positions.", debugTerm, mergedPositions.size(), positions.size());
                    // }
                    // The 'positions' object is the PositionList from the current line, for the same 'currentTerm'.
                    // Add all Position objects from the current line's 'positions' list to our 'mergedPositions' accumulator.
                    positions.getPositions().forEach(mergedPositions::add);
                    // if (debugTerm.equals(currentTerm)) {
                    //     logger.debug("After merge for '{}', new total positions: {}.", debugTerm, mergedPositions.size());
                    // }
                }
            }

            // Handle the last term
            if (currentTerm != null && mergedPositions != null) {
                // if (debugTerm.equals(currentTerm)) {
                //     logger.debug("Finalizing last term '{}'. Merged positions size: {}", debugTerm, mergedPositions.size());
                // }
                byte[] keyBytes = bytes(currentTerm);
                byte[] valueBytes = mergedPositions.serialize();
                batch.put(keyBytes, valueBytes);
                batchCounter++;
                totalNGramsGenerated++;
            }

            // Write any remaining entries in the last batch
            if (batchCounter > 0) {
                writeBatchWithRetry(batch, MAX_RETRIES, RETRY_DELAY_MS, batchCounter);
            }

            logger.info("Finished writing {} unique terms/keys to index [{}]", totalNGramsGenerated, getIndexName()); 
        } finally {
            if (batch != null) {
                try {
                    batch.close(); // Ensure batch is closed in case of exceptions
                } catch (IOException e) {
                    logger.warn("Failed to close write batch for index [{}]: {}", getIndexName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Attempts to write a batch to the index, retrying on specific failures.
     */
    private void writeBatchWithRetry(WriteBatch batch, int maxRetries, long delayMs, int numEntries) throws IOException {
        int attempt = 0;
        while (true) {
            try {
                indexAccess.write(batch);
                if (logger.isTraceEnabled()) {
                    logger.trace("Successfully wrote batch of {} entries to index [{}] on attempt {}", numEntries, getIndexName(), attempt + 1);
                }
                return; // Success
            } catch (IndexAccessException e) {
                attempt++;
                logger.warn("Attempt {}/{} failed to write batch of {} entries to index [{}]: {}. Error Type: {}",
                            attempt, maxRetries, numEntries, getIndexName(), e.getMessage(), e.getErrorType());

                if (attempt >= maxRetries || e.getErrorType() == IndexAccessException.ErrorType.INITIALIZATION_ERROR) {
                    // Log the final failure with more detail before throwing
                    logger.error("Failed to write batch of {} entries to index [{}] after {} attempts. Giving up.", numEntries, getIndexName(), attempt, e);
                    throw new IOException("Failed to write batch of " + numEntries + " entries to index [" + getIndexName() + "] after " + attempt + " attempts", e);
                }
                try {
                    Thread.sleep(delayMs * attempt); // Simple backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry wait for batch write to index [" + getIndexName() + "]", ie);
                }
            }
        }
    }

    /**
     * Converts a string to UTF-8 bytes for index operations.
     */
    protected static byte[] bytes(String str) {
        return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Generates the index by processing documents in batches, sorting externally,
     * and merging to the final index store.
     */
    public void generateIndex() throws SQLException, IOException {
        List<File> tempFiles = new ArrayList<>();
        T lastProcessedEntry = null; // Keyset pagination: track last processed entry
        IndexingMetrics metrics = new IndexingMetrics();
        long totalRawEntriesFetched = 0;
        totalNGramsGenerated = 0; 

        try {
            long totalCountForProgressBar = getDocumentCountForIndex(); 
            progress.startIndex(getIndexName(), totalCountForProgressBar);

            while (true) {
                metrics.startBatch(this.batchSize, getIndexName());

                long startTimeFetch = System.nanoTime();
                List<T> batch = fetchBatch(lastProcessedEntry);
                long durationFetchNanos = System.nanoTime() - startTimeFetch;
                int rawEntriesInBatch = batch.size();
                totalRawEntriesFetched += rawEntriesInBatch;

                if (batch.isEmpty()) {
                    metrics.recordNullBatch();
                    break;
                }

                long startTimeProcess = System.nanoTime();
                ListMultimap<String, PositionList> positions = processBatch(batch);
                long durationProcessNanos = System.nanoTime() - startTimeProcess;
                int itemsInBatchOutput = positions.asMap().size(); 

                long durationWriteTempNanos = 0;
                if (!positions.isEmpty()) {
                    long startTimeWriteTemp = System.nanoTime();
                    File tempFile = writeBatchToTempFile(positions);
                    durationWriteTempNanos = System.nanoTime() - startTimeWriteTemp;
                    tempFiles.add(tempFile);
                }

                metrics.recordBatchStageDurations(durationFetchNanos, durationProcessNanos, durationWriteTempNanos, itemsInBatchOutput, rawEntriesInBatch);

                if (!batch.isEmpty()) {
                    lastProcessedEntry = batch.get(batch.size() - 1);
                }
                
                progress.updateIndex(rawEntriesInBatch);
            }

            logger.info("Finished fetching {} raw entries for index [{}].", totalRawEntriesFetched, getIndexName());

            if (tempFiles.isEmpty()) {
                logger.warn("No indexable entries found after filtering. Index [{}] will be empty.", getIndexName());
                progress.completeIndex();
                return;
            }

            File outputFile = new File(tempDir.toFile(), "sorted.tmp");
            logger.info("Merging {} temporary files...", tempFiles.size());
            long totalTempFilesSize = 0;
            for (File f : tempFiles) {
                if (f.exists()) totalTempFilesSize += f.length();
            }
            logger.info("Total size of {} temp files to be merged: {} MB", tempFiles.size(), totalTempFilesSize / (1024*1024));

            ExternalSort.mergeSortedFiles(tempFiles, outputFile, new PositionListComparator(), Charset.defaultCharset(), false);

            logger.info("Writing merged entries to LevelDB index...");
            writeToLevelDB(outputFile);

            progress.completeIndex();

            metrics.logIndexingMetrics();

        } finally {
            logger.debug("Cleaning up {} temporary files for index [{}]...", tempFiles.size(), getIndexName());
            for (File file : tempFiles) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException e) {
                    logger.debug("Could not delete temp file: {} ({})", file, e.getMessage());
                }
            }
            logger.debug("Temporary file cleanup complete.");
        }
    }

    /**
     * Comparator for sorting position list entries
     */
    private static class PositionListComparator implements Comparator<String> {
        @Override
        public int compare(String line1, String line2) {
            String term1 = line1.split("\t", 2)[0];
            String term2 = line2.split("\t", 2)[0];
            return term1.compareTo(term2);
        }
    }

    /**
     * Gets the total number of unique n-grams generated during indexing.
     * @return The total number of unique n-grams
     */
    public long getTotalNGramsGenerated() {
        return totalNGramsGenerated;
    }

    @Override
    public void close() throws IOException {
        if (indexAccess != null) {
            try {
                indexAccess.close();
            } catch (IndexAccessException e) {
                throw new IOException("Failed to close index access", e);
            }
        }
    }
} 
