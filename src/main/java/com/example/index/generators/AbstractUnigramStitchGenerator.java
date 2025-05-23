package com.example.index.generators;

import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.index.AnnotationType;
import com.example.index.TypedAnnotationSynonymStore;
import com.example.index.StitchEntry;
import com.example.index.StitchPosition;
import com.example.logging.IndexingMetrics;
import com.example.logging.ProgressTracker;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import org.iq80.leveldb.DBException;
import com.example.index.LevelDBConfig;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Base64;

public abstract class AbstractUnigramStitchGenerator extends IndexGenerator<StitchEntry> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractUnigramStitchGenerator.class);
    protected static final int MAX_OPEN_FILES = 1000;
    protected static final long LEVELDB_CACHE_SIZE_BYTES = 100 * 1024 * 1024; // 100MB
    protected static final int LEVELDB_WRITE_BUFFER_SIZE_BYTES = 16 * 1024 * 1024; // 16MB

    protected final TypedAnnotationSynonymStore annotationSynonyms;
    private Integer lastProcessedDocumentId = null;
    protected final int batchSize;
    protected final Path indexDBPath;
    protected DB db;
    protected final String resolvedIndexName;

    public Path getIndexDBPath() {
        return indexDBPath;
    }

    protected AbstractUnigramStitchGenerator(String indexBaseDir, String indexNameParam,
                                           String stopwordsPathString, Connection sqliteConnParam,
                                           ProgressTracker progressTrackerParam, int batchSizeParam, Path customSortTempParam,
                                           AnnotationType managedAnnotationType) throws IOException {
        this(indexBaseDir, indexNameParam, stopwordsPathString, sqliteConnParam, progressTrackerParam, batchSizeParam, customSortTempParam, managedAnnotationType, true);
    }
    
    protected AbstractUnigramStitchGenerator(String indexBaseDir, String indexNameParam,
                                           String stopwordsPathString, Connection sqliteConnParam,
                                           ProgressTracker progressTrackerParam, int batchSizeParam, Path customSortTempParam,
                                           AnnotationType managedAnnotationType, boolean initializeDB) throws IOException {
        super(stopwordsPathString, sqliteConnParam, progressTrackerParam, batchSizeParam, customSortTempParam, indexNameParam);
        
        this.resolvedIndexName = indexNameParam;
        this.batchSize = batchSizeParam;
        this.indexDBPath = Path.of(indexBaseDir, this.resolvedIndexName);

        if (initializeDB) {
            Options options = new Options();
            options.createIfMissing(true);
            options.errorIfExists(false);
            options.maxOpenFiles(MAX_OPEN_FILES);
            options.cacheSize(LEVELDB_CACHE_SIZE_BYTES); 
            options.writeBufferSize(LEVELDB_WRITE_BUFFER_SIZE_BYTES);
            try {
                logger.info("Opening its own LevelDB for {} at: {}", this.resolvedIndexName, this.indexDBPath.toAbsolutePath());
                this.db = Iq80DBFactory.factory.open(this.indexDBPath.toFile(), options);
                logger.info("Successfully opened its own LevelDB for {} at: {}", this.resolvedIndexName, this.indexDBPath.toAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to open its own LevelDB for {} at {}: {}", this.resolvedIndexName, this.indexDBPath.toAbsolutePath(), e.getMessage(), e);
                throw new UncheckedIOException("Failed to open its own LevelDB at " + this.indexDBPath, e);
            }
        } else {
            this.db = null;
            logger.info("Skipping LevelDB initialization for {} (initializeDB=false)", this.resolvedIndexName);
        }

        this.annotationSynonyms = new TypedAnnotationSynonymStore(this.indexDBPath, managedAnnotationType);

        try {
            logger.info("Initializing annotation synonyms for {} stitch index using inherited sqliteConn within directory: {}", managedAnnotationType, this.indexDBPath);
            populateSpecificAnnotationSynonyms(managedAnnotationType);
            logger.info("Successfully initialized {} annotation synonyms with {} entries for type {}",
                    managedAnnotationType, annotationSynonyms.size(), managedAnnotationType);
        } catch (SQLException | IOException e) {
            closeSynonymsOnError();
            if (this.db != null) {
                try {
                    this.db.close();
                    logger.info("Closed its own DB for {} due to synonym population error.", this.resolvedIndexName);
                } catch (IOException dbCloseEx) {
                    logger.warn("Failed to close its own DB for {} after synonym population error", this.resolvedIndexName, dbCloseEx);
                }
            }
            throw new UncheckedIOException("Failed to populate " + managedAnnotationType + " annotation synonyms for " + this.resolvedIndexName, e instanceof IOException ? (IOException)e : new IOException(e));
        }
        
        if (initializeDB) {
            try {
                long count = getDocumentCountForIndex();
                if (super.progress != null) {
                    super.progress.startIndex(this.resolvedIndexName, count);
                }
            } catch (SQLException e) {
                logger.warn("Could not set progress for index {}: {}", this.resolvedIndexName, e.getMessage());
            }
        }
    }

    private void closeSynonymsOnError() {
        try {
            if (annotationSynonyms != null) {
                annotationSynonyms.close();
            }
        } catch (Exception ex) {
            logger.warn("Failed to close annotation synonyms after initialization error for {}", this.resolvedIndexName, ex);
        }
    }

    protected abstract void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException;
    protected abstract List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException;
    protected abstract AnnotationType getManagedAnnotationType();

    @Override
    protected List<StitchEntry> fetchBatch(StitchEntry lastStitchEntryFromPreviousOverallBatch) throws SQLException {
        List<StitchEntry> currentStitchEntriesForBatch = new ArrayList<>();
       
        while (currentStitchEntriesForBatch.size() < this.batchSize) {
            Integer currentDocumentId = getNextDocumentId();
            if (currentDocumentId == null) {
                logger.info("No more documents to process for {} stitch index.", getManagedAnnotationType());
                break; // No more documents
            }

            processDocument(currentDocumentId, currentStitchEntriesForBatch);

            // if (currentStitchEntriesForBatch.size() >= this.batchSize) {
            //     logger.debug("Batch size {} reached for {} after processing document {}", this.batchSize, getManagedAnnotationType(), currentDocumentId);
            // }
        }
        if (currentStitchEntriesForBatch.isEmpty() && lastProcessedDocumentId != null && !hasMoreDocuments(lastProcessedDocumentId)) {
             logger.info("FetchBatch for {} is returning an empty list because all documents have been processed.", getManagedAnnotationType());
        }
        return currentStitchEntriesForBatch;
    }

    private Integer getNextDocumentId() throws SQLException {
        String sql;
        if (this.lastProcessedDocumentId == null) {
            sql = "SELECT document_id FROM documents ORDER BY document_id LIMIT 1";
        } else {
            sql = "SELECT document_id FROM documents WHERE document_id > ? ORDER BY document_id LIMIT 1";
        }
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            if (this.lastProcessedDocumentId != null) {
                stmt.setInt(1, this.lastProcessedDocumentId);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    this.lastProcessedDocumentId = rs.getInt("document_id");
                    return this.lastProcessedDocumentId;
                } else {
                    return null; // No more documents
                }
            }
        }
    }
    
    private boolean hasMoreDocuments(int currentDocId) throws SQLException {
        String sql = "SELECT 1 FROM documents WHERE document_id > ? LIMIT 1";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, currentDocId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }


    protected void processDocument(int documentId, List<StitchEntry> entries) throws SQLException {
        Map<Integer, List<UnigramData>> unigramsBySentence = fetchUnigramsForDocument(documentId);
        if (unigramsBySentence.isEmpty()) {
            return; // No unigrams to process for this document
        }

        List<AnnotationData> annotations = fetchAnnotationsForDocument(documentId);
        if (annotations.isEmpty()) {
            return; // No relevant annotations to process for this document
        }
        
        AnnotationType currentType = getManagedAnnotationType();

        for (AnnotationData annotation : annotations) {
            int synonymId;
            try {
                 synonymId = annotationSynonyms.getOrCreateId(annotation.normalizedValue());
            } catch (IllegalArgumentException e) {
                logger.debug("Skipping annotation due to invalid value for type {}: {} - {}", currentType, annotation.normalizedValue(), e.getMessage());
                continue;
            }

            List<UnigramData> unigramsInSentence = unigramsBySentence.getOrDefault(annotation.sentenceId(), Collections.emptyList());
            for (UnigramData unigram : unigramsInSentence) {
                // TODO: Consider if duplicate checks for unigram-annotation pairs per sentence are needed here.
                // The original StitchIndexGenerator had some complex logic for DATEs.
                // For now, we simplify and add one entry per co-occurring unigram in the same sentence.
                entries.add(new StitchEntry(
                        documentId,
                        annotation.sentenceId(),
                        unigram.beginChar,    // Unigram's begin char
                        unigram.endChar,      // Unigram's end char
                        unigram.token,        // Unigram token
                        currentType,          // AnnotationType
                        synonymId,            // Annotation's synonymId
                        annotation.beginChar(), // Annotation's begin char
                        annotation.endChar()    // Annotation's end char
                ));
            }
        }
    }

    protected Map<Integer, List<UnigramData>> fetchUnigramsForDocument(int documentId) throws SQLException {
        Map<Integer, List<UnigramData>> unigramsBySentence = new HashMap<>();
        String sql = """
            SELECT sentence_id, begin_char, end_char, token, lemma
            FROM annotations
            WHERE document_id = ? AND token IS NOT NULL AND LENGTH(token) > 0
            ORDER BY sentence_id, begin_char
        """; // Ensure tokens are ordered for consistent processing if needed later

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int sentenceId = rs.getInt("sentence_id");
                    String token = rs.getString("token");
                    String lemma = rs.getString("lemma");
                    String normalizedToken = (lemma != null && !lemma.isEmpty()) ? lemma.toLowerCase() : token.toLowerCase();

                    if (isStopword(normalizedToken)) {
                        continue;
                    }

                    UnigramData unigramData = new UnigramData(
                            rs.getInt("begin_char"),
                            rs.getInt("end_char"),
                            normalizedToken
                    );
                    unigramsBySentence.computeIfAbsent(sentenceId, k -> new ArrayList<>()).add(unigramData);
                }
            }
        }
        return unigramsBySentence;
    }

    @Override
    protected ListMultimap<String, PositionListSoA> processBatch(List<StitchEntry> batch) {
        ListMultimap<String, PositionListSoA> indexData = ArrayListMultimap.create();
        Map<String, PositionListSoA> tempAggregator = new HashMap<>();
        AnnotationType currentType = getManagedAnnotationType(); // Get type once

        for (StitchEntry entry : batch) {
            String unigram = entry.value();
            if (unigram == null || unigram.isEmpty() || isStopword(unigram)) { // unigram is already lowercased by fetchUnigrams
                continue;
            }

            // Key for LevelDB will just be the unigram.
            // The AnnotationType is inherent to the specific index (e.g., "stitch/date")
            // and also stored within StitchPosition.
            String key = unigram;

            StitchPosition stitchPos = new StitchPosition(
                    entry.documentId(),
                    entry.sentenceId(),
                    entry.beginChar(),        // Unigram's begin char from StitchEntry
                    entry.endChar(),          // Unigram's end char from StitchEntry
                    currentType,              // AnnotationType
                    entry.synonymId(),        // Annotation's synonymId from StitchEntry
                    entry.annotationBeginChar(), // Annotation's begin char from StitchEntry
                    entry.annotationEndChar()   // Annotation's end char from StitchEntry
            );

            PositionListSoA pl = tempAggregator.computeIfAbsent(key, k -> new PositionListSoA());
            pl.add(stitchPos);
        }

        for (Map.Entry<String, PositionListSoA> mapEntry : tempAggregator.entrySet()) {
            indexData.put(mapEntry.getKey(), mapEntry.getValue());
        }
        
        if (indexData.isEmpty() && !batch.isEmpty()){
            logger.warn("ProcessBatch for {} produced no indexable data from a batch of {} entries. First entry: {}", getManagedAnnotationType(), batch.size(), batch.get(0));
        } else if (!indexData.isEmpty()) {
             //logger.debug("Processed batch for {} with {} unique unigrams, total {} StitchEntry items.", getManagedAnnotationType(), indexData.keySet().size(), batch.size());
        }
        return indexData;
    }
    
    @Override
    protected String getTableName() {
        // This is somewhat misleading now as we query 'documents' for IDs
        // and 'annotations' for actual content.
        // The individual generators might need more specific table names if we optimize queries.
        return "annotations"; 
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // Instead of counting or using MAX which would be slow,
        // just return -1 to indicate indeterminate progress
        return -1;
    }
    
    /**
     * Provides the SQL condition specific to the annotation type this generator handles.
     * Example: "ner = 'DATE' AND normalized_ner IS NOT NULL"
     */
    protected abstract String getSpecificAnnotationTypeDBCondition();


    @Override
    protected void writeToLevelDB(File sortedFile) throws IOException {
        logger.info("Starting custom writeToLevelDB for {} from sorted file: {}", this.resolvedIndexName, sortedFile.getAbsolutePath());
        long startTime = System.currentTimeMillis();
        long localTotalNGramsGenerated = 0;
        WriteBatch batch = null;
        int batchCounter = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(sortedFile, StandardCharsets.UTF_8))) {
            String line;
            String currentTerm = null;
            PositionListSoA mergedPositions = null;
            final int MAX_RETRIES = 3;
            final long RETRY_DELAY_MS = 1000;

            batch = this.db.createWriteBatch(); // Use this.db instead of indexAccess

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) continue;
                String term = parts[0];
                byte[] lineCompositeBlob = Base64.getDecoder().decode(parts[1]);
                PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(lineCompositeBlob);

                if (currentTerm == null) {
                    currentTerm = term;
                    mergedPositions = positions;
                    continue;
                }

                if (!currentTerm.equals(term)) {
                    // Write the current term
                    if (mergedPositions.getNumPositions() > 1_000_000) {
                        logger.warn("Serializing very large PositionListSoA for key: '{}', size: {}", currentTerm, mergedPositions.getNumPositions());
                    }
                    byte[] keyBytes = bytes(currentTerm);
                    byte[] valueBytes = mergedPositions.serializeToCompositeBlob();
                    batch.put(keyBytes, valueBytes);
                    batchCounter++;
                    localTotalNGramsGenerated++;

                    if (batchCounter >= LevelDBConfig.BATCH_SIZE) {
                        writeDbBatchWithRetry(batch, MAX_RETRIES, RETRY_DELAY_MS, batchCounter);
                        batch.close();
                        batch = this.db.createWriteBatch();
                        batchCounter = 0;
                    }

                    if (localTotalNGramsGenerated % 100000 == 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        logger.info("Custom writeToLevelDB progress for {}: {} terms, {} terms/sec",
                            getIndexName(), localTotalNGramsGenerated,
                            String.format("%.2f", localTotalNGramsGenerated * 1000.0 / elapsed));
                    }

                    currentTerm = term;
                    mergedPositions = positions;
                } else {
                    // Same term, merge positions
                    mergedPositions.addAll(positions);
                }
            }

            // Write the last term
            if (currentTerm != null && mergedPositions != null) {
                if (mergedPositions.getNumPositions() > 1_000_000) {
                    logger.warn("Serializing very large PositionListSoA for key: '{}', size: {}", currentTerm, mergedPositions.getNumPositions());
                }
                byte[] keyBytes = bytes(currentTerm);
                byte[] valueBytes = mergedPositions.serializeToCompositeBlob();
                batch.put(keyBytes, valueBytes);
                batchCounter++;
                localTotalNGramsGenerated++;
            }

            if (batchCounter > 0) {
                writeDbBatchWithRetry(batch, MAX_RETRIES, RETRY_DELAY_MS, batchCounter);
            }

            logger.info("Custom writeToLevelDB finished for {}: {} unique terms written", getIndexName(), localTotalNGramsGenerated);

        } finally {
            if (batch != null) {
                try {
                    batch.close();
                } catch (IOException e) {
                    logger.warn("Failed to close write batch for index [{}]: {}", getIndexName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Attempts to write a batch to this.db, retrying on specific failures.
     */
    private void writeDbBatchWithRetry(WriteBatch batch, int maxRetries, long delayMs, int numEntries) throws IOException {
        int attempt = 0;
        while (true) {
            try {
                this.db.write(batch); // Use this.db instead of indexAccess
                if (logger.isTraceEnabled()) {
                    logger.trace("Successfully wrote batch of {} entries to index [{}] on attempt {}", numEntries, getIndexName(), attempt + 1);
                }
                return; // Success
            } catch (DBException e) {
                attempt++;
                logger.warn("Attempt {}/{} failed to write batch of {} entries to index [{}]: {}",
                            attempt, maxRetries, numEntries, getIndexName(), e.getMessage());

                if (attempt >= maxRetries) {
                    logger.error("Failed to write batch of {} entries to index [{}] after {} attempts. Giving up.", numEntries, getIndexName(), attempt, e);
                    throw new IOException("Failed to write batch of " + numEntries + " entries to index [" + getIndexName() + "] after " + attempt + " attempts", e);
                }
                try {
                    Thread.sleep(delayMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry wait for batch write to index [" + getIndexName() + "]", ie);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing AbstractUnigramStitchGenerator {}...", this.resolvedIndexName);
        if (db != null) {
            try {
                db.close();
                logger.debug("Internal LevelDB closed for {}.", this.resolvedIndexName);
            } catch (IOException e) {
                logger.error("Error closing internal LevelDB for {}: {}", this.resolvedIndexName, e.getMessage(), e);
            } finally {
                db = null;
            }
        }
        if (annotationSynonyms != null) {
            try {
                annotationSynonyms.close();
                logger.debug("Annotation synonyms closed for {}.", this.resolvedIndexName);
            } catch (Exception e) {
                logger.error("Error closing annotation synonyms for {}: {}", this.resolvedIndexName, e.getMessage(), e);
            }
        }
        super.close(); 
        logger.info("AbstractUnigramStitchGenerator {} closed.", this.resolvedIndexName);
    }

    // Helper record for unigram data
    protected record UnigramData(int beginChar, int endChar, String token) {}

    // Helper record for annotation data fetched from DB
    protected record AnnotationData(int sentenceId, int beginChar, int endChar, String normalizedValue) {}

    /**
     * Gets the name of the specific index, which will be used as a subdirectory name.
     * Example: "unigram", "stitch/date".
     * @return The name of the index.
     */
    @Override
    public String getIndexName() {
        return this.resolvedIndexName; 
    }


} 