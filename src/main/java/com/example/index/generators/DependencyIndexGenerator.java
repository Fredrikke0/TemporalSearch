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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.index.DependencyEntry;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Generates a streaming dependency index from dependency relation entries.
 */
public final class DependencyIndexGenerator extends IndexGenerator<DependencyEntry> {
    private static final Logger logger = LoggerFactory.getLogger(DependencyIndexGenerator.class);

    // Blacklist common, less informative relations
    private static final Set<String> BLACKLISTED_RELATIONS = Set.of(
            "punct",
            "dep",
            "det");

    public DependencyIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn,
            ProgressTracker progress, int batchSize) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public DependencyIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn,
            ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
    }

    @Override
    protected String getTableName() {
        return "dependencies"; // Assuming a dependencies table
    }

    @Override
    protected List<DependencyEntry> fetchBatch(DependencyEntry lastProcessedEntry) throws SQLException {
        List<DependencyEntry> batch = new ArrayList<>();
        String sql;
        // Using dependency_id from DependencyEntry for keyset pagination
        if (lastProcessedEntry == null) {
            sql = "SELECT dependency_id, document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation "
                    +
                    "FROM dependencies ORDER BY dependency_id LIMIT ?";
        } else {
            sql = "SELECT dependency_id, document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation "
                    +
                    "FROM dependencies WHERE dependency_id > ? ORDER BY dependency_id LIMIT ?";
        }

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            if (lastProcessedEntry == null) {
                stmt.setInt(1, batchSize);
            } else {
                stmt.setLong(1, lastProcessedEntry.getDependencyId());
                stmt.setInt(2, batchSize);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String headToken = rs.getString("head_token");
                    String dependentToken = rs.getString("dependent_token");
                    String relation = rs.getString("relation");

                    if (headToken == null || dependentToken == null || relation == null ||
                            headToken.isEmpty() || dependentToken.isEmpty() || relation.isEmpty()) {
                        logger.debug(
                                "Skipping dependency due to null or empty field. Original: head='{}', dep='{}', rel='{}'",
                                headToken, dependentToken, relation);
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
                            relation));
                }
            }
        }
        return batch;
    }

    @Override
    protected ListMultimap<String, PostingList> processBatch(List<DependencyEntry> batch) {
        ListMultimap<String, PostingList> indexData = ArrayListMultimap.create();
        Map<String, Map<Long, IntArrayList>> termCellMap = new HashMap<>();
        Map<String, Byte> termConstLen = new HashMap<>();

        for (DependencyEntry entry : batch) {
            String headTokenLower = entry.getHeadToken().toLowerCase();
            String dependentTokenLower = entry.getDependentToken().toLowerCase();
            String relationLower = entry.getRelation().toLowerCase();

            if (isStopword(headTokenLower) || isStopword(dependentTokenLower) ||
                    BLACKLISTED_RELATIONS.contains(relationLower)) {
                continue;
            }

            String key = headTokenLower + DELIMITER + relationLower + DELIMITER + dependentTokenLower;

            long cellKey = PostingList.packCellKey(entry.getDocumentId(), entry.getSentenceId());
            Map<Long, IntArrayList> cellMap = termCellMap.computeIfAbsent(key, k -> new java.util.LinkedHashMap<>());
            cellMap.computeIfAbsent(cellKey, k -> new IntArrayList()).add(entry.getBeginChar());

            // Compute constant length (clamped to byte range)
            int len = entry.getEndChar() - entry.getBeginChar();
            byte cl = (byte) Math.min(len, 255);
            termConstLen.putIfAbsent(key, cl);
        }

        // Build PostingList for each dependency key
        for (Map.Entry<String, Map<Long, IntArrayList>> termEntry : termCellMap.entrySet()) {
            String key = termEntry.getKey();
            Map<Long, IntArrayList> cellMap = termEntry.getValue();

            Roaring64NavigableMap cells = new Roaring64NavigableMap();
            for (long ck : cellMap.keySet()) {
                cells.add(ck);
            }

            int numCells = cellMap.size();
            long[] cellKeysArr = new long[numCells];
            byte[][] beginsArr = new byte[numCells][];
            int idx = 0;
            for (Map.Entry<Long, IntArrayList> e : cellMap.entrySet()) {
                cellKeysArr[idx] = e.getKey();
                IntArrayList bl = e.getValue();
                byte[] b = new byte[bl.size()];
                for (int j = 0; j < bl.size(); j++) {
                    b[j] = (byte) bl.getInt(j);
                }
                beginsArr[idx] = b;
                idx++;
            }

            byte constantLength = termConstLen.getOrDefault(key, (byte) 0);
            OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(cellKeysArr, beginsArr, constantLength);
            PostingList pl = PostingList.fromCellsAndOccurrences(cells, constantLength, occ);
            indexData.put(key, pl);
        }
        return indexData;
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        String countSql = "SELECT MAX(dependency_id) FROM dependencies";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
                ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }
}
