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

import com.example.core.IndexAccessInterface;
import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.index.AnnotationEntry;
import com.example.index.IndexKey;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Generates a streaming unigram index from annotation entries.
 * Each entry maps a single token to its positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class UnigramIndexGenerator extends IndexGenerator<AnnotationEntry> {

    public UnigramIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn,
            ProgressTracker progress, int batchSize) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public UnigramIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn,
            ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
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

        if (isFirstBatch) {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " +
                    "FROM annotations WHERE (pos IS NULL OR pos NOT IN ('FW', 'ADD')) ORDER BY annotation_id LIMIT ?";
        } else {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " +
                    "FROM annotations WHERE annotation_id > ? AND (pos IS NULL OR pos NOT IN ('FW', 'ADD')) ORDER BY annotation_id LIMIT ?";
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
                            null, // pos
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
    protected ListMultimap<IndexKey, PostingList> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<IndexKey, PostingList> index = ArrayListMultimap.create();
        // Collect cell-level data per term: term -> (cellKey -> list of begin offsets)
        Map<IndexKey, Map<Long, IntArrayList>> termCellMap = new HashMap<>();
        Map<IndexKey, Byte> termConstLen = new HashMap<>();

        for (AnnotationEntry entry : batch) {
            if (entry.getToken() == null || entry.getToken().isEmpty()) {
                continue;
            }
            String tokenLower = entry.getToken().toLowerCase();
            if (isStopword(tokenLower)) {
                continue;
            }

            long cellKey = PostingList.packCellKey(entry.getDocumentId(), entry.getSentenceId());
            Map<Long, IntArrayList> cellMap = termCellMap.computeIfAbsent(IndexKey.fromUtf8(tokenLower),
                    k -> new java.util.LinkedHashMap<>());
            cellMap.computeIfAbsent(cellKey, k -> new IntArrayList()).add(entry.getBeginChar());

            // Compute constant length (clamped to byte range)
            int len = entry.getEndChar() - entry.getBeginChar();
            byte cl = (byte) Math.min(len, 255);
            termConstLen.putIfAbsent(IndexKey.fromUtf8(tokenLower), cl);
        }

        // Build PostingList for each term
        for (Map.Entry<IndexKey, Map<Long, IntArrayList>> termEntry : termCellMap.entrySet()) {
            IndexKey term = termEntry.getKey();
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

            byte constantLength = termConstLen.getOrDefault(term, (byte) 0);
            OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(cellKeysArr, beginsArr, constantLength);
            PostingList pl = PostingList.fromCellsAndOccurrences(cells, constantLength, occ);
            index.put(term, pl);
        }
        return index;
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // Unigrams are derived from annotations, so count annotations. This is an
        // intentional approximation for speed.
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
