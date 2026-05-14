package com.example.index.generators.stitch;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.index.AnnotationType;
import com.example.index.IndexEntry;
import com.example.index.KeySchema;
import com.example.index.generators.IndexGenerator;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import it.unimi.dsi.fastutil.ints.IntArrayList;

public abstract class AbstractNgramStitchGenerator
        extends IndexGenerator<AbstractNgramStitchGenerator.NgramStitchEntry> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractNgramStitchGenerator.class);

    protected final SynonymManager synonymManager;
    protected final int N;
    private Integer lastProcessedDocumentId = null;

    /**
     * Interface for annotation records that can be filtered by sentence character
     * span.
     */
    protected interface SentenceSpanFilterable {
        int sentenceId();

        int beginChar();

        String getFilterLogDetail();
    }

    public record AnnotationData(
            int sentenceId,
            int beginChar,
            int endChar,
            String annotationKeyComponent,
            String specificValueForSynonym) implements SentenceSpanFilterable {
        @Override
        public String getFilterLogDetail() {
            return annotationKeyComponent;
        }
    }

    private record ProcessedTokenInfo(String token, int beginChar, int endChar) {
    }

    protected record NgramData(int beginChar, int endChar, String ngramKey) {
    }

    protected record NgramStitchEntry(
            int documentId,
            int sentenceId,
            int ngramBeginChar,
            int ngramEndChar,
            String ngramKey,
            String annotationKeyComponent,
            int specificValueSynonymId,
            int annotationBeginChar,
            int annotationEndChar) implements IndexEntry {

        @Override
        public int getDocumentId() {
            return this.documentId;
        }

        @Override
        public int getSentenceId() {
            return this.sentenceId;
        }

        @Override
        public int getBeginChar() {
            return this.ngramBeginChar;
        }

        @Override
        public int getEndChar() {
            return this.ngramEndChar;
        }

        /** Returns the composite key with synId encoded for the temp file. */
        public String value() {
            return ngramKey + IndexAccessInterface.DELIMITER + annotationKeyComponent
                    + IndexAccessInterface.DELIMITER + specificValueSynonymId;
        }

        public long getAnnotationId() {
            return -1;
        }
    }

    @SuppressWarnings("this-escape")
    protected AbstractNgramStitchGenerator(
            int nValue,
            IndexAccessInterface indexAccess,
            String stopwordsPathString,
            Connection sqliteConnParam,
            ProgressTracker progressTrackerParam,
            int batchSizeParam,
            Path customSortTempParam,
            SynonymManager sharedSynonymManager,
            AnnotationType managedAnnotationType) throws IOException {
        super(indexAccess, stopwordsPathString, sqliteConnParam, progressTrackerParam, batchSizeParam,
                customSortTempParam);
        if (nValue < 1) {
            throw new IllegalArgumentException("N-gram size (N) must be at least 1.");
        }
        this.N = nValue;
        if (sharedSynonymManager == null) {
            throw new IllegalArgumentException("SynonymManager cannot be null for AbstractNgramStitchGenerator");
        }
        this.synonymManager = sharedSynonymManager;

        try {
            String gramType = (N == 1) ? "unigram" : N + "-gram";
            logger.info("Initializing specific annotation synonyms for {} {} stitch index using shared SynonymManager.",
                    managedAnnotationType, gramType);
            populateSpecificAnnotationSynonyms(managedAnnotationType);
            logger.info("Finished populating specific annotation synonyms for {} of type {}.", getIndexName(),
                    managedAnnotationType);
        } catch (SQLException | IOException e) {
            throw new UncheckedIOException(
                    "Failed to populate " + managedAnnotationType + " annotation synonyms for " + getIndexName(),
                    e instanceof IOException ? (IOException) e : new IOException(e));
        }
    }

    protected abstract void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException;

    protected abstract List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException;

    protected abstract boolean requiresSynonymIdForAnnotationValue();

    protected abstract AnnotationType getManagedAnnotationType();

    @Override
    protected List<NgramStitchEntry> fetchBatch(NgramStitchEntry lastStitchEntryFromPreviousOverallBatch)
            throws SQLException {
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
        if (currentStitchEntriesForBatch.isEmpty() && lastProcessedDocumentId != null
                && !hasMoreDocuments(lastProcessedDocumentId)) {
            logger.info("FetchBatch for {} is returning an empty list because all documents have been processed.",
                    getIndexName());
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

        List<AnnotationData> annotations = fetchAnnotationsForDocument(documentId);
        if (annotations.isEmpty()) {
            return;
        }

        for (AnnotationData annotation : annotations) {
            int specificValueId = -1;
            if (requiresSynonymIdForAnnotationValue()) {
                try {
                    specificValueId = synonymManager.getId(annotation.specificValueForSynonym());
                } catch (IllegalArgumentException e) {
                    String gramType = (N == 1) ? "unigram" : "N-gram";
                    logger.debug(
                            "Skipping annotation for {} stitch due to invalid specific value for synonym: {} - {} (index {})",
                            gramType, annotation.specificValueForSynonym(), e.getMessage(), getIndexName());
                    continue;
                } catch (org.rocksdb.RocksDBException e) {
                    logger.error("RocksDB error getting ID for specific value synonym '{}' (index {}): {}",
                            annotation.specificValueForSynonym(), getIndexName(), e.getMessage(), e);
                    continue;
                }
            } else {
                logger.trace(
                        "Synonym ID lookup skipped for specific value from AnnotationData ('{}') as per requiresSynonymIdForAnnotationValue() for index type {}. NgramStitchEntry will use specificValueId: {}",
                        annotation.specificValueForSynonym(), getIndexName(), specificValueId);
            }

            List<NgramData> ngramsInSentence = ngramsBySentence.getOrDefault(annotation.sentenceId(),
                    Collections.emptyList());
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
                        annotation.endChar()));
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
                    if (token == null || token.isEmpty()) {
                        continue;
                    }
                    String normalizedToken = token.toLowerCase();
                    tokensBySentence.computeIfAbsent(rs.getInt("sentence_id"), k -> new ArrayList<>())
                            .add(new ProcessedTokenInfo(normalizedToken, rs.getInt("begin_char"),
                                    rs.getInt("end_char")));
                }
            }
        }

        Map<Integer, List<NgramData>> ngramsBySentence = new HashMap<>();
        for (Map.Entry<Integer, List<ProcessedTokenInfo>> entry : tokensBySentence.entrySet()) {
            List<ProcessedTokenInfo> sentenceTokens = entry.getValue();
            Integer sentenceId = entry.getKey();

            if (sentenceTokens.isEmpty() || sentenceTokens.size() < N) {
                continue;
            }

            List<NgramData> sentenceNgrams = new ArrayList<>();
            if (N == 1) {
                for (ProcessedTokenInfo tokenInfo : sentenceTokens) {
                    if (isStopword(tokenInfo.token())) {
                        continue;
                    }
                    sentenceNgrams.add(new NgramData(tokenInfo.beginChar(), tokenInfo.endChar(), tokenInfo.token()));
                }
            } else {
                for (int i = 0; i <= sentenceTokens.size() - N; i++) {
                    boolean containsStopword = false;
                    for (int j = 0; j < N; j++) {
                        if (isStopword(sentenceTokens.get(i + j).token())) {
                            containsStopword = true;
                            break;
                        }
                    }
                    if (containsStopword) {
                        continue;
                    }
                    boolean isAdjacent = true;
                    for (int j = 0; j < N - 1; j++) {
                        ProcessedTokenInfo current = sentenceTokens.get(i + j);
                        ProcessedTokenInfo next = sentenceTokens.get(i + j + 1);
                        if (next.beginChar() > current.endChar() + 2) {
                            isAdjacent = false;
                            break;
                        }
                    }
                    if (!isAdjacent) {
                        continue;
                    }

                    List<String> ngramComponentTokens = new ArrayList<>();
                    for (int j = 0; j < N; j++) {
                        ngramComponentTokens.add(sentenceTokens.get(i + j).token());
                    }
                    String ngramKey = String.join(String.valueOf(IndexAccessInterface.DELIMITER), ngramComponentTokens);
                    int ngramBeginChar = sentenceTokens.get(i).beginChar();
                    int ngramEndChar = sentenceTokens.get(i + N - 1).endChar();
                    sentenceNgrams.add(new NgramData(ngramBeginChar, ngramEndChar, ngramKey));
                }
            }
            if (!sentenceNgrams.isEmpty()) {
                ngramsBySentence.put(sentenceId, sentenceNgrams);
            }
        }
        return ngramsBySentence;
    }

    @Override
    protected ListMultimap<String, PostingList> processBatch(List<NgramStitchEntry> batch) {
        ListMultimap<String, PostingList> indexData = ArrayListMultimap.create();
        // Group by composite key: ngramKey \0 annotationKeyComponent \0 synId
        Map<String, Map<Long, IntArrayList>> cellMapsByKey = new HashMap<>();

        for (NgramStitchEntry entry : batch) {
            String ngramKeyForFiltering = entry.ngramKey();
            if (ngramKeyForFiltering == null || ngramKeyForFiltering.isEmpty() ||
                    entry.annotationKeyComponent() == null || entry.annotationKeyComponent().isEmpty()) {
                logger.trace(
                        "Filtered N-gram stitch entry due to null/empty N-gram key or annotation component. Key: '{}', Annotation: '{}'",
                        ngramKeyForFiltering, entry.annotationKeyComponent());
                continue;
            }

            String compositeKey = entry.value(); // ngramKey \0 type \0 synId
            long cellKey = PostingList.packCellKey(entry.documentId(), entry.sentenceId());
            Map<Long, IntArrayList> cellMap = cellMapsByKey.computeIfAbsent(compositeKey, k -> new HashMap<>());
            cellMap.computeIfAbsent(cellKey, k -> new IntArrayList()).add(entry.ngramBeginChar());
        }

        for (Map.Entry<String, Map<Long, IntArrayList>> keyEntry : cellMapsByKey.entrySet()) {
            String compositeKey = keyEntry.getKey();
            Map<Long, IntArrayList> cellMap = keyEntry.getValue();

            Roaring64NavigableMap cells = new Roaring64NavigableMap();
            for (long ck : cellMap.keySet()) {
                cells.add(ck);
            }

            int numCells = cellMap.size();
            long[] cellKeysArr = new long[numCells];
            byte[][] beginsArr = new byte[numCells][];
            int idx = 0;
            for (Map.Entry<Long, IntArrayList> cellEntry : cellMap.entrySet()) {
                cellKeysArr[idx] = cellEntry.getKey();
                IntArrayList beginsList = cellEntry.getValue();
                // Sort and deduplicate: overlapping annotations of same type/value
                // can assign the same ngram beginChar multiple times within a cell.
                beginsList.sort(null);
                int uniqueCount = 0;
                for (int j = 0; j < beginsList.size(); j++) {
                    if (j == 0 || beginsList.getInt(j) != beginsList.getInt(j - 1)) {
                        uniqueCount++;
                    }
                }
                byte[] begins = new byte[uniqueCount];
                int outIdx = 0;
                for (int j = 0; j < beginsList.size(); j++) {
                    if (j == 0 || beginsList.getInt(j) != beginsList.getInt(j - 1)) {
                        begins[outIdx++] = (byte) beginsList.getInt(j);
                    }
                }
                beginsArr[idx] = begins;
                idx++;
            }

            // constantLength is not critical for stitch indexes (ngram spans vary)
            byte constLen = 0;
            OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(cellKeysArr, beginsArr, constLen);
            PostingList pl = PostingList.fromCellsAndOccurrences(cells, constLen, occ);
            indexData.put(compositeKey, pl);
        }
        return indexData;
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        return -1;
    }

    /**
     * Overrides parent to convert the string-based keys (with decimal synId suffix)
     * to proper KeySchema binary keys before writing to RocksDB.
     */
    @Override
    protected void writeToLevelDB(File sortedFile) throws IOException {
        logger.info("Stitch writeToLevelDB for {} from sorted file: {}", getIndexName(),
                sortedFile.getAbsolutePath());
        // Use parent implementation which handles PostingList deserialization.
        // The keys in the temp file include synId as decimal string suffix.
        // Parent's writeToLevelDB will pass them through as UTF-8 bytes.
        // For stitch indexes, we convert to KeySchema binary format before writing.
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
