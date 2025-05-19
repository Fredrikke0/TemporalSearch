package com.example.index;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
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
import java.util.stream.Collectors;
import com.example.logging.ProgressTracker;
import com.example.core.Position;
import com.example.core.PositionList;

/**
 * Generates a streaming bigram index from annotation entries.
 * Each entry maps a pair of consecutive lemmatized tokens to their positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class BigramIndexGenerator extends IndexGenerator<AnnotationEntry> {

    public BigramIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public BigramIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
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
                    "ORDER BY a.document_id, a.sentence_id, a.begin_char LIMIT ?";
        } else {
            query = "SELECT a.annotation_id, a.document_id, a.sentence_id, a.begin_char, a.end_char, a.token, a.pos, d.timestamp " +
                    "FROM annotations a " +
                    "JOIN documents d ON a.document_id = d.document_id " +
                    "WHERE (a.document_id > ? OR " +
                    "      (a.document_id = ? AND a.sentence_id > ?) OR " +
                    "      (a.document_id = ? AND a.sentence_id = ? AND a.begin_char > ?)) " +
                    "ORDER BY a.document_id, a.sentence_id, a.begin_char LIMIT ?";
        }
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            if (isFirstBatch) {
                stmt.setInt(1, this.batchSize);
            } else {
                stmt.setInt(1, lastProcessedEntry.getDocumentId());
                stmt.setInt(2, lastProcessedEntry.getDocumentId());
                stmt.setInt(3, lastProcessedEntry.getSentenceId());
                stmt.setInt(4, lastProcessedEntry.getDocumentId());
                stmt.setInt(5, lastProcessedEntry.getSentenceId());
                stmt.setInt(6, lastProcessedEntry.getBeginChar());
                stmt.setInt(7, this.batchSize);
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
                        rs.getString("pos"),
                        null,
                        null,
                        null
                    );
                    batch.add(entry);
                }
            }
        }
        return batch;
    }

    @Override
    protected ListMultimap<String, PositionList> processBatch(List<AnnotationEntry> batch) {
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

        ListMultimap<String, PositionList> index = ArrayListMultimap.create();
        Map<String, PositionList> positionLists = new HashMap<>();

        for (int i = 0; i < filteredBatch.size() - 1; i++) {
            AnnotationEntry firstEntry = filteredBatch.get(i);
            AnnotationEntry secondEntry = filteredBatch.get(i + 1);

            if (firstEntry.getDocumentId() == secondEntry.getDocumentId() &&
                firstEntry.getSentenceId() == secondEntry.getSentenceId()) {

                String key = String.format("%s%s%s",
                    firstEntry.getToken(),
                    DELIMITER,
                    secondEntry.getToken());

                Position position = new Position(secondEntry.getDocumentId(), secondEntry.getSentenceId(),
                    firstEntry.getBeginChar(), secondEntry.getEndChar());

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
        return "bigram";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // Since bigrams are derived from annotations, we can use the total annotation count
        // or a more specific count if available that better reflects pairs.
        String countSql = "SELECT COUNT(*) FROM documents";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }
} 
