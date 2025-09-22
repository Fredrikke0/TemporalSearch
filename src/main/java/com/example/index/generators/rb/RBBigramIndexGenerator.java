package com.example.index.generators.rb;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationEntry;
import com.example.index.presence.RBPresenceIndex;
import com.example.logging.ProgressTracker;

public final class RBBigramIndexGenerator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RBBigramIndexGenerator.class);

    private final IndexAccessInterface indexAccess;
    private final Connection sqliteConn;
    private final ProgressTracker progress;
    private final int batchSize;
    private final java.util.Set<String> stopwords;

    private AnnotationEntry lastEntryFromPreviousBatch = null;
    private long totalTermsWrittenToIndex = 0;

    public RBBigramIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this.indexAccess = indexAccess;
        this.sqliteConn = sqliteConn;
        this.progress = progress;
        this.batchSize = batchSize;
        this.stopwords = loadStopwords(stopwordsPath);
    }

    private static java.util.Set<String> loadStopwords(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) return java.util.Collections.emptySet();
        java.nio.file.Path p = java.nio.file.Path.of(path);
        if (!java.nio.file.Files.exists(p)) return java.util.Collections.emptySet();
        java.util.Set<String> s = new java.util.HashSet<>();
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(p)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) s.add(t.toLowerCase());
            }
        }
        return java.util.Collections.unmodifiableSet(s);
    }

    private boolean isStopword(String term) {
        return term != null && stopwords.contains(term.toLowerCase());
    }

    public long getDocumentCountForIndex() throws SQLException {
        String countSql = "SELECT MAX(annotation_id) FROM annotations";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql); ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) return rs.getLong(1);
        }
        return 0;
    }

    public long getTotalTermsWrittenToIndex() { return totalTermsWrittenToIndex; }

    public void generateIndex() throws SQLException, IOException {
        progress.startIndex("rb_bigram", getDocumentCountForIndex());
        AnnotationEntry last = null;
        while (true) {
            List<AnnotationEntry> batch = fetchBatch(last);
            if (batch.isEmpty()) break;
            Map<String, RBPresenceIndex> agg = processBatch(batch);
            writeAggregates(agg);
            last = batch.get(batch.size() - 1);
            progress.updateIndex(batch.size());
        }
        progress.completeIndex();
        try { indexAccess.flushAndCompact(); } catch (IndexAccessException e) { logger.warn("Flush/compact failed: {}", e.getMessage()); }
    }

    private List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String query;
        boolean isFirstBatch = (lastProcessedEntry == null);
        if (isFirstBatch) {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token FROM annotations WHERE pos NOT IN ('FW','ADD') ORDER BY annotation_id LIMIT ?";
        } else {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token FROM annotations WHERE annotation_id > ? AND pos NOT IN ('FW','ADD') ORDER BY annotation_id LIMIT ?";
        }
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            if (isFirstBatch) {
                stmt.setInt(1, batchSize);
            } else {
                stmt.setLong(1, lastProcessedEntry.getAnnotationId());
                stmt.setInt(2, batchSize);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String token = rs.getString("token");
                    if (token != null) token = token.trim();
                    batch.add(new AnnotationEntry(
                        rs.getLong("annotation_id"), rs.getInt("document_id"), rs.getInt("sentence_id"),
                        rs.getInt("begin_char"), rs.getInt("end_char"), token, null, null, null
                    ));
                }
            }
        }
        return batch;
    }

    private Map<String, RBPresenceIndex> processBatch(List<AnnotationEntry> batch) {
        List<AnnotationEntry> augmented = new ArrayList<>(batch.size() + 1);
        if (lastEntryFromPreviousBatch != null) augmented.add(lastEntryFromPreviousBatch);
        augmented.addAll(batch);
        augmented.sort(Comparator.comparingInt(AnnotationEntry::getDocumentId)
            .thenComparingInt(AnnotationEntry::getSentenceId)
            .thenComparingInt(AnnotationEntry::getBeginChar));

        Map<String, RBPresenceIndex> temp = new HashMap<>();
        for (int i = 0; i < augmented.size() - 1; i++) {
            AnnotationEntry a = augmented.get(i);
            AnnotationEntry b = augmented.get(i + 1);
            if (a.getDocumentId() != b.getDocumentId() || a.getSentenceId() != b.getSentenceId()) continue;
            if (b.getBeginChar() > a.getEndChar() + 2) continue;
            String t1 = a.getToken(); String t2 = b.getToken();
            if (t1 == null || t1.isEmpty() || t2 == null || t2.isEmpty()) continue;
            t1 = t1.toLowerCase(); t2 = t2.toLowerCase();
            if (isStopword(t1) || isStopword(t2)) continue;
            String key = t1 + String.valueOf((char)0) + t2;
            RBPresenceIndex idx = temp.computeIfAbsent(key, k -> new RBPresenceIndex());
            idx.add(b.getDocumentId(), b.getSentenceId());
        }
        lastEntryFromPreviousBatch = augmented.isEmpty() ? null : augmented.get(augmented.size() - 1);
        return temp;
    }

    private void writeAggregates(Map<String, RBPresenceIndex> agg) throws IOException {
        if (agg.isEmpty()) return;
        org.rocksdb.WriteBatch wb;
        try { wb = indexAccess.createWriteBatch(); } catch (IndexAccessException e) { throw new IOException("Failed to create write batch", e); }
        int puts = 0;
        for (Map.Entry<String, RBPresenceIndex> e : agg.entrySet()) {
            String key = e.getKey();
            byte[] k = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            RBPresenceIndex cur = e.getValue();
            try {
                Optional<byte[]> existing = indexAccess.getRaw(k);
                if (existing.isPresent()) {
                    RBPresenceIndex prev = RBPresenceIndex.fromBytes(existing.get());
                    RBPresenceIndex merged = (RBPresenceIndex) prev.or(cur);
                    wb.put(k, merged.toBytes());
                } else {
                    wb.put(k, cur.toBytes());
                    totalTermsWrittenToIndex++;
                }
                puts++;
            } catch (IndexAccessException ex) {
                throw new IOException("Index access error while writing key: " + key, ex);
            } catch (org.rocksdb.RocksDBException ex) {
                throw new IOException("RocksDB error while staging write for key: " + key, ex);
            }
        }
        try { indexAccess.write(wb); } catch (IndexAccessException ex) { throw new IOException("Failed to write batch", ex); } finally { wb.close(); }
        logger.debug("RBBigram: wrote {} keys in batch", puts);
    }

    @Override
    public void close() throws IOException { }
}


