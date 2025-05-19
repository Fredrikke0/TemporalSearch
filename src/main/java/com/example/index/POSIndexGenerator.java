package com.example.index;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.example.logging.ProgressTracker;
import com.example.core.Position;
import com.example.core.PositionList;

/**
 * Generates a streaming POS (Part-of-Speech) index from annotation entries.
 * Each entry maps a POS tag to its positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class POSIndexGenerator extends IndexGenerator<AnnotationEntry> {

    public POSIndexGenerator(String indexBaseDir, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public POSIndexGenerator(String indexBaseDir, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    protected String getIndexName() {
        return "pos";
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String query;
        boolean isFirstBatch = (lastProcessedEntry == null);

        if (isFirstBatch) {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos " +
                    "FROM annotations ORDER BY annotation_id LIMIT ?";
        } else {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos " +
                    "FROM annotations WHERE annotation_id > ? ORDER BY annotation_id LIMIT ?";
        }
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            if (isFirstBatch) {
                stmt.setInt(1, this.batchSize);
            } else {
                stmt.setInt(1, lastProcessedEntry.getAnnotationId());
                stmt.setInt(2, this.batchSize);
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AnnotationEntry entry = new AnnotationEntry(
                        rs.getInt("annotation_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        rs.getString("token"),
                        rs.getString("pos"),
                        null, // ner
                        null, // normalizedNer
                        null  // lemma
                    );
                    batch.add(entry);
                }
            }
        }
        
        return batch;
    }

    @Override
    protected ListMultimap<String, PositionList> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<String, PositionList> index = ArrayListMultimap.create();
        Map<String, PositionList> tempAggregator = new HashMap<>();
        
        for (AnnotationEntry entry : batch) {
            if (entry.getPos() == null || entry.getPos().isEmpty()) {
                continue;
            }
            // Key for POS index is typically: POS_TAG<DELIMITER>TOKEN_LOWERCASE
            // unless the requirement is just to index by POS_TAG.
            // For now, let's use POS_TAG as the key for simplicity, like NerIndex uses NER_TAG.
            String posTag = entry.getPos().toLowerCase();
            // Optionally, could make key: entry.getPos() + DELIMITER + entry.getToken().toLowerCase();
            // if we want to find specific words with a given POS tag.
            // For now, just indexing by POS tag to get all occurrences of that tag.

            Position pos = new Position(entry.getDocumentId(), entry.getSentenceId(), entry.getBeginChar(), entry.getEndChar());
            
            PositionList pl = tempAggregator.computeIfAbsent(posTag, k -> new PositionList());
            pl.add(pos);
        }
        
        for (Map.Entry<String, PositionList> mapEntry : tempAggregator.entrySet()) {
            index.put(mapEntry.getKey(), mapEntry.getValue());
        }
        return index;
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        String countSql = "SELECT COUNT(DISTINCT document_id) FROM annotations WHERE pos IS NOT NULL AND pos != ''";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }
} 