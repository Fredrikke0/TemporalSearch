package com.example.index.generators.rb.stitch;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import org.rocksdb.Options;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.RocksDBConfig;
import com.example.index.presence.RBPresenceIndex;
import com.example.logging.ProgressTracker;

/**
 * Builds rb_stitch_unigram_date by intersecting rb_unigram with rb_ner_date presence sets.
 * Key format: token + DELIMITER + yyyyMMdd
 */
public final class RBUnigramDateStitchGenerator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RBUnigramDateStitchGenerator.class);
    public static final String MY_INDEX_NAME = "rb_stitch_unigram_date";

    private final IndexAccessInterface outIndex; // target rb_stitch_unigram_date
    private final Connection sqliteConn; // unused, kept for consistent signature
    private final ProgressTracker progress;
    private final int batchSize; // unused for RB-based build, reserved for future tuning

    public RBUnigramDateStitchGenerator(IndexAccessInterface outIndex,
                                        String stopwordsPath,
                                        Connection sqliteConn,
                                        ProgressTracker progress,
                                        int batchSize) {
        this.outIndex = outIndex;
        this.sqliteConn = sqliteConn;
        this.progress = progress;
        this.batchSize = batchSize;
    }

    public long getDocumentCountForIndex() throws SQLException { return 0; }
    public long getTotalTermsWrittenToIndex() { return -1; }

    public void generateIndex() throws IOException {
        Path outDir = outIndex.getIndexPath();
        Path baseDir = outDir.getParent();
        if (baseDir == null) throw new IOException("Cannot resolve base index directory from: " + outDir);

        try (Options ro = RocksDBConfig.createOptimizedOptions()) {
            ro.setCreateIfMissing(false);
            IndexAccessInterface unigram = new IndexAccess(baseDir.resolve("rb_unigram"), "rb_unigram", ro, true);
            IndexAccessInterface dates = new IndexAccess(baseDir.resolve("rb_ner_date"), "rb_ner_date", ro, true);

            int wrote = 0;
            org.rocksdb.WriteBatch wb = outIndex.createWriteBatch();
            RocksIterator dateIt = dates.iterateFromFirst();
            for (dateIt.seekToFirst(); dateIt.isValid(); dateIt.next()) {
                byte[] dateKey = dateIt.key();
                byte[] dateVal = dateIt.value();
                String dateStr = new String(dateKey, java.nio.charset.StandardCharsets.UTF_8);
                RBPresenceIndex dateIdx = RBPresenceIndex.fromBytes(dateVal);

                RocksIterator uniIt = unigram.iterateFromFirst();
                for (uniIt.seekToFirst(); uniIt.isValid(); uniIt.next()) {
                    byte[] tokenKey = uniIt.key();
                    byte[] tokenVal = uniIt.value();
                    String tokenStr = new String(tokenKey, java.nio.charset.StandardCharsets.UTF_8);

                    RBPresenceIndex tokenIdx = RBPresenceIndex.fromBytes(tokenVal);
                    RBPresenceIndex intersect = (RBPresenceIndex) tokenIdx.and(dateIdx);
                    // quick skip: if AND is empty, serialization will be small but we can check by toDocBitmap emptiness
                    if (intersect.toDocBitmap().isEmpty()) continue;

                    String outKeyStr = tokenStr + String.valueOf(com.example.core.IndexAccessInterface.DELIMITER) + dateStr;
                    byte[] outKey = outKeyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try {
                        wb.put(outKey, intersect.toBytes());
                    } catch (org.rocksdb.RocksDBException ex) {
                        throw new IOException("RocksDB error staging stitch put for key: " + outKeyStr, ex);
                    }
                    wrote++;
                    if (wrote % 1000 == 0) {
                        outIndex.write(wb);
                        wb.close();
                        wb = outIndex.createWriteBatch();
                    }
                }
                uniIt.close();
            }
            dateIt.close();
            if (wrote % 1000 != 0) {
                outIndex.write(wb);
            }
            wb.close();
            try { outIndex.flushAndCompact(); } catch (IndexAccessException e) { logger.warn("Flush/compact failed for {}: {}", MY_INDEX_NAME, e.getMessage()); }
        } catch (IndexAccessException e) {
            throw new IOException("Failed building " + MY_INDEX_NAME + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException { }
}


