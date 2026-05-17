package com.example.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rocksdb.Options;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.index.RocksDBConfig;

/**
 * Tests verifying data integrity for RocksDB-backed IndexAccess:
 * - PostingList serialization round-trips through RocksDB
 * - Data survives close/reopen cycles
 * - Large values and many keys work correctly
 * - Edge cases (empty PostingList, null key, etc.)
 * - WriteBatch atomicity
 */
@DisplayName("IndexAccess Data Integrity Tests")
public class IndexAccessDataIntegrityTest {

    private Path tempDir;
    private IndexAccess indexAccess;
    private Options options;
    private static final Random random = new Random(42);

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("data-integrity-test-");
        options = RocksDBConfig.createOptimizedOptions();
        indexAccess = new IndexAccess(tempDir, "integrity_test", options, false);
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
     * Build a PostingList with multiple cells and occurrence data.
     */
    private static PostingList makeMultiCellPostingList(int numCells) throws IOException {
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        long[] cellKeys = new long[numCells];
        byte[][] begins = new byte[numCells][];

        for (int i = 0; i < numCells; i++) {
            int docId = i / 10 + 1;
            int sentId = i % 10;
            long ck = PostingList.packCellKey(docId, sentId);
            cells.add(ck);
            cellKeys[i] = ck;
            begins[i] = new byte[] { (byte) (i * 3) }; // Varying begin positions
        }

        byte constantLength = 5;
        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(cellKeys, begins, constantLength);
        return PostingList.fromCellsAndOccurrences(cells, constantLength, occ);
    }

    private void closeAndReopen() throws Exception {
        if (indexAccess != null && indexAccess.isOpen()) {
            indexAccess.close();
        }
        if (options != null && options.isOwningHandle()) {
            options.close();
        }
        options = RocksDBConfig.createOptimizedOptions();
        options.setCreateIfMissing(false);
        indexAccess = new IndexAccess(tempDir, "integrity_test", options, false);
    }

    // =================================================================
    // Nested: Round-Trip Serialization
    // =================================================================
    @Nested
    @DisplayName("Round-Trip Serialization")
    class RoundTripTests {

        @Test
        @DisplayName("Should round-trip a simple PostingList through RocksDB")
        void simpleRoundTrip() throws Exception {
            PostingList original = makePostingList(42, 7, 0, 11);
            byte[] key = bytes("roundtrip_simple");

            indexAccess.put(key, original.serialize());

            PostingList retrieved = indexAccess.get(key).orElseThrow();
            assertEquals(1, retrieved.cells().getLongCardinality(),
                    "Should have 1 cell after round-trip");
            assertTrue(retrieved.cells().contains(PostingList.packCellKey(42, 7)),
                    "Should contain the original cell");
            assertEquals(11, retrieved.constantLength(),
                    "Should preserve constant length");
        }

        @Test
        @DisplayName("Should round-trip a multi-cell PostingList through RocksDB")
        void multiCellRoundTrip() throws Exception {
            PostingList original = makeMultiCellPostingList(50);
            byte[] key = bytes("roundtrip_multi");

            indexAccess.put(key, original.serialize());

            PostingList retrieved = indexAccess.get(key).orElseThrow();
            assertEquals(50, retrieved.cells().getLongCardinality(),
                    "Should have 50 cells after round-trip");

            // Verify all original cells are present
            var iter = original.cells().getLongIterator();
            while (iter.hasNext()) {
                assertTrue(retrieved.cells().contains(iter.next()),
                        "Retrieved should contain all original cells");
            }
        }

        @Test
        @DisplayName("Should round-trip an empty PostingList through RocksDB")
        void emptyPostingListRoundTrip() throws Exception {
            PostingList empty = PostingList.empty((byte) 3);
            byte[] key = bytes("roundtrip_empty");

            indexAccess.put(key, empty.serialize());

            PostingList retrieved = indexAccess.get(key).orElseThrow();
            assertTrue(retrieved.isEmpty(), "Retrieved empty PostingList should be empty");
            assertEquals(3, retrieved.constantLength(), "Should preserve constant length");
        }

