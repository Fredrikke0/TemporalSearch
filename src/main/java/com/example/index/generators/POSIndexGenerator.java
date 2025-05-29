package com.example.index.generators;

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
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import java.util.Set;

/**
 * Generates a streaming POS (Part-of-Speech) index from annotation entries.
 * Each entry maps a POS tag to its positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class POSIndexGenerator extends IndexGenerator<AnnotationEntry> {

    public static final String POS_TAGS_TO_EXCLUDE_SQL = "(',', '.', ':', '``', '\'\'','$','SYM','HYPH','NFP','AFX','LS','X','-LRB-','-RRB-')";

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

        String notInClause = " AND pos NOT IN " + POS_TAGS_TO_EXCLUDE_SQL;

        if (isFirstBatch) {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos " +
                    "FROM annotations WHERE pos IS NOT NULL AND pos != ''" + notInClause + " ORDER BY annotation_id LIMIT ?";
        } else {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos " +
                    "FROM annotations WHERE annotation_id > ? AND pos IS NOT NULL AND pos != ''" + notInClause + " ORDER BY annotation_id LIMIT ?";
        }
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            int paramIdx = 1;
            if (!isFirstBatch) {
                stmt.setLong(paramIdx++, lastProcessedEntry.getAnnotationId());
            }
            stmt.setInt(paramIdx, this.batchSize);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AnnotationEntry entry = new AnnotationEntry(
                        rs.getLong("annotation_id"),
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
    protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<String, PositionListSoA> index = ArrayListMultimap.create();
        Map<String, PositionListSoA> tempAggregator = new HashMap<>();
        
        for (AnnotationEntry entry : batch) {
            if (entry.getPos() == null || entry.getPos().isEmpty() || entry.getToken() == null || entry.getToken().isEmpty()) {
                continue;
            }
            
            String posTag = entry.getPos().toUpperCase(); // Consistent casing with NER types
            String token = entry.getToken().toLowerCase();
            String compositeKey = posTag + com.example.core.IndexAccessInterface.DELIMITER + token;

            PositionListSoA pl = tempAggregator.computeIfAbsent(compositeKey, k -> new PositionListSoA());
            pl.add(entry.getDocumentId(), entry.getSentenceId(), entry.getBeginChar(), entry.getEndChar());
        }
        
        for (Map.Entry<String, PositionListSoA> mapEntry : tempAggregator.entrySet()) {
            index.put(mapEntry.getKey(), mapEntry.getValue());
        }
        return index;
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // String countSql = "SELECT MAX(annotation_id) FROM annotations WHERE pos IS NOT NULL AND pos != ''";
        // try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
        //      ResultSet rs = stmt.executeQuery()) {
        //     if (rs.next()) {
        //         return rs.getLong(1);
        //     }
        // }
        // return 0;
        // Return 0 to indicate an indeterminate progress bar, as MAX(annotation_id) is not representative.
        return 0;
    }
} 