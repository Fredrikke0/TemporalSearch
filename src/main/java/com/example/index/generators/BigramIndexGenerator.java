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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * Generates a streaming bigram index from annotation entries.
 * Each entry maps a pair of consecutive tokens to their positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 *
 * This implementation is now RocksDB-based (see IndexGenerator).
 */
public final class BigramIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(BigramIndexGenerator.class);

    // Carry over the last token of the previous batch to avoid missing
    // cross-batch bigrams (boundary-spanning n-grams)
    private AnnotationEntry lastEntryFromPreviousBatch = null;

    public BigramIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public BigramIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String query;
        boolean isFirstBatch = (lastProcessedEntry == null);

        if (isFirstBatch) {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " +
                    "FROM annotations WHERE pos NOT IN ('FW', 'ADD') ORDER BY annotation_id LIMIT ?";
        } else {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " +
                    "FROM annotations WHERE annotation_id > ? AND pos NOT IN ('FW', 'ADD') ORDER BY annotation_id LIMIT ?";
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
    protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
        // Augment the current batch with the tail of the previous batch so that
        // bigrams that span the boundary are generated in this call.
        List<AnnotationEntry> augmented = new ArrayList<>(batch.size() + 1);
        if (lastEntryFromPreviousBatch != null) {
            augmented.add(lastEntryFromPreviousBatch);
        }
        augmented.addAll(batch);

        augmented.sort(Comparator.comparingInt(AnnotationEntry::getDocumentId)
            .thenComparingInt(AnnotationEntry::getSentenceId)
            .thenComparingInt(AnnotationEntry::getBeginChar));

        ListMultimap<String, PositionListSoA> index = ArrayListMultimap.create();
        Map<String, PositionListSoA> positionLists = new HashMap<>();

        for (int i = 0; i < augmented.size() - 1; i++) {
            AnnotationEntry firstEntry = augmented.get(i);
            AnnotationEntry secondEntry = augmented.get(i + 1);

            if (firstEntry.getDocumentId() != secondEntry.getDocumentId() ||
                firstEntry.getSentenceId() != secondEntry.getSentenceId()) {
                continue; // Moved to a new sentence or document
            }

            // Check for non-adjacency between all parts of the bigram.
            if (secondEntry.getBeginChar() > firstEntry.getEndChar() + 2) {
                continue;
            }

            String firstToken = firstEntry.getToken();
            String secondToken = secondEntry.getToken();

            if (firstToken == null || firstToken.isEmpty() || secondToken == null || secondToken.isEmpty()) {
                continue;
            }

            String firstTokenLower = firstToken.toLowerCase();
            String secondTokenLower = secondToken.toLowerCase();

            if (isStopword(firstTokenLower) || isStopword(secondTokenLower)) {
                continue;
            }

            String key = String.format("%s%s%s",
                firstTokenLower,
                DELIMITER,
                secondTokenLower);

            PositionListSoA posList = positionLists.computeIfAbsent(key, k -> new PositionListSoA());
            posList.add(secondEntry.getDocumentId(), secondEntry.getSentenceId(),
                firstEntry.getBeginChar(), secondEntry.getEndChar());
        }

        for (Map.Entry<String, PositionListSoA> entry : positionLists.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }

        // Keep only the last token to bridge with the next batch.
        lastEntryFromPreviousBatch = augmented.isEmpty() ? null : augmented.get(augmented.size() - 1);
        return index;
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // Rough estimate, but fast.
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