        @ParameterizedTest
        @ValueSource(ints = { 1, 5, 10, 50, 100 })
        @DisplayName("Should round-trip PostingLists of various sizes")
        void variousSizes(int numCells) throws Exception {
            PostingList original = makeMultiCellPostingList(numCells);
            byte[] key = bytes("size_" + numCells);

            indexAccess.put(key, original.serialize());

            PostingList retrieved = indexAccess.get(key).orElseThrow();
            assertEquals(numCells, retrieved.cells().getLongCardinality(),
                    "Should preserve cell count for size " + numCells);
        }
    }

    // =================================================================
    // Nested: Close/Reopen Persistence
    // =================================================================
    @Nested
    @DisplayName("Close/Reopen Persistence")
    class PersistenceTests {

        @Test
        @DisplayName("Should persist a single entry across close/reopen")
        void persistSingleEntry() throws Exception {
            indexAccess.put(bytes("persist_key"), makePostingList(1, 2, 0, 8).serialize());

            closeAndReopen();

            Optional<PostingList> retrieved = indexAccess.get(bytes("persist_key"));
            assertTrue(retrieved.isPresent(), "Entry should survive close/reopen");
            assertTrue(retrieved.get().cells().contains(PostingList.packCellKey(1, 2)),
                    "Should contain correct cell after reopen");
        }

        @Test
        @DisplayName("Should persist multiple entries across close/reopen")
        void persistMultipleEntries() throws Exception {
            int numEntries = 100;
            for (int i = 0; i < numEntries; i++) {
                indexAccess.put(bytes("pk_" + i), makePostingList(i, i % 10, 0, 5).serialize());
            }

            closeAndReopen();

            for (int i = 0; i < numEntries; i++) {
                Optional<PostingList> retrieved = indexAccess.get(bytes("pk_" + i));
                assertTrue(retrieved.isPresent(), "Entry pk_" + i + " should survive");
                assertTrue(retrieved.get().cells().contains(PostingList.packCellKey(i, i % 10)),
                        "Entry pk_" + i + " should have correct cell");
            }
        }

        @Test
        @DisplayName("Should persist data written via WriteBatch across close/reopen")
        void persistWriteBatch() throws Exception {
            try (var batch = indexAccess.createWriteBatch()) {
                for (int i = 0; i < 50; i++) {
                    batch.put(bytes("batch_" + i), makePostingList(i + 100, 1, 0, i + 5).serialize());
                }
                indexAccess.write(batch);
            }

            closeAndReopen();

            for (int i = 0; i < 50; i++) {
                Optional<PostingList> retrieved = indexAccess.get(bytes("batch_" + i));
                assertTrue(retrieved.isPresent(), "Batch entry batch_" + i + " should persist");
            }
        }

        @Test
        @DisplayName("Should preserve deleted state across close/reopen")
        void persistDeletion() throws Exception {
            // Write then delete
            byte[] key = bytes("to_be_deleted");
            indexAccess.put(key, makePostingList(1, 1, 0, 5).serialize());
            indexAccess.delete(key);

            closeAndReopen();

            Optional<PostingList> retrieved = indexAccess.get(key);
            assertTrue(retrieved.isEmpty(), "Deleted entry should not appear after reopen");
        }

        @Test
        @DisplayName("Should handle overwriting a key across close/reopen")
        void persistOverwrite() throws Exception {
            byte[] key = bytes("overwrite_me");

            // Write v1
            indexAccess.put(key, makePostingList(1, 1, 0, 5).serialize());

            // Overwrite with v2
            PostingList v2 = makePostingList(99, 99, 0, 10);
            indexAccess.put(key, v2.serialize());

            closeAndReopen();

            PostingList retrieved = indexAccess.get(key).orElseThrow();
            assertTrue(retrieved.cells().contains(PostingList.packCellKey(99, 99)),
                    "Should contain v2 cell after reopen");
            assertFalse(retrieved.cells().contains(PostingList.packCellKey(1, 1)),
                    "Should NOT contain v1 cell after overwrite + reopen");
        }
    }

