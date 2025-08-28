package com.example.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;

/**
 * Verifies that stitch indexes are consistent with their base indexes.
 * For each stitch key (ngram + DELIM + annotation), we compute the expected
 * multiplicity as the per-sentence product of the n-gram count and the
 * annotation count, and compare with the actual count in the stitch index.
 *
 * Usage: java com.example.tools.IndexConsistencyChecker <index_root_dir>
 * Exit code 0 on success; 1 if any inconsistencies are found or on fatal errors.
 */
public final class IndexConsistencyChecker {

    private static final List<String> STITCH_INDEXES = Arrays.asList(
        "stitch_unigram_ner", "stitch_bigram_ner", "stitch_trigram_ner",
        "stitch_unigram_date", "stitch_bigram_date", "stitch_trigram_date"
    );

    private static final String UNIGRAM = "unigram";
    private static final String BIGRAM = "bigram";
    private static final String TRIGRAM = "trigram";
    private static final String NER = "ner";
    private static final String NER_DATE = "ner_date";

    private static final char DELIM = IndexAccessInterface.DELIMITER; // '\0'
    // Reserved for potential future formatting helpers
    // private static final String DELIM_REGEX = "\\0";

    // Small LRU cache to avoid re-reading identical annotation components across many n-grams
    private static final int ANNOTATION_CACHE_CAPACITY = 256;
    private static final Map<String, Map<Long, Integer>> ANN_COUNTS_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<Long, Integer>> eldest) {
            return size() > ANNOTATION_CACHE_CAPACITY;
        }
    };

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

                        System.out.printf("  Keys checked: %d, Failures: %d%n", summary.keysChecked, summary.keysFailed);
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

    private static VerificationSummary verifyStitchIndex(RocksDB stitchDb, RocksDB ngramDb, RocksDB annDb, StitchType type) throws IOException {
        VerificationSummary summary = new VerificationSummary();

        try (RocksIterator it = stitchDb.newIterator()) {
            it.seekToFirst();

            Map<Long, Integer> actualCounts = new HashMap<>();
            Map<Long, Integer> ngramCounts = new HashMap<>();
            Map<Long, Integer> expectedCounts = new HashMap<>();
            while (it.isValid()) {
                String rawKey = asString(it.key());
                String logicalKey = stripSegmentSuffix(rawKey);
                summary.keysChecked++;

                // Aggregate actual counts from all segments for this logical stitch key
                long actualTotal = readCountsByDocSentAcrossSegments(stitchDb, logicalKey, actualCounts);

                // Parse logical key into ngramKey and annotation component (split on last delimiter)
                int lastDelimIdx = logicalKey.lastIndexOf(DELIM);
                if (lastDelimIdx <= 0 || lastDelimIdx >= logicalKey.length() - 1) {
                    summary.keysFailed++;
                    summary.failDetails.add(String.format("Key parse error (no delimiter) for stitch key '%s'", logicalKey));
                    it.next();
                    continue;
                }
                String ngramKey = logicalKey.substring(0, lastDelimIdx);
                String annotationComponent = logicalKey.substring(lastDelimIdx + 1);

                // Base counts
                readCountsByDocSentAcrossSegments(ngramDb, ngramKey, ngramCounts);

                String annLookupKey = type.annotationLookupKey(annotationComponent);
                String annCacheKey = type.annotationIndexName() + "|" + annLookupKey;
                Map<Long, Integer> annCounts = ANN_COUNTS_CACHE.get(annCacheKey);
                if (annCounts == null) {
                    Map<Long, Integer> tmpAnn = new HashMap<>();
                    readCountsByDocSentAcrossSegments(annDb, annLookupKey, tmpAnn);
                    annCounts = Map.copyOf(tmpAnn); // store an unmodifiable snapshot in cache
                    ANN_COUNTS_CACHE.put(annCacheKey, annCounts);
                }

                // Expected counts per (doc,sent) = product
                expectedCounts.clear();
                long expectedTotal = 0L;

                // Iterate over smaller of the two base maps to compute products
                Map<Long, Integer> smaller = (ngramCounts.size() <= annCounts.size()) ? ngramCounts : annCounts;
                Map<Long, Integer> larger = (smaller == ngramCounts) ? annCounts : ngramCounts;
                for (Map.Entry<Long, Integer> e : smaller.entrySet()) {
                    long docSent = e.getKey();
                    int c1 = e.getValue();
                    int c2 = larger.getOrDefault(docSent, 0);
                    if (c1 > 0 && c2 > 0) {
                        int prod = c1 * c2;
                        expectedCounts.put(docSent, prod);
                        expectedTotal += prod;
                    }
                }

                boolean failed = false;
                if (expectedTotal != actualTotal) {
                    failed = true;
                } else {
                    // Compare per (doc,sent): actual must equal expected; also ensure no ghosts
                    // actual docSent set must be subset of expected docSent set
                    for (Map.Entry<Long, Integer> a : actualCounts.entrySet()) {
                        int exp = expectedCounts.getOrDefault(a.getKey(), 0);
                        if (a.getValue() != exp) {
                            failed = true;
                            break;
                        }
                    }
                    // Also ensure no missing actual where product > 0
                    if (!failed) {
                        for (Map.Entry<Long, Integer> e : expectedCounts.entrySet()) {
                            int act = actualCounts.getOrDefault(e.getKey(), 0);
                            if (act != e.getValue()) {
                                failed = true;
                                break;
                            }
                        }
                    }
                }

                if (failed) {
                    summary.keysFailed++;
                    List<String> examples = new ArrayList<>();
                    int shown = 0;
                    // Gather up to 3 example mismatches
                    for (Map.Entry<Long, Integer> e : expectedCounts.entrySet()) {
                        int act = actualCounts.getOrDefault(e.getKey(), 0);
                        if (act != e.getValue()) {
                            int doc = (int) (e.getKey() >>> 20);
                            int sent = (int) (e.getKey() & ((1L << 20) - 1));
                            int cn = ngramCounts.getOrDefault(e.getKey(), 0);
                            int ca = annCounts.getOrDefault(e.getKey(), 0);
                            examples.add(String.format("(doc:%d,sent:%d) ngram=%d ann=%d expected=%d actual=%d",
                                    doc, sent, cn, ca, e.getValue(), act));
                            shown++;
                            if (shown >= 3) break;
                        }
                    }
                    if (examples.isEmpty()) {
                        // Maybe ghost entries in actual only
                        for (Map.Entry<Long, Integer> a : actualCounts.entrySet()) {
                            if (!expectedCounts.containsKey(a.getKey())) {
                                int doc = (int) (a.getKey() >>> 20);
                                int sent = (int) (a.getKey() & ((1L << 20) - 1));
                                int cn = ngramCounts.getOrDefault(a.getKey(), 0);
                                int ca = annCounts.getOrDefault(a.getKey(), 0);
                                examples.add(String.format("(doc:%d,sent:%d) ngram=%d ann=%d expected=%d actual=%d",
                                        doc, sent, cn, ca, 0, a.getValue()));
                                shown++;
                                if (shown >= 3) break;
                            }
                        }
                    }

                    summary.failDetails.add(String.format(
                        "Key '%s' FAILED: expected_total=%d actual_total=%d; examples: %s",
                        logicalKey, expectedTotal, actualTotal, examples));
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

    // Removed unused overload returning a new map to reduce API surface

    private static long readCountsByDocSentAcrossSegments(RocksDB db, String logicalKey, Map<Long, Integer> outCounts) throws IOException {
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
                        PositionListSoA soa = PositionListSoA.deserializeFromCompositeBlob(value);
                        for (int i = 0; i < soa.getNumPositions(); i++) {
                            Position p = soa.getPositionAt(i);
                            long docSent = packDocSent(p.getDocumentId(), p.getSentenceId());
                            outCounts.merge(docSent, 1, Integer::sum);
                            total++;
                        }
                    }
                    it.next();
                }
            }
        }
        return total;
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

        String ngramIndexName() { return ngramIndex; }
        String annotationIndexName() { return annotationIndex; }
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


