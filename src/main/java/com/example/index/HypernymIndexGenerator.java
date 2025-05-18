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
    protected List<DependencyEntry> fetchBatch(DependencyEntry lastProcessedEntry) throws SQLException {
        List<DependencyEntry> batch = new ArrayList<>();
        boolean isFirstBatch = (lastProcessedEntry == null);
        
        String inClause = HYPERNYM_RELATIONS.stream()
            .map(r -> "'" + r + "'")
            .collect(java.util.stream.Collectors.joining(", "));

        // Optimized query structure
        String queryBase = "SELECT " +
                           "    d.dependency_id, d.document_id, d.sentence_id, " +
                           "    anno_head.lemma AS head_lemma, anno_dep.lemma AS dependent_lemma, " +
                           "    d.relation, d.begin_char, d.end_char, doc.timestamp " +
                           "FROM " +
                           "    dependencies d " +
                           "JOIN " +
                           "    annotations anno_head ON d.document_id = anno_head.document_id " +
                           "                       AND d.sentence_id = anno_head.sentence_id " +
                           "                       AND d.head_token = anno_head.token " +
                           "JOIN " +
                           "    annotations anno_dep ON d.document_id = anno_dep.document_id " +
                           "                       AND d.sentence_id = anno_dep.sentence_id " +
                           "                       AND d.dependent_token = anno_dep.token " +
                           "JOIN " +
                           "    documents doc ON d.document_id = doc.document_id " +
                           "WHERE " +
                           "    d.relation IN (" + inClause + ") ";

        String query;
        if (isFirstBatch) {
            query = queryBase + "ORDER BY d.dependency_id LIMIT ?";
        } else {
            query = queryBase + "AND d.dependency_id > ? ORDER BY d.dependency_id LIMIT ?";
        }
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            if (isFirstBatch) {
                stmt.setInt(1, this.batchSize);
            } else {
                stmt.setInt(1, lastProcessedEntry.getDependencyId());
                stmt.setInt(2, this.batchSize);
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Sanitize text fields
                    String headLemma = sanitizeText(rs.getString("head_lemma"));
                    String dependentLemma = sanitizeText(rs.getString("dependent_lemma"));
                    String relation = sanitizeText(rs.getString("relation"));
                    
                    if (headLemma == null || headLemma.isEmpty() ||
                        dependentLemma == null || dependentLemma.isEmpty() ||
                        relation == null || relation.isEmpty()) {
                        logger.debug("Skipping entry with null/empty fields: head={}, dependent={}, relation={}",
                                   headLemma, dependentLemma, relation);
                        continue;
                    }
                    
                    // Lowercase lemmas before stopword check
                    String headLemmaLower = headLemma.toLowerCase();
                    String dependentLemmaLower = dependentLemma.toLowerCase();

                    if (isStopword(headLemmaLower) || isStopword(dependentLemmaLower)) {
                        continue;
                    }

                    batch.add(new DependencyEntry(
                        rs.getInt("dependency_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        headLemma,     // Store original (non-lowercased here) lemma for DependencyEntry if needed elsewhere
                        dependentLemma, // Store original (non-lowercased here) lemma for DependencyEntry
                        relation
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
                entry.getEndChar()
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