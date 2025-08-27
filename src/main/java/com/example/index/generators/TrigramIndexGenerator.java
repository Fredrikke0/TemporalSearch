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
import java.util.List;
import java.util.Map;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * Generates a streaming trigram index from annotation entries.
 * Each entry maps a sequence of three consecutive tokens to their positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 *
 */
public final class TrigramIndexGenerator extends IndexGenerator<AnnotationEntry> {
    // Carry over the last two tokens of the previous batch to avoid missing
    // cross-batch trigrams (boundary-spanning n-grams)
    private final List<AnnotationEntry> tailFromPreviousBatch = new ArrayList<>(2);

    public TrigramIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public TrigramIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
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
                    "WHERE annotation_id > ?" +
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
                        rs.getLong("annotation_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        token,
                        null, // pos
                        null, // ner
                        null // normalizedNer
                    );
                    batch.add(entry);
                }
            }
        }

        return batch;
    }

    @Override
    protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
        // Augment current batch with up to two tail tokens from the previous batch
        // so that trigrams spanning the boundary are generated in this call.
        List<AnnotationEntry> augmented = new ArrayList<>(tailFromPreviousBatch.size() + batch.size());
        augmented.addAll(tailFromPreviousBatch);
        augmented.addAll(batch);

        augmented.sort(Comparator.comparingInt(AnnotationEntry::getDocumentId)
            .thenComparingInt(AnnotationEntry::getSentenceId)
            .thenComparingInt(AnnotationEntry::getBeginChar));

        ListMultimap<String, PositionListSoA> index = ArrayListMultimap.create();
        Map<String, PositionListSoA> positionLists = new HashMap<>();

        for (int i = 0; i < augmented.size() - 2; i++) { // Need 3 tokens for a trigram
            AnnotationEntry firstEntry = augmented.get(i);
            AnnotationEntry secondEntry = augmented.get(i + 1);
            AnnotationEntry thirdEntry = augmented.get(i + 2);

            if (firstEntry.getDocumentId() != secondEntry.getDocumentId() || firstEntry.getSentenceId() != secondEntry.getSentenceId() ||
                secondEntry.getDocumentId() != thirdEntry.getDocumentId() || secondEntry.getSentenceId() != thirdEntry.getSentenceId()) {
                continue;
            }

            // Check for non-adjacency between all parts of the trigram.
            if (secondEntry.getBeginChar() > firstEntry.getEndChar() + 2 ||
                thirdEntry.getBeginChar() > secondEntry.getEndChar() + 2) {
                continue;
            }

            String firstToken = firstEntry.getToken();
            String secondToken = secondEntry.getToken();
            String thirdToken = thirdEntry.getToken();

            if (firstToken == null || firstToken.isEmpty() ||
                secondToken == null || secondToken.isEmpty() ||
                thirdToken == null || thirdToken.isEmpty()) {
                continue;
            }

            String t1 = firstToken.toLowerCase();
            String t2 = secondToken.toLowerCase();
            String t3 = thirdToken.toLowerCase();

            if (isStopword(t1) || isStopword(t2) || isStopword(t3)) {
                continue;
            }


            String key = String.format("%s%s%s%s%s",
                t1,
                DELIMITER,
                t2,
                DELIMITER,
                t3);

            PositionListSoA posList = positionLists.computeIfAbsent(key, k -> new PositionListSoA());
            posList.add(thirdEntry.getDocumentId(), thirdEntry.getSentenceId(),
                firstEntry.getBeginChar(), thirdEntry.getEndChar());
        }

        for (Map.Entry<String, PositionListSoA> entry : positionLists.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }

        // Update tail: keep the last two tokens to bridge with the next batch
        tailFromPreviousBatch.clear();
        int size = augmented.size();
        if (size >= 2) {
            tailFromPreviousBatch.add(augmented.get(size - 2));
            tailFromPreviousBatch.add(augmented.get(size - 1));
        } else if (size == 1) {
            tailFromPreviousBatch.add(augmented.get(0));
        }
        return index;
    }

    @Override
    protected String getTableName() {
        return "annotations";
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