    // =================================================================
    // Nested: WriteBatch Atomicity
    // =================================================================
    @Nested
    @DisplayName("WriteBatch Atomicity")
    class WriteBatchAtomicityTests {

        @Test
        @DisplayName("Should write all entries in a batch atomically")
        void batchAllOrNothing() throws Exception {
            try (var batch = indexAccess.createWriteBatch()) {
                batch.put(bytes("atomic_1"), makePostingList(1, 1, 0, 5).serialize());
                batch.put(bytes("atomic_2"), makePostingList(2, 1, 0, 5).serialize());
                batch.put(bytes("atomic_3"), makePostingList(3, 1, 0, 5).serialize());
                indexAccess.write(batch);
            }

            assertTrue(indexAccess.get(bytes("atomic_1")).isPresent());
            assertTrue(indexAccess.get(bytes("atomic_2")).isPresent());
            assertTrue(indexAccess.get(bytes("atomic_3")).isPresent());
        }

        @Test
        @DisplayName("Should handle empty WriteBatch")
        void emptyBatch() throws Exception {
            try (var batch = indexAccess.createWriteBatch()) {
                // Write nothing
                indexAccess.write(batch);
            }
            // Should not throw — just verify we're still operational
            indexAccess.put(bytes("after_empty"), makePostingList(1, 1, 0, 5).serialize());
            assertTrue(indexAccess.get(bytes("after_empty")).isPresent());
        }

        @Test
        @DisplayName("Should handle mixed put and delete in same batch")
        void mixedPutAndDelete() throws Exception {
            // Pre-populate
            byte[] keepKey = bytes("keep_me");
            byte[] deleteKey = bytes("delete_me");
            indexAccess.put(keepKey, makePostingList(1, 1, 0, 5).serialize());
            indexAccess.put(deleteKey, makePostingList(2, 1, 0, 5).serialize());

            // Atomic: delete one, put new
            try (var batch = indexAccess.createWriteBatch()) {
                batch.delete(deleteKey);
                batch.put(bytes("new_one"), makePostingList(3, 1, 0, 5).serialize());
                indexAccess.write(batch);
            }

            assertTrue(indexAccess.get(keepKey).isPresent(), "keep_me should still exist");
            assertTrue(indexAccess.get(deleteKey).isEmpty(), "delete_me should be gone");
            assertTrue(indexAccess.get(bytes("new_one")).isPresent(), "new_one should exist");
        }
    }

    // =================================================================
    // Nested: Large Data
    // =================================================================
    @Nested
    @DisplayName("Large Data Handling")
    class LargeDataTests {

