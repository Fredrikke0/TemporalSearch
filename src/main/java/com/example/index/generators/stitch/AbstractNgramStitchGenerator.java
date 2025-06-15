package com.example.index.generators.stitch;

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
import com.example.index.generators.IndexGenerator;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

public abstract class AbstractNgramStitchGenerator extends IndexGenerator<AbstractNgramStitchGenerator.NgramStitchEntry> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractNgramStitchGenerator.class);
    protected static final int MAX_SENTENCE_CHAR_SPAN_FROM_FIRST_TOKEN = 120;

    protected final SynonymManager synonymManager;
    protected final int N; // Size of the N-gram (e.g., 1 for unigram, 2 for bigram, 3 for trigram)
    private Integer lastProcessedDocumentId = null;

    /**
     * Interface for annotation records that can be filtered by sentence character span.
     */
    protected interface SentenceSpanFilterable {
        int sentenceId();
        int beginChar();
        String getFilterLogDetail(); // For providing context (e.g., NER tag, POS tag) in log messages
    }

    // Moved AnnotationData here from AbstractUnigramStitchGenerator
    public record AnnotationData(
        int sentenceId,
        int beginChar,
        int endChar,
        String annotationKeyComponent, // e.g., "NNP", "PERSON", "20230101"
        String specificValueForSynonym // e.g., "castro", "john doe", or "20230101" for dates
    ) implements SentenceSpanFilterable {
        @Override
        public String getFilterLogDetail() {
            return annotationKeyComponent; // Or specificValueForSynonym, depending on desired log detail
        }
        // Note: sentenceId() and beginChar() are implicitly provided by the record components
    }

    // Record to hold processed token information before forming N-grams or for unigrams
    private record ProcessedTokenInfo(String token, int beginChar, int endChar) {}

    // Record for an N-gram (or unigram if N=1) found in a sentence
    protected record NgramData(int beginChar, int endChar, String ngramKey) {}

    // Record for a stitch entry combining an N-gram/unigram with an annotation
    protected record NgramStitchEntry(
            int documentId,
            int sentenceId,
            int ngramBeginChar, // For unigram, this is unigramBeginChar
            int ngramEndChar,   // For unigram, this is unigramEndChar
            String ngramKey, // e.g., "token1<DELIM>token2" or just "token1" for unigram
            String annotationKeyComponent, // e.g., "NNP", "PERSON", "20230101"
            int specificValueSynonymId,  // SynonymId of the specific annotated term
            int annotationBeginChar,
            int annotationEndChar
    ) implements IndexEntry {

        @Override
        public int getDocumentId() { return this.documentId; }
        @Override
        public int getSentenceId() { return this.sentenceId; }
        @Override
        public int getBeginChar() { return this.ngramBeginChar; } // N-gram's or Unigram's span
        @Override
        public int getEndChar() { return this.ngramEndChar; }   // N-gram's or Unigram's span

        public String value() {
            return ngramKey + IndexAccessInterface.DELIMITER + annotationKeyComponent;
        }

        // Removed @Override as IndexEntry does not define this method.
        // This method is present for consistency with the previous unigram stitch entry structure.
        public long getAnnotationId() { // Required by IndexEntry, consistent with former UnigramStitchEntry
            return -1;
        }
    }

    @SuppressWarnings("this-escape")
    protected AbstractNgramStitchGenerator(
            int nValue, // 1 for unigram, 2 for bigram, 3 for trigram
            IndexAccessInterface indexAccess,
            String stopwordsPathString,
            Connection sqliteConnParam,
            ProgressTracker progressTrackerParam,
            int batchSizeParam,
            Path customSortTempParam,
            SynonymManager sharedSynonymManager,
            AnnotationType managedAnnotationType // For populateSpecificAnnotationSynonyms logging
    ) throws IOException {
        super(indexAccess, stopwordsPathString, sqliteConnParam, progressTrackerParam, batchSizeParam, customSortTempParam);
        if (nValue < 1) { // Allow N=1 for unigrams
            throw new IllegalArgumentException("N-gram size (N) must be at least 1.");
        }
        this.N = nValue;
        if (sharedSynonymManager == null) {
            throw new IllegalArgumentException("SynonymManager cannot be null for AbstractNgramStitchGenerator");
        }
        this.synonymManager = sharedSynonymManager;

        try {
            String gramType = (N == 1) ? "unigram" : N + "-gram";
            logger.info("Initializing specific annotation synonyms for {} {} stitch index using shared SynonymManager.", managedAnnotationType, gramType);
            populateSpecificAnnotationSynonyms(managedAnnotationType); // Abstract method for subclasses
            logger.info("Finished populating specific annotation synonyms for {} of type {}.", getIndexName(), managedAnnotationType);
        } catch (SQLException | IOException e) {
            throw new UncheckedIOException("Failed to populate " + managedAnnotationType + " annotation synonyms for " + getIndexName(), e instanceof IOException ? (IOException)e : new IOException(e));
        }
    }

    // Abstract methods to be implemented by concrete (POS/NER/Date) N-gram stitch generators
    protected abstract void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException;
    protected abstract List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException;
    protected abstract boolean requiresSynonymIdForAnnotationValue();
    protected abstract AnnotationType getManagedAnnotationType();

    /**
     * Filters a list of annotation records, removing those that fall outside the
     * MAX_SENTENCE_CHAR_SPAN_FROM_FIRST_TOKEN limit within each sentence.
     *
     * @param rawAnnotations The list of raw annotation records to filter.
     * @param documentId The ID of the document being processed (for logging).
     * @param generatorTypeForLog A string indicating the type of generator (e.g., "Unigram NER", "Bigram Date") for logging.
     * @param <T> The type of the annotation record, must implement SentenceSpanFilterable.
     * @return A new list containing only the filtered annotation records.
     */
    protected <T extends SentenceSpanFilterable> List<T> filterAnnotationsBySentenceCharacterSpan(
            List<T> rawAnnotations, int documentId, String generatorTypeForLog) {

        if (rawAnnotations.isEmpty()) {
            return new ArrayList<>(); // Return empty list if input is empty
        }

        List<T> filteredAnnotations = new ArrayList<>();
        java.util.Map<Integer, Integer> firstTokenBeginCharPerSentence = new java.util.HashMap<>();
        java.util.Set<Integer> truncatedSentencesLog = new java.util.HashSet<>(); // To log truncation only once per sentence

        for (T rawAnno : rawAnnotations) {
            int sentenceId = rawAnno.sentenceId();
            int currentTokenBeginChar = rawAnno.beginChar();

            if (!firstTokenBeginCharPerSentence.containsKey(sentenceId)) {
                firstTokenBeginCharPerSentence.put(sentenceId, currentTokenBeginChar);
            }

            int firstCharInSentence = firstTokenBeginCharPerSentence.get(sentenceId);
            if (currentTokenBeginChar <= firstCharInSentence + MAX_SENTENCE_CHAR_SPAN_FROM_FIRST_TOKEN) {
                filteredAnnotations.add(rawAnno);
            } else {
                if (!truncatedSentencesLog.contains(sentenceId)) {
                    logger.trace("Sentence (doc_id: {}, sentence_id: {}) annotation processing truncated for {}. Token with begin_char {} (detail: '{}') exceeded limit (first_token_begin_char {} + span {}).",
                            documentId,
                            sentenceId,
                            generatorTypeForLog,
                            currentTokenBeginChar,
                            rawAnno.getFilterLogDetail(),
                            firstCharInSentence,
                            MAX_SENTENCE_CHAR_SPAN_FROM_FIRST_TOKEN);
                    truncatedSentencesLog.add(sentenceId);
                }
            }
        }
        return filteredAnnotations;
    }

    @Override
    protected List<NgramStitchEntry> fetchBatch(NgramStitchEntry lastStitchEntryFromPreviousOverallBatch) throws SQLException {
        List<NgramStitchEntry> currentStitchEntriesForBatch = new ArrayList<>();
        while (currentStitchEntriesForBatch.size() < this.batchSize) {
            Integer currentDocumentId = getNextDocumentId();
            if (currentDocumentId == null) {
                String gramType = (N == 1) ? "unigram" : N + "-gram";
                logger.info("No more documents to process for {} {} stitch index.", getIndexName(), gramType);
                break;
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
                    return null;
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

    protected void processDocument(int documentId, List<NgramStitchEntry> entries) throws SQLException {
        Map<Integer, List<NgramData>> ngramsBySentence = fetchNgramsForDocumentInternal(documentId);
        if (ngramsBySentence.isEmpty()) {
            return;
        }

        List<AnnotationData> annotations = fetchAnnotationsForDocument(documentId); // Implemented by subclass
        if (annotations.isEmpty()) {
            return;
        }

        for (AnnotationData annotation : annotations) {
            int specificValueId = -1;
            if (requiresSynonymIdForAnnotationValue()) { // Implemented by subclass
                try {
                    specificValueId = synonymManager.getId(annotation.specificValueForSynonym());
                } catch (IllegalArgumentException e) {
                    String gramType = (N == 1) ? "unigram" : "N-gram";
                    logger.debug("Skipping annotation for {} stitch due to invalid specific value for synonym: {} - {} (index {})", gramType, annotation.specificValueForSynonym(), e.getMessage(), getIndexName());
                    continue;
                } catch (org.rocksdb.RocksDBException e) {
                    logger.error("RocksDB error getting ID for specific value synonym '{}' (index {}): {}", annotation.specificValueForSynonym(), getIndexName(), e.getMessage(), e);
                    continue;
                }
            } else {
                 logger.trace("Synonym ID lookup skipped for specific value from AnnotationData ('{}') as per requiresSynonymIdForAnnotationValue() for index type {}. NgramStitchEntry will use specificValueId: {}", annotation.specificValueForSynonym(), getIndexName(), specificValueId);
            }

            List<NgramData> ngramsInSentence = ngramsBySentence.getOrDefault(annotation.sentenceId(), Collections.emptyList());
            for (NgramData ngram : ngramsInSentence) {
                entries.add(new NgramStitchEntry(
                        documentId,
                        annotation.sentenceId(),
                        ngram.beginChar(),
                        ngram.endChar(),
                        ngram.ngramKey(),
                        annotation.annotationKeyComponent(),
                        specificValueId,
                        annotation.beginChar(),
                        annotation.endChar()
                ));
            }
        }
    }

    private Map<Integer, List<NgramData>> fetchNgramsForDocumentInternal(int documentId) throws SQLException {
        Map<Integer, List<ProcessedTokenInfo>> tokensBySentence = new HashMap<>();
        String sql = """
            SELECT sentence_id, begin_char, end_char, token
            FROM annotations
            WHERE document_id = ? AND pos NOT IN ('FW', 'ADD')
            ORDER BY sentence_id, begin_char
        """;

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String token = rs.getString("token");
                    String normalizedToken = token.toLowerCase();

                    if (isStopword(normalizedToken) || !isValidToken(normalizedToken)) {
                        continue;
                    }
                    tokensBySentence.computeIfAbsent(rs.getInt("sentence_id"), k -> new ArrayList<>())
                                    .add(new ProcessedTokenInfo(normalizedToken, rs.getInt("begin_char"), rs.getInt("end_char")));
                }
            }
        }

        Map<Integer, List<NgramData>> ngramsBySentence = new HashMap<>();
        for (Map.Entry<Integer, List<ProcessedTokenInfo>> entry : tokensBySentence.entrySet()) {
            List<ProcessedTokenInfo> originalSentenceTokens = entry.getValue();
            Integer sentenceId = entry.getKey(); // Get sentenceId for logging

            if (originalSentenceTokens.isEmpty()) {
                continue;
            }

            List<ProcessedTokenInfo> effectiveSentenceTokens = new ArrayList<>();
            int firstTokenBeginChar = originalSentenceTokens.get(0).beginChar();

            for (ProcessedTokenInfo tokenInfo : originalSentenceTokens) {
                if (tokenInfo.beginChar() > firstTokenBeginChar + MAX_SENTENCE_CHAR_SPAN_FROM_FIRST_TOKEN) {
                    logger.debug("Sentence (doc_id: {}, sentence_id: {}) truncated for indexing. Token with begin_char {} (text: '{}') exceeded limit (first_token_begin_char {} + span {}). Last token included had begin_char: {}.",
                        documentId,
                        sentenceId,
                        tokenInfo.beginChar(),
                        tokenInfo.token(), // Added token text for better logging
                        firstTokenBeginChar,
                        MAX_SENTENCE_CHAR_SPAN_FROM_FIRST_TOKEN,
                        (effectiveSentenceTokens.isEmpty() ? "N/A" : effectiveSentenceTokens.get(effectiveSentenceTokens.size()-1).beginChar())
                    );
                    break; // Stop adding tokens from this sentence
                }
                effectiveSentenceTokens.add(tokenInfo);
            }

            if (effectiveSentenceTokens.size() < N) {
                continue;
            }

            List<NgramData> sentenceNgrams = new ArrayList<>();
            if (N == 1) { // Handle Unigrams
                for (ProcessedTokenInfo tokenInfo : effectiveSentenceTokens) { // Use effective list
                    sentenceNgrams.add(new NgramData(tokenInfo.beginChar(), tokenInfo.endChar(), tokenInfo.token()));
                }
            } else { // Handle N-grams (N > 1)
                for (int i = 0; i <= effectiveSentenceTokens.size() - N; i++) { // Use effective list
                    List<String> ngramComponentTokens = new ArrayList<>();
                    for (int j = 0; j < N; j++) {
                        ngramComponentTokens.add(effectiveSentenceTokens.get(i + j).token()); // Use effective list
                    }
                    String ngramKey = String.join(String.valueOf(IndexAccessInterface.DELIMITER), ngramComponentTokens);
                    int ngramBeginChar = effectiveSentenceTokens.get(i).beginChar(); // Use effective list
                    int ngramEndChar = effectiveSentenceTokens.get(i + N - 1).endChar(); // Use effective list
                    sentenceNgrams.add(new NgramData(ngramBeginChar, ngramEndChar, ngramKey));
                }
            }
            if (!sentenceNgrams.isEmpty()) {
                ngramsBySentence.put(sentenceId, sentenceNgrams);
            }
        }
        return ngramsBySentence;
    }

    /**
     * Validates if a token should be included in the index.
     * Uses a middle-ground approach: keeps mixed alphanumeric tokens but filters out
     * purely numeric or purely punctuation tokens.
     *
     * @param token The token to validate (should be lowercase)
     * @return true if the token should be indexed, false otherwise
     */
    private static boolean isValidToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        // Filter out tokens starting with apostrophe (parsing artifacts)
        if (token.startsWith("'")) {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasOther = false;

        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasOther = true;
            }
        }

        // Keep tokens that have at least one letter
        // This includes: pure letters, letters+digits, letters+punctuation, letters+digits+punctuation
        // Filters out: pure numbers, pure punctuation, digits+punctuation (no letters)
        return hasLetter;
    }

    @Override
    protected ListMultimap<String, PositionListSoA> processBatch(List<NgramStitchEntry> batch) {
        ListMultimap<String, PositionListSoA> indexData = ArrayListMultimap.create();
        Map<String, PositionListSoA> tempAggregator = new HashMap<>();
        int filteredCount = 0;

        for (NgramStitchEntry entry : batch) {
            String compositeKey = entry.value();
            String ngramKeyForFiltering = entry.ngramKey();

            if (ngramKeyForFiltering == null || ngramKeyForFiltering.isEmpty() ||
                entry.annotationKeyComponent() == null || entry.annotationKeyComponent().isEmpty()) {
                logger.trace("Filtered N-gram stitch entry due to null/empty N-gram key or annotation component. Key: '{}', Annotation: '{}'", ngramKeyForFiltering, entry.annotationKeyComponent());
                filteredCount++;
                continue;
            }

            PositionListSoA pl = tempAggregator.computeIfAbsent(compositeKey, k -> new PositionListSoA());
            pl.add(
                entry.documentId(),
                entry.sentenceId(),
                entry.ngramBeginChar(),
                entry.ngramEndChar(),
                entry.specificValueSynonymId()
            );
        }

        for (Map.Entry<String, PositionListSoA> mapEntry : tempAggregator.entrySet()) {
            indexData.put(mapEntry.getKey(), mapEntry.getValue());
        }
        return indexData;
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        return -1; // Indicates indeterminate progress for stitch indexes
    }

    @Override
    protected void writeToLevelDB(File sortedFile) throws IOException {
        logger.info("Using parent IndexGenerator.writeToLevelDB for {} from sorted file: {}", getIndexName(), sortedFile.getAbsolutePath());
        super.writeToLevelDB(sortedFile);
    }

    @Override
    public void close() throws IOException {
        super.close();
    }

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