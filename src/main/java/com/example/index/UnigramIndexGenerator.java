package com.example.index;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.io.IOException;
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
import java.util.stream.Collectors;

/**
 * Generates a streaming unigram index from annotation entries.
 * Each entry maps a single lemmatized token to its positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class UnigramIndexGenerator extends IndexGenerator<AnnotationEntry> {

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    protected String getIndexName() {
        return "unigram";
    }

    public UnigramIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize);
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> entries = new ArrayList<>();
        String sql;
        boolean isFirstBatch = (lastProcessedEntry == null);

        if (isFirstBatch) {
            sql = "SELECT a.annotation_id, a.document_id, a.sentence_id, a.begin_char, a.end_char, a.token, a.pos, d.timestamp " +
                  "FROM annotations a " +
                  "JOIN documents d ON a.document_id = d.document_id " +
                  "WHERE a.token IS NOT NULL " +
                  "ORDER BY a.annotation_id LIMIT ?";
        } else {
            sql = "SELECT a.annotation_id, a.document_id, a.sentence_id, a.begin_char, a.end_char, a.token, a.pos, d.timestamp " +
                  "FROM annotations a " +
                  "JOIN documents d ON a.document_id = d.document_id " +
                  "WHERE a.token IS NOT NULL AND a.annotation_id > ? " +
                  "ORDER BY a.annotation_id LIMIT ?";
        }
                    
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            if (isFirstBatch) {
                stmt.setInt(1, this.batchSize);
            } else {
                stmt.setInt(1, lastProcessedEntry.getAnnotationId());
                stmt.setInt(2, this.batchSize);
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String token = sanitizeText(rs.getString("token"));
                    if (token == null || token.isEmpty()) {
                        continue;
                    }
                    
                    entries.add(new AnnotationEntry(
                        rs.getInt("annotation_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        token,
                        sanitizeText(rs.getString("pos"))
                    ));
                }
            }
        }
        return entries;
    }

    @Override
    protected ListMultimap<String, PositionList> processBatch(List<AnnotationEntry> batch) throws IOException {

        List<AnnotationEntry> filteredBatch = batch.stream()
             .filter(entry -> entry != null && entry.getToken() != null && !entry.getToken().isEmpty() &&
                              entry.getToken().chars().anyMatch(Character::isLetterOrDigit))
             .collect(Collectors.toList());

        ListMultimap<String, PositionList> index = ArrayListMultimap.create();
        Map<String, PositionList> positionLists = new HashMap<>();

        for (AnnotationEntry entry : filteredBatch) {
            String token = entry.getToken().toLowerCase();

            if (isStopword(token)) {
                continue;
            }

            Position position = new Position(entry.getDocumentId(), entry.getSentenceId(),
                entry.getBeginChar(), entry.getEndChar());

            PositionList posList = positionLists.computeIfAbsent(token, k -> new PositionList());
            posList.add(position);
        }

        for (Map.Entry<String, PositionList> entry : positionLists.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }
        return index;
    }

    /**
     * Helper method to sanitize text by escaping null bytes.
     * This prevents conflicts with our delimiter while preserving the original meaning.
     * 
     * @param text The text to sanitize
     * @return The sanitized text with null bytes escaped
     */
    private static String sanitizeText(String text) {
        if (text == null) {
            return null;
        }
        return text.replace(DELIMITER, ESCAPE_CHAR + "0" + ESCAPE_CHAR).trim();
    }
} 