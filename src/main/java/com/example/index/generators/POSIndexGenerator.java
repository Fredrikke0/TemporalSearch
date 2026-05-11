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
import java.nio.charset.StandardCharsets;

import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.index.KeySchema;
import com.example.index.AnnotationEntry;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * Generates a streaming POS (Part-of-Speech) index from annotation entries.
 * Keys are encoded with {@link KeySchema}: {@code TAG\0<4-byte synId>}.
 * Specific tokens (e.g., "apple") are mapped to integer IDs using a shared
 * SynonymManager,
 * and these IDs are encoded in the key rather than stored in the value blob.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class POSIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(POSIndexGenerator.class);

    public static final String POS_TAGS_TO_EXCLUDE_SQL = "(',', '.', ':', '`', '''','$','SYM','HYPH','NFP','AFX','LS','X','-LRB-','-RRB-', 'FW', '', '''''', 'DT', 'WDT', 'CC', 'PRP$', 'POS', '`', 'EX', 'UH', 'IN')";
    private final SynonymManager synonymManager;

    public POSIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn,
            ProgressTracker progress, int batchSize,
            SynonymManager sharedSynonymManager) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null, sharedSynonymManager);
    }

    public POSIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn,
            ProgressTracker progress, int batchSize, Path customTempPath,
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
                    "FROM annotations WHERE pos != ''" + notInClause + " ORDER BY annotation_id LIMIT ?";
        } else {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos " +
                    "FROM annotations WHERE annotation_id > ? AND pos != ''" + notInClause
                    + " ORDER BY annotation_id LIMIT ?";
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
                            null // normalizedNer
                    );
                    batch.add(entry);
                }
            }
        }

        return batch;
    }

    @Override
    protected ListMultimap<String, PostingList> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<String, PostingList> index = ArrayListMultimap.create();

        // Key: indexKey (String from KeySchema), Value: (cellKey -> list of beginChars)
        Map<String, Map<Long, List<Integer>>> perTermData = new HashMap<>();
        Map<String, Byte> perTermConstantLength = new HashMap<>();

        for (AnnotationEntry entry : batch) {
            if (entry.getPos() == null || entry.getPos().isEmpty() || entry.getToken() == null
                    || entry.getToken().isEmpty()) {
                continue;
            }

            String posTag = entry.getPos().toUpperCase();
            String token = entry.getToken();

            if (token.trim().length() <= 1) {
                continue;
            }
            String lowerCaseToken = token.toLowerCase();

            try {
                int tokenId = synonymManager.getId(lowerCaseToken);

                byte[] indexKeyBytes = KeySchema.encodeKey(posTag, tokenId);
                String indexKey = new String(indexKeyBytes, StandardCharsets.UTF_8);

                long cellKey = PostingList.packCellKey(entry.getDocumentId(), entry.getSentenceId());

                Map<Long, List<Integer>> cellMap = perTermData.computeIfAbsent(indexKey, k -> new HashMap<>());
                cellMap.computeIfAbsent(cellKey, k -> new ArrayList<>()).add(entry.getBeginChar());

                byte constLen = (byte) Math.min(entry.getEndChar() - entry.getBeginChar(), 255);
                perTermConstantLength.putIfAbsent(indexKey, constLen);
            } catch (RocksDBException e) {
                logger.error("RocksDBException while getting ID for token '{}' with POS tag '{}'. Error: {}", token,
                        posTag, e.getMessage(), e);
            }
        }

        // Convert per-term aggregation maps to PostingLists
        for (Map.Entry<String, Map<Long, List<Integer>>> mapEntry : perTermData.entrySet()) {
            String indexKey = mapEntry.getKey();
            Map<Long, List<Integer>> cellMap = mapEntry.getValue();
            byte constLen = perTermConstantLength.getOrDefault(indexKey, (byte) 0);
            PostingList pl = buildPostingList(cellMap, constLen);
            index.put(indexKey, pl);
        }
        return index;
    }

    /**
     * Builds a {@link PostingList} from a per-cell map of begin character offsets.
     */
    private static PostingList buildPostingList(Map<Long, List<Integer>> cellMap, byte constantLength) {
        int numCells = cellMap.size();
        if (numCells == 0) {
            return PostingList.empty(constantLength);
        }

        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        long[] cellKeys = new long[numCells];
        byte[][] beginsPerCell = new byte[numCells][];
        int idx = 0;

        for (Map.Entry<Long, List<Integer>> entry : cellMap.entrySet()) {
            long cellKey = entry.getKey();
            cells.add(cellKey);
            cellKeys[idx] = cellKey;
            List<Integer> begins = entry.getValue();
            byte[] beginsArr = new byte[begins.size()];
            for (int j = 0; j < begins.size(); j++) {
                beginsArr[j] = (byte) (int) begins.get(j);
            }
            beginsPerCell[idx] = beginsArr;
            idx++;
        }

        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(cellKeys, beginsPerCell, constantLength);
        return PostingList.fromCellsAndOccurrences(cells, constantLength, occ);
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        return 0;
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
