package com.example.index.generators;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * Generates a streaming index for named entities from annotated text.
 * Processes all NER types except DATE (which has its own dedicated index).
 * The primary key is the entity type (e.g., "PERSON").
 * Specific entity values (e.g., "John Doe") are mapped to integer IDs using a shared SynonymManager,
 * and these IDs are stored in PositionListSoA.synonymId.
 * Uses streaming processing and external sorting for efficient memory usage.
 *
 * This implementation is now RocksDB-based (see IndexGenerator).
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

    public NerIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath,
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
            sql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner, lemma " +
                  "FROM annotations WHERE ner IS NOT NULL AND ner != 'O' AND ner != 'DATE' " +
                  "ORDER BY annotation_id LIMIT ?";
        } else {
            sql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner, lemma " +
                  "FROM annotations WHERE ner IS NOT NULL AND ner != 'O' AND ner != 'DATE' AND annotation_id > ? " +
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
                        rs.getString("normalized_ner"),
                        rs.getString("lemma")
                    ));
                }
            }
        }
        return batch;
    }

    @Override
    protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<String, PositionListSoA> resultMultimap = ArrayListMultimap.create();
        if (batch.isEmpty()) {
            return resultMultimap;
        }

        Map<String, PositionListSoA> currentBatchEntityPositions = new HashMap<>();

        AnnotationEntry prevEntry = null;
        List<String> currentEntityRawTokens = new ArrayList<>();
        String currentEntityType = null;
        int currentEntityDocId = -1;
        int currentEntitySentId = -1;
        int currentEntityBeginChar = -1;

        for (AnnotationEntry entry : batch) {
            String nerTag = entry.getNer();
            boolean entityBreak = false;

            if (currentEntityType != null) {
                if (nerTag == null || "O".equals(nerTag) || "DATE".equals(nerTag) ||
                    !nerTag.equals(currentEntityType) ||
                    entry.getDocumentId() != currentEntityDocId ||
                    entry.getSentenceId() != currentEntitySentId ||
                    (prevEntry != null && entry.getBeginChar() > prevEntry.getEndChar() + 1)
                   ) {
                    entityBreak = true;
                }
            }

            if (entityBreak) {
                if (!currentEntityRawTokens.isEmpty() && prevEntry != null) {
                    try {
                    addProcessedEntityToMap(currentBatchEntityPositions, currentEntityType,
                                            currentEntityRawTokens,
                                            currentEntityDocId, currentEntitySentId,
                                            currentEntityBeginChar, prevEntry.getEndChar());
                    } catch (RocksDBException e) {
                        logger.error("RocksDBException while processing entity for NerIndexGenerator. Entity type: {}, tokens: {}. Error: {}", currentEntityType, currentEntityRawTokens, e.getMessage(), e);
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
             addProcessedEntityToMap(currentBatchEntityPositions, currentEntityType,
                                    currentEntityRawTokens,
                                    currentEntityDocId, currentEntitySentId,
                                    currentEntityBeginChar, prevEntry.getEndChar());
            } catch (RocksDBException e) {
                logger.error("RocksDBException while processing final entity for NerIndexGenerator. Entity type: {}, tokens: {}. Error: {}", currentEntityType, currentEntityRawTokens, e.getMessage(), e);
            }
        }

        for (Map.Entry<String, PositionListSoA> mapEntry : currentBatchEntityPositions.entrySet()) {
            resultMultimap.put(mapEntry.getKey(), mapEntry.getValue());
        }
        return resultMultimap;
    }

    private void addProcessedEntityToMap(Map<String, PositionListSoA> map,
                                         String entityType,
                                         List<String> rawTokens, int docId, int sentId,
                                         int beginChar, int endChar) throws RocksDBException {
        if (entityType == null || rawTokens.isEmpty() || beginChar == -1 || endChar == -1 || endChar < beginChar) {
            logger.warn("Skipping invalid entity: type={}, tokens={}, doc={}, sent={}, begin={}, end={}",
                        entityType, rawTokens, docId, sentId, beginChar, endChar);
            return;
        }

        String entityValue = String.join(" ", rawTokens).toLowerCase();
        String indexKey = entityType.toUpperCase();

        int entityValueId = synonymManager.getId(entityValue);

        PositionListSoA pl = map.computeIfAbsent(indexKey, k -> new PositionListSoA());
        pl.add(docId, sentId, beginChar, endChar, entityValueId);
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