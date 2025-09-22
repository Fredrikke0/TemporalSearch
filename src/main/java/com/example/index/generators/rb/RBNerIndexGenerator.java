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

public final class RBNerIndexGenerator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RBNerIndexGenerator.class);

    private final IndexAccessInterface indexAccess;
    private final Connection sqliteConn;
    private final ProgressTracker progress;
    private final int batchSize;
    private long totalTermsWrittenToIndex = 0;

    public RBNerIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize) {
        this.indexAccess = indexAccess;
        this.sqliteConn = sqliteConn;
        this.progress = progress;
        this.batchSize = batchSize;
    }

    public long getDocumentCountForIndex() throws SQLException {
        try (PreparedStatement stmt = sqliteConn.prepareStatement("SELECT MAX(annotation_id) FROM annotations"); ResultSet rs = stmt.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        return 0;
    }
    public long getTotalTermsWrittenToIndex() { return totalTermsWrittenToIndex; }

    public void generateIndex() throws SQLException, IOException {
        progress.startIndex("rb_ner", getDocumentCountForIndex());
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
        String sql = (lastProcessedEntry == null)
            ? "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, ner FROM annotations WHERE ner != 'O' AND ner != 'DATE' ORDER BY annotation_id LIMIT ?"
            : "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, ner FROM annotations WHERE ner != 'O' AND ner != 'DATE' AND annotation_id > ? ORDER BY annotation_id LIMIT ?";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            if (lastProcessedEntry == null) stmt.setInt(1, batchSize); else { stmt.setLong(1, lastProcessedEntry.getAnnotationId()); stmt.setInt(2, batchSize); }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    batch.add(new AnnotationEntry(rs.getLong("annotation_id"), rs.getInt("document_id"), rs.getInt("sentence_id"), rs.getInt("begin_char"), rs.getInt("end_char"), rs.getString("token"), null, rs.getString("ner"), null));
                }
            }
        }
        return batch;
    }

    private Map<String, RBPresenceIndex> processBatch(List<AnnotationEntry> batch) {
        batch.sort(Comparator.comparingInt(AnnotationEntry::getDocumentId).thenComparingInt(AnnotationEntry::getSentenceId).thenComparingInt(AnnotationEntry::getBeginChar));
        Map<String, RBPresenceIndex> tmp = new HashMap<>();
        String currentType = null; int curDoc=-1, curSent=-1; int begin=-1; AnnotationEntry prev=null;
        List<String> buf = new ArrayList<>();
        for (AnnotationEntry e : batch) {
            String ner = e.getNer(); boolean brk=false;
            if (currentType != null) {
                if (ner == null || "O".equals(ner) || "DATE".equals(ner) || !ner.equals(currentType) || e.getDocumentId()!=curDoc || e.getSentenceId()!=curSent || (prev!=null && e.getBeginChar()>prev.getEndChar()+2)) brk=true;
            }
            if (brk) {
                if (!buf.isEmpty()) {
                    String key = currentType.toUpperCase();
                    RBPresenceIndex idx = tmp.computeIfAbsent(key, k -> new RBPresenceIndex());
                    idx.add(curDoc, curSent);
                }
                buf.clear(); currentType = null;
            }
            if (ner != null && !ner.isEmpty() && !"O".equals(ner) && !"DATE".equals(ner)) {
                if (currentType == null) { currentType = ner; curDoc = e.getDocumentId(); curSent = e.getSentenceId(); begin = e.getBeginChar(); }
                buf.add(e.getToken());
            }
            prev = e;
        }
        if (currentType != null && !buf.isEmpty()) {
            String key = currentType.toUpperCase(); RBPresenceIndex idx = tmp.computeIfAbsent(key, k -> new RBPresenceIndex()); idx.add(curDoc, curSent);
        }
        return tmp;
    }

    private void writeAggregates(Map<String, RBPresenceIndex> agg) throws IOException {
        if (agg.isEmpty()) return; org.rocksdb.WriteBatch wb;
        try { wb = indexAccess.createWriteBatch(); } catch (IndexAccessException e) { throw new IOException("Failed to create write batch", e); }
        for (Map.Entry<String, RBPresenceIndex> e : agg.entrySet()) {
            String key = e.getKey(); byte[] k = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                Optional<byte[]> existing = indexAccess.getRaw(k);
                if (existing.isPresent()) { RBPresenceIndex prev = RBPresenceIndex.fromBytes(existing.get()); RBPresenceIndex merged = (RBPresenceIndex) prev.or(e.getValue()); wb.put(k, merged.toBytes()); }
                else { wb.put(k, e.getValue().toBytes()); totalTermsWrittenToIndex++; }
            } catch (IndexAccessException ex) { throw new IOException("Index access error while writing key: " + key, ex); }
            catch (org.rocksdb.RocksDBException ex) { throw new IOException("RocksDB error while staging write for key: " + key, ex); }
        }
        try { indexAccess.write(wb); } catch (IndexAccessException ex) { throw new IOException("Failed to write batch", ex); } finally { wb.close(); }
    }

    @Override public void close() throws IOException {}
}


