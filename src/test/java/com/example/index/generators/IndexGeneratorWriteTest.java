package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.core.index.MockIndexAccess; // For TestIndexGenerator
import com.example.index.IndexEntry;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

public class IndexGeneratorWriteTest extends BaseIndexTest { // Renamed class
    private static final Logger logger = LoggerFactory.getLogger(IndexGeneratorWriteTest.class);

    private TestIndexGenerator testGenerator;
    private Path generatorTempDir;

    private static class TestIndexGenerator extends IndexGenerator<IndexEntry> {

        protected TestIndexGenerator(IndexAccessInterface indexAccess, Connection sqliteConn, ProgressTracker progress, Path customTempDir) throws IOException {
            super(indexAccess, null, sqliteConn, progress, 10, customTempDir);
        }

        public Path getActualTempDir() {
            try {
                java.lang.reflect.Field tempDirField = IndexGenerator.class.getDeclaredField("tempDir");
                tempDirField.setAccessible(true);
                return (Path) tempDirField.get(this);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Could not access tempDir field", e);
            }
        }

        @Override
        protected String getTableName() { return "test_table"; }
        @Override
        public String getIndexName() { return "test-index"; } // Match indexAccess type if needed
        @Override
        protected List<IndexEntry> fetchBatch(IndexEntry lastEntry) { return Collections.emptyList(); }
        @Override
        protected ListMultimap<String, PositionListSoA> processBatch(List<IndexEntry> batch) { return null; }
        @Override
        public long getDocumentCountForIndex() { return 0; }

        @Override
        public void writeToLevelDB(File sortedFile) throws IOException {
            super.writeToLevelDB(sortedFile);
        }

        public IndexAccessInterface getIndexAccessInstance() {
            return this.indexAccess; // Expose IndexAccessInterface
        }
    }

    @BeforeEach
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        generatorTempDir = tempDir.resolve("generatorSpecificParentTemp");
        Files.createDirectories(generatorTempDir);

        ProgressTracker mockProgressTracker = Mockito.mock(ProgressTracker.class);
        // Create MockIndexAccess for the TestIndexGenerator
        // The indexType for MockIndexAccess should match what TestIndexGenerator.getIndexName() returns
        MockIndexAccess mockIndexAccess = new MockIndexAccess("test-index", com.example.index.AnnotationType.UNKNOWN, new java.util.HashMap<>());
        testGenerator = new TestIndexGenerator(mockIndexAccess, sqliteConn, mockProgressTracker, generatorTempDir);

        PositionListSoA emptyList = new PositionListSoA();
        byte[] emptyBlob = emptyList.serializeToCompositeBlob();
        logger.info("DIAGNOSTIC: Serialized empty PositionListSoA has size: {} bytes", emptyBlob.length);
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        if (testGenerator != null) {
            testGenerator.close(); // This should close the IndexAccessInterface (MockIndexAccess)
        }
        super.tearDown();
    }

    @Test
    void testWriteToDB_ComplexCase() throws IOException, RocksDBException, IndexAccessException { // Renamed test method
        PositionListSoA termA_list1 = new PositionListSoA();
        termA_list1.add(1, 0, 10, 15, 101);
        termA_list1.add(1, 0, 20, 25, -1);

        PositionListSoA termA_list2 = new PositionListSoA();
        termA_list2.add(1, 1, 30, 35, 102);

        PositionListSoA termB_list1 = new PositionListSoA();
        termB_list1.add(2, 0, 5, 10, 201);

        PositionListSoA termC_emptyList = new PositionListSoA();

        String termA_blob1_b64 = Base64.getEncoder().encodeToString(termA_list1.serializeToCompositeBlob());
        String termA_blob2_b64 = Base64.getEncoder().encodeToString(termA_list2.serializeToCompositeBlob());
        String termB_blob1_b64 = Base64.getEncoder().encodeToString(termB_list1.serializeToCompositeBlob());
        String termC_blob_empty_b64 = Base64.getEncoder().encodeToString(termC_emptyList.serializeToCompositeBlob());

        Path actualGeneratorTempPath = testGenerator.getActualTempDir();
        File sortedFile = actualGeneratorTempPath.resolve("sorted.tmp").toFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(sortedFile, StandardCharsets.UTF_8))) {
            writer.write("termA\t" + termA_blob1_b64 + "\n");
            writer.write("termA\t" + termA_blob2_b64 + "\n");
            writer.write("termB\t" + termB_blob1_b64 + "\n");
            writer.write("termC\t" + termC_blob_empty_b64 + "\n");
            writer.write("termD\t" + termA_blob1_b64 + "\n");
        }
        assertTrue(sortedFile.exists() && sortedFile.length() > 0, "Sorted temp file should be created and non-empty.");

        // The concept of destroying the DB before write might change.
        // If IndexGenerator uses an IndexAccessInterface passed to it,
        // the test should perhaps clear the MockIndexAccess if that's the desired precondition.
        IndexAccessInterface ia = testGenerator.getIndexAccessInstance();
        if (ia instanceof MockIndexAccess) {
            ((MockIndexAccess) ia).clearAllData();
            logger.info("Cleared MockIndexAccess before generator writes.");
        } else {
            // For a real IndexAccess, destruction is more complex and path-dependent.
            // This test uses MockIndexAccess, so the above clear is sufficient.
            logger.warn("IndexAccessInterface is not MockIndexAccess, direct DB destruction not performed in this mock-focused test.");
        }

        assertDoesNotThrow(() -> testGenerator.writeToLevelDB(sortedFile), "writeToLevelDB should not throw an exception.");

        logger.info("Attempting to verify DB content using the TestIndexGenerator's IndexAccessInterface.");

        assertNotNull(ia, "IndexAccessInterface instance from generator should not be null.");

        // Verify Term A (merged from two lines)
        Optional<PositionListSoA> termA_value_opt = ia.get(IndexGenerator.bytes("termA"));
        assertTrue(termA_value_opt.isPresent(), "Value for 'termA' should exist in DB.");
        PositionListSoA termA_deserialized = termA_value_opt.get();
        assertEquals(3, termA_deserialized.getNumPositions(), "TermA should have 3 positions after merge.");
        assertEquals(1, termA_deserialized.getDocIdAt(0));
        assertEquals(1, termA_deserialized.getDocIdAt(1));
        assertEquals(1, termA_deserialized.getDocIdAt(2));

        // Verify Term B
        Optional<PositionListSoA> termB_value_opt = ia.get(IndexGenerator.bytes("termB"));
        assertTrue(termB_value_opt.isPresent(), "Value for 'termB' should exist in DB.");
        PositionListSoA termB_deserialized = termB_value_opt.get();
        assertEquals(1, termB_deserialized.getNumPositions(), "TermB should have 1 position.");
        assertEquals(2, termB_deserialized.getDocIdAt(0));
        assertEquals(201, termB_deserialized.getSynonymIdAt(0));

        // Verify Term C (written with an empty PositionListSoA)
        Optional<PositionListSoA> termC_value_opt = ia.get(IndexGenerator.bytes("termC"));
        assertTrue(termC_value_opt.isEmpty(), "Value for 'termC' (from empty list) should NOT exist in DB / be empty Optional.");

        // Verify Term D
        Optional<PositionListSoA> termD_value_opt = ia.get(IndexGenerator.bytes("termD"));
        assertTrue(termD_value_opt.isPresent(), "Value for 'termD' should exist in DB.");
        PositionListSoA termD_deserialized = termD_value_opt.get();
        assertEquals(2, termD_deserialized.getNumPositions(), "TermD should have 2 positions.");
    }
}