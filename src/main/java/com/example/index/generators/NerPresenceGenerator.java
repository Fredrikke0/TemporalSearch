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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Builds a presence-with-bindings index for NER types at sentence granularity.
 * Key schema: {TYPE}{DELIMITER}{docId}
 * Value: PositionListSoA containing tuples (docId, sentId, 0, 0) with synonymId set to synId.
 * Each (sentId, synId) pair is unique per key.
 */
public final class NerPresenceGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(NerPresenceGenerator.class);

    private final SynonymManager synonymManager;

    public NerPresenceGenerator(IndexAccessInterface indexAccess,
                                String stopwordsPath,
                                Connection sqliteConn,
                                ProgressTracker progress,
                                int batchSize,
                                Path customTempPath,
                                SynonymManager sharedSynonymManager) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
        if (sharedSynonymManager == null) {
            throw new IllegalArgumentException("Shared SynonymManager cannot be null.");
        }
        this.synonymManager = sharedSynonymManager;
    }

    public NerPresenceGenerator(IndexAccessInterface indexAccess,
                                String stopwordsPath,
                                Connection sqliteConn,
                                ProgressTracker progress,
                                int batchSize,
                                SynonymManager sharedSynonymManager) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null, sharedSynonymManager);
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String baseSql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner " +
                         "FROM annotations WHERE ner != 'O' AND ner != 'DATE' ";

        String sql;
        if (lastProcessedEntry == null) {
            sql = baseSql + "ORDER BY annotation_id LIMIT ?";
        } else {
            sql = baseSql + "AND annotation_id > ? ORDER BY annotation_id LIMIT ?";
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
                        rs.getString("normalized_ner")
                    ));
                }
            }
        }

        // Extend to not split inside a sentence (same as NerIndexGenerator)
        if (!batch.isEmpty()) {
            AnnotationEntry last = batch.get(batch.size() - 1);
            int lastDocId = last.getDocumentId();
            int lastSentId = last.getSentenceId();
            long lastAnnoId = last.getAnnotationId();

            String extendSql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner " +
                               "FROM annotations " +
                               "WHERE ner != 'O' AND ner != 'DATE' AND document_id = ? AND sentence_id = ? AND annotation_id > ? " +
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
                            rs2.getString("normalized_ner")
                        ));
                    }
                }
            }
        }

        return batch;
    }

    @Override
    protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<String, PositionListSoA> out = ArrayListMultimap.create();
        if (batch.isEmpty()) return out;

        // Sort to reconstruct entities reliably (same as NerIndexGenerator)
        batch.sort(Comparator
            .comparingInt(AnnotationEntry::getDocumentId)
            .thenComparingInt(AnnotationEntry::getSentenceId)
            .thenComparingInt(AnnotationEntry::getBeginChar));

        // Aggregator: key -> synId -> unique sentenceIds
        Map<String, Map<Integer, Set<Integer>>> presence = new HashMap<>();

        AnnotationEntry prev = null;
        List<String> currentTokens = new ArrayList<>();
        String currentType = null;
        int currentDoc = -1;
        int currentSent = -1;
        int currentBegin = -1;

        for (AnnotationEntry e : batch) {
            String nerTag = e.getNer();
            boolean entityBreak = false;
            if (currentType != null) {
                if (nerTag == null || "O".equals(nerTag) || "DATE".equals(nerTag) ||
                    !nerTag.equals(currentType) ||
                    e.getDocumentId() != currentDoc ||
                    e.getSentenceId() != currentSent ||
                    (prev != null && e.getBeginChar() > prev.getEndChar() + 2)) {
                    entityBreak = true;
                }
            }
            if (entityBreak) {
                if (!currentTokens.isEmpty() && prev != null) {
                    addEntityToPresence(presence, currentType, currentTokens, currentDoc, currentSent);
                }
                currentTokens.clear();
                currentType = null;
            }

            if (nerTag != null && !nerTag.isEmpty() && !"O".equals(nerTag) && !"DATE".equals(nerTag)) {
                if (currentType == null) {
                    currentType = nerTag;
                    currentDoc = e.getDocumentId();
                    currentSent = e.getSentenceId();
                    currentBegin = e.getBeginChar();
                }
                currentTokens.add(e.getToken());
            }
            prev = e;
        }
        if (currentType != null && !currentTokens.isEmpty() && prev != null) {
            addEntityToPresence(presence, currentType, currentTokens, currentDoc, currentSent);
        }

        // Materialize to PositionListSoA per key
        for (Map.Entry<String, Map<Integer, Set<Integer>>> keyEntry : presence.entrySet()) {
            String key = keyEntry.getKey();
            PositionListSoA pl = new PositionListSoA();
            Map<Integer, Set<Integer>> synToSents = keyEntry.getValue();
            for (Map.Entry<Integer, Set<Integer>> synEntry : synToSents.entrySet()) {
                int synId = synEntry.getKey();
                // Ensure deterministic order: sort sentence IDs
                List<Integer> sortedSents = new ArrayList<>(synEntry.getValue());
                sortedSents.sort(Integer::compare);
                int docId = parseDocIdFromPresenceKey(key);
                for (int sentId : sortedSents) {
                    pl.add(docId, sentId, 0, 0, synId);
                }
            }
            out.put(key, pl);
        }
        return out;
    }

    private void addEntityToPresence(Map<String, Map<Integer, Set<Integer>>> presence,
                                     String entityType,
                                     List<String> tokens,
                                     int docId,
                                     int sentId) {
        if (entityType == null || tokens.isEmpty()) return;
        String value = String.join(" ", tokens).toLowerCase();
        String type = entityType.toUpperCase();
        try {
            int synId = synonymManager.getId(value);
            String key = type + IndexGenerator.DELIMITER + docId;
            Map<Integer, Set<Integer>> synMap = presence.computeIfAbsent(key, k -> new HashMap<>());
            Set<Integer> sentSet = synMap.computeIfAbsent(synId, k -> new HashSet<>());
            sentSet.add(sentId);
        } catch (RocksDBException e) {
            logger.error("RocksDBException while getting ID for entity '{}' (type '{}')", value, entityType, e);
        }
    }

    private int parseDocIdFromPresenceKey(String key) {
        int idx = key.lastIndexOf(IndexGenerator.DELIMITER);
        if (idx < 0 || idx + 1 >= key.length()) return -1;
        try {
            return Integer.parseInt(key.substring(idx + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        return 0;
    }
}


