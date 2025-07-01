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
        Map<Integer, Map<Integer, List<AnnotationEntry>>> groupedByDocumentAndSentence = batch.stream()
            .collect(Collectors.groupingBy(AnnotationEntry::getDocumentId,
                Collectors.groupingBy(AnnotationEntry::getSentenceId)));

        List<AnnotationEntry> sentenceSpanFilteredTokens = new ArrayList<>();

        groupedByDocumentAndSentence.forEach((documentId, sentencesMap) -> {
            sentencesMap.forEach((sentenceId, sentenceTokens) -> {
                sentenceTokens.sort(Comparator.comparingInt(AnnotationEntry::getBeginChar));

                sentenceSpanFilteredTokens.addAll(sentenceTokens);
            });
        });

        List<AnnotationEntry> fullyFilteredBatch = sentenceSpanFilteredTokens.stream()
            .filter(entry -> entry != null && entry.getToken() != null && !entry.getToken().isEmpty())
            .map(entry -> {
                String lowerToken = entry.getToken().toLowerCase();
                if (isStopword(lowerToken) || !isValidToken(lowerToken)) {
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

        for (int i = 0; i < fullyFilteredBatch.size() - 1; i++) {
            AnnotationEntry firstEntry = fullyFilteredBatch.get(i);
            AnnotationEntry secondEntry = fullyFilteredBatch.get(i + 1);

            if (firstEntry.getDocumentId() == secondEntry.getDocumentId() &&
                firstEntry.getSentenceId() == secondEntry.getSentenceId()) {

                String key = String.format("%s%s%s",
                    firstEntry.getToken(),
                    DELIMITER,
                    secondEntry.getToken());

                PositionListSoA posList = positionLists.computeIfAbsent(key, k -> new PositionListSoA());
                posList.add(secondEntry.getDocumentId(), secondEntry.getSentenceId(),
                    firstEntry.getBeginChar(), secondEntry.getEndChar());
            }
        }

        for (Map.Entry<String, PositionListSoA> entry : positionLists.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }
        return index;
    }

    /**
     * Validates if a token should be included in the index.
     * Uses a middle-ground approach: keeps mixed alphanumeric tokens but filters out
     * purely numeric or purely punctuation tokens.
     *
     * @param token The token to validate (should be lowercase)
     * @return true if the token should be indexed, false otherwise
     */
    private static boolean isValidToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        // Filter out tokens starting with apostrophe (parsing artifacts)
        if (token.startsWith("'")) {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasOther = false;

        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasOther = true;
            }
        }

        // Keep tokens that have at least one letter
        // This includes: pure letters, letters+digits, letters+punctuation, letters+digits+punctuation
        // Filters out: pure numbers, pure punctuation, digits+punctuation (no letters)
        return hasLetter;
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // Since bigrams are derived from annotations, we can use the total annotation count
        // or a more specific count if available that better reflects pairs.
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
