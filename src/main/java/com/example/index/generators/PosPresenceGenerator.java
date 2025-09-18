package com.example.index.generators;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
 * Builds a presence-with-bindings index for POS tags at sentence granularity.
 * Key schema: {TAG}{DELIMITER}{docId}
 * Value: PositionListSoA containing tuples (docId, sentId, 0, 0) with synonymId set to tokenId.
 * Ensures uniqueness of (sentId, tokenId) pairs per key.
 */
public final class PosPresenceGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(PosPresenceGenerator.class);

    private static final String POS_TAGS_TO_EXCLUDE_SQL = POSIndexGenerator.POS_TAGS_TO_EXCLUDE_SQL;

    private final SynonymManager synonymManager;

    public PosPresenceGenerator(IndexAccessInterface indexAccess,
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

    public PosPresenceGenerator(IndexAccessInterface indexAccess,
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
        String baseSql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos " +
                         "FROM annotations WHERE pos != '' AND pos NOT IN " + POS_TAGS_TO_EXCLUDE_SQL + " ";

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
                        null,
                        null
                    ));
                }
            }
        }
        return batch;
    }

    @Override
    protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<String, PositionListSoA> out = ArrayListMultimap.create();
        if (batch.isEmpty()) return out;

        // Aggregator: key -> tokenId -> unique sentenceIds
        Map<String, Map<Integer, Set<Integer>>> presence = new HashMap<>();

        for (AnnotationEntry e : batch) {
            if (e.getPos() == null || e.getPos().isEmpty() || e.getToken() == null || e.getToken().isEmpty()) {
                continue;
            }
            String tag = e.getPos().toUpperCase();
            String token = e.getToken().toLowerCase();
            try {
                int tokenId = synonymManager.getId(token);
                String key = tag + IndexGenerator.DELIMITER + e.getDocumentId();
                Map<Integer, Set<Integer>> tokenToSents = presence.computeIfAbsent(key, k -> new HashMap<>());
                Set<Integer> sents = tokenToSents.computeIfAbsent(tokenId, k -> new HashSet<>());
                sents.add(e.getSentenceId());
            } catch (RocksDBException ex) {
                logger.error("RocksDBException while getting ID for token '{}' with POS tag '{}'.", token, tag, ex);
            }
        }

        // Materialize to PositionListSoA per key
        for (Map.Entry<String, Map<Integer, Set<Integer>>> keyEntry : presence.entrySet()) {
            String key = keyEntry.getKey();
            int docId = parseDocIdFromPresenceKey(key);
            PositionListSoA pl = new PositionListSoA();
            Map<Integer, Set<Integer>> idToSents = keyEntry.getValue();
            for (Map.Entry<Integer, Set<Integer>> idEntry : idToSents.entrySet()) {
                int synId = idEntry.getKey();
                List<Integer> sortedSents = new ArrayList<>(idEntry.getValue());
                sortedSents.sort(Integer::compare);
                for (int sentId : sortedSents) {
                    pl.add(docId, sentId, 0, 0, synId);
                }
            }
            out.put(key, pl);
        }
        return out;
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


