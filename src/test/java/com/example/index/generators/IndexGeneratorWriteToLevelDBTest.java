package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccess;
import com.example.core.PositionListSoA;
import com.example.index.IndexEntry; // Assuming a simple IndexEntry or mock if needed
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

public class IndexGeneratorWriteToLevelDBTest extends BaseIndexTest {
    private static final Logger logger = LoggerFactory.getLogger(IndexGeneratorWriteToLevelDBTest.class);

    private TestIndexGenerator testGenerator;
    private Path generatorTempDir; // Specific temp dir for the generator instance

    // Simple concrete implementation or mock for IndexGenerator
    private static class TestIndexGenerator extends IndexGenerator<IndexEntry> {
        // private final Path actualTempDirToUse; // Not strictly needed if getActualTempDir() works

        protected TestIndexGenerator(String indexBaseDir, String indexName, Connection sqliteConn, ProgressTracker progress, Path customTempDir) throws IOException {
            // Pass null for stopwordsPath, batchSize can be arbitrary for this test's focus
            super(indexBaseDir, indexName, null, sqliteConn, progress, 10, customTempDir);
            // The super constructor already calls initializeTempDir, which creates the unique subdir.
            // We retrieve it later via getActualTempDir()
            // this.actualTempDirToUse = customTempDir.resolve(getIndexName() + "-index-temp-" + UUID.randomUUID().toString());
            // Files.createDirectories(this.actualTempDirToUse);
        }

        // Public accessor for the actual temp directory
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
        public String getIndexName() { return "test-index"; }
        @Override
        protected List<IndexEntry> fetchBatch(IndexEntry lastEntry) { return Collections.emptyList(); } // Not used in this test
        @Override
        protected ListMultimap<String, PositionListSoA> processBatch(List<IndexEntry> batch) { return null; } // Not used
        @Override
        public long getDocumentCountForIndex() { return 0; } // Not used

        // Make writeToLevelDB public for testing
        @Override
        public long writeToLevelDB(File sortedFile) throws IOException {
            return super.writeToLevelDB(sortedFile);
        }

        public IndexAccess getIndexAccessInstance() {
            return this.indexAccess; // Expose IndexAccess
        }
    }

    @BeforeEach
    @Override
    protected void setUp() throws Exception {
        super.setUp(); // Sets up indexBaseDir, sqliteConn etc. from BaseIndexTest

        // This generatorTempDir is the PARENT directory we pass to the constructor.
        // The IndexGenerator will create its unique subdirectory INSIDE this.
        generatorTempDir = tempDir.resolve("generatorSpecificParentTemp");
        Files.createDirectories(generatorTempDir);

        ProgressTracker mockProgressTracker = Mockito.mock(ProgressTracker.class);
        testGenerator = new TestIndexGenerator(indexBaseDir.resolve("test-index").toString(), "test-index", sqliteConn, mockProgressTracker, generatorTempDir);

        // Diagnostic: Print size of serialized empty PositionListSoA
        PositionListSoA emptyList = new PositionListSoA();
        byte[] emptyBlob = emptyList.serializeToCompositeBlob();
        logger.info("DIAGNOSTIC: Serialized empty PositionListSoA has size: {} bytes", emptyBlob.length);
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        if (testGenerator != null) {
            testGenerator.close();
        }
        // generatorTempDir and its contents (including the actual temp dir created by IndexGenerator)
        // will be cleaned up by super.tearDown() as it's within 'tempDir' from BaseIndexTest.
        super.tearDown();
    }

