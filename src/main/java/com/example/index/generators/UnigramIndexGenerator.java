package com.example.index.generators;

import java.io.IOException;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.code.externalsorting.ExternalSort;

/**
 * Generates a streaming unigram index from annotation entries.
 * Each entry maps a single token to its positions in the corpus.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class UnigramIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final int SPILL_UNIQUE_TERMS_THRESHOLD = 250_000;
    private static final int MAX_TEMP_FILES_BEFORE_MERGE_UNIGRAM = 15_000;

    public UnigramIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public UnigramIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
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
                long lastId = (lastProcessedEntry != null) ? lastProcessedEntry.getAnnotationId() : 0L;
                stmt.setLong(1, lastId);
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
    protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<String, PositionListSoA> index = ArrayListMultimap.create();
        Map<String, PositionListSoA> tempAggregator = new HashMap<>();

        for (AnnotationEntry entry : batch) {
            if (entry.getToken() == null || entry.getToken().isEmpty()) {
                continue;
            }
            String tokenLower = entry.getToken().toLowerCase();
            if (isStopword(tokenLower)) {
                continue;
            }

            PositionListSoA pl = tempAggregator.computeIfAbsent(tokenLower, k -> new PositionListSoA());
            pl.add(entry.getDocumentId(), entry.getSentenceId(), entry.getBeginChar(), entry.getEndChar());
        }

        for (Map.Entry<String, PositionListSoA> mapEntry : tempAggregator.entrySet()) {
            index.put(mapEntry.getKey(), mapEntry.getValue());
        }
        return index;
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // Unigrams are derived from annotations, so count annotations. This is an intentional approximation for speed.
        String countSql = "SELECT MAX(annotation_id) FROM annotations";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }

    // Streaming aggregation path replaces legacy batched approach
    @Override
    public void generateIndex() throws SQLException, IOException {
        List<File> tempFiles = new ArrayList<>();
        Map<String, PositionListSoA> aggregator = new HashMap<>();
        long lastAnnotationId = 0L;

        try {
            while (true) {
                boolean isFirstPage = (lastAnnotationId == 0L);
                String sql = isFirstPage
                    ? "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " +
                      "FROM annotations WHERE (pos IS NULL OR pos NOT IN ('FW', 'ADD')) ORDER BY annotation_id LIMIT ?"
                    : "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " +
                      "FROM annotations WHERE annotation_id > ? AND (pos IS NULL OR pos NOT IN ('FW', 'ADD')) ORDER BY annotation_id LIMIT ?";

                int rowsThisPage = 0;
                try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
                    if (isFirstPage) {
                        stmt.setInt(1, this.batchSize);
                    } else {
                        stmt.setLong(1, lastAnnotationId);
                        stmt.setInt(2, this.batchSize);
                    }
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            rowsThisPage++;
                            long id = rs.getLong("annotation_id");
                            lastAnnotationId = id;

                            String rawToken = rs.getString("token");
                            if (rawToken == null) continue;
                            String token = rawToken.trim();
                            if (token.isEmpty()) continue;
                            String t = token.toLowerCase();
                            if (isStopword(t)) continue;

                            PositionListSoA pl = aggregator.computeIfAbsent(t, k -> new PositionListSoA());
                            pl.add(rs.getInt("document_id"), rs.getInt("sentence_id"), rs.getInt("begin_char"), rs.getInt("end_char"));

                            if (aggregator.size() >= SPILL_UNIQUE_TERMS_THRESHOLD) {
                                tempFiles.add(writeAndClearAggregator(aggregator));
                                if (tempFiles.size() >= MAX_TEMP_FILES_BEFORE_MERGE_UNIGRAM) {
                                    tempFiles = performIncrementalMerge(tempFiles);
                                }
                            }
                        }
                    }
                }

                if (rowsThisPage == 0) {
                    break;
                }
                progress.updateIndex(rowsThisPage);
            }

            if (!aggregator.isEmpty()) {
                tempFiles.add(writeAndClearAggregator(aggregator));
            }

            if (tempFiles.isEmpty()) {
                progress.completeIndex();
                return;
            }

            ExternalSort.mergeSortedFiles(tempFiles, this.tempFilePathForSorting.toFile(), new PositionListComparator(), Charset.defaultCharset(), false);
            progress.startIndex(getIndexName() + " - Writing to DB", 0);
            writeToLevelDB(this.tempFilePathForSorting.toFile());
            progress.completeIndex();
        } finally {
            for (File f : tempFiles) {
                try { java.nio.file.Files.deleteIfExists(f.toPath()); } catch (IOException ignore) {}
            }
        }
    }

    private File writeAndClearAggregator(Map<String, PositionListSoA> aggregator) throws IOException {
        ArrayListMultimap<String, PositionListSoA> mm = ArrayListMultimap.create();
        for (Map.Entry<String, PositionListSoA> e : aggregator.entrySet()) {
            mm.put(e.getKey(), e.getValue());
        }
        File f = writeBatchToTempFile(mm);
        aggregator.clear();
        return f;
    }

    // Local comparator mirroring IndexGenerator's ordering
    private static class PositionListComparator implements Comparator<String> {
        @Override
        public int compare(String a, String b) {
            int ta = a.indexOf('\t');
            if (ta < 0) ta = a.length();
            int tb = b.indexOf('\t');
            if (tb < 0) tb = b.length();
            String ka = a.substring(0, ta);
            String kb = b.substring(0, tb);
            return compareKeysUtf8(ka, kb);
        }
    }

    private static int compareKeysUtf8(String a, String b) {
        byte[] ab = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int la = ab.length, lb = bb.length, i = 0;
        int min = Math.min(la, lb);
        while (i < min) {
            int va = ab[i] & 0xFF;
            int vb = bb[i] & 0xFF;
            if (va != vb) return va - vb;
            i++;
        }
        return la - lb;
    }
}