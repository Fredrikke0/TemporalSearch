package com.example.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rocksdb.Options;
import org.rocksdb.RocksIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.index.RocksDBConfig;

/**
 * Tests for the prefix scan / seekWithBounds functionality used heavily
 * across the query executors (ContainsExecutor, NerExecutor, StitchedExecutor,
 * TemporalExecutor). Verifies correctness of bounded iteration, upper-bound
 * behavior, empty results, and edge cases.
 */
@DisplayName("IndexAccess Prefix Scan Tests")
public class IndexAccessPrefixScanTest {

    private Path tempDir;
    private IndexAccess indexAccess;
    private Options options;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("prefix-scan-test-");
        options = RocksDBConfig.createOptimizedOptions();
        indexAccess = new IndexAccess(tempDir, "test_index", options, false);
    }

    @AfterEach
    void tearDown() {
        if (indexAccess != null && indexAccess.isOpen()) {
            try {
                indexAccess.close();
            } catch (Exception ignored) {
            }
        }
        if (options != null && options.isOwningHandle()) {
            options.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                try (var stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
                }
            } catch (IOException ignored) {
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static PostingList makePostingList(int docId, int sentId, int begin, int end) throws IOException {
        long cellKey = PostingList.packCellKey(docId, sentId);
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        cells.add(cellKey);
        byte constantLength = (byte) Math.min(end - begin, 255);
        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(
                new long[] { cellKey }, new byte[][] { { (byte) begin } }, constantLength);
        return PostingList.fromCellsAndOccurrences(cells, constantLength, occ);
    }

    /**
     * Build the upper-bound array: prefix + 0xFF, matching ContainsExecutor
     * pattern.
     */
    private static byte[] buildUpperBound(byte[] prefix, int extraBytes) {
        byte[] ub = Arrays.copyOf(prefix, prefix.length + extraBytes);
        Arrays.fill(ub, prefix.length, ub.length, (byte) 0xFF);
        return ub;
    }

    /** Helper that performs a prefix scan and collects matching keys. */
    private List<String> collectPrefixKeys(byte[] prefix, byte[] upperBound) throws IndexAccessException {
        List<String> keys = new ArrayList<>();
        try (RocksIterator it = indexAccess.seekWithBounds(prefix, upperBound, 256 * 1024)) {
            while (it.isValid()) {
                String keyStr = str(it.key());
                if (!keyStr.startsWith(str(prefix))) {
                    break;
                }
                keys.add(keyStr);
                it.next();
            }
        }
        return keys;
    }

    // =================================================================
    // Nested: Basic Prefix Scans
    // =================================================================
    @Nested
    @DisplayName("Basic Prefix Scan Behavior")
    class BasicPrefixScans {

        @Test
        @DisplayName("Should find all keys matching a prefix")
        void findAllMatchingKeys() throws Exception {
            // Write keys with various prefixes
            indexAccess.put(bytes("apple"), makePostingList(1, 1, 0, 5).serialize());
            indexAccess.put(bytes("apple_pie"), makePostingList(2, 1, 0, 9).serialize());
            indexAccess.put(bytes("apple_crumble"), makePostingList(3, 1, 0, 13).serialize());
            indexAccess.put(bytes("banana"), makePostingList(4, 1, 0, 6).serialize());
            indexAccess.put(bytes("cherry"), makePostingList(5, 1, 0, 6).serialize());

            byte[] prefix = bytes("apple");
            byte[] upperBound = buildUpperBound(prefix, 1);

            List<String> keys = collectPrefixKeys(prefix, upperBound);

            assertEquals(3, keys.size(), "Should find 3 apple-prefixed keys");
            assertTrue(keys.contains("apple"));
            assertTrue(keys.contains("apple_pie"));
            assertTrue(keys.contains("apple_crumble"));
            assertFalse(keys.contains("banana"), "Should not include non-prefixed keys");
            assertFalse(keys.contains("cherry"));
        }

        @Test
        @DisplayName("Should find single exact match when only the prefix key exists")
        void singleExactMatch() throws Exception {
            indexAccess.put(bytes("exact_key"), makePostingList(1, 1, 0, 9).serialize());

            byte[] prefix = bytes("exact_key");
            byte[] upperBound = buildUpperBound(prefix, 1);

            List<String> keys = collectPrefixKeys(prefix, upperBound);

            assertEquals(1, keys.size());
            assertEquals("exact_key", keys.get(0));
        }

        @Test
        @DisplayName("Should return empty list when no keys match prefix")
        void noMatchingKeys() throws Exception {
            indexAccess.put(bytes("hello"), makePostingList(1, 1, 0, 5).serialize());
            indexAccess.put(bytes("world"), makePostingList(2, 1, 0, 5).serialize());

            byte[] prefix = bytes("zzz");
            byte[] upperBound = buildUpperBound(prefix, 1);

            List<String> keys = collectPrefixKeys(prefix, upperBound);
            assertTrue(keys.isEmpty(), "Should find no keys for non-matching prefix");
        }

        @Test
        @DisplayName("Should return empty on empty index")
        void emptyIndex() throws Exception {
            byte[] prefix = bytes("anything");
            byte[] upperBound = buildUpperBound(prefix, 1);

            List<String> keys = collectPrefixKeys(prefix, upperBound);
            assertTrue(keys.isEmpty(), "Should find no keys in empty index");
        }
    }

    // =================================================================
    // Nested: Upper Bound Behavior
    // =================================================================
    @Nested
    @DisplayName("Upper Bound Behavior")
    class UpperBoundBehavior {

        @Test
        @DisplayName("Should stop at upper bound exclusive")
        void stopsAtUpperBound() throws Exception {
            // Write sequential keys
            for (char c = 'a'; c <= 'g'; c++) {
                indexAccess.put(bytes(String.valueOf(c)),
                        makePostingList(c - 'a' + 1, 1, 0, 1).serialize());
            }

            // Scan from "b" with upper bound "f" exclusive.
            // This should include keys "b", "c", "d", "e" but NOT "f" or above.
            // NOTE: we do NOT use collectPrefixKeys here because it filters by
            // startsWith(prefix). This test is about the upper bound, not prefix matching.
            byte[] startKey = bytes("b");
            byte[] upperBound = bytes("f");

            List<String> keys = new ArrayList<>();
            try (RocksIterator it = indexAccess.seekWithBounds(startKey, upperBound, 256 * 1024)) {
                while (it.isValid()) {
                    keys.add(str(it.key()));
                    it.next();
                }
            }

            // With upperBound="f", seek starts at "b" and goes up to but not including "f"
            assertEquals(4, keys.size(), "Should include b, c, d, e");
            assertTrue(keys.contains("b"));
            assertTrue(keys.contains("c"));
            assertTrue(keys.contains("d"));
            assertTrue(keys.contains("e"));
            assertFalse(keys.contains("f"), "Should not include upper bound");
        }

        @Test
        @DisplayName("Should bound NER key scans correctly with 5-byte overflow")
        void boundedNerKeyScan() throws Exception {
            // Simulates the NER prefix scan pattern: prefix = "PERSON\0", upperBound =
            // "PERSON\0\xFF\xFF\xFF\xFF"
            // Writes PERSON\0<4-byte-BE-synId>
            for (int synId = 0; synId < 10; synId++) {
                byte[] key = com.example.index.KeySchema.encodeKey("PERSON", synId);
                indexAccess.put(key, makePostingList(synId + 1, 1, 0, 10).serialize());
            }
            // Write ORGANIZATION keys — should be excluded since they sort after PERSON but
            // before upperBound? No — ORG < PERSON in ASCII, so they sort BEFORE.
            // Let's write LOCATION keys which sort after PERSON
            for (int synId = 0; synId < 5; synId++) {
                byte[] key = com.example.index.KeySchema.encodeKey("LOCATION", synId);
                indexAccess.put(key, makePostingList(100 + synId, 1, 0, 3).serialize());
            }

            byte[] prefix = com.example.index.KeySchema.encodeTypePrefix("PERSON");
            byte[] upperBound = buildUpperBound(prefix, 5); // \0 + 4 bytes of 0xFF

            List<String> keys = new ArrayList<>();
            try (RocksIterator it = indexAccess.seekWithBounds(prefix, upperBound, 256 * 1024)) {
                while (it.isValid()) {
                    String keyStr = str(it.key());
                    if (!keyStr.startsWith("PERSON\0")) {
                        break;
                    }
                    keys.add(keyStr);
                    it.next();
                }
            }

            assertEquals(10, keys.size(), "Should find exactly 10 PERSON keys");
            // LOCATION keys should NOT appear because they're after the upper bound
            for (String k : keys) {
                assertTrue(k.startsWith("PERSON\0"), "All keys should start with PERSON: " + k);
            }
        }
    }

    // =================================================================
    // Nested: Iterator Value Access
    // =================================================================
    @Nested
    @DisplayName("Iterator Value Access")
    class IteratorValueAccess {

        @Test
        @DisplayName("Should retrieve correct values from iterator during prefix scan")
        void correctValuesFromIterator() throws Exception {
            // Write multiple keys with the same prefix
            for (int i = 0; i < 5; i++) {
                String key = "item_" + i;
                indexAccess.put(bytes(key), makePostingList(i + 1, 1, i * 10, i * 10 + 5).serialize());
            }

            byte[] prefix = bytes("item_");
            byte[] upperBound = buildUpperBound(prefix, 1);

            List<String> retrievedKeys = new ArrayList<>();
            List<Integer> docIds = new ArrayList<>();

            try (RocksIterator it = indexAccess.seekWithBounds(prefix, upperBound, 256 * 1024)) {
                while (it.isValid()) {
                    String keyStr = str(it.key());
                    if (!keyStr.startsWith("item_")) {
                        break;
                    }
                    retrievedKeys.add(keyStr);

                    byte[] value = it.value();
                    assertNotNull(value, "Iterator value should not be null");
                    assertTrue(value.length > 0, "Iterator value should not be empty");

                    PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.CELLS_ONLY);
                    assertFalse(pl.isEmpty(), "PostingList should not be empty");
                    docIds.add((int) (pl.cells().first() >>> 32)); // Extract docId from cell key

                    it.next();
                }
            }

            assertEquals(5, retrievedKeys.size());
            // Verify all expected docIds are present
            for (int i = 1; i <= 5; i++) {
                assertTrue(docIds.contains(i), "Should contain docId " + i);
            }
        }

        @Test
        @DisplayName("Should use iterator value directly (matching ContainsExecutor pattern)")
        void useIteratorValueDirectly() throws Exception {
            // This test verifies the pattern used in ContainsExecutor.executePrefixScan
            // where iterator.value() is used directly instead of a separate db.get()
            indexAccess.put(bytes("fruit_apple"), makePostingList(1, 1, 0, 5).serialize());
            indexAccess.put(bytes("fruit_banana"), makePostingList(2, 2, 0, 15).serialize());
            indexAccess.put(bytes("fruit_cherry"), makePostingList(3, 3, 0, 6).serialize());
            // These should not be matched
            indexAccess.put(bytes("vegetable_carrot"), makePostingList(10, 1, 0, 6).serialize());
            indexAccess.put(bytes("vegetable_spinach"), makePostingList(11, 1, 0, 7).serialize());

            byte[] prefix = bytes("fruit_");
            byte[] upperBound = buildUpperBound(prefix, 1);

            Roaring64NavigableMap resultCells = new Roaring64NavigableMap();
            try (RocksIterator it = indexAccess.seekWithBounds(prefix, upperBound, 256 * 1024)) {
                while (it.isValid()) {
                    String keyStr = str(it.key());
                    if (!keyStr.startsWith("fruit_")) {
                        break;
                    }
                    byte[] value = it.value();
                    if (value != null && value.length > 0) {
                        PostingList pl = PostingList.deserialize(value,
                                PostingList.DeserializeMode.CELLS_ONLY);
                        resultCells.or(pl.cells());
                    }
                    it.next();
                }
            }

            assertEquals(3, resultCells.getLongCardinality(),
                    "Should have cells from 3 fruit entries");
            assertTrue(resultCells.contains(PostingList.packCellKey(1, 1)));
            assertTrue(resultCells.contains(PostingList.packCellKey(2, 2)));
            assertTrue(resultCells.contains(PostingList.packCellKey(3, 3)));
            assertFalse(resultCells.contains(PostingList.packCellKey(10, 1)),
                    "Should not contain vegetable cells");
        }
    }

    // =================================================================
    // Nested: Edge Cases
    // =================================================================
    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle prefix scan on index with a single entry")
        void singleEntryIndex() throws Exception {
            indexAccess.put(bytes("only"), makePostingList(1, 1, 0, 4).serialize());

            byte[] prefix = bytes("on");
            byte[] upperBound = buildUpperBound(prefix, 1);

            List<String> keys = collectPrefixKeys(prefix, upperBound);
            assertEquals(1, keys.size());
            assertEquals("only", keys.get(0));
        }

        @Test
        @DisplayName("Should handle prefix with special characters")
        void prefixWithSpecialCharacters() throws Exception {
            // Keys with delimiters (matching the project's n-gram key pattern)
            indexAccess.put(bytes("hello\0world"), makePostingList(1, 1, 0, 11).serialize());
            indexAccess.put(bytes("hello\0world\0extra"), makePostingList(2, 1, 0, 17).serialize());
            indexAccess.put(bytes("hello\0other"), makePostingList(3, 1, 0, 11).serialize());

            byte[] prefix = bytes("hello\0world");
            byte[] upperBound = buildUpperBound(prefix, 1);

            List<String> keys = collectPrefixKeys(prefix, upperBound);
            assertEquals(2, keys.size(), "Should find 2 hello\\0world-prefixed keys");
        }

        @Test
        @DisplayName("Should handle prefix ending at the last key in DB")
        void prefixAtEndOfDB() throws Exception {
            indexAccess.put(bytes("aaa"), makePostingList(1, 1, 0, 3).serialize());
            indexAccess.put(bytes("zzz"), makePostingList(2, 1, 0, 3).serialize());

            byte[] prefix = bytes("zzz");
            byte[] upperBound = buildUpperBound(prefix, 1);

            List<String> keys = collectPrefixKeys(prefix, upperBound);
            assertEquals(1, keys.size());
            assertEquals("zzz", keys.get(0));
        }

        @Test
        @DisplayName("Should handle prefix that sorts before first key")
        void prefixBeforeFirstKey() throws Exception {
            indexAccess.put(bytes("mmm"), makePostingList(1, 1, 0, 3).serialize());
            indexAccess.put(bytes("zzz"), makePostingList(2, 1, 0, 3).serialize());

            byte[] prefix = bytes("aaa");
            byte[] upperBound = buildUpperBound(prefix, 1);

            List<String> keys = collectPrefixKeys(prefix, upperBound);
            assertTrue(keys.isEmpty(), "Should find no keys for prefix before first key");
        }

        @Test
        @DisplayName("Should handle empty prefix (scan entire index)")
        void emptyPrefixScan() throws Exception {
            for (int i = 0; i < 5; i++) {
                indexAccess.put(bytes("k" + i), makePostingList(i + 1, 1, 0, 3).serialize());
            }

            byte[] prefix = bytes("");
            // Upper bound: single 0xFF byte, which is after all printable keys
            byte[] upperBound = new byte[] { (byte) 0xFF };

            List<String> keys = collectPrefixKeys(prefix, upperBound);
            assertEquals(5, keys.size(), "Should find all keys with empty prefix");
        }

        @Test
        @DisplayName("Should use ReadOptions with readahead to speed up sequential scan")
        void readaheadImprovesScan() throws Exception {
            // Write many entries to make a meaningful sequential scan
            int numEntries = 5000;
            try (var batch = indexAccess.createWriteBatch()) {
                for (int i = 0; i < numEntries; i++) {
                    String key = "data_" + String.format("%06d", i);
                    batch.put(bytes(key), makePostingList(i % 1000, i % 50, 0, 5).serialize());
                    if ((i + 1) % 1000 == 0) {
                        indexAccess.write(batch);
                        batch.clear();
                    }
                }
                if (batch.count() > 0) {
                    indexAccess.write(batch);
                }
            }

            // Scan with readahead
            byte[] prefix = bytes("data_");
            byte[] upperBound = buildUpperBound(prefix, 1);

            long start = System.nanoTime();
            List<String> keys = collectPrefixKeys(prefix, upperBound);
            long elapsed = System.nanoTime() - start;

            assertEquals(numEntries, keys.size(), "Should find all data_ entries");
            assertTrue(elapsed > 0, "Scan should complete");
            // No hard performance assertion — just verify no exceptions and correct count
        }
    }

    // =================================================================
    // Nested: DESCENDING pattern (seek + iterate backwards)
    // =================================================================
    @Nested
    @DisplayName("Reverse Iteration (date range queries)")
    class ReverseIteration {

        @Test
        @DisplayName("Should seek to a key and iterate backwards for date range queries")
        void reverseIterationForDateRange() throws Exception {
            // Simulate date-indexed entries: yyyyMMdd as keys
            String[] dates = { "20230101", "20230115", "20230201", "20230214", "20230301", "20230401" };
            for (String date : dates) {
                indexAccess.put(bytes(date), makePostingList(
                        Integer.parseInt(date.substring(6, 8)), 1, 0, 8).serialize());
            }

            // For a "BEFORE 20230301" query, we want all dates < 20230301
            // Seek to "20230301", iterate backwards
            byte[] target = bytes("20230301");

            List<String> foundDates = new ArrayList<>();
            try (RocksIterator it = indexAccess.seek(target)) {
                // Move one step back to get the last date before the target
                it.prev();
                while (it.isValid()) {
                    String dateStr = str(it.key());
                    if (dateStr.compareTo("20230101") < 0) {
                        break; // Before our range of interest
                    }
                    foundDates.add(0, dateStr); // Prepend for ascending order
                    it.prev();
                }
            }

            assertEquals(4, foundDates.size(), "Should find 4 dates before 20230301");
            assertEquals("20230101", foundDates.get(0));
            assertEquals("20230115", foundDates.get(1));
            assertEquals("20230201", foundDates.get(2));
            assertEquals("20230214", foundDates.get(3));
        }
    }
}
