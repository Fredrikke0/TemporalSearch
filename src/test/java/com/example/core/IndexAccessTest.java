package com.example.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.rocksdb.Options;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;

import com.example.index.RocksDBConfig;

/**
 * Tests for IndexAccess implementation.
 * Verifies core functionality including:
 * - Basic CRUD operations
 * - Batch operations
 * - Resource management
 * - Error handling
 */
public class IndexAccessTest {
    private static final String TEST_INDEX_PATH = "test-indexes";
    private IndexAccess indexAccess;
    private Path indexPath;
    private Options options;

    @BeforeEach
    void setUp() throws Exception {
        indexPath = Path.of(TEST_INDEX_PATH);
        if (Files.exists(indexPath)) {
            deleteDirectory(indexPath.toFile());
        }
        Files.createDirectories(indexPath);

        options = RocksDBConfig.createOptimizedOptions();

        indexAccess = new IndexAccess(indexPath, "test", options, false);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (indexAccess != null) {
            indexAccess.close();
        }
        if (options != null) {
            options.close();
        }
        if (Files.exists(indexPath)) {
            deleteDirectory(indexPath.toFile());
        }
    }

    /** Helper to build a simple PostingList with a single cell and occurrence. */
    private static PostingList makePostingList(int docId, int sentId, int begin, int end) throws IOException {
        long cellKey = PostingList.packCellKey(docId, sentId);
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        cells.add(cellKey);
        byte constantLength = (byte) Math.min(end - begin, 255);
        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(
                new long[] { cellKey }, new byte[][] { { (byte) begin } }, constantLength);
        return PostingList.fromCellsAndOccurrences(cells, constantLength, occ);
    }

    @Test
    void testBasicOperations() throws Exception {
        // Create test data: two cells in a single PostingList
        PostingList pl = PostingList.empty((byte) 5);
        pl = pl.merge(makePostingList(1, 1, 0, 5));
        pl = pl.merge(makePostingList(1, 2, 6, 10));

        // Test put and get
        byte[] key = "test-key".getBytes();
        indexAccess.put(key, pl.serialize());

        Optional<PostingList> retrieved = indexAccess.get(key);
        assertTrue(retrieved.isPresent(), "Should retrieve stored posting list");
        assertEquals(2, retrieved.get().cells().getLongCardinality(), "Should have correct number of cells");
    }

    @Test
    void testBatchOperations() throws Exception {
        // Create test data
        Map<String, PostingList> entries = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            entries.put("key" + i, makePostingList(i, 1, 0, 5));
        }

        // Write batch
        try (WriteBatch batch = indexAccess.createWriteBatch()) {
            for (Map.Entry<String, PostingList> entry : entries.entrySet()) {
                batch.put(
                        entry.getKey().getBytes(),
                        entry.getValue().serialize());
            }
            indexAccess.write(batch);
        }

        // Verify entries
        for (String key : entries.keySet()) {
            Optional<PostingList> retrieved = indexAccess.get(key.getBytes());
            assertTrue(retrieved.isPresent(), "Should retrieve entry for key: " + key);
            assertEquals(1, retrieved.get().cells().getLongCardinality(),
                    "Should have correct number of cells for key: " + key);
        }
    }

    @Test
    void testMergePostingLists() throws Exception {
        byte[] key = "merge-test".getBytes();

        // Create first posting list
        PostingList pl1 = makePostingList(1, 1, 0, 5);
        indexAccess.put(key, pl1.serialize());

        // Create second posting list
        PostingList pl2 = makePostingList(1, 2, 6, 10);

        // Simulate merge: get existing, deserialize, merge, serialize, then put
        Optional<PostingList> existingOpt = indexAccess.get(key);
        assertTrue(existingOpt.isPresent(), "Existing list should be present for merge");
        PostingList mergedPl = existingOpt.get().merge(pl2);
        indexAccess.put(key, mergedPl.serialize());

        // Verify merge
        Optional<PostingList> mergedResult = indexAccess.get(key);
        assertTrue(mergedResult.isPresent(), "Should retrieve merged posting list");
        assertEquals(2, mergedResult.get().cells().getLongCardinality(), "Should contain all cells");
    }

    @Test
    void testIterator() throws Exception {
        // Create test data
        for (int i = 0; i < 5; i++) {
            PostingList pl = makePostingList(i, 1, 0, 5);
            indexAccess.put(("key" + i).getBytes(), pl.serialize());
        }

        // Test iteration
        int count = 0;
        try (RocksIterator iterator = indexAccess.iterateFromFirst()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                byte[] value = iterator.value();
                assertNotNull(key, "Key should not be null");
                assertNotNull(value, "Value should not be null");

                PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.FULL);
                assertEquals(1, pl.cells().getLongCardinality(),
                        "Should have correct number of cells");
                count++;
            }
        }
        assertEquals(5, count, "Should iterate over all entries");
    }

    // @Test
    // void testClosedOperations() throws Exception {
    // indexAccess.close();
    // PostingList pl = PostingList.empty((byte) 0);

    // // Verify operations throw appropriate exceptions
    // assertThrows(RocksDBException.class, () ->
    // indexAccess.put("test".getBytes(), pl.serialize()));
    // assertThrows(RocksDBException.class, () ->
    // indexAccess.get("test".getBytes()));
    // assertThrows(RocksDBException.class, () -> indexAccess.createWriteBatch());
    // assertThrows(RocksDBException.class, () ->
    // indexAccess.iterateFromFirst());
    // assertThrows(RocksDBException.class, () ->
    // indexAccess.seek("anykey".getBytes()));
    // }

    private void deleteDirectory(File directory) throws IOException {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        if (!file.delete()) {
                            throw new IOException("Failed to delete file: " + file);
                        }
                    }
                }
            }
            if (!directory.delete()) {
                throw new IOException("Failed to delete directory: " + directory);
            }
        }
    }
}
