package com.example.index.generators;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;

import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.index.AnnotationEntry;
import com.example.index.IndexKey;
import com.example.index.KeySchema;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * Generates a streaming index for named entities from annotated text.
 * Processes all NER types except DATE (which has its own dedicated index).
 * Keys are encoded with {@link KeySchema}: {@code TYPE\0<4-byte synId>}.
 * Specific entity values (e.g., "John Doe") are mapped to integer IDs using a
 * shared SynonymManager,
 * and these IDs are encoded in the key rather than stored in the value blob.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class NerIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(NerIndexGenerator.class);

    public static final String NER_TAGS_TO_EXCLUDE_SQL = "('O', 'DATE')";

    private final SynonymManager synonymManager;

    public NerIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize,
            SynonymManager sharedSynonymManager) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null, sharedSynonymManager);
    }

    public NerIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn,
            ProgressTracker progress, int batchSize, Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
        if (sharedSynonymManager == null) {
            throw new IllegalArgumentException("Shared SynonymManager cannot be null.");
        }
        this.synonymManager = sharedSynonymManager;
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String sql;
        if (lastProcessedEntry == null) {
            sql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner "
                    +
                    "FROM annotations WHERE ner NOT IN " + NER_TAGS_TO_EXCLUDE_SQL + " " +
                    "ORDER BY annotation_id LIMIT ?";
        } else {
            sql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner "
                    +
                    "FROM annotations WHERE ner NOT IN " + NER_TAGS_TO_EXCLUDE_SQL + " AND annotation_id > ? " +
                    "ORDER BY annotation_id LIMIT ?";
        }

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            if (lastProcessedEntry == null) {
                stmt.setInt(1, batchSize);
            } else {
                stmt.setLong(1, lastProcessedEntry.getAnnotationId());
                stmt.setInt(2, batchSize);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    batch.add(new AnnotationEntry(
                            rs.getLong("annotation_id"),
                            rs.getInt("document_id"),
                            rs.getInt("sentence_id"),
                            rs.getInt("begin_char"),
                            rs.getInt("end_char"),
                            rs.getString("token"),
                            rs.getString("pos"),
                            rs.getString("ner"),
                            rs.getString("normalized_ner")));
                }
            }
        }
        // Ensure we don't split inside a sentence: extend batch to include the rest of
        // the trailing sentence
        if (!batch.isEmpty()) {
            AnnotationEntry last = batch.get(batch.size() - 1);
            int lastDocId = last.getDocumentId();
            int lastSentId = last.getSentenceId();
            long lastAnnoId = last.getAnnotationId();

            String extendSql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner "
                    +
                    "FROM annotations " +
                    "WHERE ner NOT IN " + NER_TAGS_TO_EXCLUDE_SQL
                    + " AND document_id = ? AND sentence_id = ? AND annotation_id > ? " +
                    "ORDER BY annotation_id";
            try (PreparedStatement extendStmt = sqliteConn.prepareStatement(extendSql)) {
                extendStmt.setInt(1, lastDocId);
                extendStmt.setInt(2, lastSentId);
                extendStmt.setLong(3, lastAnnoId);
                try (ResultSet rs2 = extendStmt.executeQuery()) {
                    while (rs2.next()) {
                        batch.add(new AnnotationEntry(
                                rs2.getLong("annotation_id"),
                                rs2.getInt("document_id"),
                                rs2.getInt("sentence_id"),
                                rs2.getInt("begin_char"),
                                rs2.getInt("end_char"),
                                rs2.getString("token"),
                                rs2.getString("pos"),
                                rs2.getString("ner"),
                                rs2.getString("normalized_ner")));
                    }
                }
            }
        }

        return batch;
    }

    @Override
    protected ListMultimap<IndexKey, PostingList> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<IndexKey, PostingList> resultMultimap = ArrayListMultimap.create();
        if (batch.isEmpty()) {
            return resultMultimap;
        }

        // Sort the entire batch by position. This is all that's needed for the merging
        // logic.
        batch.sort(Comparator
                .comparingInt(AnnotationEntry::getDocumentId)
                .thenComparingInt(AnnotationEntry::getSentenceId)
                .thenComparingInt(AnnotationEntry::getBeginChar));

        // Key: indexKey (IndexKey from KeySchema), Value: (cellKey -> list of
        // beginChars)
        Map<IndexKey, Map<Long, List<Integer>>> perTermData = new HashMap<>();
        Map<IndexKey, Byte> perTermConstantLength = new HashMap<>();

        AnnotationEntry prevEntry = null;
        List<String> currentEntityRawTokens = new ArrayList<>();
        String currentEntityType = null;
        int currentEntityDocId = -1;
        int currentEntitySentId = -1;
        int currentEntityBeginChar = -1;

        // Entity merging logic now uses the sorted batch directly
        for (AnnotationEntry entry : batch) {
            String nerTag = entry.getNer();
            boolean entityBreak = false;

            if (currentEntityType != null) {
                if (nerTag == null || "O".equals(nerTag) || "DATE".equals(nerTag) ||
                        !nerTag.equals(currentEntityType) ||
                        entry.getDocumentId() != currentEntityDocId ||
                        entry.getSentenceId() != currentEntitySentId ||
                        (prevEntry != null && entry.getBeginChar() > prevEntry.getEndChar() + 2)) {
                    entityBreak = true;
                }
            }

            if (entityBreak) {
                if (!currentEntityRawTokens.isEmpty() && prevEntry != null) {
                    try {
                        addProcessedEntityToMap(perTermData, perTermConstantLength, currentEntityType,
                                currentEntityRawTokens,
                                currentEntityDocId, currentEntitySentId,
                                currentEntityBeginChar, prevEntry.getEndChar());
                    } catch (RocksDBException e) {
                        logger.error(
                                "RocksDBException while processing entity for NerIndexGenerator. Entity type: {}, tokens: {}. Error: {}",
                                currentEntityType, currentEntityRawTokens, e.getMessage(), e);
                    }
                }
                currentEntityRawTokens.clear();
                currentEntityType = null;
            }

            if (nerTag != null && !nerTag.isEmpty() && !"O".equals(nerTag) && !"DATE".equals(nerTag)) {
                if (currentEntityType == null) {
                    currentEntityType = nerTag;
                    currentEntityDocId = entry.getDocumentId();
                    currentEntitySentId = entry.getSentenceId();
                    currentEntityBeginChar = entry.getBeginChar();
                }
                currentEntityRawTokens.add(entry.getToken());
            }
            prevEntry = entry;
        }

        if (currentEntityType != null && !currentEntityRawTokens.isEmpty() && prevEntry != null) {
            try {
                addProcessedEntityToMap(perTermData, perTermConstantLength, currentEntityType,
                        currentEntityRawTokens,
                        currentEntityDocId, currentEntitySentId,
                        currentEntityBeginChar, prevEntry.getEndChar());
            } catch (RocksDBException e) {
                logger.error(
                        "RocksDBException while processing final entity for NerIndexGenerator. Entity type: {}, tokens: {}. Error: {}",
                        currentEntityType, currentEntityRawTokens, e.getMessage(), e);
            }
        }

        // Convert per-term aggregation maps to PostingLists
        for (Map.Entry<IndexKey, Map<Long, List<Integer>>> mapEntry : perTermData.entrySet()) {
            IndexKey indexKey = mapEntry.getKey();
            Map<Long, List<Integer>> cellMap = mapEntry.getValue();
            byte constLen = perTermConstantLength.getOrDefault(indexKey, (byte) 0);
            PostingList pl = buildPostingList(cellMap, constLen);
            resultMultimap.put(indexKey, pl);
        }
        return resultMultimap;
    }

    private void addProcessedEntityToMap(Map<IndexKey, Map<Long, List<Integer>>> perTermData,
            Map<IndexKey, Byte> perTermConstantLength,
            String entityType,
            List<String> rawTokens, int docId, int sentId,
            int beginChar, int endChar) throws RocksDBException {
        if (entityType == null || rawTokens.isEmpty() || beginChar == -1 || endChar == -1 || endChar < beginChar) {
            logger.warn("Skipping invalid entity: type={}, tokens={}, doc={}, sent={}, begin={}, end={}",
                    entityType, rawTokens, docId, sentId, beginChar, endChar);
            return;
        }

        String entityValue = String.join(" ", rawTokens).toLowerCase();
        int entityValueId = synonymManager.getId(entityValue);

        byte[] indexKeyBytes = KeySchema.encodeKey(entityType.toUpperCase(), entityValueId);
        IndexKey indexKey = IndexKey.fromBytes(indexKeyBytes);

        long cellKey = PostingList.packCellKey(docId, sentId);

        Map<Long, List<Integer>> cellMap = perTermData.computeIfAbsent(indexKey, k -> new HashMap<>());
        cellMap.computeIfAbsent(cellKey, k -> new ArrayList<>()).add(beginChar);

        // Track constantLength (use the first one seen for this key)
        perTermConstantLength.putIfAbsent(indexKey, (byte) Math.min(endChar - beginChar, 255));
    }

    /**
     * Builds a {@link PostingList} from a per-cell map of begin character offsets.
     *
     * @param cellMap        map from packed cell key to list of begin character
     *                       offsets
     * @param constantLength the constant span length for all occurrences in this
     *                       list
     * @return a new PostingList with cells and occurrences
     */
    private static PostingList buildPostingList(Map<Long, List<Integer>> cellMap, byte constantLength) {
        int numCells = cellMap.size();
        if (numCells == 0) {
            return PostingList.empty(constantLength);
        }

        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        long[] cellKeys = new long[numCells];
        byte[][] beginsPerCell = new byte[numCells][];
        int idx = 0;

        for (Map.Entry<Long, List<Integer>> entry : cellMap.entrySet()) {
            long cellKey = entry.getKey();
            cells.add(cellKey);
            cellKeys[idx] = cellKey;
            List<Integer> begins = entry.getValue();
            byte[] beginsArr = new byte[begins.size()];
            for (int j = 0; j < begins.size(); j++) {
                beginsArr[j] = (byte) (int) begins.get(j);
            }
            beginsPerCell[idx] = beginsArr;
            idx++;
        }

        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(cellKeys, beginsPerCell, constantLength);
        return PostingList.fromCellsAndOccurrences(cells, constantLength, occ);
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        return 0;
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
