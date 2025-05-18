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
 * Generates a streaming dependency index from dependency relation entries.
 * Each entry maps a head token, relation type, and dependent token to their positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class DependencyIndexGenerator extends IndexGenerator<DependencyEntry> {
    private static final Logger logger = LoggerFactory.getLogger(DependencyIndexGenerator.class);

    private static final Set<String> BLACKLISTED_RELATIONS = Set.of(
        "punct", "det", "case", "cc"
    );

    public DependencyIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize);
    }

    @Override
    protected String getTableName() {
        return "dependencies";
    }

    @Override
    protected String getIndexName() {
        return "dependency";
    }

    @Override
    protected List<DependencyEntry> fetchBatch(DependencyEntry lastProcessedEntry) throws SQLException {
        List<DependencyEntry> batch = new ArrayList<>();
        String query;
        boolean isFirstBatch = (lastProcessedEntry == null);

        if (isFirstBatch) {
            query = "SELECT d.dependency_id, d.document_id, d.sentence_id, d.head_token, d.dependent_token, d.relation, " +
                    "d.begin_char, d.end_char, doc.timestamp " +
                    "FROM dependencies d " +
                    "JOIN documents doc ON d.document_id = doc.document_id " +
                    "ORDER BY d.dependency_id LIMIT ?";
        } else {
            query = "SELECT d.dependency_id, d.document_id, d.sentence_id, d.head_token, d.dependent_token, d.relation, " +
                    "d.begin_char, d.end_char, doc.timestamp " +
                    "FROM dependencies d " +
                    "JOIN documents doc ON d.document_id = doc.document_id " +
                    "WHERE d.dependency_id > ? " +
                    "ORDER BY d.dependency_id LIMIT ?";
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
                    String headToken = sanitizeText(rs.getString("head_token"));
                    String dependentToken = sanitizeText(rs.getString("dependent_token"));
                    String relation = sanitizeText(rs.getString("relation"));

                    // Skip entries where any required field is null or empty after sanitization
                    if (headToken == null || headToken.isEmpty() ||
                        dependentToken == null || dependentToken.isEmpty() ||
                        relation == null || relation.isEmpty()) {
                        logger.debug("Skipping entry with null/empty fields: head={}, dependent={}, relation={}",
                                   headToken, dependentToken, relation);
                        continue;
                    }

                    batch.add(new DependencyEntry(
                        rs.getInt("dependency_id"), // Use the new dependency_id
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        headToken,
                        dependentToken,
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
            // Ensure tokens are lowercased before stopword check
            String headTokenLower = entry.getHeadToken().toLowerCase();
            String dependentTokenLower = entry.getDependentToken().toLowerCase();

            if (isStopword(headTokenLower) ||
                isStopword(dependentTokenLower) ||
                BLACKLISTED_RELATIONS.contains(entry.getRelation())) {
                continue;
            }

            // Create key in format: headToken\0relation\0dependentToken
            String key = generateKey(entry);

            Position position = new Position(
                entry.getDocumentId(),
                entry.getSentenceId(),
                entry.getBeginChar(),
                entry.getEndChar()
            );

            // Get or create position list for this dependency
            PositionList posList = positionLists.computeIfAbsent(key, k -> new PositionList());
            posList.add(position);
        }

        // Add all position lists to result
        for (Map.Entry<String, PositionList> entry : positionLists.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }

        return index;
    }

    /**
     * Creates an index key from a dependency entry
     * @param entry The dependency entry
     * @return A delimited key in the format headToken${DELIMITER}relation${DELIMITER}dependentToken
     */
    protected String generateKey(DependencyEntry entry) {
        return String.format("%s%s%s%s%s", 
            entry.getHeadToken().trim().toLowerCase(),
            DELIMITER,
            entry.getRelation().trim().toLowerCase(),
            DELIMITER,
            entry.getDependentToken().trim().toLowerCase());
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
        return text.trim()
                  .replaceAll("\\s+", " ")
                  .replaceAll("[^\\p{L}\\p{N}\\s-]", "");
    }
} 