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
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize);
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String query;
        boolean isFirstBatch = (lastProcessedEntry == null);

        if (isFirstBatch) {
            query = "SELECT a.annotation_id, a.document_id, a.sentence_id, a.begin_char, a.end_char, a.token, a.pos, d.timestamp " +
                    "FROM annotations a " +
                    "JOIN documents d ON a.document_id = d.document_id " +
                    "ORDER BY a.annotation_id LIMIT ?";
        } else {
            query = "SELECT a.annotation_id, a.document_id, a.sentence_id, a.begin_char, a.end_char, a.token, a.pos, d.timestamp " +
                    "FROM annotations a " +
                    "JOIN documents d ON a.document_id = d.document_id " +
                    "WHERE a.annotation_id > ? " +
                    "ORDER BY a.annotation_id LIMIT ?";
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
                        LocalDate.parse(rs.getString("timestamp").substring(0, 10))
                    );
                    batch.add(entry);
                }
            }
        }
        
        return batch;
    }

    @Override
    protected ListMultimap<String, PositionList> processBatch(List<AnnotationEntry> batch) throws IOException {
        List<AnnotationEntry> filteredBatch = batch.stream()
             .filter(entry -> entry != null && entry.getToken() != null && !entry.getToken().isEmpty() &&
                              entry.getToken().chars().anyMatch(Character::isLetterOrDigit))
             .collect(Collectors.toList());

        ListMultimap<String, PositionList> index = ArrayListMultimap.create();
        Map<String, PositionList> positionLists = new HashMap<>();

        for (int i = 0; i < filteredBatch.size() - 2; i++) {
            AnnotationEntry firstEntry = filteredBatch.get(i);
            AnnotationEntry secondEntry = filteredBatch.get(i + 1);
            AnnotationEntry thirdEntry = filteredBatch.get(i + 2);

            if (firstEntry.getDocumentId() == secondEntry.getDocumentId() &&
                firstEntry.getDocumentId() == thirdEntry.getDocumentId() &&
                firstEntry.getSentenceId() == secondEntry.getSentenceId() &&
                firstEntry.getSentenceId() == thirdEntry.getSentenceId()) {

                String key = String.format("%s%s%s%s%s",
                    firstEntry.getToken().toLowerCase(),
                    DELIMITER,
                    secondEntry.getToken().toLowerCase(),
                    DELIMITER,
                    thirdEntry.getToken().toLowerCase());

                Position position = new Position(thirdEntry.getDocumentId(), thirdEntry.getSentenceId(),
                    firstEntry.getBeginChar(), thirdEntry.getEndChar(), thirdEntry.getTimestamp());

                PositionList posList = positionLists.computeIfAbsent(key, k -> new PositionList());
                posList.add(position);
            }
        }

        for (Map.Entry<String, PositionList> entry : positionLists.entrySet()) {
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
} 