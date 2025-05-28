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
import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import java.util.stream.Collectors;

/**
 * Generates a streaming trigram index from annotation entries.
 * Each entry maps a sequence of three consecutive lemmatized tokens to their positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class TrigramIndexGenerator extends IndexGenerator<AnnotationEntry> {
    public TrigramIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public TrigramIndexGenerator(String indexBaseDir, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String query;
        boolean isFirstBatch = (lastProcessedEntry == null);

        if (isFirstBatch) {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " + 
                    "FROM annotations " +
                    "ORDER BY annotation_id LIMIT ?";
        } else {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " +
                    "FROM annotations " +
                    "WHERE annotation_id > ? " +
                    "ORDER BY annotation_id LIMIT ?";
        }
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            if (isFirstBatch) {
                stmt.setInt(1, this.batchSize);
            } else {
                stmt.setLong(1, lastProcessedEntry.getAnnotationId());
                stmt.setInt(2, this.batchSize);
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String rawToken = rs.getString("token");
                    String token = (rawToken != null) ? rawToken.trim() : null;
                    AnnotationEntry entry = new AnnotationEntry(
                        rs.getInt("annotation_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        token,
                        null, // pos
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
        List<AnnotationEntry> filteredBatch = batch.stream()
            .filter(entry -> entry != null && entry.getToken() != null && !entry.getToken().isEmpty())
            .map(entry -> {
                String lowerToken = entry.getToken().toLowerCase();
                if (isStopword(lowerToken) || !lowerToken.chars().anyMatch(Character::isLetterOrDigit)) {
                    return null; 
                }
                return new AnnotationEntry(entry.getAnnotationId(), entry.getDocumentId(), entry.getSentenceId(),
                                           entry.getBeginChar(), entry.getEndChar(), lowerToken, entry.getPos(),
                                           entry.getNer(), entry.getNormalizedNer(), entry.getLemma());
            })
            .filter(entry -> entry != null)
            .collect(Collectors.toList());

        ListMultimap<String, PositionListSoA> index = ArrayListMultimap.create();
        Map<String, PositionListSoA> positionLists = new HashMap<>();

        for (int i = 0; i < filteredBatch.size() - 2; i++) { // Need 3 tokens for a trigram
            AnnotationEntry firstEntry = filteredBatch.get(i);
            AnnotationEntry secondEntry = filteredBatch.get(i + 1);
            AnnotationEntry thirdEntry = filteredBatch.get(i + 2);

            if (firstEntry.getDocumentId() == secondEntry.getDocumentId() && firstEntry.getSentenceId() == secondEntry.getSentenceId() &&
                secondEntry.getDocumentId() == thirdEntry.getDocumentId() && secondEntry.getSentenceId() == thirdEntry.getSentenceId()) {

                String key = String.format("%s%s%s%s%s",
                    firstEntry.getToken(), // Already lowercased
                    DELIMITER,
                    secondEntry.getToken(), // Already lowercased
                    DELIMITER,
                    thirdEntry.getToken()); // Already lowercased

                PositionListSoA posList = positionLists.computeIfAbsent(key, k -> new PositionListSoA());
                posList.add(thirdEntry.getDocumentId(), thirdEntry.getSentenceId(),
                    firstEntry.getBeginChar(), thirdEntry.getEndChar());
            }
        }

        for (Map.Entry<String, PositionListSoA> entry : positionLists.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }
        return index;
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    protected String getIndexName() {
        return "trigram";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        String countSql = "SELECT MAX(annotation_id) FROM annotations";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }
} 