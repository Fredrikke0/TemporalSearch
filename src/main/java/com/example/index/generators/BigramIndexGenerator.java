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
 * Generates a streaming bigram index from annotation entries.
 * Each entry maps a pair of consecutive tokens to their positions in the
 * corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 *
 * This implementation is now RocksDB-based (see IndexGenerator).
 */
public final class BigramIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(BigramIndexGenerator.class);

    // Carry over the last token of the previous batch to avoid missing
    // cross-batch bigrams (boundary-spanning n-grams)
    private AnnotationEntry lastEntryFromPreviousBatch = null;

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
                            null);
                    batch.add(entry);
                }
            }
        }
        return batch;
    }

    @Override
    protected ListMultimap<IndexKey, PostingList> processBatch(List<AnnotationEntry> batch) {
        // Augment the current batch with the tail of the previous batch so that
        // bigrams that span the boundary are generated in this call.
        List<AnnotationEntry> augmented = new ArrayList<>(batch.size() + 1);
        if (lastEntryFromPreviousBatch != null) {
            augmented.add(lastEntryFromPreviousBatch);
        }
        augmented.addAll(batch);

        augmented.sort(Comparator.comparingInt(AnnotationEntry::getDocumentId)
                .thenComparingInt(AnnotationEntry::getSentenceId)
                .thenComparingInt(AnnotationEntry::getBeginChar));

        ListMultimap<IndexKey, PostingList> index = ArrayListMultimap.create();
        Map<IndexKey, Map<Long, IntArrayList>> termCellMap = new HashMap<>();
        Map<IndexKey, Byte> termConstLen = new HashMap<>();

        for (int i = 0; i < augmented.size() - 1; i++) {
            AnnotationEntry firstEntry = augmented.get(i);
            AnnotationEntry secondEntry = augmented.get(i + 1);

            if (firstEntry.getDocumentId() != secondEntry.getDocumentId() ||
                    firstEntry.getSentenceId() != secondEntry.getSentenceId()) {
                continue; // Moved to a new sentence or document
            }

            // Check for non-adjacency between all parts of the bigram.
            if (secondEntry.getBeginChar() > firstEntry.getEndChar() + 2) {
                continue;
            }

            String firstToken = firstEntry.getToken();
            String secondToken = secondEntry.getToken();

            if (firstToken == null || firstToken.isEmpty() || secondToken == null || secondToken.isEmpty()) {
                continue;
            }

            String firstTokenLower = firstToken.toLowerCase();
            String secondTokenLower = secondToken.toLowerCase();

            if (isStopword(firstTokenLower) || isStopword(secondTokenLower)) {
                continue;
            }

            String key = String.format("%s%s%s",
                    firstTokenLower,
                    DELIMITER,
                    secondTokenLower);

            long cellKey = PostingList.packCellKey(secondEntry.getDocumentId(), secondEntry.getSentenceId());
            Map<Long, IntArrayList> cellMap = termCellMap.computeIfAbsent(IndexKey.fromUtf8(key),
                    k -> new java.util.LinkedHashMap<>());
            cellMap.computeIfAbsent(cellKey, k -> new IntArrayList()).add(firstEntry.getBeginChar());

            // Compute constant length for the bigram span
            int len = secondEntry.getEndChar() - firstEntry.getBeginChar();
            byte cl = (byte) Math.min(len, 255);
            termConstLen.putIfAbsent(IndexKey.fromUtf8(key), cl);
        }

        // Build PostingList for each bigram key
        for (Map.Entry<IndexKey, Map<Long, IntArrayList>> termEntry : termCellMap.entrySet()) {
            IndexKey key = termEntry.getKey();
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
            index.put(key, pl);
        }

        // Keep only the last token to bridge with the next batch.
        lastEntryFromPreviousBatch = augmented.isEmpty() ? null : augmented.get(augmented.size() - 1);
        return index;
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // Rough estimate, but fast.
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
