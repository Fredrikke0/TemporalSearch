package com.example.index.generators;

import com.example.core.IndexAccess;
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
import com.google.common.collect.Ordering;

public abstract class AbstractUnigramStitchGenerator extends IndexGenerator<StitchEntry> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractUnigramStitchGenerator.class);
    // protected static final int MAX_OPEN_FILES = 1000; // No longer directly managing DB options here
    // protected static final long LEVELDB_CACHE_SIZE_BYTES = 100 * 1024 * 1024; // 100MB
    // protected static final int LEVELDB_WRITE_BUFFER_SIZE_BYTES = 16 * 1024 * 1024; // 16MB

    protected final TypedAnnotationSynonymStore annotationSynonyms;
    private Integer lastProcessedDocumentId = null;
    // protected final int batchSize; // Already in parent
    protected final Path indexDBPath; // Path for synonyms, specific to this stitch index
    // protected DB db; // Will use indexAccess from parent
    protected final String resolvedIndexName;

    public Path getIndexDBPath() {
        return indexDBPath;
    }

    protected AbstractUnigramStitchGenerator(String indexBaseDir, String indexNameParam,
                                           String stopwordsPathString, Connection sqliteConnParam,
                                           ProgressTracker progressTrackerParam, int batchSizeParam, Path customSortTempParam,
                                           AnnotationType managedAnnotationType) throws IOException {
        // Call the full constructor of IndexGenerator which initializes indexAccess
        // and also passes the correct index name for indexAccess to use for its subdirectory.
        super(indexBaseDir, stopwordsPathString, sqliteConnParam, progressTrackerParam, batchSizeParam, customSortTempParam);
        
        this.resolvedIndexName = indexNameParam; // Keep this for clarity and synonym store path
        // this.batchSize = batchSizeParam; // Handled by parent
        
        // The indexDBPath for a stitch index is indexBaseDir/resolvedIndexName (e.g., projects/nyt/indexes/stitch-date)
        // This path is used by TypedAnnotationSynonymStore for its own files.
        this.indexDBPath = Path.of(indexBaseDir, this.resolvedIndexName);
        if (!Files.exists(this.indexDBPath)) {
            Files.createDirectories(this.indexDBPath);
        }

        // this.db = null; // No longer managing its own DB instance
        // logger.info("Skipping LevelDB initialization for {} (initializeDB=false)", this.resolvedIndexName);

        this.annotationSynonyms = new TypedAnnotationSynonymStore(this.indexDBPath, managedAnnotationType);

        try {
            logger.info("Initializing annotation synonyms for {} stitch index using inherited sqliteConn within directory: {}", managedAnnotationType, this.indexDBPath);
            populateSpecificAnnotationSynonyms(managedAnnotationType);
            logger.info("Successfully initialized {} annotation synonyms with {} entries for type {}",
                    managedAnnotationType, annotationSynonyms.size(), managedAnnotationType);
        } catch (SQLException | IOException e) {
            closeSynonymsOnError();
            // No db to close here as it's managed by the parent (indexAccess)
            // If indexAccess failed to initialize in super, it would have thrown from there.
            throw new UncheckedIOException("Failed to populate " + managedAnnotationType + " annotation synonyms for " + this.resolvedIndexName, e instanceof IOException ? (IOException)e : new IOException(e));
        }
        
        // Progress starting is handled by the parent IndexGenerator constructor if indexAccess is successfully created.
        // We just need to make sure getIndexName() in this class returns what the parent expects for its progress bar.
        // The parent constructor already calls: this.progress.startIndex(getIndexName(), totalDocs);
        // So, the override of getIndexName() at the bottom of this file to return this.resolvedIndexName is correct.
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

                    boolean isStop = isStopword(normalizedToken);
                    boolean noLetterOrDigit = !normalizedToken.chars().anyMatch(Character::isLetterOrDigit);

                    if (isStop || noLetterOrDigit) {
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
        int filteredCount = 0;

        for (StitchEntry entry : batch) {
            String unigram = entry.value();
            boolean isFiltered = false;
            String filterReason = "";

            if (unigram == null) {
                isFiltered = true;
                filterReason = "null unigram";
            } else if (unigram.isEmpty()) {
                isFiltered = true;
                filterReason = "empty unigram";
            } else if (isStopword(unigram)) {
                isFiltered = true;
                filterReason = "stopword";
            } else if (!unigram.chars().anyMatch(Character::isLetterOrDigit)) {
                isFiltered = true;
                filterReason = "no letter/digit";
            }

            if (isFiltered) {
                logger.trace("Filtered unigram: '{}' from entry: {}. Reason: {}", unigram, entry, filterReason);
                filteredCount++;
                continue;
            }
            
            logger.trace("Processing unigram: '{}' from entry: {}", unigram, entry);

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
        
        if (!batch.isEmpty()) {
            logger.debug("ProcessBatch for {} input {} entries, produced {} unique unigram keys, filtered out {} entries.", 
                getManagedAnnotationType(), batch.size(), indexData.keySet().size(), filteredCount);
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
    protected long writeToLevelDB(File sortedFile) throws IOException {
        logger.info("Starting custom writeToLevelDB for {} from sorted file: {}", this.resolvedIndexName, sortedFile.getAbsolutePath());
        long startTime = System.currentTimeMillis();
        long localTotalNGramsGenerated = 0;
        WriteBatch batch = null;
        int batchCounter = 0;

        // try (BufferedReader reader = new BufferedReader(new FileReader(sortedFile, StandardCharsets.UTF_8))) {
        //     String line;
        //     String currentTerm = null;
        //     PositionListSoA mergedPositions = null;
        //     final int MAX_RETRIES = 3;
        //     final long RETRY_DELAY_MS = 1000;

        //     batch = this.db.createWriteBatch(); // Use this.db instead of indexAccess

        //     while ((line = reader.readLine()) != null) {
        //         String[] parts = line.split("	", 2);
        //         if (parts.length != 2) continue;
        //         String term = parts[0];
        //         byte[] lineCompositeBlob = Base64.getDecoder().decode(parts[1]);
        //         PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(lineCompositeBlob);

        //         if (currentTerm == null) {
        //             currentTerm = term;
        //             mergedPositions = positions;
        //             continue;
        //         }

        //         if (!currentTerm.equals(term)) {
        //             // Write the current term
        //             if (mergedPositions.getNumPositions() > 1_000_000) {
        //                 logger.warn("Serializing very large PositionListSoA for key: '{}', size: {}", currentTerm, mergedPositions.getNumPositions());
        //             }
        //             byte[] keyBytes = bytes(currentTerm);
        //             byte[] valueBytes = mergedPositions.serializeToCompositeBlob();
        //             batch.put(keyBytes, valueBytes);
        //             batchCounter++;
        //             localTotalNGramsGenerated++;

        //             if (batchCounter >= LevelDBConfig.BATCH_SIZE) {
        //                 writeDbBatchWithRetry(batch, MAX_RETRIES, RETRY_DELAY_MS, batchCounter);
        //                 batch.close();
        //                 batch = this.db.createWriteBatch();
        //                 batchCounter = 0;
        //             }

        //             if (localTotalNGramsGenerated % 100000 == 0) {
        //                 long elapsed = System.currentTimeMillis() - startTime;
        //                 logger.info("Custom writeToLevelDB progress for {}: {} terms, {} terms/sec",
        //                     getIndexName(), localTotalNGramsGenerated,
        //                     String.format("%.2f", localTotalNGramsGenerated * 1000.0 / elapsed));
        //             }

        //             currentTerm = term;
        //             mergedPositions = positions;
        //         } else {
        //             // Same term, merge positions
        //             mergedPositions.addAll(positions);
        //         }
        //     }

        //     // Write the last term
        //     if (currentTerm != null && mergedPositions != null) {
        //         if (mergedPositions.getNumPositions() > 1_000_000) {
        //             logger.warn("Serializing very large PositionListSoA for key: '{}', size: {}", currentTerm, mergedPositions.getNumPositions());
        //         }
        //         byte[] keyBytes = bytes(currentTerm);
        //         byte[] valueBytes = mergedPositions.serializeToCompositeBlob();
        //         batch.put(keyBytes, valueBytes);
        //         batchCounter++;
        //         localTotalNGramsGenerated++;
        //     }

        //     if (batchCounter > 0) {
        //         writeDbBatchWithRetry(batch, MAX_RETRIES, RETRY_DELAY_MS, batchCounter);
        //     }

        //     logger.info("Custom writeToLevelDB finished for {}: {} unique terms written", getIndexName(), localTotalNGramsGenerated);

        // } finally {
        //     if (batch != null) {
        //         try {
        //             batch.close();
        //         } catch (IOException e) {
        //             logger.warn("Failed to close write batch for index [{}]: {}", getIndexName(), e.getMessage());
        //         }
        //     }
        // }
        // Now, delegate to the parent's writeToLevelDB method
        long totalTermsWritten = super.writeToLevelDB(sortedFile);
        localTotalNGramsGenerated = totalTermsWritten;
        logger.info("Delegated writeToLevelDB for {} to parent IndexGenerator.", this.resolvedIndexName);
        return localTotalNGramsGenerated;
    }

    /**
     * Attempts to write a batch to this.db, retrying on specific failures.
     */
    // private void writeDbBatchWithRetry(WriteBatch batch, int maxRetries, long delayMs, int numEntries) throws IOException { ... } // No longer needed

    @Override
    public void close() throws IOException {
        logger.info("Closing AbstractUnigramStitchGenerator {}...", this.resolvedIndexName);
        // if (db != null) { // db field is removed
        //     try {
        //         db.close();
        //         logger.debug("Internal LevelDB closed for {}.", this.resolvedIndexName);
        //     } catch (IOException e) {
        //         logger.error("Error closing internal LevelDB for {}: {}", this.resolvedIndexName, e.getMessage(), e);
        //     } finally {
        //         db = null;
        //     }
        // }
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

    // Method to allow tests to access the DB instance via IndexAccess
    public IndexAccess getIndexAccess() {
        return this.indexAccess;
    }

    // Method to allow tests to access the generator's internal synonym store instance
    public TypedAnnotationSynonymStore getAnnotationSynonyms() {
        return this.annotationSynonyms;
    }

} 