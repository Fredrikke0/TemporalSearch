package com.example.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;

import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Verifies that stitch indexes are consistent with their base indexes.
 * For each stitch key (ngram + DELIM + annotation), we compute the expected
 * multiplicity as the per-sentence product of the n-gram count and the
 * annotation count, and compare with the actual count in the stitch index.
 *
 * Usage: java com.example.tools.IndexConsistencyChecker <index_root_dir>
 * Exit code 0 on success; 1 if any inconsistencies are found or on fatal
 * errors.
 */
public final class IndexConsistencyChecker {

    private static final List<String> STITCH_INDEXES = Arrays.asList(
            "stitch_unigram_ner", "stitch_bigram_ner", "stitch_trigram_ner",
            "stitch_unigram_date", "stitch_bigram_date", "stitch_trigram_date");

    private static final String UNIGRAM = "unigram";
    private static final String BIGRAM = "bigram";
    private static final String TRIGRAM = "trigram";
    private static final String NER = "ner";
    private static final String NER_DATE = "ner_date";

    private static final char DELIM = IndexAccessInterface.DELIMITER; // '\0'
    // Reserved for potential future formatting helpers
    // private static final String DELIM_REGEX = "\\0";

    // Removed annotation counts cache; fast-path streaming minimizes repeated heavy
    // reads

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java com.example.tools.IndexConsistencyChecker <index_root_dir>");
            System.exit(1);
        }
        Path baseDir = Paths.get(args[0]);
        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            System.err.printf("Index root directory not found or not a directory: %s%n", baseDir.toAbsolutePath());
            System.exit(1);
        }

        boolean anyFailures = false;
        try {
            for (String stitchIndexName : STITCH_INDEXES) {
                Path stitchDir = baseDir.resolve(stitchIndexName);
                if (!Files.exists(stitchDir) || !Files.isDirectory(stitchDir)) {
                    // Not present in this project - skip silently
                    continue;
                }

                StitchType type = StitchType.fromIndexName(stitchIndexName);
                String ngramIndexName = type.ngramIndexName();
                String annotationIndexName = type.annotationIndexName();

                Path ngramDir = baseDir.resolve(ngramIndexName);
                Path annDir = baseDir.resolve(annotationIndexName);

                if (!Files.isDirectory(ngramDir) || !Files.isDirectory(annDir)) {
                    System.err.printf("Required base index missing for %s. Expected %s and %s%n",
                            stitchIndexName, ngramDir.toAbsolutePath(), annDir.toAbsolutePath());
                    anyFailures = true;
                    continue;
                }

                System.out.printf("Verifying stitch index: %s%n", stitchIndexName);

                try (Options opt = new Options()) {
                    opt.setCreateIfMissing(false);
                    try (RocksDB stitchDb = RocksDB.openReadOnly(opt, stitchDir.toFile().getAbsolutePath());
                            RocksDB ngramDb = RocksDB.openReadOnly(opt, ngramDir.toFile().getAbsolutePath());
                            RocksDB annDb = RocksDB.openReadOnly(opt, annDir.toFile().getAbsolutePath())) {

                        VerificationSummary summary = verifyStitchIndex(stitchDb, ngramDb, annDb, type);

                        System.out.printf("  Keys checked: %d, Failures: %d%n", summary.keysChecked,
                                summary.keysFailed);
                        if (!summary.failDetails.isEmpty()) {
                            for (int i = 0; i < Math.min(10, summary.failDetails.size()); i++) {
                                System.out.println("  " + summary.failDetails.get(i));
                            }
                        }
                        anyFailures |= (summary.keysFailed > 0);
                    } catch (RocksDBException e) {
                        System.err.printf("Error opening RocksDB for %s: %s%n", stitchIndexName, e.getMessage());
                        anyFailures = true;
                    }
                }
            }
        } catch (Exception e) {
            System.err.printf("Fatal error: %s%n", e.getMessage());
            anyFailures = true;
        }

        if (anyFailures) {
            System.exit(1);
        } else {
            System.out.println("All stitch indexes verified successfully.");
            System.exit(0);
        }
    }

    private static VerificationSummary verifyStitchIndex(RocksDB stitchDb, RocksDB ngramDb, RocksDB annDb,
            StitchType type) throws IOException {
        VerificationSummary summary = new VerificationSummary();

        try (RocksIterator it = stitchDb.newIterator()) {
            it.seekToFirst();

            Long2IntOpenHashMap smallBaseCounts = new Long2IntOpenHashMap();
            Long2IntOpenHashMap expectedCounts = new Long2IntOpenHashMap();
            while (it.isValid()) {
                String rawKey = asString(it.key());
                String logicalKey = stripSegmentSuffix(rawKey);
                summary.keysChecked++;

                // Parse logical key into ngramKey and annotation component (split on last
                // delimiter)
                int lastDelimIdx = logicalKey.lastIndexOf(DELIM);
                if (lastDelimIdx <= 0 || lastDelimIdx >= logicalKey.length() - 1) {
                    summary.keysFailed++;
                    summary.failDetails
                            .add(String.format("Key parse error (no delimiter) for stitch key '%s'", logicalKey));
                    it.next();
                    continue;
                }
                String ngramKey = logicalKey.substring(0, lastDelimIdx);
                String annotationComponent = logicalKey.substring(lastDelimIdx + 1);

                String annLookupKey = type.annotationLookupKey(annotationComponent);

                // Expected counts per (doc,sent) = product
                expectedCounts.clear();
                smallBaseCounts.clear();
                boolean ngramIsSmaller = estimateNgramIsSmaller(ngramDb, ngramKey, annDb, annLookupKey);
                // Build counts for smaller base via selective decompression of doc/sent
                if (ngramIsSmaller) {
                    buildDocSentCountsMapFast(ngramDb, ngramKey, smallBaseCounts);
                } else {
                    buildDocSentCountsMapFast(annDb, annLookupKey, smallBaseCounts);
                }
                long expectedTotal = ngramIsSmaller
                        ? accumulateExpectedFromOtherBaseFast(annDb, annLookupKey, smallBaseCounts, expectedCounts)
                        : accumulateExpectedFromOtherBaseFast(ngramDb, ngramKey, smallBaseCounts, expectedCounts);

                boolean failed = false;
                VerifyResult verify = verifyActualAgainstExpectedFast(stitchDb, logicalKey, expectedCounts);
                failed = !verify.ok;

                if (failed) {
                    summary.keysFailed++;
                    // Build precise examples using existing heavy path for this failing key
                    Map<Long, Integer> actualCountsHeavy = readCountsByDocSentAcrossSegments(stitchDb, logicalKey);
                    Map<Long, Integer> ngramCountsHeavy = readCountsByDocSentAcrossSegments(ngramDb, ngramKey);
                    Map<Long, Integer> annCountsHeavy = readCountsByDocSentAcrossSegments(annDb, annLookupKey);

                    List<String> examples = new ArrayList<>();
                    int shown = 0;
                    // Compute expected with products from heavy maps for example clarity
                    Map<Long, Integer> expectedHeavy = new HashMap<>();
                    for (Map.Entry<Long, Integer> e2 : ngramCountsHeavy.entrySet()) {
                        int c2 = annCountsHeavy.getOrDefault(e2.getKey(), 0);
                        if (e2.getValue() > 0 && c2 > 0) {
                            expectedHeavy.put(e2.getKey(), e2.getValue() * c2);
                        }
                    }
                    for (Map.Entry<Long, Integer> e2 : expectedHeavy.entrySet()) {
                        int act = actualCountsHeavy.getOrDefault(e2.getKey(), 0);
                        if (act != e2.getValue()) {
                            int doc = (int) (e2.getKey() >>> 20);
                            int sent = (int) (e2.getKey() & ((1L << 20) - 1));
                            int cn = ngramCountsHeavy.getOrDefault(e2.getKey(), 0);
                            int ca = annCountsHeavy.getOrDefault(e2.getKey(), 0);
                            examples.add(String.format("(doc:%d,sent:%d) ngram=%d ann=%d expected=%d actual=%d",
                                    doc, sent, cn, ca, e2.getValue(), act));
                            shown++;
                            if (shown >= 3)
                                break;
                        }
                    }
                    if (shown < 3) {
                        for (Map.Entry<Long, Integer> a2 : actualCountsHeavy.entrySet()) {
                            if (!expectedHeavy.containsKey(a2.getKey())) {
                                int doc = (int) (a2.getKey() >>> 20);
                                int sent = (int) (a2.getKey() & ((1L << 20) - 1));
                                int cn = ngramCountsHeavy.getOrDefault(a2.getKey(), 0);
                                int ca = annCountsHeavy.getOrDefault(a2.getKey(), 0);
                                examples.add(String.format("(doc:%d,sent:%d) ngram=%d ann=%d expected=%d actual=%d",
                                        doc, sent, cn, ca, 0, a2.getValue()));
                                shown++;
                                if (shown >= 3)
                                    break;
                            }
                        }
                    }

                    summary.failDetails.add(String.format(
                            "Key '%s' FAILED: expected_total=%d actual_total=%d; examples: %s",
                            logicalKey, expectedTotal, verify.actualTotal, examples));
                }

                // advance iterator to next logical key (skip segments for this key)
                seekPastLogicalKey(it, logicalKey);

                // Progress output every 100000 keys
                if (summary.keysChecked % 100000 == 0) {
                    System.out.printf("    Processed %d keys...%n", summary.keysChecked);
                }
            }
        }

        return summary;
    }

    // Removed unused countLogicalKeys after on-the-fly progress logging change

    private static void seekPastLogicalKey(RocksIterator it, String logicalKey) {
        // Assumes iterator currently at first-or-middle of this logical key group
        String prefixWithHash = logicalKey + "#";
        while (it.isValid()) {
            String k = asString(it.key());
            if (k.equals(logicalKey) || k.startsWith(prefixWithHash)) {
                it.next();
            } else {
                break;
            }
        }
    }

    // Convenience overload used in heavy fallback path
    private static Map<Long, Integer> readCountsByDocSentAcrossSegments(RocksDB db, String logicalKey)
            throws IOException {
        Map<Long, Integer> counts = new HashMap<>();
        readCountsByDocSentAcrossSegments(db, logicalKey, counts);
        return counts;
    }

    private static long readCountsByDocSentAcrossSegments(RocksDB db, String logicalKey, Map<Long, Integer> outCounts)
            throws IOException {
        outCounts.clear();
        long total = 0L;
        String prefixWithHash = logicalKey + "#";
        try (ReadOptions ro = new ReadOptions(); Slice ub = new Slice(bytes(logicalKey + "$"))) {
            ro.setIterateUpperBound(ub);
            try (RocksIterator it = db.newIterator(ro)) {
                it.seek(bytes(logicalKey));
                while (it.isValid()) {
                    String k = asString(it.key());
                    if (!(k.equals(logicalKey) || k.startsWith(prefixWithHash))) {
                        break;
                    }
                    byte[] value = it.value();
                    if (value != null && value.length > 0) {
                        try {
                            PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.CELLS_ONLY);
                            int numCells = (int) pl.cells().getLongCardinality();
                            var iter = pl.cells().getLongIterator();
                            while (iter.hasNext()) {
                                long cellKey = iter.next();
                                int docId = PostingList.docIdFromCellKey(cellKey);
                                int sentId = PostingList.sentIdFromCellKey(cellKey);
                                long docSent = packDocSent(docId, sentId);
                                outCounts.merge(docSent, 1, Integer::sum);
                            }
                            total += numCells;
                        } catch (IOException e) {
                            // skip malformed entry
                        }
                    }
                    it.next();
                }
            }
        }
        return total;
    }

    // Fast path helpers: header-only totals and selective doc/sent aggregation
    private static long readTotalPositionsAcrossSegmentsFast(RocksDB db, String logicalKey) throws IOException {
        long total = 0L;
        String prefixWithHash = logicalKey + "#";
        try (ReadOptions ro = new ReadOptions(); Slice ub = new Slice(bytes(logicalKey + "$"))) {
            ro.setIterateUpperBound(ub);
            try (RocksIterator it = db.newIterator(ro)) {
                it.seek(bytes(logicalKey));
                while (it.isValid()) {
                    String k = asString(it.key());
                    if (!(k.equals(logicalKey) || k.startsWith(prefixWithHash))) {
                        break;
                    }
                    byte[] value = it.value();
                    if (value != null && value.length > 0) {
                        try {
                            PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.CELLS_ONLY);
                            total += pl.cells().getLongCardinality();
                        } catch (IOException e) {
                            // skip malformed entry
                        }
                    }
                    it.next();
                }
            }
        }
        return total;
    }

    // Estimate smaller base with a capped sample to avoid full scans on huge keys
    private static boolean estimateNgramIsSmaller(RocksDB ngramDb, String ngramKey, RocksDB annDb, String annKey)
            throws IOException {
        long ngramEstimate = estimateTotalPositionsWithCap(ngramDb, ngramKey, 1_000_000);
        long annEstimate = estimateTotalPositionsWithCap(annDb, annKey, 1_000_000);
        return ngramEstimate <= annEstimate;
    }

    private static long estimateTotalPositionsWithCap(RocksDB db, String logicalKey, int cap) throws IOException {
        long total = 0L;
        int remaining = cap;
        String prefixWithHash = logicalKey + "#";
        try (ReadOptions ro = new ReadOptions(); Slice ub = new Slice(bytes(logicalKey + "$"))) {
            ro.setIterateUpperBound(ub);
            try (RocksIterator it = db.newIterator(ro)) {
                it.seek(bytes(logicalKey));
                while (it.isValid() && remaining > 0) {
                    String k = asString(it.key());
                    if (!(k.equals(logicalKey) || k.startsWith(prefixWithHash)))
                        break;
                    byte[] value = it.value();
                    if (value != null && value.length > 0) {
                        try {
                            PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.CELLS_ONLY);
                            int num = (int) pl.cells().getLongCardinality();
                            if (num <= remaining) {
                                total += num;
                                remaining -= num;
                            } else {
                                total += remaining;
                                remaining = 0;
                            }
                        } catch (IOException e) {
                            // skip malformed entry
                        }
                    }
                    it.next();
                }
            }
        }
        return total;
    }

    private static void buildDocSentCountsMapFast(RocksDB db, String logicalKey, Long2IntOpenHashMap out)
            throws IOException {
        out.clear();
        String prefixWithHash = logicalKey + "#";
        try (ReadOptions ro = new ReadOptions(); Slice ub = new Slice(bytes(logicalKey + "$"))) {
            ro.setIterateUpperBound(ub);
            try (RocksIterator it = db.newIterator(ro)) {
                it.seek(bytes(logicalKey));
                while (it.isValid()) {
                    String k = asString(it.key());
                    if (!(k.equals(logicalKey) || k.startsWith(prefixWithHash))) {
                        break;
                    }
                    byte[] value = it.value();
                    if (value != null && value.length > 0) {
                        try {
                            PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.CELLS_ONLY);
                            int num = (int) pl.cells().getLongCardinality();
                            if (num > 0) {
                                var iter = pl.cells().getLongIterator();
                                while (iter.hasNext()) {
                                    long cellKey = iter.next();
                                    int docId = PostingList.docIdFromCellKey(cellKey);
                                    int sentId = PostingList.sentIdFromCellKey(cellKey);
                                    long docSent = packDocSent(docId, sentId);
                                    out.addTo(docSent, 1);
                                }
                            }
                        } catch (IOException e) {
                            // skip malformed entry
                        }
                    }
                    it.next();
                }
            }
        }
    }

    private static long accumulateExpectedFromOtherBaseFast(RocksDB db, String logicalKey,
            Long2IntOpenHashMap smallBase, Long2IntOpenHashMap outExpected) throws IOException {
        outExpected.clear();
        long expectedTotal = 0L;
        String prefixWithHash = logicalKey + "#";
        try (ReadOptions ro = new ReadOptions(); Slice ub = new Slice(bytes(logicalKey + "$"))) {
            ro.setIterateUpperBound(ub);
            try (RocksIterator it = db.newIterator(ro)) {
                it.seek(bytes(logicalKey));
                while (it.isValid()) {
                    String k = asString(it.key());
                    if (!(k.equals(logicalKey) || k.startsWith(prefixWithHash))) {
                        break;
                    }
                    byte[] value = it.value();
                    if (value != null && value.length > 0) {
                        try {
                            PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.CELLS_ONLY);
                            int num = (int) pl.cells().getLongCardinality();
                            if (num > 0) {
                                var iter = pl.cells().getLongIterator();
                                while (iter.hasNext()) {
                                    long cellKey = iter.next();
                                    int docId = PostingList.docIdFromCellKey(cellKey);
                                    int sentId = PostingList.sentIdFromCellKey(cellKey);
                                    long docSent = packDocSent(docId, sentId);
                                    int c1 = smallBase.getOrDefault(docSent, 0);
                                    if (c1 > 0) {
                                        outExpected.addTo(docSent, c1);
                                        expectedTotal += c1;
                                    }
                                }
                            }
                        } catch (IOException e) {
                            // skip malformed entry
                        }
                    }
                    it.next();
                }
            }
        }
        return expectedTotal;
    }

    private static VerifyResult verifyActualAgainstExpectedFast(RocksDB stitchDb, String logicalKey,
            Long2IntOpenHashMap expected) throws IOException {
        String prefixWithHash = logicalKey + "#";
        long actualTotal = 0L;
        try (ReadOptions ro = new ReadOptions(); Slice ub = new Slice(bytes(logicalKey + "$"))) {
            ro.setIterateUpperBound(ub);
            try (RocksIterator it = stitchDb.newIterator(ro)) {
                it.seek(bytes(logicalKey));
                while (it.isValid()) {
                    String k = asString(it.key());
                    if (!(k.equals(logicalKey) || k.startsWith(prefixWithHash))) {
                        break;
                    }
                    byte[] value = it.value();
                    if (value != null && value.length > 0) {
                        PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.CELLS_ONLY);
                        int num = (int) pl.cells().getLongCardinality();
                        actualTotal += num;
                        if (num > 0) {
                            var iter = pl.cells().getLongIterator();
                            while (iter.hasNext()) {
                                long cellKey = iter.next();
                                int docId = PostingList.docIdFromCellKey(cellKey);
                                int sentId = PostingList.sentIdFromCellKey(cellKey);
                                long docSent = packDocSent(docId, sentId);
                                int remaining = expected.addTo(docSent, -1);
                                // addTo returns the old value; after decrement, value becomes old-1
                                if (remaining <= 0) {
                                    // if old value was 0, now -1 -> mismatch; if old was 1, now 0 ok; if >1, still
                                    // >0
                                    if (remaining == 0) {
                                        // exactly consumed
                                    } else if (remaining < 0) {
                                        return new VerifyResult(false, actualTotal); // actual has extra occurrence
                                    }
                                }
                            }
                        }
                    }
                    it.next();
                }
            }
        }
        // Ensure no leftovers in expected
        for (Long2IntOpenHashMap.Entry e : expected.long2IntEntrySet()) {
            if (e.getIntValue() != 0)
                return new VerifyResult(false, actualTotal);
        }
        return new VerifyResult(true, actualTotal);
    }

    private static final class VerifyResult {
        final boolean ok;
        final long actualTotal;

        VerifyResult(boolean ok, long actualTotal) {
            this.ok = ok;
            this.actualTotal = actualTotal;
        }
    }

    private static String stripSegmentSuffix(String key) {
        int hashIdx = key.indexOf('#');
        return (hashIdx >= 0) ? key.substring(0, hashIdx) : key;
    }

    // sumCounts unused after accumulating totals on insert

    private static long packDocSent(int docId, int sentId) {
        // Use upper bits for docId, lower 20 bits for sentId
        return ((long) docId << 20) | (sentId & ((1 << 20) - 1));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String asString(byte[] b) {
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class VerificationSummary {
        long keysChecked = 0;
        long keysFailed = 0;
        List<String> failDetails = new ArrayList<>();
    }

    private enum StitchType {
        UNIGRAM_NER(UNIGRAM, NER, true),
        BIGRAM_NER(BIGRAM, NER, true),
        TRIGRAM_NER(TRIGRAM, NER, true),
        UNIGRAM_DATE(UNIGRAM, NER_DATE, false),
        BIGRAM_DATE(BIGRAM, NER_DATE, false),
        TRIGRAM_DATE(TRIGRAM, NER_DATE, false);

        private final String ngramIndex;
        private final String annotationIndex;
        @SuppressWarnings("unused")
        private final boolean isNer; // reserved for potential future type-specific logic

        StitchType(String ngramIndex, String annotationIndex, boolean isNer) {
            this.ngramIndex = ngramIndex;
            this.annotationIndex = annotationIndex;
            this.isNer = isNer;
        }

        String ngramIndexName() {
            return ngramIndex;
        }

        String annotationIndexName() {
            return annotationIndex;
        }
        // boolean isNer() { return isNer; } // not used currently

        String annotationLookupKey(String annotationComponent) {
            // For NER, keys are TYPE (already uppercase) as in NerIndexGenerator
            // For DATE, keys are normalized yyyyMMdd strings as in NerDateIndexGenerator
            return annotationComponent;
        }

        static StitchType fromIndexName(String name) {
            return switch (name) {
                case "stitch_unigram_ner" -> UNIGRAM_NER;
                case "stitch_bigram_ner" -> BIGRAM_NER;
                case "stitch_trigram_ner" -> TRIGRAM_NER;
                case "stitch_unigram_date" -> UNIGRAM_DATE;
                case "stitch_bigram_date" -> BIGRAM_DATE;
                case "stitch_trigram_date" -> TRIGRAM_DATE;
                default -> throw new IllegalArgumentException("Unknown stitch index name: " + name);
            };
        }
    }
}
