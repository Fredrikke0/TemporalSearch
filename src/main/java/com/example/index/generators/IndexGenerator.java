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
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.IndexEntry;
import com.example.logging.IndexingMetrics;
import com.example.logging.ProgressTracker;
import com.google.code.externalsorting.ExternalSort;
import com.google.common.collect.ArrayListMultimap;
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
    private static final int MAX_POSITIONS_PER_SEGMENT = 50_000_000; // Max positions per term segment
    private static final long MAX_MEMORY_BUFFER_SIZE = 1024 * 1024 * 1024; // 1GB buffer before writing temp file

    protected final IndexAccessInterface indexAccess;
    protected final Connection sqliteConn;
    protected final ProgressTracker progress;
    private final Path tempDir;
    protected long totalNGramsGenerated = 0;
    protected final int batchSize;
    protected final String effectiveIndexName;
    protected final Path tempFilePathForSorting;
    protected final Set<String> stopwords;

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
                             Connection sqliteConn, ProgressTracker progressTracker, int batchSizeNum, Path customBaseTempDir) throws IOException {
        this.indexAccess = indexAccess;
        this.effectiveIndexName = indexAccess.getIndexType(); // Get name from IndexAccess
        this.sqliteConn = sqliteConn;
        this.progress = progressTracker;
        this.batchSize = batchSizeNum;

        // Load stopwords first as it only depends on effectiveIndexName for logging
        this.stopwords = loadStopwords(stopwordsPath);

        // Initialize tempDir: a unique directory for this generator instance.
        // It will be created under customBaseTempDir if provided, or system temp otherwise.
        this.tempDir = initializeInstanceTempDir(this.effectiveIndexName, customBaseTempDir);

        // tempFilePathForSorting is now always inside this specific generator's tempDir
        this.tempFilePathForSorting = this.tempDir.resolve("sorted.tmp");

        try {
            long totalDocs = getDocumentCountForIndex(); // `this` is used here before constructor finishes
            this.progress.startIndex(this.effectiveIndexName, totalDocs);
        } catch (SQLException e) {
            throw new IOException("Failed to get document count for index: " + this.effectiveIndexName, e);
        }
        logger.debug("IndexGenerator for [{}] initialized. IndexAccess provided. Instance temp dir: {}, Batch size: {}",
                     this.effectiveIndexName, this.tempDir.toAbsolutePath(), this.batchSize);
    }

    // Slim constructor that delegates to the primary one, providing null for customBaseTempDir.
    protected IndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    private Path initializeInstanceTempDir(String indexNameForTempDir, Path customBaseTempDir) throws IOException {
        Path baseLocationToCreateIn;
        String logMessagePrefix;

        if (customBaseTempDir != null) {
            try {
                if (!Files.exists(customBaseTempDir)) {
                    Files.createDirectories(customBaseTempDir);
                    logger.info("Created custom base temporary directory: {}", customBaseTempDir.toAbsolutePath());
                }
                if (!Files.isDirectory(customBaseTempDir) || !Files.isWritable(customBaseTempDir)) {
                    logger.warn("Custom base temporary directory '{}' for index [{}] is not a writable directory. Falling back to system default.", customBaseTempDir, indexNameForTempDir);
                    baseLocationToCreateIn = null; // Fallback to system default
                } else {
                    baseLocationToCreateIn = customBaseTempDir;
                }
            } catch (IOException e) {
                logger.warn("Failed to create or use custom base temporary directory '{}' for index [{}]. Falling back to system default. Error: {}", customBaseTempDir, indexNameForTempDir, e.getMessage());
                baseLocationToCreateIn = null; // Fallback to system default
            }
        } else {
            baseLocationToCreateIn = null; // Use system default
        }

        Path uniqueTempDirForThisInstance;
        String instancePrefix = indexNameForTempDir + "-instance-";
        if (baseLocationToCreateIn != null) {
            uniqueTempDirForThisInstance = Files.createTempDirectory(baseLocationToCreateIn, instancePrefix);
            logMessagePrefix = "IndexGenerator for [" + indexNameForTempDir + "] using instance temp dir inside custom base '{}': {}";
            logger.info(logMessagePrefix, baseLocationToCreateIn.toAbsolutePath(), uniqueTempDirForThisInstance.toAbsolutePath());
        } else { // System default
            uniqueTempDirForThisInstance = Files.createTempDirectory(instancePrefix);
            logMessagePrefix = "IndexGenerator for [" + indexNameForTempDir + "] using instance temp dir in system default location: {}";
            logger.info(logMessagePrefix, uniqueTempDirForThisInstance.toAbsolutePath());
        }
        return uniqueTempDirForThisInstance;
    }

    private Set<String> loadStopwords(String stopwordsPath) throws IOException {
        if (stopwordsPath == null || stopwordsPath.trim().isEmpty()) {
            logger.info("No stopwords file path provided for index [{}]. No stopwords will be used.", this.effectiveIndexName);
            return Collections.emptySet();
        }
        Path path = Path.of(stopwordsPath);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            logger.warn("Stopwords file not found or not readable at: {} for index [{}]. No stopwords will be used.", stopwordsPath, this.effectiveIndexName);
            return Collections.emptySet();
        }
        Set<String> loadedStopwords = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();
                if (!trimmedLine.isEmpty() && !trimmedLine.startsWith("#")) { // Ignore empty lines and comments
                    loadedStopwords.add(trimmedLine.toLowerCase());
                }
            }
        }
        logger.info("Loaded {} stopwords from {} for index [{}].", loadedStopwords.size(), stopwordsPath, this.effectiveIndexName);
        return Collections.unmodifiableSet(loadedStopwords);
    }

    /**
     * Checks if the given term is a stopword.
     * The check is case-insensitive (term is converted to lowercase).
     *
     * @param term The term to check.
     * @return True if the term is a stopword, false otherwise.
     */
    protected boolean isStopword(String term) {
        if (term == null || term.isEmpty()) {
            return false; // Or true, depending on desired behavior for empty strings
        }
        return stopwords.contains(term.toLowerCase());
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
    protected void writeToLevelDB(File sortedFile) throws IOException {
        logger.info("Starting to write to RocksDB from sorted file: {}", sortedFile.getAbsolutePath());
        String currentTerm = null;
        long totalTermsWritten = 0; // Counts unique base terms written
        long totalSegmentsWritten = 0; // Counts total segments (including non-segmented terms as 1 segment)
        final long TARGET_BATCH_BYTES = 8 * 1024 * 1024; // 8MB target batch size
        long currentBatchSizeBytes = 0;
        int termsInCurrentBatch = 0; // Number of physical RocksDB puts in the current WriteBatch

        IntArrayList termDocIdsList = new IntArrayList();
        IntArrayList termSentIdsList = new IntArrayList();
        IntArrayList termBeginCharsList = new IntArrayList();
        IntArrayList termEndCharsList = new IntArrayList();
        IntArrayList termSynonymIdsList = new IntArrayList();

        int numPositionsForCurrentTermSegment = 0; // Positions for the current segment of currentTerm
        int segmentCounter = 0; // For term#0, term#1, etc.

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

                    // If term changes, write out the previous term's data (or its last segment)
                    if (!termFromFile.equals(currentTerm)) {
                        if (numPositionsForCurrentTermSegment > 0) {
                            String keyToWrite = (segmentCounter == 0) ? currentTerm : currentTerm + "#" + segmentCounter;
                            byte[] termKeyBytes = bytes(keyToWrite);
                            byte[] termValueBytes = serializeTermDataToBlob(numPositionsForCurrentTermSegment, termDocIdsList, termSentIdsList, termBeginCharsList, termEndCharsList, termSynonymIdsList);

                            if (currentBatchSizeBytes > 0 && (currentBatchSizeBytes + termKeyBytes.length + termValueBytes.length > TARGET_BATCH_BYTES)) {
                                writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                                progress.updateIndex(termsInCurrentBatch); // Update progress after batch write
                                batch.close();
                                batch = indexAccess.createWriteBatch();
                                termsInCurrentBatch = 0;
                                currentBatchSizeBytes = 0;
                            }
                            try {
                                batch.put(termKeyBytes, termValueBytes);
                                termsInCurrentBatch++;
                                currentBatchSizeBytes += termKeyBytes.length + termValueBytes.length;
                                totalSegmentsWritten++;
                            } catch (org.rocksdb.RocksDBException e) {
                                throw new IOException("Failed to put to WriteBatch for term segment: " + keyToWrite, e);
                            }
                        }
                        if (segmentCounter > 0 || numPositionsForCurrentTermSegment > 0) { // Only count if something was written for this term
                            totalTermsWritten++;
                        }

                        currentTerm = termFromFile;
                        clearAccumulators(termDocIdsList, termSentIdsList, termBeginCharsList, termEndCharsList, termSynonymIdsList);
                        numPositionsForCurrentTermSegment = 0;
                        segmentCounter = 0;
                    }

                    // Process current line's data
                    try (DataInputStream disChunk = new DataInputStream(new ByteArrayInputStream(lineCompositeBlob))) {
                        int chunkNumPositions = disChunk.readInt();
                        if (chunkNumPositions > 0) {
                            if (numPositionsForCurrentTermSegment + chunkNumPositions > MAX_POSITIONS_PER_SEGMENT && numPositionsForCurrentTermSegment > 0) {
                                // Current segment is full, write it out before adding this new chunk
                                String keyToWrite = currentTerm + "#" + segmentCounter; // Must be a segment if it's full
                                byte[] termKeyBytes = bytes(keyToWrite);
                                byte[] termValueBytes = serializeTermDataToBlob(numPositionsForCurrentTermSegment, termDocIdsList, termSentIdsList, termBeginCharsList, termEndCharsList, termSynonymIdsList);

                                if (currentBatchSizeBytes > 0 && (currentBatchSizeBytes + termKeyBytes.length + termValueBytes.length > TARGET_BATCH_BYTES)) {
                                    writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                                    progress.updateIndex(termsInCurrentBatch); // Update progress after batch write
                                    batch.close();
                                    batch = indexAccess.createWriteBatch();
                                    termsInCurrentBatch = 0;
                                    currentBatchSizeBytes = 0;
                                }
                                try {
                                    batch.put(termKeyBytes, termValueBytes);
                                    termsInCurrentBatch++;
                                    currentBatchSizeBytes += termKeyBytes.length + termValueBytes.length;
                                    totalSegmentsWritten++;
                                } catch (org.rocksdb.RocksDBException e) {
                                    throw new IOException("Failed to put to WriteBatch for term segment: " + keyToWrite, e);
                                }

                                clearAccumulators(termDocIdsList, termSentIdsList, termBeginCharsList, termEndCharsList, termSynonymIdsList);
                                numPositionsForCurrentTermSegment = 0;
                                segmentCounter++;
                            }

                            // Add current chunk data to accumulators
                            IntArrayList tempChunkDocIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            termDocIdsList.addAll(tempChunkDocIds);

                            IntArrayList tempChunkSentIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            termSentIdsList.addAll(tempChunkSentIds);

                            IntArrayList tempChunkBeginChars = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            termBeginCharsList.addAll(tempChunkBeginChars);

                            IntArrayList tempChunkEndChars = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            termEndCharsList.addAll(tempChunkEndChars);

                            IntArrayList tempChunkSynonymIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, false); // original for chunk
                            termSynonymIdsList.addAll(tempChunkSynonymIds);

                            numPositionsForCurrentTermSegment += chunkNumPositions;
                        }
                    }
                } // End of while loop over lines
            } // End of try-with-resources for BufferedReader

            // Write the last term's data or its last segment
            if (currentTerm != null && numPositionsForCurrentTermSegment > 0) {
                String keyToWrite = (segmentCounter == 0) ? currentTerm : currentTerm + "#" + segmentCounter;
                byte[] termKeyBytes = bytes(keyToWrite);
                byte[] termValueBytes = serializeTermDataToBlob(numPositionsForCurrentTermSegment, termDocIdsList, termSentIdsList, termBeginCharsList, termEndCharsList, termSynonymIdsList);

                if (currentBatchSizeBytes > 0 && (currentBatchSizeBytes + termKeyBytes.length + termValueBytes.length > TARGET_BATCH_BYTES) && termsInCurrentBatch > 0) {
                    writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                    progress.updateIndex(termsInCurrentBatch); // Update progress after batch write
                    batch.close();
                    batch = indexAccess.createWriteBatch();
                    logger.info("Written batch of {} DB entries (approx {:.2f} MB) to RocksDB before adding final term segment. Total unique terms written: {}, total segments: {}.",
                                termsInCurrentBatch, currentBatchSizeBytes / (1024.0 * 1024.0), totalTermsWritten, totalSegmentsWritten);
                    termsInCurrentBatch = 0;
                    currentBatchSizeBytes = 0;
                }
                try {
                    batch.put(termKeyBytes, termValueBytes);
                    termsInCurrentBatch++;
                    totalSegmentsWritten++;
                } catch (org.rocksdb.RocksDBException e) {
                    throw new IOException("Failed to put to WriteBatch for final term segment: " + keyToWrite, e);
                }
                totalTermsWritten++; // Count the last base term
            }

            if (termsInCurrentBatch > 0) {
                 writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                 progress.updateIndex(termsInCurrentBatch); // Update progress after final batch write
                logger.info("Written final batch of {} DB entries to RocksDB. Total unique terms written: {}, total segments: {}", termsInCurrentBatch, totalTermsWritten, totalSegmentsWritten);
            }

        } catch (IOException e) {
            logger.error("IOException during writeToRocksDB. Last term processed: {}. Total unique terms written: {}, total segments: {}. Error: {}",
                    currentTerm, totalTermsWritten, totalSegmentsWritten, e.getMessage(), e);
            throw e;
        } catch (IndexAccessException e) {
            logger.error("IndexAccessException during writeToRocksDB. Last term processed: {}. Total unique terms written: {}, total segments: {}. Error: {}",
                    currentTerm, totalTermsWritten, totalSegmentsWritten, e.getMessage(), e);
            throw new IOException("Database access error during writeToRocksDB: " + e.getMessage(), e);
        } finally {
            if (batch != null) {
                batch.close();
            }
            logger.info("Finished writing to RocksDB. Total unique base terms written: {}. Total segments written: {}. Total n-grams generated: {}",
                totalTermsWritten, totalSegmentsWritten, getTotalNGramsGenerated());
        }
    }

    /**
     * Helper method to serialize the current term's accumulated data to a byte blob.
     */
    private byte[] serializeTermDataToBlob(int numPositions, IntArrayList docIds, IntArrayList sentIds,
                                           IntArrayList beginChars, IntArrayList endChars, IntArrayList synonymIds) throws IOException {
        ByteArrayOutputStream finalCompositeBlobStream = new ByteArrayOutputStream();
        try (DataOutputStream dosFinal = new DataOutputStream(finalCompositeBlobStream)) {
            dosFinal.writeInt(numPositions);
            PositionListSoA.writeCompressedIntArray(dosFinal, docIds.elements(), docIds.size(), true);
            PositionListSoA.writeCompressedIntArray(dosFinal, sentIds.elements(), sentIds.size(), true);
            PositionListSoA.writeCompressedIntArray(dosFinal, beginChars.elements(), beginChars.size(), true);
            PositionListSoA.writeCompressedIntArray(dosFinal, endChars.elements(), endChars.size(), true);
            PositionListSoA.writeCompressedIntArray(dosFinal, synonymIds.elements(), synonymIds.size(), false); // Still false for synonymIds as per original
        }
        return finalCompositeBlobStream.toByteArray();
    }

    /**
     * Helper method to clear accumulator lists.
     */
    private void clearAccumulators(IntArrayList... lists) {
        for (IntArrayList list : lists) {
            list.clear();
        }
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
     * Estimates the memory size of a PositionListSoA for buffering decisions.
     * Uses the actual serialized size for accurate memory tracking.
     */
    private long estimatePositionListSize(PositionListSoA positionList) {
        try {
            // Use the actual compressed/serialized size for accurate estimation
            byte[] serialized = positionList.serializeToCompositeBlob();
            return serialized.length;
        } catch (IOException e) {
            logger.warn("Failed to serialize PositionListSoA for size estimation, using fallback calculation. Error: {}", e.getMessage());
            // Fallback to rough estimate: 5 integers per position + compression factor
            return (long) positionList.getNumPositions() * 15; // Assume ~25% compression ratio
        }
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

        // Memory buffer to accumulate multiple batches before writing temp files
        ListMultimap<String, PositionListSoA> memoryBuffer = ArrayListMultimap.create();
        long currentBufferSizeBytes = 0;

        try {
            long totalCountForProgressBar = getDocumentCountForIndex();
            progress.startIndex(getIndexName(), totalCountForProgressBar); // For the main phase

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
                    // Add to memory buffer instead of immediately writing to disk
                    for (Map.Entry<String, Collection<PositionListSoA>> entry : positions.asMap().entrySet()) {
                        for (PositionListSoA positionList : entry.getValue()) {
                            memoryBuffer.put(entry.getKey(), positionList);
                            currentBufferSizeBytes += estimatePositionListSize(positionList);
                        }
                    }

                    // Write buffer to temp file if it exceeds the size threshold
                    if (currentBufferSizeBytes >= MAX_MEMORY_BUFFER_SIZE) {
                        long startTimeWriteTemp = System.nanoTime();
                        File tempFile = writeBatchToTempFile(memoryBuffer);
                        durationWriteTempNanos = System.nanoTime() - startTimeWriteTemp;
                        tempFiles.add(tempFile);
                        memoryBuffer.clear();
                        currentBufferSizeBytes = 0;
                        logger.debug("Wrote buffered temp file for index [{}], total temp files so far: {}", getIndexName(), tempFiles.size());
                    }
                }

                metrics.recordBatchStageDurations(durationFetchNanos, durationProcessNanos, durationWriteTempNanos, itemsInBatchOutput, rawEntriesInBatch);

                if (!batch.isEmpty()) {
                    lastProcessedEntry = batch.get(batch.size() - 1);
                }

                progress.updateIndex(rawEntriesInBatch);
            }

            // Write any remaining data in the memory buffer
            if (!memoryBuffer.isEmpty()) {
                logger.info("Writing final buffered temp file for index [{}] with {} terms", getIndexName(), memoryBuffer.keySet().size());
                File tempFile = writeBatchToTempFile(memoryBuffer);
                tempFiles.add(tempFile);
                memoryBuffer.clear();
                currentBufferSizeBytes = 0;
            }

            logger.info("Finished fetching {} raw entries for index [{}].", totalRawEntriesFetched, getIndexName());

            if (tempFiles.isEmpty()) {
                logger.warn("No indexable entries found after filtering. Index [{}] will be empty.", getIndexName());
                progress.completeIndex();
                return;
            }

            // File outputFile = new File(tempDir.toFile(), "sorted.tmp"); // This is now this.tempFilePathForSorting
            logger.info("Merging {} temporary files into {} for index [{}]...", tempFiles.size(), this.tempFilePathForSorting.toAbsolutePath(), getIndexName());
            long totalTempFilesSize = 0;
            for (File f : tempFiles) {
                if (f.exists()) totalTempFilesSize += f.length();
            }
            logger.info("Total size of {} temp files to be merged: {} MB for index [{}]", tempFiles.size(), totalTempFilesSize / (1024*1024), getIndexName());

            ExternalSort.mergeSortedFiles(tempFiles, this.tempFilePathForSorting.toFile(), new PositionListComparator(), Charset.defaultCharset(), false);

            logger.info("Writing merged entries from {} to RocksDB index [{}]...", this.tempFilePathForSorting.toAbsolutePath(), getIndexName());
            // --- Start Progress for writeToRocksDB ---
            progress.startIndex(getIndexName() + " - Writing to DB", 0); // 0 or -1 for indeterminate
            writeToLevelDB(this.tempFilePathForSorting.toFile());
            progress.completeIndex(); // Complete this sub-stage
            // --- End Progress for writeToRocksDB ---

            metrics.logIndexingMetrics();

        } finally {
            logger.debug("Cleaning up {} temporary batch files for index [{}] from directory {}...", tempFiles.size(), getIndexName(), this.tempDir.toAbsolutePath());
            for (File file : tempFiles) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException e) {
                    logger.debug("Could not delete temp batch file: {} for index [{}]. Error: {}", file.getAbsolutePath(), getIndexName(), e.getMessage());
                }
            }
            // The main sorted.tmp (this.tempFilePathForSorting) will be cleaned by the close() method
            // when this.tempDir is recursively deleted.
            logger.debug("Temporary batch file cleanup complete for index [{}]. Main sorted file and instance temp dir will be cleaned by close().", getIndexName());
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
        // The IndexAccessInterface instance (this.indexAccess) is provided by the caller.
        // The caller is responsible for its lifecycle (e.g., closing it).
        // This generator should not close an externally managed IndexAccessInterface.

        // Clean up the unique temporary directory created by this generator instance.
        // This directory contains all temporary files, including any remaining batch files
        // and the final sorted.tmp (this.tempFilePathForSorting).
        if (this.tempDir != null && Files.exists(this.tempDir)) {
            logger.info("Cleaning up instance temporary directory for index [{}]: {}", getIndexName(), this.tempDir.toAbsolutePath());
            try (Stream<Path> walk = Files.walk(this.tempDir)) {
                walk.sorted(Comparator.reverseOrder()) // Delete contents before the directory itself
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Log as warn, as this might indicate a lock or permission issue
                            logger.warn("Failed to delete temporary path {} during cleanup of {} for index [{}]. Error: {}",
                                        path.toAbsolutePath(), this.tempDir.toAbsolutePath(), getIndexName(), e.getMessage());
                        }
                    });
                // logger.info("Successfully cleaned up instance temporary directory for index [{}]: {}", getIndexName(), this.tempDir.toAbsolutePath());
            } catch (IOException e) {
                // Log an error if walking the directory fails, as files might be left over.
                logger.error("Error during recursive deletion of instance temporary directory {} for index [{}]. Some temporary files may remain. Error: {}",
                             this.tempDir.toAbsolutePath(), getIndexName(), e.getMessage(), e);
            }
        } else {
            logger.debug("Instance temporary directory for index [{}] was null, did not exist, or was already cleaned up: {}",
                         getIndexName(), this.tempDir == null ? "null" : this.tempDir.toAbsolutePath());
        }
    }
}
