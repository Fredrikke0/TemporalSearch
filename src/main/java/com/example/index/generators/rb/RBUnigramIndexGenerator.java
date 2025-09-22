package com.example.index.generators.rb;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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

/**
 * Streaming Unigram generator that writes Roaring presence bitmaps.
 * Index type directory: rb_unigram
 */
public final class RBUnigramIndexGenerator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RBUnigramIndexGenerator.class);

    private final IndexAccessInterface indexAccess;
    private final Connection sqliteConn;
    private final ProgressTracker progress;
    private final int batchSize;
    private final String stopwordsPath;
    private final java.util.Set<String> stopwords;

    private long totalTermsWrittenToIndex = 0;

    public RBUnigramIndexGenerator(IndexAccessInterface indexAccess,
                                   String stopwordsPath,
                                   Connection sqliteConn,
                                   ProgressTracker progress,
                                   int batchSize) throws IOException {
        this.indexAccess = indexAccess;
        this.sqliteConn = sqliteConn;
        this.progress = progress;
        this.batchSize = batchSize;
        this.stopwordsPath = stopwordsPath;
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
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }

    public long getTotalTermsWrittenToIndex() {
        return totalTermsWrittenToIndex;
    }

    public void generateIndex() throws SQLException, IOException {
        progress.startIndex("rb_unigram", getDocumentCountForIndex());
        AnnotationEntry last = null;
        long fetchedTotal = 0;

        while (true) {
            List<AnnotationEntry> batch = fetchBatch(last);
            if (batch.isEmpty()) break;
            fetchedTotal += batch.size();

            Map<String, RBPresenceIndex> agg = processBatch(batch);
            writeAggregates(agg);

            last = batch.get(batch.size() - 1);
            progress.updateIndex(batch.size());
        }
        progress.completeIndex();
        logger.info("RBUnigram: done. Terms written (unique writes counted on first creation): {}", totalTermsWrittenToIndex);

        try {
            this.indexAccess.flushAndCompact();
            logger.info("Flush and compact completed for index [rb_unigram].");
        } catch (IndexAccessException e) {
            logger.warn("Flush/compact failed for rb_unigram: {}", e.getMessage());
        }
    }

    private List<AnnotationEntry> fetchBatch(AnnotationEntry last) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String query;
        boolean first = (last == null);
        if (first) {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " +
                    "FROM annotations ORDER BY annotation_id LIMIT ?";
        } else {
            query = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token " +
                    "FROM annotations WHERE annotation_id > ? ORDER BY annotation_id LIMIT ?";
        }
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            if (first) {
                stmt.setInt(1, this.batchSize);
            } else {
                stmt.setLong(1, last.getAnnotationId());
                stmt.setInt(2, this.batchSize);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String rawToken = rs.getString("token");
                    String token = rawToken == null ? null : rawToken.trim();
                    AnnotationEntry entry = new AnnotationEntry(
                        rs.getLong("annotation_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        token,
                        null,
                        null,
                        null
                    );
                    batch.add(entry);
                }
            }
        }
        return batch;
    }

    private Map<String, RBPresenceIndex> processBatch(List<AnnotationEntry> batch) {
        Map<String, RBPresenceIndex> tmp = new HashMap<>();
        for (AnnotationEntry e : batch) {
            String token = e.getToken();
            if (token == null || token.isEmpty()) continue;
            String norm = token.toLowerCase();
            if (isStopword(norm)) continue;
            RBPresenceIndex idx = tmp.computeIfAbsent(norm, k -> new RBPresenceIndex());
            idx.add(e.getDocumentId(), e.getSentenceId());
        }
        return tmp;
    }

    private void writeAggregates(Map<String, RBPresenceIndex> agg) throws IOException {
        if (agg.isEmpty()) return;
        org.rocksdb.WriteBatch wb;
        try {
            wb = indexAccess.createWriteBatch();
        } catch (IndexAccessException e) {
            throw new IOException("Failed to create write batch", e);
        }

        int puts = 0;
        for (Map.Entry<String, RBPresenceIndex> entry : agg.entrySet()) {
            String key = entry.getKey();
            byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            RBPresenceIndex current = entry.getValue();

            try {
                Optional<byte[]> existing = indexAccess.getRaw(keyBytes);
                if (existing.isPresent()) {
                    RBPresenceIndex prev = RBPresenceIndex.fromBytes(existing.get());
                    RBPresenceIndex merged = (RBPresenceIndex) prev.or(current);
                    wb.put(keyBytes, merged.toBytes());
                } else {
                    wb.put(keyBytes, current.toBytes());
                    totalTermsWrittenToIndex++; // new key created
                }
                puts++;
            } catch (IndexAccessException ex) {
                throw new IOException("Index access error while writing key: " + key, ex);
            } catch (org.rocksdb.RocksDBException ex) {
                throw new IOException("RocksDB error while staging write for key: " + key, ex);
            }
        }

        try {
            indexAccess.write(wb);
        } catch (IndexAccessException e) {
            throw new IOException("Failed to write batch of presence bitmaps", e);
        } finally {
            wb.close();
        }
        logger.debug("RBUnigram: wrote {} keys in batch", puts);
    }

    @Override
    public void close() throws IOException {
        // Nothing to close here beyond IndexAccess, which is managed by caller
    }
}


