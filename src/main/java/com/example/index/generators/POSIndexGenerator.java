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

import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * Generates a streaming POS (Part-of-Speech) index from annotation entries.
 * The primary key is the POS tag (e.g., "NOUN").
 * Specific tokens (e.g., "apple") are mapped to integer IDs using a shared SynonymManager,
 * and these IDs are stored in PositionListSoA.synonymId.
 * Uses streaming processing and external sorting for efficient memory usage.
 *
 * This implementation is now RocksDB-based (see IndexGenerator).
 */
public final class POSIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(POSIndexGenerator.class);

    public static final String POS_TAGS_TO_EXCLUDE_SQL = "(',', '.', ':', '``', '''','$','SYM','HYPH','NFP','AFX','LS','X','-LRB-','-RRB-', 'FW')";
    private final SynonymManager synonymManager;

    public POSIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize,
            SynonymManager sharedSynonymManager) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null, sharedSynonymManager);
    }

    public POSIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
        if (sharedSynonymManager == null) {
            throw new IllegalArgumentException("Shared SynonymManager cannot be null.");
        }
        this.synonymManager = sharedSynonymManager;
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String query;
        boolean isFirstBatch = (lastProcessedEntry == null);

        String notInClause = " AND pos NOT IN " + POS_TAGS_TO_EXCLUDE_SQL;

        if (isFirstBatch) {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos " +
                    "FROM annotations WHERE pos IS NOT NULL AND pos != ''" + notInClause + " ORDER BY annotation_id LIMIT ?";
        } else {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos " +
                    "FROM annotations WHERE annotation_id > ? AND pos IS NOT NULL AND pos != ''" + notInClause + " ORDER BY annotation_id LIMIT ?";
        }

        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            int paramIdx = 1;
            if (!isFirstBatch) {
                stmt.setLong(paramIdx++, lastProcessedEntry.getAnnotationId());
            }
            stmt.setInt(paramIdx, this.batchSize);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AnnotationEntry entry = new AnnotationEntry(
                        rs.getLong("annotation_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        rs.getString("token"),
                        rs.getString("pos"),
                        null, // ner
                        null, // normalizedNer
                        null  // lemma
                    );
                    batch.add(entry);
                }
            }
        }

        return batch;
    }

    @Override
    protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<String, PositionListSoA> index = ArrayListMultimap.create();
        Map<String, PositionListSoA> tempAggregator = new HashMap<>();

        for (AnnotationEntry entry : batch) {
            if (entry.getPos() == null || entry.getPos().isEmpty() || entry.getToken() == null || entry.getToken().isEmpty()) {
                continue;
            }

            String posTag = entry.getPos().toUpperCase();
            String token = entry.getToken();

            if (token.trim().length() <= 1) {
                continue;
            }
            String lowerCaseToken = token.toLowerCase();

            String indexKey = posTag;

            try {
                int tokenId = synonymManager.getId(lowerCaseToken);

                PositionListSoA pl = tempAggregator.computeIfAbsent(indexKey, k -> new PositionListSoA());
                pl.add(entry.getDocumentId(), entry.getSentenceId(), entry.getBeginChar(), entry.getEndChar(), tokenId);
            } catch (RocksDBException e) {
                logger.error("RocksDBException while getting ID for token '{}' with POS tag '{}'. Error: {}", token, posTag, e.getMessage(), e);
            }
        }

        for (Map.Entry<String, PositionListSoA> mapEntry : tempAggregator.entrySet()) {
            index.put(mapEntry.getKey(), mapEntry.getValue());
        }
        return index;
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // String countSql = "SELECT MAX(annotation_id) FROM annotations WHERE pos IS NOT NULL AND pos != ''";
        // try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
        //      ResultSet rs = stmt.executeQuery()) {
        //     if (rs.next()) {
        //         return rs.getLong(1);
        //     }
        // }
        // return 0;
        // Return 0 to indicate an indeterminate progress bar, as MAX(annotation_id) is not representative.
        return 0;
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}