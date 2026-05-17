package com.example.index.generators;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.index.BinarySortedFile;
import com.example.index.IndexEntry;
import com.example.index.IndexKey;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

/**
 * Abstract base class for streaming index generation that processes large
 * datasets efficiently
 * while maintaining bounded memory usage. Uses external sorting for scalable
 * processing.
 *
 * @param <T> The type of index entry this generator processes
 */
public abstract class IndexGenerator<T extends IndexEntry> implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(IndexGenerator.class);
    private static final Logger commandLogger = LoggerFactory.getLogger(
            IndexGenerator.class.getName() + ".command");
    public static final String DELIMITER = "\0";
    public static final char ESCAPE_CHAR = '\u001F';
    // Merge temp files when we hit this limit.
    // Kept high to minimise the number of incremental merges (each merge
    // re-reads and re-writes all accumulated data, so fewer = less I/O).
    // The merge implementation internally limits its fan-in to stay within
    // file-descriptor limits, so this value only controls merge frequency.
    private static final int MAX_TEMP_FILES_BEFORE_MERGE = 15_000;

    protected final IndexAccessInterface indexAccess;
    protected final Connection sqliteConn;
    protected final ProgressTracker progress;
    private final Path tempDir;
    protected long totalNGramsGenerated = 0;
    protected final int batchSize;
    protected final String effectiveIndexName;
    protected final Path tempFilePathForSorting;
    protected final Set<String> stopwords;

    private long totalTermsWrittenToIndex = 0;

    /**
     * Gets the name of the table to query for entries.
     *
     * @return The table name
     */
    protected abstract String getTableName();

    /**
     * Gets the name of this index for progress tracking and logging.
     *
     * @return The name of the index
     */
    public String getIndexName() {
        return this.effectiveIndexName;
    }

    /**
     * Fetches a batch of entries from the database.
     *
     * @param lastEntryFromPreviousBatch The last entry processed in the previous
     *                                   overall batch, used for pagination.
     *                                   Null if this is the first batch.
     * @return A list of entries.
     * @throws SQLException if a database error occurs.
     */
    protected abstract List<T> fetchBatch(T lastEntryFromPreviousBatch) throws SQLException;

    /**
     * Processes a raw batch of entries into an intermediate, aggregated form
     * suitable for writing to a temp file.
     *
     * @param batch The list of entries fetched from the database.
     * @return A ListMultimap <String, PostingList>, where keys are terms and
     *         values are their posting lists.
     */
    protected abstract ListMultimap<IndexKey, PostingList> processBatch(List<T> batch);

    /**
     * Estimates or retrieves the total number of documents/items to be processed
     * for this index.
     * This is used for progress tracking.
     *
     * @return Total count of items.
     * @throws SQLException if a database error occurs.
     */
    public abstract long getDocumentCountForIndex() throws SQLException;

    // Bulk load settings for SST generation
    private static final long TARGET_SST_BYTES = 512L * 1024 * 1024; // rotate SST files around 512MB

    // Primary constructor that all IndexGenerator implementations should use.
    @SuppressWarnings("this-escape") // For getDocumentCountForIndex and getIndexType in constructor
    protected IndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progressTracker, int batchSizeNum, Path customBaseTempDir)
            throws IOException {
        this.indexAccess = indexAccess;
        this.effectiveIndexName = indexAccess.getIndexType(); // Get name from IndexAccess
        this.sqliteConn = sqliteConn;
        this.progress = progressTracker;
        this.batchSize = batchSizeNum;

        // Load stopwords first as it only depends on effectiveIndexName for logging
        this.stopwords = loadStopwords(stopwordsPath);

        // Initialize tempDir: a unique directory for this generator instance.
        // It will be created under customBaseTempDir if provided, or system temp
        // otherwise.
        this.tempDir = initializeInstanceTempDir(this.effectiveIndexName, customBaseTempDir);

        // tempFilePathForSorting is now always inside this specific generator's tempDir
        this.tempFilePathForSorting = this.tempDir.resolve(BinarySortedFile.DEFAULT_OUTPUT_FILENAME);

        try {
            long totalDocs = getDocumentCountForIndex();
            this.progress.startIndex(this.effectiveIndexName, totalDocs);
        } catch (SQLException e) {
            throw new IOException("Failed to get document count for index: " + this.effectiveIndexName, e);
        }
        logger.debug("IndexGenerator for [{}] initialized. IndexAccess provided. Instance temp dir: {}, Batch size: {}",
                this.effectiveIndexName, this.tempDir.toAbsolutePath(), this.batchSize);
    }

    // Slim constructor that delegates to the primary one, providing null for
    // customBaseTempDir.
    protected IndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn,
            ProgressTracker progress, int batchSize) throws IOException {
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
                    logger.warn(
                            "Custom base temporary directory '{}' for index [{}] is not a writable directory. Falling back to system default.",
                            customBaseTempDir, indexNameForTempDir);
                    baseLocationToCreateIn = null; // Fallback to system default
                } else {
                    baseLocationToCreateIn = customBaseTempDir;
                }
            } catch (IOException e) {
                logger.warn(
                        "Failed to create or use custom base temporary directory '{}' for index [{}]. Falling back to system default. Error: {}",
                        customBaseTempDir, indexNameForTempDir, e.getMessage());
                baseLocationToCreateIn = null; // Fallback to system default
            }
        } else {
            baseLocationToCreateIn = null; // Use system default
        }

        Path uniqueTempDirForThisInstance;
        String instancePrefix = indexNameForTempDir + "-instance-";
        if (baseLocationToCreateIn != null) {
            uniqueTempDirForThisInstance = Files.createTempDirectory(baseLocationToCreateIn, instancePrefix);
            logMessagePrefix = "IndexGenerator for [" + indexNameForTempDir
                    + "] using instance temp dir inside custom base '{}': {}";
            logger.debug(logMessagePrefix, baseLocationToCreateIn.toAbsolutePath(),
                    uniqueTempDirForThisInstance.toAbsolutePath());
        } else { // System default
            uniqueTempDirForThisInstance = Files.createTempDirectory(instancePrefix);
            logMessagePrefix = "IndexGenerator for [" + indexNameForTempDir
                    + "] using instance temp dir in system default location: {}";
            logger.debug(logMessagePrefix, uniqueTempDirForThisInstance.toAbsolutePath());
        }
        return uniqueTempDirForThisInstance;
    }

    private Set<String> loadStopwords(String stopwordsPath) throws IOException {
        if (stopwordsPath == null || stopwordsPath.trim().isEmpty()) {
            logger.info("No stopwords file path provided for index [{}]. No stopwords will be used.",
                    this.effectiveIndexName);
            return Collections.emptySet();
        }
        Path path = Path.of(stopwordsPath);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            logger.warn("Stopwords file not found or not readable at: {} for index [{}]. No stopwords will be used.",
                    stopwordsPath, this.effectiveIndexName);
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
        logger.info("Loaded {} stopwords from {} for index [{}].", loadedStopwords.size(), stopwordsPath,
                this.effectiveIndexName);
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
     *
     * @param postings The processed posting lists to write
     * @return The temporary file containing the sorted entries
     */
    protected File writeBatchToTempFile(ListMultimap<IndexKey, PostingList> postings) throws IOException {
        File tempFile = Files.createTempFile(tempDir, "batch-", BinarySortedFile.EXTENSION).toFile();

        try (BinarySortedFile.Writer writer = BinarySortedFile.writer(tempFile,
                BinarySortedFile.SEQUENTIAL_BUFFER_BYTES)) {
            // Sort the entries by key (unsigned bytewise) before writing to ensure
            // each batch file is sorted.
            List<Map.Entry<IndexKey, Collection<PostingList>>> sortedEntries = new ArrayList<>(
                    postings.asMap().entrySet());
            sortedEntries.sort(Map.Entry.comparingByKey());

            for (Map.Entry<IndexKey, Collection<PostingList>> entry : sortedEntries) {
                // Merge all posting lists for this term within this batch
                List<PostingList> lists = new ArrayList<>();
                for (PostingList pl : entry.getValue()) {
                    lists.add(pl);
                }
                PostingList merged = PostingList.union(lists);
                writer.writeEntry(entry.getKey().bytes(), merged.serialize());
            }
        } catch (IOException e) {
            logger.error("IOException while writing to temp file {}: {}",
                    tempFile.getAbsolutePath(), e.getMessage(), e);
            throw e;
        }
        return tempFile;
    }

    /**
     * Context for managing SST file writing and rotation during bulk load.
     */
    private static class SstWriteContext implements AutoCloseable {
        private final List<String> producedFiles = new ArrayList<>();
        private final java.util.function.Supplier<org.rocksdb.SstFileWriter> sstFactory;
        private final IndexAccessInterface indexAccess;
        private org.rocksdb.SstFileWriter currentWriter;
        private long currentSstBytes;

        SstWriteContext(java.util.function.Supplier<org.rocksdb.SstFileWriter> sstFactory,
                IndexAccessInterface indexAccess) {
            this.sstFactory = sstFactory;
            this.indexAccess = indexAccess;
        }

        void start() {
            rotate(true);
        }

        void put(byte[] key, byte[] value) throws IOException {
            try {
                currentWriter.put(key, value);
            } catch (org.rocksdb.RocksDBException e) {
                throw new IOException("Failed to add key to SST", e);
            }
            currentSstBytes += key.length + value.length;
            if (currentSstBytes >= TARGET_SST_BYTES) {
                rotate(false);
            }
        }

        void ingestAll() throws IndexAccessException {
            indexAccess.ingestExternalFiles(producedFiles);
        }

        int producedFileCount() {
            return producedFiles.size();
        }

        private void rotate(boolean force) {
            try {
                if (force || currentWriter == null || currentSstBytes >= TARGET_SST_BYTES) {
                    if (currentWriter != null) {
                        currentWriter.finish();
                        currentWriter.close();
                        currentWriter = null;
                    }
                    String sstPath = indexAccess.getIndexPath()
                            .resolve("ingest-" + System.nanoTime() + ".sst").toString();
                    currentWriter = sstFactory.get();
                    currentWriter.open(sstPath);
                    producedFiles.add(sstPath);
                    currentSstBytes = 0L;
                    logger.debug("Opened new SST file: {}", sstPath);
                }
            } catch (org.rocksdb.RocksDBException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            if (currentWriter != null) {
                try {
                    currentWriter.finish();
                } catch (org.rocksdb.RocksDBException e) {
                    logger.error("Failed to finish SST file", e);
                }
                currentWriter.close();
                currentWriter = null;
            }
        }
    }

    /**
     * Flushes the accumulated blobs for a single term: deserializes, merges, and
     * writes to the SST.
     *
     * @param term  The current term
     * @param blobs The accumulated serialized PostingList blobs
     * @param ctx   The SST write context
     * @return 1 if a term was flushed, 0 if blobs was empty
     * @throws IOException if an I/O error occurs
     */
    private int flushTerm(IndexKey term, List<byte[]> blobs, SstWriteContext ctx) throws IOException {
        if (blobs.isEmpty()) {
            return 0;
        }
        List<PostingList> postings = new ArrayList<>(blobs.size());
        for (byte[] blob : blobs) {
            postings.add(PostingList.deserialize(blob));
        }
        PostingList merged = PostingList.union(postings);
        byte[] payload = merged.serialize();
        ctx.put(term.bytes(), payload);
        blobs.clear();
        return 1;
    }

    /**
     * Writes the final merged and sorted entries to the index using the PostingList
     * format.
     * No segmentation is used; each term maps to a single PostingList payload.
     *
     * @param sortedFile The file containing the sorted entries
     */
    protected void writeToLevelDB(File sortedFile) throws IOException {
        logger.info("Starting bulk load (SST ingestion) from sorted file: {}", sortedFile.getAbsolutePath());
        long totalTermsProcessedFromFile = 0;

        try (org.rocksdb.EnvOptions envOptions = new org.rocksdb.EnvOptions();
                org.rocksdb.Options sstOptions = com.example.index.RocksDBConfig.createOptimizedOptions()) {
            // Attempt to disable auto-compactions during ingest (best-effort; safe to skip
            // if not applicable)
            try {
                com.example.index.RocksDBConfig.configureForBulkLoad(sstOptions);
            } catch (Throwable t) {
                logger.debug("Bulk load configuration on Options skipped: {}", t.getMessage());
            }

            java.util.function.Supplier<org.rocksdb.SstFileWriter> sstFactory = () -> new org.rocksdb.SstFileWriter(
                    envOptions, sstOptions);

            SstWriteContext ctx = new SstWriteContext(sstFactory, indexAccess);
            try {
                ctx.start();

                IndexKey currentTerm = null;
                List<byte[]> blobsForCurrentTerm = new ArrayList<>();

                try (BinarySortedFile.Reader reader = BinarySortedFile.reader(sortedFile)) {
                    while (reader.advance()) {
                        byte[] keyBytes = reader.currentKey();
                        byte[] valueBytes = reader.currentValue();
                        IndexKey termFromFile = IndexKey.fromBytes(keyBytes);

                        if (currentTerm == null) {
                            currentTerm = termFromFile;
                        }

                        if (!termFromFile.equals(currentTerm)) {
                            totalTermsProcessedFromFile += flushTerm(currentTerm, blobsForCurrentTerm, ctx);
                            currentTerm = termFromFile;
                        }

                        if (valueBytes != null && valueBytes.length > 0) {
                            blobsForCurrentTerm.add(valueBytes);
                        }
                    }
                }

                if (currentTerm != null) {
                    totalTermsProcessedFromFile += flushTerm(currentTerm, blobsForCurrentTerm, ctx);
                }

                // Finish current writer and ingest
                ctx.close();
                ctx.ingestAll();
                this.totalTermsWrittenToIndex = totalTermsProcessedFromFile;
            } finally {
                ctx.close();
            }
        } catch (IOException e) {
            logger.error(
                    "IOException during SST bulk load for index [{}]. Total unique terms processed from file: {}. Error: {}",
                    getIndexName(), totalTermsProcessedFromFile, e.getMessage(), e);
            throw e;
        } catch (IndexAccessException e) {
            logger.error(
                    "IndexAccessException during SST ingestion for index [{}]. Total unique terms processed from file: {}. Error: {}",
                    getIndexName(), totalTermsProcessedFromFile, e.getMessage(), e);
            throw new IOException("Database access error during SST ingestion: " + e.getMessage(), e);
        } finally {
            try {
                indexAccess.compactRange();
            } catch (Exception ex) {
                logger.warn("Post-ingestion compaction/config restore encountered an error: {}", ex.getMessage());
            }
            logger.info("Finished SST bulk load for index [{}]. Total unique terms written: {}.",
                    getIndexName(), this.totalTermsWrittenToIndex);
        }
    }

    /**
     * Attempts to write a batch to the index, retrying on specific failures.
     */
    private void writeBatchWithRetry(org.rocksdb.WriteBatch batch, int maxRetries, long delayMs, int numEntries)
            throws IOException {
        int attempt = 0;
        while (true) {
            try {
                indexAccess.write(batch);
                if (logger.isTraceEnabled()) {
                    logger.trace("Successfully wrote batch of {} entries to index [{}] on attempt {}", numEntries,
                            getIndexName(), attempt + 1);
                }
                return;
            } catch (IndexAccessException e) {
                attempt++;
                logger.warn("Attempt {}/{} failed to write batch of {} entries to index [{}]: {}. Error Type: {}",
                        attempt, maxRetries, numEntries, getIndexName(), e.getMessage(), e.getErrorType());

                if (attempt >= maxRetries || e.getErrorType() == IndexAccessException.ErrorType.INITIALIZATION_ERROR) {
                    logger.error("Failed to write batch of {} entries to index [{}] after {} attempts. Giving up.",
                            numEntries, getIndexName(), attempt, e);
                    throw new IOException("Failed to write batch of " + numEntries + " entries to index ["
                            + getIndexName() + "] after " + attempt + " attempts", e);
                }
                try {
                    Thread.sleep(delayMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted during retry wait for batch write to index [" + getIndexName() + "]", ie);
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
     * Performs incremental merge of temp files to prevent file count explosion.
     * Merges all current temp files into a single intermediate file.
     */
    protected List<File> performIncrementalMerge(List<File> tempFiles) throws IOException {
        if (tempFiles.size() <= 1) {
            return tempFiles;
        }

        File mergedFile = Files.createTempFile(tempDir, "merged-", BinarySortedFile.EXTENSION).toFile();

        BinarySortedFile.merge(tempFiles, mergedFile);

        for (File file : tempFiles) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                logger.debug("Could not delete temp file during incremental merge: {}. Error: {}",
                        file.getAbsolutePath(), e.getMessage());
            }
        }

        List<File> result = new ArrayList<>();
        result.add(mergedFile);
        return result;
    }

    /**
     * Streams entries from multiple sorted temp files through a k-way merge
     * directly into RocksDB SST files, without writing an intermediate merged
     * file to disk. Eliminates one full read+write cycle over the dataset.
     */
    private void streamMergeToSST(List<File> tempFiles, long totalEntries) throws IOException {
        List<File> nonEmpty = new ArrayList<>();
        for (File f : tempFiles) {
            if (f.length() > 0) {
                nonEmpty.add(f);
            }
        }

        if (nonEmpty.isEmpty()) {
            logger.info("No data to ingest for index [{}].", getIndexName());
            return;
        }

        // If more files than safe to open at once, pre-merge via regular
        // multi-pass merge first, then stream the single resulting file.
        // In practice the incremental merge prevents hitting this path.
        if (nonEmpty.size() > BinarySortedFile.MAX_MERGE_FAN_IN) {
            logger.info("Too many temp files ({}) for direct streaming; pre-merging for index [{}]...",
                    nonEmpty.size(), getIndexName());
            File merged = Files.createTempFile(tempDir, "premerge-",
                    BinarySortedFile.EXTENSION).toFile();
            BinarySortedFile.merge(nonEmpty, merged);
            nonEmpty.clear();
            nonEmpty.add(merged);
        }

        logger.info("Streaming k-way merge of {} files directly to SST for index [{}]...",
                nonEmpty.size(), getIndexName());
        long totalTermsProcessed = 0;
        long entriesProcessed = 0;
        long mergeLoopMs = 0;
        long ingestMs = 0;

        progress.startRocksDBWrite(getIndexName(), totalEntries);

        try (org.rocksdb.EnvOptions envOptions = new org.rocksdb.EnvOptions();
                org.rocksdb.Options sstOptions = com.example.index.RocksDBConfig.createOptimizedOptions()) {
            try {
                com.example.index.RocksDBConfig.configureForBulkLoad(sstOptions);
            } catch (Throwable t) {
                logger.debug("Bulk load configuration on Options skipped: {}", t.getMessage());
            }

            java.util.function.Supplier<org.rocksdb.SstFileWriter> sstFactory = () -> new org.rocksdb.SstFileWriter(
                    envOptions, sstOptions);

            SstWriteContext ctx = new SstWriteContext(sstFactory, indexAccess);

            List<BinarySortedFile.Reader> readers = new ArrayList<>(nonEmpty.size());
            try {
                for (File f : nonEmpty) {
                    readers.add(BinarySortedFile.reader(f));
                }

                PriorityQueue<BinarySortedFile.Reader> heap = new PriorityQueue<>(
                        (a, b) -> IndexKey.compareBytes(a.currentKey(), b.currentKey()));

                for (BinarySortedFile.Reader r : readers) {
                    if (r.advance()) {
                        heap.add(r);
                    } else {
                        r.close();
                    }
                }

                if (heap.isEmpty()) {
                    progress.completeRocksDBWrite();
                    return;
                }

                ctx.start();

                long mergeStart = System.currentTimeMillis();

                IndexKey currentTerm = null;
                List<byte[]> blobsForCurrentTerm = new ArrayList<>();

                while (!heap.isEmpty()) {
                    BinarySortedFile.Reader r = heap.poll();
                    byte[] keyBytes = r.currentKey();
                    byte[] valueBytes = r.currentValue();
                    IndexKey termFromFile = IndexKey.fromBytes(keyBytes);

                    if (currentTerm == null) {
                        currentTerm = termFromFile;
                    }

                    if (!termFromFile.equals(currentTerm)) {
                        totalTermsProcessed += flushTerm(currentTerm, blobsForCurrentTerm, ctx);
                        currentTerm = termFromFile;
                    }

                    if (valueBytes != null && valueBytes.length > 0) {
                        blobsForCurrentTerm.add(valueBytes);
                    }

                    entriesProcessed++;
                    progress.updateRocksDBWriteTo(entriesProcessed);

                    if (r.advance()) {
                        heap.add(r);
                    } else {
                        r.close();
                    }
                }

                if (currentTerm != null) {
                    totalTermsProcessed += flushTerm(currentTerm, blobsForCurrentTerm, ctx);
                }

                mergeLoopMs = System.currentTimeMillis() - mergeStart;

                // Ensure progress bar reaches 100%
                progress.updateRocksDBWriteTo(totalEntries);

                long ingestStart = System.currentTimeMillis();
                ctx.close();
                ctx.ingestAll();
                ingestMs = System.currentTimeMillis() - ingestStart;
                this.totalTermsWrittenToIndex = totalTermsProcessed;

            } finally {
                for (BinarySortedFile.Reader r : readers) {
                    try {
                        r.close();
                    } catch (IOException ignored) {
                    }
                }
                progress.completeRocksDBWrite();
            }
        } catch (IndexAccessException e) {
            progress.completeRocksDBWrite();
            logger.error(
                    "IndexAccessException during streaming SST ingestion for index [{}]. Terms processed: {}. Error: {}",
                    getIndexName(), totalTermsProcessed, e.getMessage(), e);
            throw new IOException("Database access error during SST ingestion: " + e.getMessage(), e);
        } finally {
            long compactStart = System.currentTimeMillis();
            try {
                indexAccess.compactRange();
            } catch (Exception ex) {
                logger.warn("Post-ingestion compaction/config restore encountered an error: {}", ex.getMessage());
            }
            long compactMs = System.currentTimeMillis() - compactStart;

            logger.info("Finished streaming SST bulk load for index [{}]. Total unique terms written: {}.",
                    getIndexName(), this.totalTermsWrittenToIndex);
            commandLogger.info("[sst:{}] mergeLoop={}ms ingest={}ms compact={}ms totalTerms={}",
                    getIndexName(), mergeLoopMs, ingestMs, compactMs, totalTermsProcessed);
        }
    }

    /**
     * Generates the index by processing documents in batches, sorting externally,
     * and merging to the final index store.
     */
    public void generateIndex() throws SQLException, IOException {
        List<File> tempFiles = new ArrayList<>();
        T lastProcessedEntry = null;
        long totalRawEntriesFetched = 0;
        totalNGramsGenerated = 0;

        long batchTimeMs = 0;
        long mergeTimeMs = 0;
        int mergeCount = 0;
        long totalTempFileEntries = 0;

        try {
            long totalCountForProgressBar = getDocumentCountForIndex();
            progress.startIndex(getIndexName(), totalCountForProgressBar);

            while (true) {
                long iterStart = System.currentTimeMillis();

                List<T> batch = fetchBatch(lastProcessedEntry);
                int rawEntriesInBatch = batch.size();
                totalRawEntriesFetched += rawEntriesInBatch;

                if (batch.isEmpty()) {
                    break;
                }

                ListMultimap<IndexKey, PostingList> postings = processBatch(batch);

                if (!postings.isEmpty()) {
                    File tempFile = writeBatchToTempFile(postings);
                    tempFiles.add(tempFile);
                    totalTempFileEntries += postings.asMap().size();
                }

                batchTimeMs += System.currentTimeMillis() - iterStart;

                if (tempFiles.size() >= MAX_TEMP_FILES_BEFORE_MERGE) {
                    long mergeStart = System.currentTimeMillis();
                    logger.info("Reached {} temp files for index [{}]. Performing incremental merge...",
                            tempFiles.size(), getIndexName());
                    tempFiles = performIncrementalMerge(tempFiles);
                    long mergeMs = System.currentTimeMillis() - mergeStart;
                    mergeTimeMs += mergeMs;
                    mergeCount++;
                    logger.info("Incremental merge complete for index [{}]. Reduced to {} files in {} ms.",
                            getIndexName(), tempFiles.size(), mergeMs);
                }

                if (!batch.isEmpty()) {
                    lastProcessedEntry = batch.get(batch.size() - 1);
                }

                progress.updateIndex(rawEntriesInBatch);
            }

            logger.info("Finished fetching {} raw entries for index [{}].", totalRawEntriesFetched, getIndexName());

            if (tempFiles.isEmpty()) {
                logger.warn("No indexable entries found after filtering. Index [{}] will be empty.", getIndexName());
                progress.completeIndex();
                commandLogger.info("[phase:{}] batch={}ms", getIndexName(), batchTimeMs);
                return;
            }

            long totalTempFilesSize = 0;
            for (File f : tempFiles) {
                if (f.exists())
                    totalTempFilesSize += f.length();
            }
            logger.info("Total size of {} temp files: {} MB for index [{}]", tempFiles.size(),
                    totalTempFilesSize / (1024 * 1024), getIndexName());

            logger.info("Streaming merge of {} temp files directly to RocksDB SST for index [{}]...",
                    tempFiles.size(), getIndexName());

            // Complete the batch-processing bar before starting the DB-write bar.
            progress.completeIndex();

            long sstStart = System.currentTimeMillis();
            streamMergeToSST(tempFiles, totalTempFileEntries);
            long sstTimeMs = System.currentTimeMillis() - sstStart;

            // streamMergeToSST manages its own progress bar (rocksDBWriteProgress),
            // so no completeIndex() call is needed here.

            commandLogger.info("[phase:{}] batch={}ms merge={}ms (x{}) sst={}ms total={}ms entries={}",
                    getIndexName(), batchTimeMs, mergeTimeMs, mergeCount, sstTimeMs,
                    batchTimeMs + mergeTimeMs + sstTimeMs, totalRawEntriesFetched);

        } finally {
            logger.debug("Cleaning up {} temporary batch files for index [{}] from directory {}...", tempFiles.size(),
                    getIndexName(), this.tempDir.toAbsolutePath());
            for (File file : tempFiles) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException e) {
                    logger.debug("Could not delete temp batch file: {} for index [{}]. Error: {}",
                            file.getAbsolutePath(), getIndexName(), e.getMessage());
                }
            }
            logger.debug(
                    "Temporary batch file cleanup complete for index [{}]. Main sorted file and instance temp dir will be cleaned by close().",
                    getIndexName());
        }
    }

    /**
     * Gets the total number of unique n-grams generated during indexing.
     *
     * @return The total number of unique n-grams
     */
    public long getTotalNGramsGenerated() {
        return totalNGramsGenerated;
    }

    // New getter for total terms written to this index
    public long getTotalTermsWrittenToIndex() {
        return totalTermsWrittenToIndex;
    }

    @Override
    public void close() throws IOException {
        if (this.tempDir != null && Files.exists(this.tempDir)) {
            logger.info("Cleaning up instance temporary directory for index [{}]: {}", getIndexName(),
                    this.tempDir.toAbsolutePath());
            try (Stream<Path> walk = Files.walk(this.tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                logger.warn(
                                        "Failed to delete temporary path {} during cleanup of {} for index [{}]. Error: {}",
                                        path.toAbsolutePath(), this.tempDir.toAbsolutePath(), getIndexName(),
                                        e.getMessage());
                            }
                        });
            } catch (IOException e) {
                logger.error(
                        "Error during recursive deletion of instance temporary directory {} for index [{}]. Some temporary files may remain. Error: {}",
                        this.tempDir.toAbsolutePath(), getIndexName(), e.getMessage(), e);
            }
        } else {
            logger.debug(
                    "Instance temporary directory for index [{}] was null, did not exist, or was already cleaned up: {}",
                    getIndexName(), this.tempDir == null ? "null" : this.tempDir.toAbsolutePath());
        }
    }
}
