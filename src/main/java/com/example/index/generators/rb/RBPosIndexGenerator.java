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
import com.example.index.presence.RBGroupValueBlob;
import com.example.index.presence.RBGroupValueBlob.DocBlock;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class RBPosIndexGenerator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RBPosIndexGenerator.class);

    private final IndexAccessInterface indexAccess;
    private final Connection sqliteConn;
    private final ProgressTracker progress;
    private final int batchSize;
    private long totalTermsWrittenToIndex = 0;
    private final SynonymManager synonymManager;

    public RBPosIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, SynonymManager synonymManager) {
        this.indexAccess = indexAccess;
        this.sqliteConn = sqliteConn;
        this.progress = progress;
        this.batchSize = batchSize;
        this.synonymManager = synonymManager;
    }

    public long getDocumentCountForIndex() throws SQLException {
        try (PreparedStatement stmt = sqliteConn.prepareStatement("SELECT MAX(annotation_id) FROM annotations"); ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) return rs.getLong(1);
        }
        return 0;
    }
    public long getTotalTermsWrittenToIndex() { return totalTermsWrittenToIndex; }

    public void generateIndex() throws SQLException, IOException {
        progress.startIndex("rb_pos", getDocumentCountForIndex());
        AnnotationEntry last = null;
        while (true) {
            List<AnnotationEntry> batch = fetchBatch(last);
            if (batch.isEmpty()) break;
            var agg = processBatch(batch);
            writeAggregates(agg.getKey(), agg.getValue());
            last = batch.get(batch.size() - 1);
            progress.updateIndex(batch.size());
        }
        progress.completeIndex();
        try { indexAccess.flushAndCompact(); } catch (IndexAccessException e) { logger.warn("Flush/compact failed: {}", e.getMessage()); }
    }

    private List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        boolean first = (lastProcessedEntry == null);
        String notIn = " AND pos NOT IN (',', '.', ':', '`', '\'', '$','SYM','HYPH','NFP','AFX','LS','X','-LRB-','-RRB-', 'FW', '', '\'\'\'\'', 'DT', 'WDT', 'CC', 'PRP$', 'POS', '`', 'EX', 'UH', 'IN')";
        String sql = first
            ? ("SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos FROM annotations WHERE pos != ''" + notIn + " ORDER BY annotation_id LIMIT ?")
            : ("SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos FROM annotations WHERE annotation_id > ? AND pos != ''" + notIn + " ORDER BY annotation_id LIMIT ?");
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            if (first) stmt.setInt(1, batchSize); else { stmt.setLong(1, lastProcessedEntry.getAnnotationId()); stmt.setInt(2, batchSize); }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    batch.add(new AnnotationEntry(rs.getLong("annotation_id"), rs.getInt("document_id"), rs.getInt("sentence_id"), rs.getInt("begin_char"), rs.getInt("end_char"), rs.getString("token"), rs.getString("pos"), null, null));
                }
            }
        }
        return batch;
    }

    private java.util.AbstractMap.SimpleEntry<Map<String, RBPresenceIndex>, Map<String, Map<Integer, Map<Integer, java.util.List<Integer>>>>> processBatch(List<AnnotationEntry> batch) {
        Map<String, RBPresenceIndex> presenceByTag = new HashMap<>();
        Map<String, Map<Integer, Map<Integer, java.util.List<Integer>>>> valuesByTag = new HashMap<>();
        for (AnnotationEntry e : batch) {
            String pos = e.getPos(); String token = e.getToken();
            if (pos == null || pos.isEmpty() || token == null || token.isEmpty()) continue;
            String key = pos.toUpperCase();
            presenceByTag.computeIfAbsent(key, k -> new RBPresenceIndex()).add(e.getDocumentId(), e.getSentenceId());
            int synId;
            try { synId = synonymManager.getId(token.toLowerCase()); } catch (Exception ex) { synId = -1; }
            if (synId >= 0) {
                valuesByTag
                    .computeIfAbsent(key, k -> new HashMap<>())
                    .computeIfAbsent(e.getDocumentId(), k -> new HashMap<>())
                    .computeIfAbsent(e.getSentenceId(), k -> new java.util.ArrayList<>())
                    .add(synId);
            }
        }
        return new java.util.AbstractMap.SimpleEntry<>(presenceByTag, valuesByTag);
    }

    private void writeAggregates(Map<String, RBPresenceIndex> presenceByTag, Map<String, Map<Integer, Map<Integer, java.util.List<Integer>>>> valuesByTag) throws IOException {
        if (presenceByTag.isEmpty()) return; org.rocksdb.WriteBatch wb;
        try { wb = indexAccess.createWriteBatch(); } catch (IndexAccessException e) { throw new IOException("Failed to create write batch", e); }
        for (Map.Entry<String, RBPresenceIndex> e : presenceByTag.entrySet()) {
            String key = e.getKey(); byte[] k = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                Optional<byte[]> existing = indexAccess.getRaw(k);
                Map<Integer, Map<Integer, java.util.List<Integer>>> docSentValues = valuesByTag.getOrDefault(key, java.util.Collections.emptyMap());
                if (existing.isPresent()) {
                    RBGroupValueBlob prev = RBGroupValueBlob.fromBytes(existing.get());
                    RBPresenceIndex mergedPresence = (RBPresenceIndex) prev.getPresenceIndex().or(e.getValue());
                    Map<Integer, DocBlock> existingBlocks = prev.getDocBlocks();
                    Map<Integer, DocBlock> newBlocks = RBGroupValueBlob.buildDocBlocksFromPresenceAndValues(e.getValue(), docSentValues);
                    Map<Integer, DocBlock> mergedBlocks = mergeBlocks(existingBlocks, newBlocks);
                    RBGroupValueBlob out = new RBGroupValueBlob(mergedPresence, mergedBlocks);
                    wb.put(k, out.toBytes());
                } else {
                    Map<Integer, DocBlock> blocks = RBGroupValueBlob.buildDocBlocksFromPresenceAndValues(e.getValue(), docSentValues);
                    RBGroupValueBlob out = new RBGroupValueBlob(e.getValue(), blocks);
                    wb.put(k, out.toBytes());
                    totalTermsWrittenToIndex++;
                }
            } catch (IndexAccessException ex) { throw new IOException("Index access error while writing key: " + key, ex); }
            catch (org.rocksdb.RocksDBException ex) { throw new IOException("RocksDB error while staging write for key: " + key, ex); }
        }
        try { indexAccess.write(wb); } catch (IndexAccessException ex) { throw new IOException("Failed to write batch", ex); } finally { wb.close(); }
    }

    private Map<Integer, DocBlock> mergeBlocks(Map<Integer, DocBlock> a, Map<Integer, DocBlock> b) {
        Map<Integer, DocBlock> out = new HashMap<>();
        java.util.Set<Integer> allDocs = new java.util.HashSet<>();
        allDocs.addAll(a.keySet());
        allDocs.addAll(b.keySet());
        for (int docId : allDocs) {
            DocBlock ab = a.get(docId);
            DocBlock bb = b.get(docId);
            if (ab == null) { out.put(docId, bb); continue; }
            if (bb == null) { out.put(docId, ab); continue; }
            java.util.Map<Integer, java.util.List<Integer>> map = new java.util.HashMap<>();
            for (int i = 0; i < ab.sentIds.length; i++) {
                int sid = ab.sentIds[i];
                java.util.List<Integer> vals = map.computeIfAbsent(sid, k -> new java.util.ArrayList<>());
                vals.addAll(ab.getValuesForSentenceIndex(i));
            }
            for (int i = 0; i < bb.sentIds.length; i++) {
                int sid = bb.sentIds[i];
                java.util.List<Integer> vals = map.computeIfAbsent(sid, k -> new java.util.ArrayList<>());
                vals.addAll(bb.getValuesForSentenceIndex(i));
            }
            java.util.List<Integer> sids = new java.util.ArrayList<>(map.keySet());
            java.util.Collections.sort(sids);
            int S = sids.size();
            int[] sentIds = new int[S];
            int[] offsets = new int[S+1];
            java.util.List<Integer> values = new java.util.ArrayList<>();
            for (int i = 0; i < S; i++) {
                int sid = sids.get(i); sentIds[i] = sid; offsets[i] = values.size();
                java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>(map.get(sid));
                values.addAll(set);
            }
            offsets[S] = values.size();
            int[] valArr = values.stream().mapToInt(Integer::intValue).toArray();
            out.put(docId, new DocBlock(sentIds, offsets, valArr));
        }
        return out;
    }

    @Override public void close() throws IOException {}
}


