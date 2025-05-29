package com.example.index.generators;

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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.PositionListSoA;
import com.example.index.DependencyEntry;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

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

    public HypernymIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public HypernymIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
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
            .map(r -> "'" + r.replace("'", "''") + "'")
            .collect(Collectors.joining(", "));

        String queryBase = "SELECT " +
                           "    d.dependency_id, d.document_id, d.sentence_id, " +
                           "    d.head_token, d.dependent_token, " +
                           "    d.relation, d.begin_char, d.end_char " +
                           "FROM dependencies d " +
                           "WHERE d.relation IN (" + inClause + ") ";

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
                stmt.setLong(1, lastProcessedEntry.getDependencyId());
                stmt.setInt(2, this.batchSize);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String headToken = sanitizeText(rs.getString("head_token"));
                    String dependentToken = sanitizeText(rs.getString("dependent_token"));
                    String relation = rs.getString("relation");

                    if (headToken == null || headToken.isEmpty() ||
                        dependentToken == null || dependentToken.isEmpty() ||
                        relation == null || relation.isEmpty()) {
                        logger.debug("Skipping entry with null/empty fields: head='{}', dependent='{}', relation='{}'",
                                   headToken, dependentToken, relation);
                        continue;
                    }

                    String headTokenLower = headToken.toLowerCase();
                    String dependentTokenLower = dependentToken.toLowerCase();

                    if (isStopword(headTokenLower) || isStopword(dependentTokenLower)) {
                        continue;
                    }

                    batch.add(new DependencyEntry(
                        rs.getLong("dependency_id"),
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
    protected ListMultimap<String, PositionListSoA> processBatch(List<DependencyEntry> batch) {
        ListMultimap<String, PositionListSoA> index = ArrayListMultimap.create();
        Map<String, PositionListSoA> positionLists = new HashMap<>();

        for (DependencyEntry entry : batch) {
            String key = createKey(entry.getHeadToken(), entry.getDependentToken());

            PositionListSoA posList = positionLists.computeIfAbsent(key, k -> new PositionListSoA());
            posList.add(
                entry.getDocumentId(),
                entry.getSentenceId(),
                entry.getBeginChar(),
                entry.getEndChar()
            );
        }

        for (Map.Entry<String, PositionListSoA> entryMap : positionLists.entrySet()) {
            index.put(entryMap.getKey(), entryMap.getValue());
        }

        return index;
    }

    protected String createKey(String category, String instance) {
        return category.toLowerCase() + DELIMITER + instance.toLowerCase();
    }

    private String sanitizeText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String cleaned = text.trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // Hypernyms are derived from dependencies, so count documents with dependencies.
        // This might be an overestimate if not all dependencies yield hypernyms, but it's a starting point.
        String inClause = HYPERNYM_RELATIONS.stream()
            .map(r -> "'" + r.replace("'", "''") + "'")
            .collect(Collectors.joining(", "));
        String countSql = "SELECT MAX(dependency_id) FROM dependencies WHERE relation IN (" + inClause + ")";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }
}