    @Test
    void testWriteToLevelDB_ComplexCase() throws IOException {
        // 1. Prepare data for PositionListSoA instances
        PositionListSoA termA_list1 = new PositionListSoA();
        termA_list1.add(1, 0, 10, 15, 101); // doc, sent, begin, end, synId
        termA_list1.add(1, 0, 20, 25, -1);

        PositionListSoA termA_list2 = new PositionListSoA();
        termA_list2.add(1, 1, 30, 35, 102);

        PositionListSoA termB_list1 = new PositionListSoA();
        termB_list1.add(2, 0, 5, 10, 201);

        PositionListSoA termC_emptyList = new PositionListSoA();


        // 2. Serialize these to Base64 strings
        String termA_blob1_b64 = Base64.getEncoder().encodeToString(termA_list1.serializeToCompositeBlob());
        String termA_blob2_b64 = Base64.getEncoder().encodeToString(termA_list2.serializeToCompositeBlob());
        String termB_blob1_b64 = Base64.getEncoder().encodeToString(termB_list1.serializeToCompositeBlob());
        String termC_blob_empty_b64 = Base64.getEncoder().encodeToString(termC_emptyList.serializeToCompositeBlob());


        // 3. Create the 'sorted.tmp' file within the generator's actual temp directory
        Path actualGeneratorTempPath = testGenerator.getActualTempDir(); // This gets the unique dir like /tmp/.../generatorSpecificParentTemp/test-index-index-temp-UUID
        File sortedFile = actualGeneratorTempPath.resolve("sorted.tmp").toFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(sortedFile, StandardCharsets.UTF_8))) {
            writer.write("termA\t" + termA_blob1_b64 + "\n");
            writer.write("termA\t" + termA_blob2_b64 + "\n"); // Same term, different blob
            writer.write("termB\t" + termB_blob1_b64 + "\n");
            writer.write("termC\t" + termC_blob_empty_b64 + "\n"); // Term with an empty position list
            writer.write("termD\t" + termA_blob1_b64 + "\n"); // Another term, re-using a blob for simplicity
        }
        assertTrue(sortedFile.exists() && sortedFile.length() > 0, "Sorted temp file should be created and non-empty.");

        // Try to fully destroy the DB directory first to ensure no stale state from previous runs
        // before the generator writes to it.
        Path dbPathForDestroy = indexBaseDir.resolve(testGenerator.getIndexName());
        try {
            logger.warn("Attempting to DESTROY database at: {} before generator writes.", dbPathForDestroy);
            // Ensure the parent directory exists for Iq80DBFactory.factory.destroy, as it might not if this is the very first run
            Files.createDirectories(dbPathForDestroy.getParent());
            Iq80DBFactory.factory.destroy(dbPathForDestroy.toFile(), new Options());
            logger.info("Database at: {} reportedly destroyed before generator writes.", dbPathForDestroy);
        } catch (IOException e) {
            logger.error("Failed to destroy database at {}: {}. This might be okay if it didn't exist.", dbPathForDestroy, e.getMessage());
            // Not failing the test here, as destroy is a best-effort cleanup.
            // The generator's createIfMissing should handle it.
        }

        // 4. Call writeToLevelDB
        assertDoesNotThrow(() -> testGenerator.writeToLevelDB(sortedFile), "writeToLevelDB should not throw an exception.");

        // 5. Verify LevelDB content directly using the generator's DB instance
        logger.info("Attempting to verify DB content using the TestIndexGenerator's own DB instance.");
        DB db = null;
        try {
            IndexAccess ia = testGenerator.getIndexAccessInstance();
            assertNotNull(ia, "IndexAccess instance from generator should not be null.");
            db = ia.getDbForVerification();
            assertNotNull(db, "DB instance from generator's IndexAccess should not be null.");
            logger.info("Successfully obtained DB instance from TestIndexGenerator for verification.");

            // Verify Term A (merged from two lines)
            byte[] termA_value_db = db.get(IndexGenerator.bytes("termA"));
            assertNotNull(termA_value_db, "Value for 'termA' should exist in DB.");
            PositionListSoA termA_deserialized = PositionListSoA.deserializeFromCompositeBlob(termA_value_db);
            assertEquals(3, termA_deserialized.getNumPositions(), "TermA should have 3 positions after merge.");
            // Check a few values to be sure (docId of first original, docId of second original)
            assertEquals(1, termA_deserialized.getDocIdAt(0)); // From termA_list1
            assertEquals(1, termA_deserialized.getDocIdAt(1)); // From termA_list1
            assertEquals(1, termA_deserialized.getDocIdAt(2)); // From termA_list2

            // Verify Term B
            byte[] termB_value_db = db.get(IndexGenerator.bytes("termB"));
            assertNotNull(termB_value_db, "Value for 'termB' should exist in DB.");
            PositionListSoA termB_deserialized = PositionListSoA.deserializeFromCompositeBlob(termB_value_db);
            assertEquals(1, termB_deserialized.getNumPositions(), "TermB should have 1 position.");
            assertEquals(2, termB_deserialized.getDocIdAt(0));
            assertEquals(201, termB_deserialized.getSynonymIdAt(0));

            // Verify Term C (written with an empty PositionListSoA)
            byte[] termC_value_db = db.get(IndexGenerator.bytes("termC"));
            assertNull(termC_value_db, "Value for 'termC' (from empty list) should NOT exist in DB, as it has 0 positions and should be filtered.");

            // Verify Term D
            byte[] termD_value_db = db.get(IndexGenerator.bytes("termD"));
            assertNotNull(termD_value_db, "Value for 'termD' should exist in DB.");
            PositionListSoA termD_deserialized = PositionListSoA.deserializeFromCompositeBlob(termD_value_db);
            assertEquals(2, termD_deserialized.getNumPositions(), "TermD should have 2 positions.");
        } catch (Exception e) {
            fail("Exception during verification using generator's DB instance: " + e.getMessage(), e);
        }
        // No explicit close of 'db' here, as it's managed by testGenerator which is closed in @AfterEach
    }
}