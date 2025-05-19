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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.logging.ProgressTracker;
import com.example.core.Position;
import com.example.core.PositionList;

/**
 * Generates a streaming dependency index from dependency relation entries.
 */
public final class DependencyIndexGenerator extends IndexGenerator<DependencyEntry> {
    private static final Logger logger = LoggerFactory.getLogger(DependencyIndexGenerator.class);

    // Example: Blacklist common, less informative relations if needed
    private static final Set<String> BLACKLISTED_RELATIONS = Set.of(
        // "punct", "det", "case", "cc" // Example, adjust as needed
    );

    public DependencyIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public DependencyIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
    }

    @Override
    protected String getTableName() {
        return "dependencies"; // Assuming this is the source table
    }

    @Override
    protected String getIndexName() {
        return "dependency";
    }

    @Override
    protected List<DependencyEntry> fetchBatch(DependencyEntry lastProcessedEntry) throws SQLException {
        List<DependencyEntry> batch = new ArrayList<>();
        String sql;
        // Using dependency_id from DependencyEntry for keyset pagination
        if (lastProcessedEntry == null) {
            sql = "SELECT dependency_id, document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation " +
                  "FROM dependencies ORDER BY dependency_id LIMIT ?";
        } else {
            sql = "SELECT dependency_id, document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation " +
                  "FROM dependencies WHERE dependency_id > ? ORDER BY dependency_id LIMIT ?";
        }

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            if (lastProcessedEntry == null) {
                stmt.setInt(1, batchSize);
            } else {
                stmt.setInt(1, lastProcessedEntry.getDependencyId()); // Correct getter
                stmt.setInt(2, batchSize);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Sanitize text fields before creating the entry
                    String headToken = sanitizeText(rs.getString("head_token"));
                    String dependentToken = sanitizeText(rs.getString("dependent_token"));
                    String relation = sanitizeText(rs.getString("relation"));

                    if (headToken == null || dependentToken == null || relation == null) {
                        logger.debug("Skipping dependency due to null field after sanitization. Original: head='{}', dep='{}', rel='{}'", 
                                     rs.getString("head_token"), rs.getString("dependent_token"), rs.getString("relation"));
                        continue;
                    }

                    batch.add(new DependencyEntry(
                        rs.getInt("dependency_id"),
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
    protected ListMultimap<String, PositionList> processBatch(List<DependencyEntry> batch) {
        ListMultimap<String, PositionList> indexData = ArrayListMultimap.create();
        Map<String, PositionList> tempAggregator = new HashMap<>();

        for (DependencyEntry entry : batch) {
            // Tokens are already sanitized from fetchBatch
            String headTokenLower = entry.getHeadToken().toLowerCase(); 
            String dependentTokenLower = entry.getDependentToken().toLowerCase();
            String relationLower = entry.getRelation().toLowerCase();

            if (isStopword(headTokenLower) || isStopword(dependentTokenLower) || 
                BLACKLISTED_RELATIONS.contains(relationLower)) {
                continue;
            }
            
            String key = headTokenLower + DELIMITER + relationLower + DELIMITER + dependentTokenLower;
            
            // Using standard Position. For dependencies, begin/end char might refer to the span of the relation or one of the tokens.
            // Here, using the entry's overall begin/end char. This might need adjustment based on desired semantics.
            Position pos = new Position(entry.getDocumentId(), entry.getSentenceId(), entry.getBeginChar(), entry.getEndChar());
            
            PositionList pl = tempAggregator.computeIfAbsent(key, k -> new PositionList());
            pl.add(pos);
        }
        
        for (Map.Entry<String, PositionList> mapEntry : tempAggregator.entrySet()) {
            indexData.put(mapEntry.getKey(), mapEntry.getValue());
        }
        return indexData;
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        String countSql = "SELECT COUNT(DISTINCT document_id) FROM dependencies";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }
    
    private String sanitizeText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        // Basic sanitization: trim, normalize multiple spaces, remove non-alphanumeric (except hyphens and spaces)
        // Adjust regex as needed for more specific cleaning.
        String cleaned = text.trim().replaceAll("\\s+", " ");
        // Consider if a more restrictive character set is needed. 
        // This example keeps letters, numbers, spaces, hyphens.
        // cleaned = cleaned.replaceAll("[^\\p{L}\\p{N}\\s-]", ""); 
        return cleaned.isEmpty() ? null : cleaned;
    }
} 