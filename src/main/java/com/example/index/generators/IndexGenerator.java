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

import com.example.core.PositionListSoA;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.lemire.integercompression.FastPFOR128;
import me.lemire.integercompression.IntegerCODEC;
import me.lemire.integercompression.IntWrapper;

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
        logger.info("Starting to write to LevelDB from sorted file: {}", sortedFile.getAbsolutePath());
        String currentTerm = null;
        long totalTermsWritten = 0;
        long entriesSinceLastReport = 0;
        long lastReportTime = System.currentTimeMillis();
        final long reportIntervalMillis = 30000; 
        final long TARGET_BATCH_BYTES = 8 * 1024 * 1024; // 8MB target batch size
        long currentBatchSizeBytes = 0;
        int termsInCurrentBatch = 0;

        // Use ByteArrayOutputStream to accumulate raw integers for each attribute
        // Only one chunk's worth of uncompressed data per attribute is in memory at any time
        ByteArrayOutputStream baosDocIds = new ByteArrayOutputStream();
        ByteArrayOutputStream baosSentIds = new ByteArrayOutputStream();
        ByteArrayOutputStream baosBeginChars = new ByteArrayOutputStream();
        ByteArrayOutputStream baosEndChars = new ByteArrayOutputStream();
        ByteArrayOutputStream baosSynonymIds = new ByteArrayOutputStream();

        DataOutputStream dosDocIds = new DataOutputStream(baosDocIds);
        DataOutputStream dosSentIds = new DataOutputStream(baosSentIds);
        DataOutputStream dosBeginChars = new DataOutputStream(baosBeginChars);
        DataOutputStream dosEndChars = new DataOutputStream(baosEndChars);
        DataOutputStream dosSynonymIds = new DataOutputStream(baosSynonymIds);
        
        int numPositionsForCurrentTerm = 0;

        WriteBatch batch = null; 
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
                            // Create final composite blob using proper compression format
                            ByteArrayOutputStream finalCompositeBlobStream = new ByteArrayOutputStream();
                            try (DataOutputStream dosFinal = new DataOutputStream(finalCompositeBlobStream)) {
                                dosFinal.writeInt(numPositionsForCurrentTerm);

                                // Close streams to ensure all data is written
                                dosDocIds.close();
                                dosSentIds.close();
                                dosBeginChars.close();
                                dosEndChars.close();
                                dosSynonymIds.close();

                                // Convert accumulated raw bytes back to int arrays and compress properly
                                int[] termDocIdInts = convertByteArrayToIntArray(baosDocIds.toByteArray());
                                PositionListSoA.writeCompressedIntArray(dosFinal, termDocIdInts, termDocIdInts.length, true);
                                
                                int[] termSentIdInts = convertByteArrayToIntArray(baosSentIds.toByteArray());
                                PositionListSoA.writeCompressedIntArray(dosFinal, termSentIdInts, termSentIdInts.length, true);
                                
                                int[] termBeginCharInts = convertByteArrayToIntArray(baosBeginChars.toByteArray());
                                PositionListSoA.writeCompressedIntArray(dosFinal, termBeginCharInts, termBeginCharInts.length, true);
                                
                                int[] termEndCharInts = convertByteArrayToIntArray(baosEndChars.toByteArray());
                                PositionListSoA.writeCompressedIntArray(dosFinal, termEndCharInts, termEndCharInts.length, true);
                                
                                int[] termSynonymIdInts = convertByteArrayToIntArray(baosSynonymIds.toByteArray());
                                PositionListSoA.writeCompressedIntArray(dosFinal, termSynonymIdInts, termSynonymIdInts.length, false); // No delta coding for synonym IDs
                            }
                            
                            byte[] termKeyBytes = bytes(currentTerm);
                            byte[] termValueBytes = finalCompositeBlobStream.toByteArray();

                            // If the current batch + this new term exceeds target, write current batch first.
                            if (currentBatchSizeBytes > 0 && (currentBatchSizeBytes + termKeyBytes.length + termValueBytes.length > TARGET_BATCH_BYTES)) {
                                writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                                batch.close(); 
                                batch = indexAccess.createWriteBatch(); 
                                logger.debug("\n Written batch of {} terms (approx {} MB) to LevelDB due to size limit. Total terms written: {}.\n", 
                                    termsInCurrentBatch, currentBatchSizeBytes / (1024 * 1024), totalTermsWritten);
                                termsInCurrentBatch = 0;
                                currentBatchSizeBytes = 0;
                            }
                            
                            batch.put(termKeyBytes, termValueBytes);
                            termsInCurrentBatch++;
                            currentBatchSizeBytes += termKeyBytes.length + termValueBytes.length;
                            totalTermsWritten++;
                            entriesSinceLastReport++;

                            // Update Progress periodically
                            if (entriesSinceLastReport % 1000 == 0) {
                                progress.updateIndex(entriesSinceLastReport);
                                entriesSinceLastReport = 0;
                            }
                        }

                        currentTerm = termFromFile;
                        // Reset streams for the new term
                        baosDocIds.reset();
                        baosSentIds.reset();
                        baosBeginChars.reset();
                        baosEndChars.reset();
                        baosSynonymIds.reset();

                        dosDocIds = new DataOutputStream(baosDocIds);
                        dosSentIds = new DataOutputStream(baosSentIds);
                        dosBeginChars = new DataOutputStream(baosBeginChars);
                        dosEndChars = new DataOutputStream(baosEndChars);
                        dosSynonymIds = new DataOutputStream(baosSynonymIds);
                        numPositionsForCurrentTerm = 0;
                    }

                    // TRUE STREAMING: Process chunk data one attribute at a time
                    // Only one uncompressed attribute array is in memory at any moment
                    try (DataInputStream disChunk = new DataInputStream(new ByteArrayInputStream(lineCompositeBlob))) {
                        int chunkNumPositions = disChunk.readInt();
                        if (chunkNumPositions > 0) {
                            
                            // Process docIds: decompress, stream to accumulator, discard
                            IntArrayList tempChunkDocIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            for (int i = 0; i < tempChunkDocIds.size(); i++) {
                                dosDocIds.writeInt(tempChunkDocIds.getInt(i));
                            }
                            // tempChunkDocIds goes out of scope and becomes eligible for GC
                            
                            // Process sentIds: decompress, stream to accumulator, discard
                            IntArrayList tempChunkSentIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            for (int i = 0; i < tempChunkSentIds.size(); i++) {
                                dosSentIds.writeInt(tempChunkSentIds.getInt(i));
                            }
                            // tempChunkSentIds goes out of scope and becomes eligible for GC
                            
                            // Process beginChars: decompress, stream to accumulator, discard
                            IntArrayList tempChunkBeginChars = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            for (int i = 0; i < tempChunkBeginChars.size(); i++) {
                                dosBeginChars.writeInt(tempChunkBeginChars.getInt(i));
                            }
                            // tempChunkBeginChars goes out of scope and becomes eligible for GC
                            
                            // Process endChars: decompress, stream to accumulator, discard
                            IntArrayList tempChunkEndChars = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            for (int i = 0; i < tempChunkEndChars.size(); i++) {
                                dosEndChars.writeInt(tempChunkEndChars.getInt(i));
                            }
                            // tempChunkEndChars goes out of scope and becomes eligible for GC
                            
                            // Process synonymIds: decompress, stream to accumulator, discard
                            IntArrayList tempChunkSynonymIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, false); // No delta coding for synonym IDs
                            for (int i = 0; i < tempChunkSynonymIds.size(); i++) {
                                dosSynonymIds.writeInt(tempChunkSynonymIds.getInt(i));
                            }
                            // tempChunkSynonymIds goes out of scope and becomes eligible for GC
                        }
                        numPositionsForCurrentTerm += chunkNumPositions;
                    }

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastReportTime > reportIntervalMillis) {
                        logger.info("writeToLevelDB progress: {} terms processed in last {} ms. Total terms written: {}. Current term: {}",
                                entriesSinceLastReport, currentTime - lastReportTime, totalTermsWritten, currentTerm);
                        lastReportTime = currentTime;
                        entriesSinceLastReport = 0;
                    }
                }
            } // End of try-with-resources for BufferedReader

            // Write the last term's data
            if (currentTerm != null && numPositionsForCurrentTerm > 0) {
                ByteArrayOutputStream finalCompositeBlobStream = new ByteArrayOutputStream();
                try (DataOutputStream dosFinal = new DataOutputStream(finalCompositeBlobStream)) {
                    dosFinal.writeInt(numPositionsForCurrentTerm);

                    // Close streams to ensure all data is written
                    dosDocIds.close();
                    dosSentIds.close();
                    dosBeginChars.close();
                    dosEndChars.close();
                    dosSynonymIds.close();

                    // Convert accumulated raw bytes back to int arrays and compress properly
                    int[] termDocIdInts = convertByteArrayToIntArray(baosDocIds.toByteArray());
                    PositionListSoA.writeCompressedIntArray(dosFinal, termDocIdInts, termDocIdInts.length, true);
                    
                    int[] termSentIdInts = convertByteArrayToIntArray(baosSentIds.toByteArray());
                    PositionListSoA.writeCompressedIntArray(dosFinal, termSentIdInts, termSentIdInts.length, true);
                    
                    int[] termBeginCharInts = convertByteArrayToIntArray(baosBeginChars.toByteArray());
                    PositionListSoA.writeCompressedIntArray(dosFinal, termBeginCharInts, termBeginCharInts.length, true);
                    
                    int[] termEndCharInts = convertByteArrayToIntArray(baosEndChars.toByteArray());
                    PositionListSoA.writeCompressedIntArray(dosFinal, termEndCharInts, termEndCharInts.length, true);
                    
                    int[] termSynonymIdInts = convertByteArrayToIntArray(baosSynonymIds.toByteArray());
                    PositionListSoA.writeCompressedIntArray(dosFinal, termSynonymIdInts, termSynonymIdInts.length, false); // No delta coding for synonym IDs
                }

                byte[] termKeyBytes = bytes(currentTerm);
                byte[] termValueBytes = finalCompositeBlobStream.toByteArray();

                // Check if we need to write current batch before adding final term
                if (currentBatchSizeBytes > 0 && (currentBatchSizeBytes + termKeyBytes.length + termValueBytes.length > TARGET_BATCH_BYTES) && termsInCurrentBatch > 0) {
                    writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                    batch.close();
                    batch = indexAccess.createWriteBatch();
                    logger.info("Written batch of {} terms (approx {:.2f} MB) to LevelDB before adding final term. Total terms written: {}.",
                                termsInCurrentBatch, currentBatchSizeBytes / (1024.0 * 1024.0), totalTermsWritten);
                    termsInCurrentBatch = 0;
                    currentBatchSizeBytes = 0;
                }

                batch.put(termKeyBytes, termValueBytes);
                termsInCurrentBatch++;
                totalTermsWritten++;

                // Final progress update for any remaining terms
                if (entriesSinceLastReport > 0) {
                    progress.updateIndex(entriesSinceLastReport);
                }
            }

            if (termsInCurrentBatch > 0) {
                 writeBatchWithRetry(batch, 3, 1000, termsInCurrentBatch);
                logger.info("Written final batch of {} terms to LevelDB. Total terms written: {}", termsInCurrentBatch, totalTermsWritten);
            }

        } catch (IOException e) {
            logger.error("IOException during writeToLevelDB. Last term processed: {}. Total terms written: {}. Error: {}",
                    currentTerm, totalTermsWritten, e.getMessage(), e);
            throw e;
        } finally {
            if (batch != null) {
                try {
                    batch.close();
                } catch (IOException e) {
                    logger.warn("Error closing final write batch: {}", e.getMessage());
                }
            }
            // Clean up streams
            closeQuietly(dosDocIds);
            closeQuietly(dosSentIds);
            closeQuietly(dosBeginChars);
            closeQuietly(dosEndChars);
            closeQuietly(dosSynonymIds);
            logger.info("Finished writing to LevelDB. Total terms written: {}. Total n-grams generated: {}", totalTermsWritten, getTotalNGramsGenerated());
        }
        return totalTermsWritten;
    }

    /**
     * Converts a byte array (assumed to be a sequence of 4-byte integers in big-endian order)
     * back to an int array.
     * @param bytes The byte array to convert.
     * @return The corresponding int array.
     * @throws IOException If the byte array length is not a multiple of 4.
     */
    private int[] convertByteArrayToIntArray(byte[] bytes) throws IOException {
        if (bytes == null) return new int[0];
        if (bytes.length % 4 != 0) {
            throw new IOException("Byte array length (" + bytes.length + ") is not a multiple of 4, cannot convert to int array.");
        }
        if (bytes.length == 0) {
            return new int[0];
        }
        int[] ints = new int[bytes.length / 4];
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream dis = new DataInputStream(bais)) {
            for (int i = 0; i < ints.length; i++) {
                ints[i] = dis.readInt();
            }
        }
        return ints;
    }

    // Helper method to close streams quietly
    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                logger.debug("Error closing stream quietly: {}", e.getMessage());
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

            logger.info("Writing merged entries to LevelDB index...");
            // --- Start Progress for writeToLevelDB --- 
            progress.startIndex(getIndexName() + " - Writing to DB", 0); // 0 or -1 for indeterminate
            long totalTermsWrittenToDb = writeToLevelDB(outputFile);
            progress.updateIndex(totalTermsWrittenToDb); // Update with total terms written in this stage
            progress.completeIndex(); // Complete this sub-stage
            // --- End Progress for writeToLevelDB ---

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
