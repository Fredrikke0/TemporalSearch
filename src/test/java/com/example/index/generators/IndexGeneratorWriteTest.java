package com.example.index.generators;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        MockIndexAccess mockIndexAccess = new MockIndexAccess("test-index");
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
}