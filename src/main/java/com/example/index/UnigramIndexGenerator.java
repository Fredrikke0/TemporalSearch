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
            Connection sqliteConn, ProgressTracker progress) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress);
    }

    public UnigramIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, IndexConfig config) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, config);
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(int offset) throws SQLException {
        List<AnnotationEntry> entries = new ArrayList<>();
        String sql = "SELECT a.annotation_id, a.document_id, a.sentence_id, a.begin_char, a.end_char, a.lemma, a.pos, d.timestamp " +
                    "FROM annotations a " +
                    "JOIN documents d ON a.document_id = d.document_id " +
                    "WHERE a.lemma IS NOT NULL " +
                    "ORDER BY a.document_id, a.sentence_id, a.begin_char LIMIT ? OFFSET ?";
                    
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, config.getBatchSize());
            stmt.setInt(2, offset);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String lemma = sanitizeText(rs.getString("lemma"));
                    if (lemma == null || lemma.isEmpty()) {
                        continue;
                    }
                    
                    entries.add(new AnnotationEntry(
                        rs.getInt("annotation_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        lemma,
                        sanitizeText(rs.getString("pos")),
                        LocalDate.parse(rs.getString("timestamp").substring(0, 10))
                    ));
                }
            }
        }
        return entries;
    }

    @Override
    protected ListMultimap<String, PositionList> processBatch(List<AnnotationEntry> batch) throws IOException {

        // ---> Filter the batch here (primarily for symbol check, as DB handles nulls) <---
        List<AnnotationEntry> filteredBatch = batch.stream()
             .filter(entry -> entry != null && entry.getLemma() != null && !entry.getLemma().isEmpty() &&
                              entry.getLemma().chars().anyMatch(Character::isLetterOrDigit))
             .collect(Collectors.toList());

        ListMultimap<String, PositionList> index = ArrayListMultimap.create();
        Map<String, PositionList> positionLists = new HashMap<>();

        // Operate on filteredBatch
        for (AnnotationEntry entry : filteredBatch) {
            String lemma = entry.getLemma().toLowerCase();

            // Skip stopwords (keep this specific logic here)
            if (isStopword(lemma)) {
                continue;
            }

            Position position = new Position(entry.getDocumentId(), entry.getSentenceId(),
                entry.getBeginChar(), entry.getEndChar(), entry.getTimestamp());

            PositionList posList = positionLists.computeIfAbsent(lemma, k -> new PositionList());
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