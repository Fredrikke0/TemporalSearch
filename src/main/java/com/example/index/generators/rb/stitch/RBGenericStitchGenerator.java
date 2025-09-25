package com.example.index.generators.rb.stitch;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.function.BiFunction;

import org.rocksdb.Options;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.RocksDBConfig;
import com.example.index.presence.RBPresenceIndex;
import com.example.index.presence.RBGroupValueBlob;
import com.example.index.presence.RBGroupValueBlob.DocBlock;

final class RBGenericStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(RBGenericStitchGenerator.class);

    private final IndexAccessInterface outIndex;
    private final String leftIndexName;   // rb_unigram | rb_bigram | rb_trigram
    private final String rightIndexName;  // rb_ner_date | rb_ner
    private final BiFunction<String,String,String> keyJoiner; // (leftKey, rightKey) -> composite

    RBGenericStitchGenerator(IndexAccessInterface outIndex,
                             String leftIndexName,
                             String rightIndexName,
                             BiFunction<String,String,String> keyJoiner) {
        this.outIndex = outIndex;
        this.leftIndexName = leftIndexName;
        this.rightIndexName = rightIndexName;
        this.keyJoiner = keyJoiner;
    }

    void build(Connection sqliteConn) throws IOException { // sqliteConn unused; retained for signature parity
        Path outDir = outIndex.getIndexPath();
        Path baseDir = outDir.getParent();
        if (baseDir == null) throw new IOException("Cannot resolve base dir for stitch build: " + outDir);

        try (Options ro = RocksDBConfig.createOptimizedOptions()) {
            ro.setCreateIfMissing(false);
            try (IndexAccessInterface left = new IndexAccess(baseDir.resolve(leftIndexName), leftIndexName, ro, true);
                 IndexAccessInterface right = new IndexAccess(baseDir.resolve(rightIndexName), rightIndexName, ro, true)) {

            org.rocksdb.WriteBatch wb = outIndex.createWriteBatch();
            int staged = 0;

            RocksIterator rightIt = right.iterateFromFirst();
            for (rightIt.seekToFirst(); rightIt.isValid(); rightIt.next()) {
                byte[] rk = rightIt.key();
                byte[] rv = rightIt.value();
                String rightKey = new String(rk, java.nio.charset.StandardCharsets.UTF_8);
                RBPresenceIndex rightIdx = RBPresenceIndex.fromBytes(rv);
                var rightDocs = rightIdx.toDocBitmap();
                if (rightDocs.isEmpty()) continue;

                RocksIterator leftIt = left.iterateFromFirst();
                for (leftIt.seekToFirst(); leftIt.isValid(); leftIt.next()) {
                    byte[] lk = leftIt.key();
                    byte[] lv = leftIt.value();
                    String leftKey = new String(lk, java.nio.charset.StandardCharsets.UTF_8);
                    RBPresenceIndex leftIdx = RBPresenceIndex.fromBytes(lv);
                    var leftDocs = leftIdx.toDocBitmap();
                    if (leftDocs.isEmpty()) continue;
                    boolean docProbe = intersects(leftDocs, rightDocs);
                    if (!docProbe) continue;

                    RBPresenceIndex inter = (RBPresenceIndex) leftIdx.and(rightIdx);
                    if (inter.toDocBitmap().isEmpty()) continue;
                    String outKeyStr = keyJoiner.apply(leftKey, rightKey);
                    byte[] outKey = outKeyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                    // Try to produce value blocks if right side has them
                    byte[] rightRaw = rightIt.value();
                    byte[] outBytes;
                    try {
                        RBGroupValueBlob rightBlob = RBGroupValueBlob.fromBytes(rightRaw);
                        // Build values for sentences present in intersection
                        java.util.Map<Integer, java.util.Map<Integer, java.util.List<Integer>>> docSentVals = new java.util.HashMap<>();
                        org.roaringbitmap.longlong.LongIterator lit = inter.getBitmap().getLongIterator();
                        while (lit.hasNext()) {
                            long pair = lit.next();
                            int docId = (int)(pair >>> 16);
                            int sentId = (int)(pair & 0xFFFFL);
                            DocBlock block = rightBlob.getDocBlocks().get(docId);
                            if (block == null) continue;
                            // find sentence index
                            int idx = java.util.Arrays.binarySearch(block.sentIds, sentId);
                            if (idx < 0) continue;
                            java.util.List<Integer> vals = block.getValuesForSentenceIndex(idx);
                            if (vals.isEmpty()) continue;
                            docSentVals
                                .computeIfAbsent(docId, k -> new java.util.HashMap<>())
                                .computeIfAbsent(sentId, k -> new java.util.ArrayList<>())
                                .addAll(vals);
                        }
                        java.util.Map<Integer, DocBlock> blocks = RBGroupValueBlob.buildDocBlocksFromPresenceAndValues(inter, docSentVals);
                        RBGroupValueBlob outBlob = new RBGroupValueBlob(inter, blocks);
                        outBytes = outBlob.toBytes();
                    } catch (Exception parseFail) {
                        // Fallback to presence-only
                        outBytes = inter.toBytes();
                    }

                    try {
                        wb.put(outKey, outBytes);
                    } catch (org.rocksdb.RocksDBException ex) {
                        throw new IOException("RocksDB error staging stitch put for key: " + outKeyStr, ex);
                    }
                    staged++;
                    if (staged % 1000 == 0) { outIndex.write(wb); wb.close(); wb = outIndex.createWriteBatch(); }
                }
                leftIt.close();
            }
            rightIt.close();
            if (staged % 1000 != 0) outIndex.write(wb);
            wb.close();
            try { outIndex.flushAndCompact(); } catch (IndexAccessException e) { logger.warn("Flush/compact failed: {}", e.getMessage()); }
        } catch (IndexAccessException e) {
            throw new IOException("RB stitch build failed: " + e.getMessage(), e);
        }
        }
    }

    // Tiny helper to avoid extra deps
    private static boolean intersects(org.roaringbitmap.RoaringBitmap a, org.roaringbitmap.RoaringBitmap b) {
        org.roaringbitmap.IntIterator it = (a.getCardinality() <= b.getCardinality()) ? a.getIntIterator() : b.getIntIterator();
        org.roaringbitmap.RoaringBitmap other = (a.getCardinality() <= b.getCardinality()) ? b : a;
        while (it.hasNext()) { if (other.contains(it.next())) return true; }
        return false;
    }
}