        @Test
        @DisplayName("Should handle 10K entries with correct retrieval")
        void tenThousandEntries() throws Exception {
            int numEntries = 10_000;
            try (var batch = indexAccess.createWriteBatch()) {
                for (int i = 0; i < numEntries; i++) {
                    String key = String.format("large_%05d", i);
                    batch.put(bytes(key), makePostingList(i % 5000, i % 100, 0, 5).serialize());
                    if ((i + 1) % 1000 == 0) {
                        indexAccess.write(batch);
                        batch.clear();
                    }
                }
                if (batch.count() > 0) {
                    indexAccess.write(batch);
                }
            }

            // Spot-check various entries
            for (int i : new int[] { 0, 1, 999, 5000, 9999 }) {
                String key = String.format("large_%05d", i);
                Optional<PostingList> pl = indexAccess.get(bytes(key));
                assertTrue(pl.isPresent(), "Entry " + key + " should exist");
                assertEquals(1, pl.get().cells().getLongCardinality(),
                        "Entry " + key + " should have 1 cell");
            }

            // Full scan to verify count
            int count = 0;
            try (var it = indexAccess.iterateFromFirst()) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    count++;
                    // Quick sanity — every value should be deserializable
                    assertDoesNotThrow(() -> PostingList.deserialize(it.value(),
                            PostingList.DeserializeMode.CELLS_ONLY));
                }
            }
            assertEquals(numEntries, count, "Full scan should count " + numEntries + " entries");
        }

        @Test
        @DisplayName("Should handle large PostingList values (many cells)")
        void largePostingListValue() throws Exception {
            PostingList large = makeMultiCellPostingList(500);
            byte[] key = bytes("large_value");

            indexAccess.put(key, large.serialize());

            PostingList retrieved = indexAccess.get(key).orElseThrow();
            assertEquals(500, retrieved.cells().getLongCardinality(),
                    "Should preserve 500 cells in large PostingList");
        }
    }

    // =================================================================
    // Nested: Edge Cases
    // =================================================================
    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should return empty Optional for non-existent key")
        void nonExistentKey() throws Exception {
            Optional<PostingList> result = indexAccess.get(bytes("no_such_key"));
            assertTrue(result.isEmpty(), "Non-existent key should return empty Optional");
        }

        @Test
        @DisplayName("Should return empty Optional for non-existent raw key")
        void nonExistentRawKey() throws Exception {
            Optional<byte[]> result = indexAccess.getRaw(bytes("no_such_raw_key"));
            assertTrue(result.isEmpty(), "Non-existent raw key should return empty Optional");
        }

        @Test
        @DisplayName("Should handle delete of non-existent key without error")
        void deleteNonExistentKey() throws Exception {
            assertDoesNotThrow(() -> indexAccess.delete(bytes("no_such_key_for_delete")),
                    "Deleting non-existent key should not throw");
        }

        @Test
        @DisplayName("Should handle 0-byte keys")
        void emptyByteKey() throws Exception {
            byte[] emptyKey = new byte[0];
            indexAccess.put(emptyKey, makePostingList(1, 1, 0, 5).serialize());

            PostingList retrieved = indexAccess.get(emptyKey).orElseThrow();
            assertTrue(retrieved.cells().contains(PostingList.packCellKey(1, 1)),
                    "Empty key should work");
        }

        @Test
        @DisplayName("Should handle very long keys (1KB+)")
        void veryLongKey() throws Exception {
            // Simulate a very long n-gram or stitch key
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 128; i++) {
                sb.append("token_").append(i);
                if (i < 127)
                    sb.append('\0');
            }
            String longKey = sb.toString();
            assertTrue(longKey.length() > 1024, "Key should be > 1KB");

            indexAccess.put(bytes(longKey), makePostingList(1, 1, 0, longKey.length()).serialize());

            PostingList retrieved = indexAccess.get(bytes(longKey)).orElseThrow();
            assertTrue(retrieved.cells().contains(PostingList.packCellKey(1, 1)),
                    "Long key should round-trip correctly");
        }

        @Test
        @DisplayName("Should handle PostingList with maximum constant length (255)")
        void maxConstantLength() throws Exception {
            Roaring64NavigableMap cells = new Roaring64NavigableMap();
            cells.add(PostingList.packCellKey(1, 1));
            byte cl = (byte) 255;
            OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(
                    new long[] { PostingList.packCellKey(1, 1) },
                    new byte[][] { { 0 } },
                    cl);
            PostingList pl = PostingList.fromCellsAndOccurrences(cells, cl, occ);

            byte[] key = bytes("max_cl");
            indexAccess.put(key, pl.serialize());

            PostingList retrieved = indexAccess.get(key).orElseThrow();
            // (byte)255 is -1 in signed Java byte — use unsigned comparison
            assertEquals(255, Byte.toUnsignedInt(retrieved.constantLength()),
                    "Should preserve maximum constant length of 255");
        }

        @Test
        @DisplayName("Should handle keys that differ only in the last byte")
        void keysDifferingOnlyInLastByte() throws Exception {
            // These keys differ only in the last character
            indexAccess.put(bytes("prefix_a"), makePostingList(1, 1, 0, 5).serialize());
            indexAccess.put(bytes("prefix_b"), makePostingList(2, 1, 0, 5).serialize());
            indexAccess.put(bytes("prefix_c"), makePostingList(3, 1, 0, 5).serialize());

            // Each PostingList has 1 cell, but with different docIds
            assertEquals(1, indexAccess.get(bytes("prefix_a")).orElseThrow().cells().getLongCardinality());
            assertTrue(indexAccess.get(bytes("prefix_a")).orElseThrow().cells()
                    .contains(PostingList.packCellKey(1, 1)), "prefix_a should contain doc 1");
            assertEquals(1, indexAccess.get(bytes("prefix_b")).orElseThrow().cells().getLongCardinality());
            assertTrue(indexAccess.get(bytes("prefix_b")).orElseThrow().cells()
                    .contains(PostingList.packCellKey(2, 1)), "prefix_b should contain doc 2");
            assertEquals(1, indexAccess.get(bytes("prefix_c")).orElseThrow().cells().getLongCardinality());
            assertTrue(indexAccess.get(bytes("prefix_c")).orElseThrow().cells()
                    .contains(PostingList.packCellKey(3, 1)), "prefix_c should contain doc 3");

            // Verify iteration order is correct
            List<String> keys = new ArrayList<>();
            try (var it = indexAccess.iterateFromFirst()) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    keys.add(new String(it.key(), StandardCharsets.UTF_8));
                }
            }
            assertEquals(List.of("prefix_a", "prefix_b", "prefix_c"), keys,
                    "Keys should be in lexicographic order");
        }

        @Test
        @DisplayName("Should handle rapid consecutive writes to the same key")
        void rapidOverwrites() throws Exception {
            byte[] key = bytes("rapid_key");

            for (int i = 1; i <= 100; i++) {
                indexAccess.put(key, makePostingList(i, 1, 0, 5).serialize());
            }

            // Last write should win
            PostingList retrieved = indexAccess.get(key).orElseThrow();
            assertTrue(retrieved.cells().contains(PostingList.packCellKey(100, 1)),
                    "Last write (docId=100) should be the visible value");
            assertEquals(1, retrieved.cells().getLongCardinality(),
                    "Should have exactly 1 cell after overwrites");
        }
    }

    // =================================================================
    // Nested: Compaction
    // =================================================================
    @Nested
    @DisplayName("Compaction Behavior")
    class CompactionTests {

        @Test
        @DisplayName("Should not lose data after manual compaction")
        void dataAfterCompaction() throws Exception {
            // Write enough data to trigger SST files at L0
            try (var batch = indexAccess.createWriteBatch()) {
                for (int i = 0; i < 5000; i++) {
                    String key = "compact_" + String.format("%06d", i);
                    batch.put(bytes(key), makePostingList(i, 1, 0, 5).serialize());
                    if ((i + 1) % 500 == 0) {
                        indexAccess.write(batch);
                        batch.clear();
                    }
                }
                if (batch.count() > 0) {
                    indexAccess.write(batch);
                }
            }

            // Trigger manual compaction
            indexAccess.compactRange();

            // Verify data is intact
            for (int i : new int[] { 0, 1000, 2500, 4999 }) {
                String key = "compact_" + String.format("%06d", i);
                assertTrue(indexAccess.get(bytes(key)).isPresent(),
                        "Key " + key + " should survive compaction");
            }

            // Full count should still be correct
            int count = 0;
            try (var it = indexAccess.iterateFromFirst()) {
                for (it.seekToFirst(); it.isValid(); it.next())
                    count++;
            }
            assertEquals(5000, count, "Compaction should not lose entries");
        }
    }
}
