package com.example.index;

import com.google.common.collect.ListMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.logging.ProgressTracker;
import com.example.logging.IndexingMetrics;
import com.google.code.externalsorting.ExternalSort;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import org.iq80.leveldb.Options;

import java.io.*;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.*;
import java.nio.charset.Charset;
import java.util.stream.Collectors;

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
    protected final IndexConfig config;

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
            Connection sqliteConn, ProgressTracker progress) throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progress, new IndexConfig.Builder().build());
    }

    protected IndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, IndexConfig config) throws IOException {
        // Initialize IndexAccess with optimized options
        Options options = new Options();
        options.createIfMissing(true);
        options.cacheSize(64 * 1024 * 1024); // 64MB cache
        options.writeBufferSize(8 * 1024 * 1024); // 8MB write buffer
        options.blockSize(4 * 1024); // 4KB block size
        options.compressionType(org.iq80.leveldb.CompressionType.SNAPPY);

        try {
            this.indexAccess = new IndexAccess(Path.of(indexBaseDir), getIndexName(), options);
        } catch (IndexAccessException e) {
            throw new IOException("Failed to initialize IndexAccess", e);
        }

        this.stopwords = loadStopwords(stopwordsPath);
        this.sqliteConn = sqliteConn;
        this.progress = progress;
        this.tempDir = Files.createTempDirectory("index-");
        this.config = config;

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

    protected boolean isStopword(String word) {
        return word == null || stopwords.contains(word.toLowerCase());
    }

    /**
     * Fetches a batch of entries from the database for processing.
     * @param offset The offset to start fetching from
     * @return List of entries for processing
     */
    protected abstract List<T> fetchBatch(int offset) throws SQLException;

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
        int batchCount = 0;
        long startTime = System.currentTimeMillis();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(sortedFile))) {
            String currentTerm = null;
            PositionList mergedPositions = null;
            String line;

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) {
                    logger.warn("Invalid line format: {}", line);
                    continue;
                }

                String term = parts[0];
                byte[] positionData = Base64.getDecoder().decode(parts[1]);
                PositionList positions = PositionList.deserialize(positionData);

                // First term initialization
                if (currentTerm == null) {
                    currentTerm = term;
                    mergedPositions = positions;
                    continue;
                }

                // If we have a new term, write the current one
                if (!currentTerm.equals(term)) {
                    // Write current term
                    try {
                        indexAccess.put(bytes(currentTerm), mergedPositions);
                        batchCount++;
                        totalNGramsGenerated++;
                    } catch (IndexAccessException e) {
                        throw new IOException("Failed to write term: " + currentTerm, e);
                    }
                    
                    // Collect stats periodically
                    if (totalNGramsGenerated % 10000 == 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        logger.info("Write progress: {} terms, {} terms/sec", 
                            totalNGramsGenerated,
                            String.format("%.2f", totalNGramsGenerated * 1000.0 / elapsed));
                    }
                    
                    // Start new term
                    currentTerm = term;
                    mergedPositions = positions;
                } else {
                    // Merge positions for same term
                    positions.getPositions().forEach(mergedPositions::add);
                }
            }

            // Write the last term if we have one
            if (currentTerm != null && mergedPositions != null) {
                try {
                    indexAccess.put(bytes(currentTerm), mergedPositions);
                    totalNGramsGenerated++;
                } catch (IndexAccessException e) {
                    throw new IOException("Failed to write final term: " + currentTerm, e);
                }
            }

            logger.info("Finished writing {} terms to index", totalNGramsGenerated);
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
        int offset = 0;
        IndexingMetrics metrics = new IndexingMetrics();
        metrics.startBatch(config.getBatchSize(), getIndexName());
        long totalRawEntriesProcessed = 0;

        try {
            while (true) {
                List<T> batch = fetchBatch(offset);
                if (batch.isEmpty()) {
                    break;
                }
                totalRawEntriesProcessed += batch.size();

                // ---> Call processBatch directly with the fetched batch <---
                ListMultimap<String, PositionList> positions = processBatch(batch);

                if (!positions.isEmpty()) {
                    File tempFile = writeBatchToTempFile(positions);
                    tempFiles.add(tempFile);
                }

                // Update progress based on the batch size processed from DB
                offset += batch.size();
                metrics.recordBatchSuccess(batch.size());
                progress.updateIndex(batch.size());
            }

            logger.info("Finished fetching {} raw entries.", totalRawEntriesProcessed);

            if (tempFiles.isEmpty()) {
                logger.warn("No indexable entries found after filtering. Index will be empty.");
                return; // Exit early if no temp files were created
            }

            // Sort and merge temp files
            File outputFile = new File(tempDir.toFile(), "sorted.tmp");
            logger.info("Merging {} temporary files...", tempFiles.size());
            ExternalSort.mergeSortedFiles(tempFiles, outputFile, new PositionListComparator(), Charset.defaultCharset(), true); // Using distinct=true assumes writeBatchToTempFile might produce duplicates per term within a batch

            // Write sorted entries to LevelDB
            logger.info("Writing merged entries to LevelDB index...");
            writeToLevelDB(outputFile);

            // Final metrics
            metrics.logIndexingMetrics();
            logger.info("Index generation complete. Total unique terms written: {}", totalNGramsGenerated);

        } finally {
            // Cleanup temp files
            logger.debug("Cleaning up {} temporary files...", tempFiles.size());
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
        try {
            indexAccess.close();
        } catch (IndexAccessException e) {
            throw new IOException("Failed to close index access", e);
        }
    }
} 
