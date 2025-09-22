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
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.DependencyEntry;
import com.example.index.presence.RBPresenceIndex;
import com.example.logging.ProgressTracker;

public final class RBDependencyIndexGenerator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RBDependencyIndexGenerator.class);
    private static final Set<String> BLACKLISTED_RELATIONS = Set.of("punct","dep","det");

    private final IndexAccessInterface indexAccess;
    private final Connection sqliteConn;
    private final ProgressTracker progress;
    private final int batchSize;
    private final java.util.Set<String> stopwords;
    private long totalTermsWrittenToIndex = 0;

    public RBDependencyIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
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
            String line; while ((line = reader.readLine()) != null) { String t = line.trim(); if (!t.isEmpty() && !t.startsWith("#")) s.add(t.toLowerCase()); }
        }
        return java.util.Collections.unmodifiableSet(s);
    }
    private boolean isStopword(String term) { return term != null && stopwords.contains(term.toLowerCase()); }

    public long getDocumentCountForIndex() throws SQLException {
        try (PreparedStatement stmt = sqliteConn.prepareStatement("SELECT MAX(dependency_id) FROM dependencies"); ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) return rs.getLong(1);
        }
        return 0;
    }
    public long getTotalTermsWrittenToIndex() { return totalTermsWrittenToIndex; }

    public void generateIndex() throws SQLException, IOException {
        progress.startIndex("rb_dependency", getDocumentCountForIndex());
        DependencyEntry last = null;
        while (true) {
            List<DependencyEntry> batch = fetchBatch(last);
            if (batch.isEmpty()) break;
            Map<String, RBPresenceIndex> agg = processBatch(batch);
            writeAggregates(agg);
            last = batch.get(batch.size() - 1);
            progress.updateIndex(batch.size());
        }
        progress.completeIndex();
        try { indexAccess.flushAndCompact(); } catch (IndexAccessException e) { logger.warn("Flush/compact failed: {}", e.getMessage()); }
    }

    private List<DependencyEntry> fetchBatch(DependencyEntry last) throws SQLException {
        List<DependencyEntry> batch = new ArrayList<>();
        String sql = (last == null)
            ? "SELECT dependency_id, document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation FROM dependencies ORDER BY dependency_id LIMIT ?"
            : "SELECT dependency_id, document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation FROM dependencies WHERE dependency_id > ? ORDER BY dependency_id LIMIT ?";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            if (last == null) stmt.setInt(1, batchSize); else { stmt.setLong(1, last.getDependencyId()); stmt.setInt(2, batchSize); }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    batch.add(new DependencyEntry(
                        rs.getLong("dependency_id"), rs.getInt("document_id"), rs.getInt("sentence_id"),
                        rs.getInt("begin_char"), rs.getInt("end_char"), rs.getString("head_token"), rs.getString("dependent_token"), rs.getString("relation")));
                }
            }
        }
        return batch;
    }

    private Map<String, RBPresenceIndex> processBatch(List<DependencyEntry> batch) {
        Map<String, RBPresenceIndex> tmp = new HashMap<>();
        for (DependencyEntry e : batch) {
            String h = e.getHeadToken(); String d = e.getDependentToken(); String r = e.getRelation();
            if (h == null || d == null || r == null || h.isEmpty() || d.isEmpty() || r.isEmpty()) continue;
            String hl = h.toLowerCase(); String dl = d.toLowerCase(); String rl = r.toLowerCase();
            if (isStopword(hl) || isStopword(dl) || BLACKLISTED_RELATIONS.contains(rl)) continue;
            String key = hl + String.valueOf((char)0) + rl + String.valueOf((char)0) + dl;
            RBPresenceIndex idx = tmp.computeIfAbsent(key, k -> new RBPresenceIndex());
            idx.add(e.getDocumentId(), e.getSentenceId());
        }
        return tmp;
    }

    private void writeAggregates(Map<String, RBPresenceIndex> agg) throws IOException {
        if (agg.isEmpty()) return;
        org.rocksdb.WriteBatch wb;
        try { wb = indexAccess.createWriteBatch(); } catch (IndexAccessException e) { throw new IOException("Failed to create write batch", e); }
        int puts = 0;
        for (Map.Entry<String, RBPresenceIndex> e : agg.entrySet()) {
            String key = e.getKey(); byte[] k = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                Optional<byte[]> existing = indexAccess.getRaw(k);
                if (existing.isPresent()) { RBPresenceIndex prev = RBPresenceIndex.fromBytes(existing.get()); RBPresenceIndex merged = (RBPresenceIndex) prev.or(e.getValue()); wb.put(k, merged.toBytes()); }
                else { wb.put(k, e.getValue().toBytes()); totalTermsWrittenToIndex++; }
                puts++;
            } catch (IndexAccessException ex) { throw new IOException("Index access error while writing key: " + key, ex); }
            catch (org.rocksdb.RocksDBException ex) { throw new IOException("RocksDB error while staging write for key: " + key, ex); }
        }
        try { indexAccess.write(wb); } catch (IndexAccessException ex) { throw new IOException("Failed to write batch", ex); } finally { wb.close(); }
        logger.debug("RBDependency: wrote {} keys in batch", puts);
    }

    @Override public void close() throws IOException {}
}


