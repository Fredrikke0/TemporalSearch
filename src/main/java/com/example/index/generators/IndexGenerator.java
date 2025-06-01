package com.example.index.generators;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.IndexEntry;
import com.example.logging.IndexingMetrics;
import com.example.logging.ProgressTracker;
import com.google.code.externalsorting.ExternalSort;
import com.google.common.collect.ListMultimap;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Abstract base class for streaming index generation that processes large datasets efficiently
 * while maintaining bounded memory usage. Uses external sorting for scalable processing.
 *
 * @param <T> The type of index entry this generator processes
 */
public abstract class IndexGenerator<T extends IndexEntry> implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(IndexGenerator.class);
    public static final String DELIMITER = "\0";
    public static final char ESCAPE_CHAR = '\u001F';

    protected final IndexAccessInterface indexAccess;
    protected final Connection sqliteConn;
    protected Set<String> stopwords;
    protected final ProgressTracker progress;
    private final Path tempDir;
    private long totalNGramsGenerated = 0;
    protected final int batchSize;
    protected final String effectiveIndexName;

    /**
     * Gets the name of the table to query for entries.
     * @return The table name
     */
    protected abstract String getTableName();

    /**
     * Gets the name of this index for progress tracking and logging.
     * @return The name of the index
     */
    public String getIndexName() {
        return this.effectiveIndexName;
    }

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
     * @return A ListMultimap সেরা<String, PositionListSoA>, where keys are terms and values are their positions.
     */
    protected abstract ListMultimap<String, PositionListSoA> processBatch(List<T> batch);

    /**
     * Estimates or retrieves the total number of documents/items to be processed for this index.
     * This is used for progress tracking.
     * @return Total count of items.
     * @throws SQLException if a database error occurs.
     */
    public abstract long getDocumentCountForIndex() throws SQLException;

    // Primary constructor that all IndexGenerator implementations should use.
    @SuppressWarnings("this-escape") // For getDocumentCountForIndex and getIndexType in constructor
    protected IndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath,
                             Connection sqliteConn, ProgressTracker progressTracker, int batchSizeNum, Path customTempPath) throws IOException {
        this.indexAccess = indexAccess;
        this.effectiveIndexName = indexAccess.getIndexType(); // Get name from IndexAccess
        this.sqliteConn = sqliteConn;
        this.progress = progressTracker;
        this.batchSize = batchSizeNum;
        this.stopwords = loadStopwordsInternal(stopwordsPath);
        this.tempDir = initializeTempDir(this.effectiveIndexName, customTempPath);

        try {
            long totalDocs = getDocumentCountForIndex(); // `this` is used here before constructor finishes
            this.progress.startIndex(this.effectiveIndexName, totalDocs);
        } catch (SQLException e) {
            throw new IOException("Failed to get document count for index: " + this.effectiveIndexName, e);
        }
        logger.debug("IndexGenerator for [{}] initialized. IndexAccess provided. Temp dir: {}, Batch size: {}",
                     this.effectiveIndexName, this.tempDir.toAbsolutePath(), this.batchSize);
        registerShutdownHook();
    }

    // Slim constructor that delegates to the primary one, providing null for customTempPath.
    protected IndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null);
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
    protected File writeBatchToTempFile(ListMultimap<String, PositionListSoA> positions) throws IOException {
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
            List<Map.Entry<String, Collection<PositionListSoA>>> sortedEntries =
                new ArrayList<>(positions.asMap().entrySet());
            sortedEntries.sort(Map.Entry.comparingByKey());

            for (Map.Entry<String, Collection<PositionListSoA>> entry : sortedEntries) {
                // Merge all position lists for this term within this batch
                PositionListSoA mergedListSoA = new PositionListSoA();
                for (PositionListSoA list : entry.getValue()) {
                    mergedListSoA.addAll(list);
                }

                String line = String.format("%s\t%s\n",
                    entry.getKey(),
                    Base64.getEncoder().encodeToString(mergedListSoA.serializeToCompositeBlob()));
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
        //logger.info("Temp file {} written with {} bytes.", tempFile.getAbsolutePath(), bytesWrittenToFile);
        return tempFile;
    }

    /**
     * Writes the final merged and sorted entries to the index.
     * Implements a streaming re-compression strategy using PositionListSoA.
     * Each chunk from the sorted file is processed one attribute at a time to minimize memory.
     * Only one uncompressed attribute array from one chunk is held in memory at any time.
     *
     * @param sortedFile The file containing the sorted entries
     * @return The total number of terms written to the database.
     */
    protected long writeToLevelDB(File sortedFile) throws IOException {
        logger.info("Starting to write to RocksDB from sorted file: {}", sortedFile.getAbsolutePath());
        String currentTerm = null;
        long totalTermsWritten = 0;
        final long TARGET_BATCH_BYTES = 8 * 1024 * 1024; // 8MB target batch size
        long currentBatchSizeBytes = 0;
        int termsInCurrentBatch = 0;

        // Use IntArrayLists to accumulate integers for each attribute for the current term
        IntArrayList termDocIdsList = new IntArrayList();
        IntArrayList termSentIdsList = new IntArrayList();
        IntArrayList termBeginCharsList = new IntArrayList();
        IntArrayList termEndCharsList = new IntArrayList();
        IntArrayList termSynonymIdsList = new IntArrayList();

        int numPositionsForCurrentTerm = 0;

        org.rocksdb.WriteBatch batch = null;
        try {
            batch = indexAccess.createWriteBatch();
            try (BufferedReader reader = new BufferedReader(new FileReader(sortedFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length != 2) {
                        logger.warn("Skipping malformed line in sorted file: {}", line);
                        continue;
                    }

                    String termFromFile = parts[0];
                    byte[] lineCompositeBlob = Base64.getDecoder().decode(parts[1]);

                    if (currentTerm == null) {
                        currentTerm = termFromFile;
                    }

                    if (!termFromFile.equals(currentTerm)) {
                        if (numPositionsForCurrentTerm > 0) {
                            ByteArrayOutputStream finalCompositeBlobStream = new ByteArrayOutputStream();
                            try (DataOutputStream dosFinal = new DataOutputStream(finalCompositeBlobStream)) {
                                dosFinal.writeInt(numPositionsForCurrentTerm);

                                PositionListSoA.writeCompressedIntArray(dosFinal, termDocIdsList.elements(), termDocIdsList.size(), true);
                                PositionListSoA.writeCompressedIntArray(dosFinal, termSentIdsList.elements(), termSentIdsList.size(), true);
                                PositionListSoA.writeCompressedIntArray(dosFinal, termBeginCharsList.elements(), termBeginCharsList.size(), true);
                                PositionListSoA.writeCompressedIntArray(dosFinal, termEndCharsList.elements(), termEndCharsList.size(), true);
                                PositionListSoA.writeCompressedIntArray(dosFinal, termSynonymIdsList.elements(), termSynonymIdsList.size(), false); // No delta coding for synonym IDs
                            }

                            byte[] termKeyBytes = bytes(currentTerm);
                            byte[] termValueBytes = finalCompositeBlobStream.toByteArray();

                            if (currentBatchSizeBytes > 0 && (currentBatchSizeBytes + termKeyBytes.length + termValueBytes.length > TARGET_BATCH_BYTES)) {
                                writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                                batch.close();
                                batch = indexAccess.createWriteBatch();
                                termsInCurrentBatch = 0;
                                currentBatchSizeBytes = 0;
                            }
                            try {
                                batch.put(termKeyBytes, termValueBytes);
                            } catch (org.rocksdb.RocksDBException e) {
                                throw new IOException("Failed to put to WriteBatch for term: " + currentTerm, e);
                            }
                            termsInCurrentBatch++;
                            currentBatchSizeBytes += termKeyBytes.length + termValueBytes.length;
                            totalTermsWritten++;
                        }

                        currentTerm = termFromFile;
                        // Reset lists for the new term
                        termDocIdsList.clear();
                        termSentIdsList.clear();
                        termBeginCharsList.clear();
                        termEndCharsList.clear();
                        termSynonymIdsList.clear();
                        numPositionsForCurrentTerm = 0;
                    }

                    try (DataInputStream disChunk = new DataInputStream(new ByteArrayInputStream(lineCompositeBlob))) {
                        int chunkNumPositions = disChunk.readInt();
                        if (chunkNumPositions > 0) {
                            IntArrayList tempChunkDocIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            termDocIdsList.addAll(tempChunkDocIds);

                            IntArrayList tempChunkSentIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            termSentIdsList.addAll(tempChunkSentIds);

                            IntArrayList tempChunkBeginChars = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            termBeginCharsList.addAll(tempChunkBeginChars);

                            IntArrayList tempChunkEndChars = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            termEndCharsList.addAll(tempChunkEndChars);

                            IntArrayList tempChunkSynonymIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, false);
                            termSynonymIdsList.addAll(tempChunkSynonymIds);
                        }
                        numPositionsForCurrentTerm += chunkNumPositions;
                    }
                }
            } // End of try-with-resources for BufferedReader

            // Write the last term's data
            if (currentTerm != null && numPositionsForCurrentTerm > 0) {
                ByteArrayOutputStream finalCompositeBlobStream = new ByteArrayOutputStream();
                try (DataOutputStream dosFinal = new DataOutputStream(finalCompositeBlobStream)) {
                    dosFinal.writeInt(numPositionsForCurrentTerm);

                    PositionListSoA.writeCompressedIntArray(dosFinal, termDocIdsList.elements(), termDocIdsList.size(), true);
                    PositionListSoA.writeCompressedIntArray(dosFinal, termSentIdsList.elements(), termSentIdsList.size(), true);
                    PositionListSoA.writeCompressedIntArray(dosFinal, termBeginCharsList.elements(), termBeginCharsList.size(), true);
                    PositionListSoA.writeCompressedIntArray(dosFinal, termEndCharsList.elements(), termEndCharsList.size(), true);
                    PositionListSoA.writeCompressedIntArray(dosFinal, termSynonymIdsList.elements(), termSynonymIdsList.size(), false);
                }

                byte[] termKeyBytes = bytes(currentTerm);
                byte[] termValueBytes = finalCompositeBlobStream.toByteArray();

                if (currentBatchSizeBytes > 0 && (currentBatchSizeBytes + termKeyBytes.length + termValueBytes.length > TARGET_BATCH_BYTES) && termsInCurrentBatch > 0) {
                    writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                    batch.close();
                    batch = indexAccess.createWriteBatch();
                    logger.info("Written batch of {} terms (approx {:.2f} MB) to RocksDB before adding final term. Total terms written: {}.",
                                termsInCurrentBatch, currentBatchSizeBytes / (1024.0 * 1024.0), totalTermsWritten);
                    termsInCurrentBatch = 0;
                    currentBatchSizeBytes = 0;
                }
                try {
                    batch.put(termKeyBytes, termValueBytes);
                } catch (org.rocksdb.RocksDBException e) {
                    throw new IOException("Failed to put to WriteBatch for final term: " + currentTerm, e);
                }
                termsInCurrentBatch++;
                totalTermsWritten++;
            }

            if (termsInCurrentBatch > 0) {
                 writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                logger.info("Written final batch of {} terms to RocksDB. Total terms written: {}", termsInCurrentBatch, totalTermsWritten);
            }

        } catch (IOException e) {
            logger.error("IOException during writeToRocksDB. Last term processed: {}. Total terms written: {}. Error: {}",
                    currentTerm, totalTermsWritten, e.getMessage(), e);
            throw e;
        } catch (IndexAccessException e) {
            logger.error("IndexAccessException during writeToRocksDB. Last term processed: {}. Total terms written: {}. Error: {}",
                    currentTerm, totalTermsWritten, e.getMessage(), e);
            throw new IOException("Database access error during writeToRocksDB: " + e.getMessage(), e);
        } finally {
            if (batch != null) {
                batch.close();
            }
            logger.info("Finished writing to RocksDB. Total terms written: {}. Total n-grams generated: {}", totalTermsWritten, getTotalNGramsGenerated());
        }
        return totalTermsWritten;
    }

    /**
     * Attempts to write a batch to the index, retrying on specific failures.
     */
    private void writeBatchWithRetry(org.rocksdb.WriteBatch batch, int maxRetries, long delayMs, int numEntries) throws IOException {
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
        long initialTotalProgress = 0;

        try {
            long totalCountForProgressBar = getDocumentCountForIndex();
            progress.startIndex(getIndexName(), totalCountForProgressBar);
            initialTotalProgress = totalCountForProgressBar; // Store for later use if needed

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
                ListMultimap<String, PositionListSoA> positions = processBatch(batch);
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
                // progress.completeIndex(); // Complete the main stage if empty
                // Ensure the main progress is completed if we return early.
                if (initialTotalProgress == 0) progress.updateIndex(0); // If it was indeterminate, show 0/0 or similar
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

            logger.info("Writing merged entries to RocksDB index...");
            // --- Start Progress for writeToRocksDB ---
            progress.startIndex(getIndexName() + " - Writing to DB", 0); // 0 or -1 for indeterminate
            long totalTermsWrittenToDb = writeToLevelDB(outputFile);
            progress.updateIndex(totalTermsWrittenToDb); // Update with total terms written in this stage
            progress.completeIndex(); // Complete this sub-stage
            // --- End Progress for writeToRocksDB ---

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
