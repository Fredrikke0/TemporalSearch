package com.example.index.generators;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
import com.example.core.PostingList;
import com.example.index.IndexEntry;
import com.example.logging.ProgressTracker;
import com.google.code.externalsorting.ExternalSort;
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
    public static final String DELIMITER = "\0";
    public static final char ESCAPE_CHAR = '\u001F';
    private static final int MAX_TEMP_FILES_BEFORE_MERGE = 15_000; // Merge temp files when we hit this limit

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
    protected abstract ListMultimap<String, PostingList> processBatch(List<T> batch);

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
        this.tempFilePathForSorting = this.tempDir.resolve("sorted.tmp");

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
    protected File writeBatchToTempFile(ListMultimap<String, PostingList> postings) throws IOException {
        File tempFile = Files.createTempFile(tempDir, "batch-", ".tmp").toFile();

        long bytesWrittenToFile = 0;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
            // Sort the entries by term (key) before writing to ensure each batch file is
            // sorted.
            List<Map.Entry<String, Collection<PostingList>>> sortedEntries = new ArrayList<>(
                    postings.asMap().entrySet());
            sortedEntries.sort((e1, e2) -> compareByteArrays(
                    e1.getKey().getBytes(StandardCharsets.UTF_8),
                    e2.getKey().getBytes(StandardCharsets.UTF_8)));

            for (Map.Entry<String, Collection<PostingList>> entry : sortedEntries) {
                // Merge all posting lists for this term within this batch
                List<PostingList> lists = new ArrayList<>();
                for (PostingList pl : entry.getValue()) {
                    lists.add(pl);
                }
                PostingList merged = PostingList.union(lists);

                String b64Key = Base64.getEncoder().encodeToString(
                        entry.getKey().getBytes(StandardCharsets.UTF_8));
                String line = String.format("%s\t%s\n",
                        b64Key,
                        Base64.getEncoder().encodeToString(merged.serialize()));
                if (line.length() > 10 * 1024 * 1024) { // Log if a single line is very large (e.g. >10MB)
                    logger.warn("Very large line being written to temp file {} for key '{}'. Line length: {} bytes",
                            tempFile.getName(), entry.getKey(), line.length());
                }
                writer.write(line);
                bytesWrittenToFile += line.getBytes(StandardCharsets.UTF_8).length; // Approximate byte count
            }
        } catch (IOException e) {
            logger.error(
                    "IOException while writing to temp file {}. Bytes written before error (approx): {}. Error: {}",
                    tempFile.getAbsolutePath(), bytesWrittenToFile, e.getMessage(), e);
            throw e; // Re-throw the exception
        }
        return tempFile;
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

        String currentTerm = null;
        List<byte[]> blobsForCurrentTerm = new ArrayList<>();

        java.util.List<String> producedSstFiles = new java.util.ArrayList<>();
        final org.rocksdb.SstFileWriter[] sstRef = new org.rocksdb.SstFileWriter[1];
        final long[] currentSstBytesRef = new long[] { 0L };
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

            java.util.function.Consumer<Boolean> rotateSst = (force) -> {
                try {
                    if (force || sstRef[0] == null || currentSstBytesRef[0] >= TARGET_SST_BYTES) {
                        if (sstRef[0] != null) {
                            sstRef[0].finish();
                            sstRef[0].close();
                            sstRef[0] = null;
                        }
                        String sstPath = indexAccess.getIndexPath().resolve("ingest-" + System.nanoTime() + ".sst")
                                .toString();
                        try {
                            sstRef[0] = sstFactory.get();
                            sstRef[0].open(sstPath);
                            producedSstFiles.add(sstPath);
                            currentSstBytesRef[0] = 0L;
                            logger.debug("Opened new SST file: {}", sstPath);
                        } catch (org.rocksdb.RocksDBException e) {
                            throw new RuntimeException("Failed to open SST file: " + sstPath, e);
                        }
                    }
                } catch (org.rocksdb.RocksDBException e) {
                    throw new RuntimeException(e);
                }
            };

            rotateSst.accept(true); // open first

            try (BufferedReader reader = new BufferedReader(new FileReader(sortedFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0 || tab == line.length() - 1) {
                        logger.warn("Skipping malformed line in sorted file: {}", line);
                        continue;
                    }

                    String b64Key = line.substring(0, tab);
                    String b64Value = line.substring(tab + 1);
                    String termFromFile = new String(Base64.getDecoder().decode(b64Key), StandardCharsets.UTF_8);
                    byte[] lineBlob = Base64.getDecoder().decode(b64Value);

                    if (currentTerm == null) {
                        currentTerm = termFromFile;
                    }

                    if (!termFromFile.equals(currentTerm)) {
                        if (!blobsForCurrentTerm.isEmpty()) {
                            // Deserialize all PostingList blobs and merge
                            List<PostingList> postings = new ArrayList<>();
                            for (byte[] blob : blobsForCurrentTerm) {
                                postings.add(PostingList.deserialize(blob));
                            }
                            PostingList merged = PostingList.union(postings);
                            byte[] payload = merged.serialize();

                            try {
                                sstRef[0].put(bytes(currentTerm), payload);
                            } catch (org.rocksdb.RocksDBException e) {
                                throw new IOException("Failed to add key to SST: " + currentTerm, e);
                            }
                            currentSstBytesRef[0] += currentTerm.length() + payload.length;
                            totalTermsProcessedFromFile++;

                            blobsForCurrentTerm.clear();
                            if (currentSstBytesRef[0] >= TARGET_SST_BYTES)
                                rotateSst.accept(false);
                        }
                        currentTerm = termFromFile;
                    }

                    if (lineBlob != null && lineBlob.length > 0) {
                        blobsForCurrentTerm.add(lineBlob);
                    }
                }
            }

            if (currentTerm != null && !blobsForCurrentTerm.isEmpty()) {
                // Deserialize all PostingList blobs and merge
                List<PostingList> postings = new ArrayList<>();
                for (byte[] blob : blobsForCurrentTerm) {
                    postings.add(PostingList.deserialize(blob));
                }
                PostingList merged = PostingList.union(postings);
                byte[] payload = merged.serialize();

                try {
                    sstRef[0].put(bytes(currentTerm), payload);
                } catch (org.rocksdb.RocksDBException e) {
                    throw new IOException("Failed to add key to SST: " + currentTerm, e);
                }
                currentSstBytesRef[0] += currentTerm.length() + payload.length;
                totalTermsProcessedFromFile++;
            }

            // finalize current SST
            if (sstRef[0] != null) {
                try {
                    sstRef[0].finish();
                } catch (org.rocksdb.RocksDBException e) {
                    throw new IOException("Failed to finish SST file", e);
                }
                sstRef[0].close();
                sstRef[0] = null;
            }

            // Ingest all produced SSTs
            logger.info("Ingesting {} SST files into RocksDB for index [{}]...", producedSstFiles.size(),
                    getIndexName());
            indexAccess.ingestExternalFiles(producedSstFiles);
            this.totalTermsWrittenToIndex = totalTermsProcessedFromFile;

        } catch (IOException e) {
            logger.error(
                    "IOException during SST bulk load. Last term processed: {}. Total unique terms processed from file: {}. Error: {}",
                    currentTerm, totalTermsProcessedFromFile, e.getMessage(), e);
            throw e;
        } catch (IndexAccessException e) {
            logger.error(
                    "IndexAccessException during SST ingestion. Last term processed: {}. Total unique terms processed from file: {}. Error: {}",
                    currentTerm, totalTermsProcessedFromFile, e.getMessage(), e);
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

        File mergedFile = Files.createTempFile(tempDir, "merged-", ".tmp").toFile();

        ExternalSort.mergeSortedFiles(tempFiles, mergedFile, new PostingListComparator(),
                Charset.defaultCharset(), false);

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
     * Generates the index by processing documents in batches, sorting externally,
     * and merging to the final index store.
     */
    public void generateIndex() throws SQLException, IOException {
        List<File> tempFiles = new ArrayList<>();
        T lastProcessedEntry = null;
        long totalRawEntriesFetched = 0;
        totalNGramsGenerated = 0;

        try {
            long totalCountForProgressBar = getDocumentCountForIndex();
            progress.startIndex(getIndexName(), totalCountForProgressBar);

            while (true) {
                List<T> batch = fetchBatch(lastProcessedEntry);
                int rawEntriesInBatch = batch.size();
                totalRawEntriesFetched += rawEntriesInBatch;

                if (batch.isEmpty()) {
                    break;
                }

                ListMultimap<String, PostingList> postings = processBatch(batch);

                if (!postings.isEmpty()) {
                    File tempFile = writeBatchToTempFile(postings);
                    tempFiles.add(tempFile);

                    if (tempFiles.size() >= MAX_TEMP_FILES_BEFORE_MERGE) {
                        logger.info("Reached {} temp files for index [{}]. Performing incremental merge...",
                                tempFiles.size(), getIndexName());
                        tempFiles = performIncrementalMerge(tempFiles);
                        logger.info("Incremental merge complete for index [{}]. Reduced to {} files.",
                                getIndexName(), tempFiles.size());
                    }
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
                return;
            }

            logger.info("Merging {} temporary files into {} for index [{}]...", tempFiles.size(),
                    this.tempFilePathForSorting.toAbsolutePath(), getIndexName());
            long totalTempFilesSize = 0;
            for (File f : tempFiles) {
                if (f.exists())
                    totalTempFilesSize += f.length();
            }
            logger.info("Total size of {} temp files to be merged: {} MB for index [{}]", tempFiles.size(),
                    totalTempFilesSize / (1024 * 1024), getIndexName());

            ExternalSort.mergeSortedFiles(tempFiles, this.tempFilePathForSorting.toFile(), new PostingListComparator(),
                    Charset.defaultCharset(), false);

            logger.info("Writing merged entries from {} to RocksDB index [{}]...",
                    this.tempFilePathForSorting.toAbsolutePath(), getIndexName());
            progress.startIndex(getIndexName() + " - Writing to DB", 0);
            writeToLevelDB(this.tempFilePathForSorting.toFile());
            progress.completeIndex();

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
     * Comparator for sorting posting list entries by key.
     */
    private static class PostingListComparator implements Comparator<String> {
        @Override
        public int compare(String a, String b) {
            int ta = a.indexOf('\t');
            if (ta < 0)
                ta = a.length();
            int tb = b.indexOf('\t');
            if (tb < 0)
                tb = b.length();
            // Keys are now base64-encoded in the temp file; decode before comparing
            byte[] ka = Base64.getDecoder().decode(a.substring(0, ta));
            byte[] kb = Base64.getDecoder().decode(b.substring(0, tb));
            return compareByteArrays(ka, kb);
        }
    }

    private static int compareByteArrays(byte[] a, byte[] b) {
        int la = a.length, lb = b.length, i = 0;
        int min = Math.min(la, lb);
        while (i < min) {
            int va = a[i] & 0xFF;
            int vb = b[i] & 0xFF;
            if (va != vb)
                return va - vb;
            i++;
        }
        return la - lb;
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
