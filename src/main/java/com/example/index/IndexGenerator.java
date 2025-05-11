package com.example.index;

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

/**
 * Abstract base class for streaming index generation that processes large datasets efficiently
 * while maintaining bounded memory usage. Uses external sorting for scalable processing.
 *
 * @param <T> The type of index entry this generator processes
 */
public abstract class IndexGenerator<T extends IndexEntry> implements AutoCloseable {
    protected static final Logger logger = LoggerFactory.getLogger(IndexGenerator.class);
    public static final String DELIMITER = "\0";
    public static final char ESCAPE_CHAR = '\u001F';

    protected final IndexAccess indexAccess;
    private final Set<String> stopwords;
    protected final Connection sqliteConn;
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

    protected IndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        // Initialize IndexAccess with optimized options from LevelDBConfig
        Options options = LevelDBConfig.createOptimizedOptions();

        try {
            this.indexAccess = new IndexAccess(Path.of(indexBaseDir), getIndexName(), options);
        } catch (IndexAccessException e) {
            throw new IOException("Failed to initialize IndexAccess", e);
        }

        this.stopwords = loadStopwords(stopwordsPath);
        this.sqliteConn = sqliteConn;
        this.progress = progress;
        this.tempDir = Files.createTempDirectory("index-");
        this.batchSize = batchSize;

        // Register shutdown hook for cleanup
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

    private Set<String> loadStopwords(String path) throws IOException {
        Set<String> words = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                words.add(line.trim().toLowerCase());
            }
        }
        return words;
    }

    /**
     * Checks if a word is a stopword.
     * The input word is expected to be already lowercased by the caller.
     *
     * @param word The word to check (expected to be in lowercase).
     * @return True if the word is null or a stopword, false otherwise.
     */
    protected boolean isStopword(String word) {
        return word == null || stopwords.contains(word); // Optimized: removed .toLowerCase()
    }

    /**
     * Fetches a batch of entries from the database for processing.
     * @param lastProcessedEntry The last entry processed in the previous batch (null if first batch)
     * @return List of entries for processing
     */
    protected abstract List<T> fetchBatch(T lastProcessedEntry) throws SQLException;

    /**
     * Process a batch of documents and return a map of terms to their position lists.
     * @param batch The batch of documents to process
     * @return A multimap of terms to their position lists
     */
    protected abstract ListMultimap<String, PositionList> processBatch(List<T> batch) throws IOException;

    /**
     * Writes a batch of processed entries to a temporary file.
     * @param positions The processed position lists to write
     * @return The temporary file containing the sorted entries
     */
    protected File writeBatchToTempFile(ListMultimap<String, PositionList> positions) throws IOException {
        File tempFile = Files.createTempFile(tempDir, "batch-", ".tmp").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
            for (Map.Entry<String, Collection<PositionList>> entry : positions.asMap().entrySet()) {
                // Merge all position lists for this term
                PositionList mergedList = new PositionList();
                for (PositionList list : entry.getValue()) {
                    list.getPositions().forEach(mergedList::add);
                }
                
                String line = String.format("%s\t%s\n", 
                    entry.getKey(), 
                    Base64.getEncoder().encodeToString(mergedList.serialize()));
                writer.write(line);
            }
        }
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

        try (BufferedReader reader = new BufferedReader(new FileReader(sortedFile))) {
            String line;
            String currentTerm = null;
            PositionList mergedPositions = null;
            final int MAX_RETRIES = 3; // Retries for batch write
            final long RETRY_DELAY_MS = 1000; // Longer delay for batch retries

            batch = indexAccess.createWriteBatch();

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) continue;
                String term = parts[0];
                PositionList positions = PositionList.deserialize(Base64.getDecoder().decode(parts[1]));

                if (currentTerm == null) {
                    currentTerm = term;
                    mergedPositions = positions;
                    continue;
                }

                if (!currentTerm.equals(term)) {
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

                    if (totalNGramsGenerated % 100000 == 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        logger.debug("Write progress: {} terms, {} terms/sec",
                            totalNGramsGenerated,
                            String.format("%.2f", totalNGramsGenerated * 1000.0 / elapsed));
                    }

                    currentTerm = term;
                    mergedPositions = positions;
                } else {
                    // This case implies that the sortedFile was not properly unique-term aggregated.
                    // For safety, we'll merge. If sortedFile is guaranteed unique, this can be an error or simplified.
                    mergedPositions.getPositions().forEach(positions::add); 
                }
            }

            // Handle the last term
            if (currentTerm != null && mergedPositions != null) {
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
                    logger.trace("Successfully wrote batch of {} entries to index [{}].", numEntries, getIndexName());
                }
                return; // Success
            } catch (IndexAccessException e) {
                attempt++;
                Throwable cause = e.getCause();
                boolean possiblyTransient = cause instanceof org.iq80.leveldb.DBException &&
                                             cause.getMessage() != null &&
                                             (cause.getMessage().contains("Could not open table") ||
                                              cause.getMessage().contains("FileNotFoundException"));

                if (possiblyTransient && attempt <= maxRetries) {
                    logger.warn("Attempt {} failed to write batch of {} entries to index [{}] due to potential transient LevelDB issue ({}). Retrying in {}ms...",
                                attempt, numEntries, getIndexName(), cause.getMessage(), delayMs * attempt);
                    try {
                        Thread.sleep(delayMs * attempt); // Simple backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry wait for batch write to index [" + getIndexName() + "]", ie);
                    }
                } else {
                    throw new IOException("Failed to write batch of " + numEntries + " entries to index [" + getIndexName() + "] after " + attempt + " attempts", e);
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
            ExternalSort.mergeSortedFiles(tempFiles, outputFile, new PositionListComparator(), Charset.defaultCharset(), true);

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
     * Helper method to get the relevant document count for the progress bar.
     * Specific index types might override this if they process different units.
     */
    protected long getDocumentCountForIndex() throws SQLException {
        String countTable = getTableName(); // Default to the generator's table
        String countSql = "SELECT COUNT(*) FROM " + countTable;
        
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(countSql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
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
        try {
            indexAccess.close();
        } catch (IndexAccessException e) {
            throw new IOException("Failed to close index access", e);
        }
    }
} 
