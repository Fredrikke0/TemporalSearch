package com.example.index.generators;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationType;
import com.example.index.IndexEntry;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

public abstract class AbstractUnigramStitchGenerator extends IndexGenerator<AbstractUnigramStitchGenerator.StitchEntry> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractUnigramStitchGenerator.class);

    protected final SynonymManager synonymManager;
    private Integer lastProcessedDocumentId = null;

    // New Record Definitions
    protected record AnnotationData(
        int sentenceId,
        int beginChar,
        int endChar,
        String annotationKeyComponent, // e.g., "NNP", "PERSON", "2023-01-01"
        String specificValueForSynonym // e.g., "castro", "john doe", "2023-01-01"
    ) {}

    // StitchEntry now defined as an inner record and implements IndexEntry
    protected record StitchEntry(
        int documentId,
        int sentenceId,
        int unigramBeginChar,
        int unigramEndChar,
        String unigramToken,
        String annotationKeyComponent, // This will be part of the RocksDB key
        int specificValueSynonymId,  // This will be the synonymId in PositionListSoA
        int annotationBeginChar,
        int annotationEndChar
    ) implements IndexEntry {

        // Explicitly implement methods required by IndexEntry
        @Override
        public int getDocumentId() { return this.documentId; }

        @Override
        public int getSentenceId() { return this.sentenceId; }

        @Override
        public int getBeginChar() { return this.unigramBeginChar; } // Use unigram's span

        @Override
        public int getEndChar() { return this.unigramEndChar; }   // Use unigram's span

        // Method for sorting, used by IndexGenerator (e.g. in its writeBatchToTempFile).
        // Removed @Override as per linter error (implies IndexEntry interface doesn't have this signature).
        public String value() {
            return unigramToken + IndexAccessInterface.DELIMITER + annotationKeyComponent;
        }

        // Required by IndexEntry (if it was an abstract method there, @Override would be fine).
        // Removed @Override as per linter error (implies IndexEntry interface doesn't have this signature,
        // or StitchEntry was trying to override a non-existent/differently-signed method).
        public long getAnnotationId() {
            return -1;
        }
    }

    @SuppressWarnings("this-escape")
    protected AbstractUnigramStitchGenerator(IndexAccessInterface indexAccess,
                                           String stopwordsPathString, Connection sqliteConnParam,
                                           ProgressTracker progressTrackerParam, int batchSizeParam, Path customSortTempParam,
                                           AnnotationType managedAnnotationType, SynonymManager sharedSynonymManager) throws IOException {
        super(indexAccess,
              stopwordsPathString,
              sqliteConnParam,
              progressTrackerParam,
              batchSizeParam,
              customSortTempParam);

        if (sharedSynonymManager == null) {
            throw new IllegalArgumentException("SynonymManager cannot be null for AbstractUnigramStitchGenerator");
        }
        this.synonymManager = sharedSynonymManager;

        try {
            logger.info("Initializing specific annotation synonyms for {} stitch index using inherited sqliteConn and shared SynonymManager.", managedAnnotationType);
            populateSpecificAnnotationSynonyms(managedAnnotationType);
            logger.info("Finished populating specific annotation synonyms for {} of type {}.", getIndexName(), managedAnnotationType);
        } catch (SQLException | IOException e) {
            throw new UncheckedIOException("Failed to populate " + managedAnnotationType + " annotation synonyms for " + getIndexName(), e instanceof IOException ? (IOException)e : new IOException(e));
        }
    }

    protected abstract void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException;
    protected abstract List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException;
    protected abstract AnnotationType getManagedAnnotationType(); // May become less relevant if StitchEntry carries all info

    /**
     * Determines if the specific value from AnnotationData (annotation.specificValueForSynonym())
     * should be resolved to an ID via the SynonymManager.
     * Subclasses can override this to return false if the specific value itself (or its derivative)
     * should be part of the key directly, and no synonym ID is needed for it in PositionListSoA.
     * @return true if a synonym ID should be looked up, false otherwise.
     */
    protected boolean requiresSynonymIdForAnnotationValue() {
        return true; // Default behavior is to use synonym manager
    }

    @Override
    protected List<StitchEntry> fetchBatch(StitchEntry lastStitchEntryFromPreviousOverallBatch) throws SQLException {
        List<StitchEntry> currentStitchEntriesForBatch = new ArrayList<>();

        // The pagination for stitch entries is based on document ID, not the last StitchEntry's composite value.
        // So, lastStitchEntryFromPreviousOverallBatch is not directly used for query conditions here,
        // but this.lastProcessedDocumentId handles the document-level pagination.

        while (currentStitchEntriesForBatch.size() < this.batchSize) {
            Integer currentDocumentId = getNextDocumentId();
            if (currentDocumentId == null) {
                logger.info("No more documents to process for {} stitch index.", getIndexName());
                break; // No more documents
            }

            processDocument(currentDocumentId, currentStitchEntriesForBatch);
        }
        if (currentStitchEntriesForBatch.isEmpty() && lastProcessedDocumentId != null && !hasMoreDocuments(lastProcessedDocumentId)) {
             logger.info("FetchBatch for {} is returning an empty list because all documents have been processed.", getIndexName());
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

        List<AnnotationData> annotations = fetchAnnotationsForDocument(documentId); // Fetches new AnnotationData structure
        if (annotations.isEmpty()) {
            return; // No relevant annotations to process for this document
        }

        // AnnotationType currentTypeEnum = getManagedAnnotationType(); // May not be needed if StitchEntry has all info

        for (AnnotationData annotation : annotations) {
            int specificValueId = -1; // Default to -1 (no synonym or not applicable)

            if (requiresSynonymIdForAnnotationValue()) {
                try {
                    // Get ID for the specific value part of the annotation (e.g., for "castro", "john doe")
                    specificValueId = synonymManager.getId(annotation.specificValueForSynonym());
                } catch (IllegalArgumentException e) {
                    logger.debug("Skipping annotation due to invalid specific value for synonym: {} - {} (for index {})", annotation.specificValueForSynonym(), e.getMessage(), getIndexName());
                    continue;
                } catch (org.rocksdb.RocksDBException e) {
                    logger.error("RocksDB error getting ID for specific value synonym '{}' (for index {}): {}", annotation.specificValueForSynonym(), getIndexName(), e.getMessage(), e);
                    continue;
                }
            } else {
                // If synonym ID is not required, specificValueId remains -1.
                // The annotation.annotationKeyComponent() is expected to contain the actual value (e.g., YYYYMMDD date string)
                // that will be part of the RocksDB key.
                logger.trace("Synonym ID lookup skipped for specific value from AnnotationData.specificValueForSynonym() ('{}') as per requiresSynonymIdForAnnotationValue() for index type {}. StitchEntry will use specificValueId: {}", annotation.specificValueForSynonym(), getIndexName(), specificValueId);
            }

            List<UnigramData> unigramsInSentence = unigramsBySentence.getOrDefault(annotation.sentenceId(), Collections.emptyList());
            for (UnigramData unigram : unigramsInSentence) {
                entries.add(new StitchEntry(
                        documentId,
                        annotation.sentenceId(),
                        unigram.beginChar,
                        unigram.endChar,
                        unigram.token,                         // unigramToken
                        annotation.annotationKeyComponent(), // e.g., "NNP", "PERSON", "2023-01-01"
                        specificValueId,                   // ID for "castro", "john doe", "2023-01-01"
                        annotation.beginChar(),            // Annotation's begin char from AnnotationData
                        annotation.endChar()               // Annotation's end char from AnnotationData
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
        """;

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
        // AnnotationType currentType = getManagedAnnotationType(); // No longer needed for key/value logic here
        int filteredCount = 0;

        for (StitchEntry entry : batch) {
            // The key for sorting and for RocksDB is now the composite key from StitchEntry.value()
            String compositeKey = entry.value(); // e.g., "fidel<DELIM>NNP"
            String unigramForFiltering = entry.unigramToken(); // For stopword checks, etc.

            boolean isFiltered = false;
            String filterReason = "";

            if (unigramForFiltering == null) {
                isFiltered = true;
                filterReason = "null unigram part";
            } else if (unigramForFiltering.isEmpty()) {
                isFiltered = true;
                filterReason = "empty unigram part";
            } else if (isStopword(unigramForFiltering)) {
                isFiltered = true;
                filterReason = "unigram part is stopword";
            } else if (!unigramForFiltering.chars().anyMatch(Character::isLetterOrDigit)) {
                isFiltered = true;
                filterReason = "unigram part has no letter/digit";
            }
            // Potentially add filtering for entry.annotationKeyComponent() if needed.

            if (isFiltered) {
                logger.trace("Filtered entry based on unigram part: '{}' from composite key: {}. Reason: {}", unigramForFiltering, compositeKey, filterReason);
                filteredCount++;
                continue;
            }

            logger.trace("Processing entry with composite key: '{}'", compositeKey);

            PositionListSoA pl = tempAggregator.computeIfAbsent(compositeKey, k -> new PositionListSoA());
            pl.add(
                entry.documentId(),
                entry.sentenceId(),
                entry.unigramBeginChar(),        // Unigram's begin char from StitchEntry
                entry.unigramEndChar(),          // Unigram's end char from StitchEntry
                entry.specificValueSynonymId() // ID of specific annotation value (e.g., for "castro", "john doe")
            );
        }

        for (Map.Entry<String, PositionListSoA> mapEntry : tempAggregator.entrySet()) {
            indexData.put(mapEntry.getKey(), mapEntry.getValue());
        }

        if (!batch.isEmpty()) {
            // Adjust logging for new structure
            // logger.debug("ProcessBatch for {} input {} StitchEntry items, produced {} unique composite keys, filtered out {} entries.",
            // getIndexName(), batch.size(), indexData.keySet().size(), filteredCount);
        }

        if (indexData.isEmpty() && !batch.isEmpty()){
            logger.warn("ProcessBatch for {} produced no indexable data from a batch of {} StitchEntry items. First entry original unigram: {}",
                         getIndexName(), batch.size(), batch.get(0).unigramToken());
        } else if (!indexData.isEmpty()) {
             //logger.debug("Processed batch for {} with {} unique composite keys, total {} StitchEntry items input.",
             //              getIndexName(), indexData.keySet().size(), batch.size());
        }
        return indexData;
    }

    @Override
    protected String getTableName() {
        return "annotations"; // For fetching unigrams and annotations primarily
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        return -1; // Indicates indeterminate progress for stitch indexes
    }

    protected abstract String getSpecificAnnotationTypeDBCondition(); // Still needed for fetching raw annotations

    @Override
    protected long writeToLevelDB(File sortedFile) throws IOException {
        // The parent IndexGenerator.writeToLevelDB should work correctly now,
        // as the sortedFile will contain lines with the composite key:
        // "unigram<DELIM>annotationKeyComponent	tBase64(PositionListSoA)"
        // where PositionListSoA.synonymId is the ID of the specific annotation value.
        logger.info("Using parent IndexGenerator.writeToLevelDB for {} from sorted file: {}", getIndexName(), sortedFile.getAbsolutePath());
        return super.writeToLevelDB(sortedFile);
    }

    @Override
    public void close() throws IOException {
        super.close();
        // SynonymManager is shared, not closed here.
    }

    // Helper record for unigram data (used in fetchUnigramsForDocument)
    protected record UnigramData(int beginChar, int endChar, String token) {}

    @Override
    public String getIndexName() {
        return this.indexAccess.getIndexType();
    }

    public IndexAccessInterface getIndexAccess() {
        return this.indexAccess;
    }

    public SynonymManager getSynonymManager() {
        return this.synonymManager;
    }
}