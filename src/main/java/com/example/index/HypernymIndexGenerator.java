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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.logging.ProgressTracker;
import com.example.core.Position;
import com.example.core.PositionList;

/**
 * Generates a streaming hypernym index from dependency entries.
 * Each entry maps a hypernym-hyponym (category-instance) pair to its positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class HypernymIndexGenerator extends IndexGenerator<DependencyEntry> {
    private static final Logger logger = LoggerFactory.getLogger(HypernymIndexGenerator.class);

    private static final Set<String> HYPERNYM_RELATIONS = Set.of(
        "nmod:such_as",
        "nmod:as",
        "nmod:including",
        "nmod:especially",
        "nmod:particularly"
    );

    public HypernymIndexGenerator(String levelDbPath, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        super(levelDbPath, stopwordsPath, sqliteConn, progress, batchSize);
    }

    @Override
    protected String getTableName() {
        return "dependencies";
    }

    @Override
    protected String getIndexName() {
        return "hypernym";
    }

    @Override
    protected List<DependencyEntry> fetchBatch(int offset) throws SQLException {
        List<DependencyEntry> batch = new ArrayList<>();
        
        // Build the IN clause from HYPERNYM_RELATIONS
        String inClause = HYPERNYM_RELATIONS.stream()
            .map(r -> "'" + r + "'")
            .collect(java.util.stream.Collectors.joining(", "));
            
        String query = String.format(
            "WITH filtered_deps AS (" +
            "    SELECT document_id, sentence_id, head_token, dependent_token, " +
            "           relation, begin_char, end_char " +
            "    FROM dependencies " +
            "    WHERE relation IN (%s)" +
            "), " +
            "head_tokens AS (" +
            "    SELECT d.*, a.lemma as head_lemma " +
            "    FROM filtered_deps d " +
            "    JOIN annotations a ON d.document_id = a.document_id " +
            "        AND d.sentence_id = a.sentence_id " +
            "        AND d.head_token = a.token" +
            ") " +
            "SELECT h.*, a.lemma as dependent_lemma, doc.timestamp " +
            "FROM head_tokens h " +
            "JOIN annotations a ON h.document_id = a.document_id " +
            "    AND h.sentence_id = a.sentence_id " +
            "    AND h.dependent_token = a.token " +
            "JOIN documents doc ON h.document_id = doc.document_id " +
            "ORDER BY h.document_id, h.sentence_id, h.begin_char " +
            "LIMIT ? OFFSET ?",
            inClause);
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            stmt.setInt(1, this.batchSize);
            stmt.setInt(2, offset);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Sanitize text fields
                    String headLemma = sanitizeText(rs.getString("head_lemma"));
                    String dependentLemma = sanitizeText(rs.getString("dependent_lemma"));
                    String relation = sanitizeText(rs.getString("relation"));
                    
                    // Skip if any required field is null or empty after sanitization
                    if (headLemma == null || headLemma.isEmpty() ||
                        dependentLemma == null || dependentLemma.isEmpty() ||
                        relation == null || relation.isEmpty()) {
                        logger.debug("Skipping entry with null/empty fields: head={}, dependent={}, relation={}",
                                   headLemma, dependentLemma, relation);
                        continue;
                    }
                    
                    // Skip if either term is a stopword
                    if (isStopword(headLemma) || isStopword(dependentLemma)) {
                        continue;
                    }

                    batch.add(new DependencyEntry(
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        headLemma,
                        dependentLemma,
                        relation,
                        LocalDate.parse(rs.getString("timestamp").substring(0, 10))
                    ));
                }
            }
        }
        return batch;
    }

    @Override
    protected ListMultimap<String, PositionList> processBatch(List<DependencyEntry> batch) throws IOException {
        ListMultimap<String, PositionList> index = ArrayListMultimap.create();
        Map<String, PositionList> positionLists = new HashMap<>();
        
        for (DependencyEntry entry : batch) {
            // Create position for this occurrence
            Position position = new Position(
                entry.getDocumentId(),
                entry.getSentenceId(),
                entry.getBeginChar(),
                entry.getEndChar(),
                entry.getTimestamp()
            );

            // Create key in format: category\0instance
            String key = createKey(entry.getHeadToken(), entry.getDependentToken());
            
            // Get or create position list for this hypernym pair
            PositionList posList = positionLists.computeIfAbsent(key, k -> new PositionList());
            posList.add(position);
            
            logger.debug("Added hypernym relation: {} -> {} at position {}", 
                entry.getHeadToken(), entry.getDependentToken(), position);
        }
        
        // Add all position lists to result
        for (Map.Entry<String, PositionList> entry : positionLists.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }
        
        return index;
    }

    /**
     * Creates an index key from a category and instance
     * @param category The hypernym (category)
     * @param instance The hyponym (instance)
     * @return A delimited key in the format category${DELIMITER}instance
     */
    protected String createKey(String category, String instance) {
        return category.toLowerCase() + DELIMITER + instance.toLowerCase();
    }

    /**
     * Sanitizes text by removing special characters and normalizing whitespace.
     * @param text The text to sanitize
     * @return The sanitized text, or null if the input is null or empty
     */
    private String sanitizeText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        
        // Special handling for relation names to preserve colons
        if (text.startsWith("nmod:")) {
            return text.trim().replaceAll("\\s+", " ");
        }
        
        return text.trim()
                  .replaceAll("\\s+", " ")
                  .replaceAll("[^\\p{L}\\p{N}\\s:-]", "");
    }
} 