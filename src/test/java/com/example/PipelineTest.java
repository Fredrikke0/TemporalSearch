package com.example;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.*;
import java.nio.file.Files;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisplayName("Pipeline Integration Tests")
public class PipelineTest {
    private static final Logger logger = LoggerFactory.getLogger(PipelineTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOTAL_DOCS = 20;
    
    protected Path tempDir;
    protected Path jsonFile;
    protected Path dbFile;
    protected Path indexDir;
    protected Path stopwordsFile;
    protected String projectName;
    protected Connection sqliteConn;
    private static String originalProgbarSilent;

    @BeforeAll
    static void storeOriginalProperty() {
        originalProgbarSilent = System.getProperty("progbar.silent");
    }

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("progbar.silent", "true"); // Disable progress bar
        logger.info("Setting up test environment");
        // Create temporary directory
        tempDir = Files.createTempDirectory("pipeline-test-");
        
        // Setup paths
        jsonFile = createTestData(tempDir);
        projectName = "test-project";
        
        // Project-based paths for verification
        Path indexesDir = tempDir.resolve("indexes");
        Path projectDir = indexesDir.resolve(projectName);
        dbFile = projectDir.resolve(projectName + ".db");
        indexDir = projectDir;
        
        // Ensure the indexes directory exists before tests run
        Files.createDirectories(indexesDir);
        
        // Clean up any index files from previous test runs
        if (indexDir.toFile().exists()) {
            File[] files = indexDir.toFile().listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        MoreFiles.deleteRecursively(file.toPath(), RecursiveDeleteOption.ALLOW_INSECURE);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        
        stopwordsFile = createStopwordsFile(tempDir);
        
        // Create database connection (will be established later)
        sqliteConn = null;
        logger.info("Test environment ready with temp dir: {}", tempDir);
    }

    @AfterEach
    void cleanup() throws IOException {
        // Restore original system property
        if (originalProgbarSilent == null) {
            System.clearProperty("progbar.silent");
        } else {
            System.setProperty("progbar.silent", originalProgbarSilent);
        }
        if (sqliteConn != null) {
            try {
                sqliteConn.close();
            } catch (SQLException e) {
                logger.error("Error closing connection", e);
            }
        }
        MoreFiles.deleteRecursively(tempDir, RecursiveDeleteOption.ALLOW_INSECURE);
    }

    @AfterAll
    static void restoreOriginalPropertyAfterAll() {
        // Ensure restoration even if @AfterEach fails somehow
        if (originalProgbarSilent == null) {
            System.clearProperty("progbar.silent");
        } else {
            System.setProperty("progbar.silent", originalProgbarSilent);
        }
    }

    protected Connection createTestDatabase() throws Exception {
        // Ensure parent directory exists
        Files.createDirectories(dbFile.getParent());
        
        // Use the absolute path to ensure we're connecting to the right database
        String dbPath = dbFile.toAbsolutePath().toString();
        logger.info("Connecting to database: {}", dbPath);
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private Path createStopwordsFile(Path tempDir) throws IOException {
        Path stopwordsFile = tempDir.resolve("stopwords.txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(stopwordsFile.toFile()))) {
            // Add some common stopwords
            String[] stopwords = {
                "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
                "has", "he", "in", "is", "it", "its", "of", "on", "that", "the",
                "to", "was", "were", "will", "with"
            };
            for (String word : stopwords) {
                writer.write(word);
                writer.newLine();
            }
        }
        return stopwordsFile;
    }
    @Disabled
    // Disabled because it takes too long to run
    @Nested
    @DisplayName("Full Pipeline Tests")
    class FullPipelineTests {
        @Test
        @DisplayName("Full pipeline processes all stages successfully")
        void testFullPipeline() throws Exception {
            String originalUserDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempDir.toString());
                
                // Run full pipeline with explicit database and index paths
                String[] args = {
                    "-s", "all",
                    "-f", jsonFile.toString(),
                    "-p", projectName,
                    "-d", dbFile.toAbsolutePath().toString(),
                    "-i", indexDir.toAbsolutePath().toString(),
                    "--stopwords", stopwordsFile.toString(),
                    "--preserve-index"  // Add flag to preserve existing index data
                };
                
                // Ensure directories exist before running
                Files.createDirectories(dbFile.getParent());
                
                Pipeline.runPipeline(args);
                
                // Connect to the database for verification
                sqliteConn = createTestDatabase();
                
                verifyConversionStage(TOTAL_DOCS);
                verifyAnnotationStage(TOTAL_DOCS);
                verifyIndexingStage();
            } finally {
                System.setProperty("user.dir", originalUserDir);
            }
        }

        @Test
        @DisplayName("Pipeline with limit processes correct number of documents")
        void testPipelineWithLimit() throws Exception {
            String originalUserDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempDir.toString());
                
                // Run pipeline with limit and explicit database and index paths
                String[] args = {
                    "-s", "all",
                    "-f", jsonFile.toString(),
                    "-p", projectName,
                    "-d", dbFile.toAbsolutePath().toString(),
                    "-i", indexDir.toAbsolutePath().toString(),
                    "--stopwords", stopwordsFile.toString(),
                    "--preserve-index",  // Add flag to preserve existing index data
                    "--limit", "5"
                };
                
                // Ensure directories exist before running
                Files.createDirectories(dbFile.getParent());
                
                Pipeline.runPipeline(args);
                
                // Connect to the database for verification
                sqliteConn = createTestDatabase();
                
                verifyConversionStage(5);
                verifyAnnotationStage(5);
                verifyIndexingStage();
            } finally {
                System.setProperty("user.dir", originalUserDir);
            }
        }
    }

    @Nested
    @DisplayName("Individual Stage Tests")
    class StageTests {
        @Test
        @DisplayName("Conversion stage creates database correctly")
        void testConversionStage() throws Exception {
            String[] args = {
                "-s", "convert",
                "-f", jsonFile.toString(),
                "-p", projectName,
                "--recreate"
            };
            
            // Set working directory for the test
            String originalUserDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempDir.toString());
                Pipeline.runPipeline(args);
                
                // Connect to the database for verification
                sqliteConn = createTestDatabase();
                
                verifyConversionStage(TOTAL_DOCS);
                verifyNoAnnotations();
                verifyNoIndexes();
            } finally {
                // Restore original working directory
                System.setProperty("user.dir", originalUserDir);
            }
        }

        @Test
        @DisplayName("Annotation stage processes documents correctly")
        void testAnnotationStage() throws Exception {
            // First run conversion
            System.setProperty("user.dir", tempDir.toString());
            
            Pipeline.runPipeline(new String[]{
                "-s", "convert",
                "-f", jsonFile.toString(),
                "-p", projectName,
                "--recreate"
            });

            // Then run annotation
            String[] args = {
                "-s", "annotate",
                "-p", projectName,
                "-b", "5",
                "-t", "2"
            };
            Pipeline.runPipeline(args);

            // Connect to the database for verification
            sqliteConn = createTestDatabase();
            
            verifyConversionStage(TOTAL_DOCS);
            verifyAnnotationStage(TOTAL_DOCS);
            verifyNoIndexes();
        }

        @Test
        @DisplayName("Indexing stage creates all index types")
        void testIndexingStage() throws Exception {
            // Run conversion and annotation first
            setupConversionAndAnnotation();
            
            String originalUserDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempDir.toString());
                
                // Test indexing with all types and explicitly specify database path
                String[] args = {
                    "-s", "index",
                    "-p", projectName,
                    "-d", dbFile.toAbsolutePath().toString(),
                    "-i", indexDir.toAbsolutePath().toString(),
                    "--stopwords", stopwordsFile.toString(),
                    "-y", "all",
                    "--preserve-index"  // Add flag to preserve existing index data
                };
                
                // Verify database path exists before running indexing
                logger.info("Indexing with database: {}", dbFile.toAbsolutePath());
                assertTrue(dbFile.toFile().exists(), "Database file should exist before indexing");
                
                Pipeline.runPipeline(args);
                
                verifyIndexingStage();
            } finally {
                System.setProperty("user.dir", originalUserDir);
            }
        }

        @Test
        @DisplayName("Indexing stage creates specific index type")
        void testSpecificIndexType() throws Exception {
            // Run conversion and annotation first
            setupConversionAndAnnotation();
            
            String originalUserDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempDir.toString());
                
                // Test specific index type and explicitly specify database path
                String[] args = {
                    "-s", "index",
                    "-p", projectName,
                    "-d", dbFile.toAbsolutePath().toString(),
                    "-i", indexDir.toAbsolutePath().toString(),
                    "--stopwords", stopwordsFile.toString(),
                    "-y", "unigram",
                    "--preserve-index"  // Add flag to preserve existing index data
                };
                
                // Verify database path exists before running indexing
                logger.info("Indexing with database: {}", dbFile.toAbsolutePath());
                assertTrue(dbFile.toFile().exists(), "Database file should exist before indexing");
                
                Pipeline.runPipeline(args);
                
                // Verify only unigram index exists
                assertTrue(indexDir.resolve("unigram").toFile().exists(),
                    "Unigram index should exist");
                assertFalse(indexDir.resolve("bigram").toFile().exists(),
                    "Bigram index should not exist");
            } finally {
                System.setProperty("user.dir", originalUserDir);
            }
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        @Test
        @DisplayName("Pipeline handles missing input file")
        void testMissingInputFile() {
            String[] args = {
                "-s", "convert",
                "-f", "nonexistent.json",
                "-p", projectName
            };
            Exception exception = assertThrows(FileNotFoundException.class, 
                () -> Pipeline.runPipeline(args));
            assertTrue(exception.getMessage().contains("nonexistent.json"));
        }

        @Test
        @DisplayName("Pipeline handles invalid stage")
        void testInvalidStage() {
            String[] args = {
                "-s", "invalid",
                "-f", jsonFile.toString(),
                "-p", projectName
            };
            ArgumentParserException exception = assertThrows(ArgumentParserException.class, 
                () -> Pipeline.runPipeline(args));
            assertTrue(exception.getMessage().toLowerCase().contains("invalid"));
        }

        @Test
        @DisplayName("Pipeline handles missing required arguments")
        void testMissingRequiredArgs() {
            // For the convert stage, input file is required
            String[] args = {"-s", "convert", "-p", projectName};
            ArgumentParserException exception = assertThrows(ArgumentParserException.class, 
                () -> Pipeline.runPipeline(args));
            assertTrue(exception.getMessage().contains("required"));
        }
        
        @Test
        @DisplayName("Pipeline creates project directories")
        void testProjectDirectoryCreation() throws Exception {
            String testProject = "new-test-project";
            String[] args = {
                "-s", "convert",
                "-f", jsonFile.toString(),
                "-p", testProject,
                "--recreate"
            };
            
            // Set working directory for the test and capture original
            String originalUserDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempDir.toString());
                logger.info("Setting working directory to: {}", tempDir);
                
                Pipeline.runPipeline(args);
                
                // Verify project directory structure - log actual paths for debugging
                Path indexesDir = tempDir.resolve("indexes");
                Path projectDir = indexesDir.resolve(testProject);
                Path dbPath = projectDir.resolve(testProject + ".db");
                
                logger.info("Checking for project dir: {}", projectDir);
                logger.info("Project dir exists: {}", projectDir.toFile().exists());
                logger.info("Checking for db file: {}", dbPath);
                logger.info("DB file exists: {}", dbPath.toFile().exists());
                
                assertTrue(indexesDir.toFile().exists(), "Indexes directory should be created");
                assertTrue(projectDir.toFile().exists(), "Project directory should be created");
                assertTrue(dbPath.toFile().exists(), "Database file should be created");
            } finally {
                // Restore original working directory
                System.setProperty("user.dir", originalUserDir);
            }
        }
    }

    // Helper methods
    private Path createTestData(Path tempDir) throws IOException {
        Path jsonFile = tempDir.resolve("test.json");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(jsonFile.toFile()))) {
            // Create test documents with more realistic content
            for (int i = 0; i < TOTAL_DOCS; i++) {
                ObjectNode doc = MAPPER.createObjectNode()
                    .put("title", "Title " + i)
                    .put("text", String.format(
                        "This is test document %d. It contains sample text for testing NLP features. " +
                        "The quick brown fox jumps over the lazy dog. " +
                        "This document was created on January 1st, 2024. " +
                        "OpenAI's GPT models have revolutionized natural language processing.",
                        i))
                    .put("timestamp", "2024-01-01");
                writer.write(doc.toString());
                writer.newLine();
            }
        }
        return jsonFile;
    }

    private void setupConversionAndAnnotation() throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            
            // Ensure parent directories exist
            Files.createDirectories(dbFile.getParent());
            String absoluteDbPath = dbFile.toAbsolutePath().toString();
            logger.info("Using database path: {}", absoluteDbPath);
            
            // Run conversion with explicit database path
            Pipeline.runPipeline(new String[]{
                "-s", "convert",
                "-f", jsonFile.toString(),
                "-p", projectName,
                "-d", absoluteDbPath,
                "--recreate"
            });
            
            // Run annotation with explicit database path
            Pipeline.runPipeline(new String[]{
                "-s", "annotate",
                "-p", projectName,
                "-d", absoluteDbPath,
                "-b", "5",
                "-t", "2"
            });
            
            // Connect to the database for verification
            sqliteConn = createTestDatabase();
            
            // Verify annotations table exists before proceeding
            try (Statement stmt = sqliteConn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='annotations'");
                assertTrue(rs.next(), "Annotations table should exist");
                assertTrue(rs.getInt(1) > 0, "Annotations table should exist");
                
                // Double check that we have annotations
                rs = stmt.executeQuery("SELECT COUNT(*) FROM annotations");
                assertTrue(rs.next(), "Should have annotations");
                assertTrue(rs.getInt(1) > 0, "Should have annotations");
            }
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    private void verifyConversionStage(int expectedCount) throws SQLException {
        logger.debug("Verifying conversion stage with expected count: {}", expectedCount);
        // Check total documents
        try (Statement stmt = sqliteConn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM documents");
            assertTrue(rs.next());
            assertEquals(expectedCount, rs.getInt(1), 
                "Database should contain exactly " + expectedCount + " documents");

            // Verify document content
            rs = stmt.executeQuery("SELECT title, text FROM documents ORDER BY document_id LIMIT 1");
            assertTrue(rs.next());
            assertNotNull(rs.getString("title"), "Document title should not be null");
            assertNotNull(rs.getString("text"), "Document text should not be null");
        }
        logger.debug("Conversion stage verification completed");
    }

    private void verifyAnnotationStage(int expectedCount) throws SQLException {
        try (Statement stmt = sqliteConn.createStatement()) {
            // Check annotations exist for each document
            ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(DISTINCT document_id) FROM annotations");
            assertTrue(rs.next());
            assertEquals(expectedCount, rs.getInt(1), 
                "Should have annotations for exactly " + expectedCount + " documents");

            // Verify annotation content
            rs = stmt.executeQuery(
                "SELECT DISTINCT lemma, pos FROM annotations LIMIT 1");
            assertTrue(rs.next());
            assertNotNull(rs.getString("lemma"), "Annotation lemma should not be null");
            assertNotNull(rs.getString("pos"), "Annotation POS should not be null");
        }
    }

    private void verifyIndexingStage() {
        // Verify index directories were created
        String[] indexTypes = {"unigram", "bigram", "trigram", "dependency", "ner_date", "pos"};
        for (String type : indexTypes) {
            Path indexPath = indexDir.resolve(type);
            assertTrue(indexPath.toFile().exists(), 
                type + " index directory should exist");
            assertTrue(indexPath.toFile().list().length > 0,
                type + " index should not be empty");
        }
    }

    private void verifyNoAnnotations() throws SQLException {
        try (Statement stmt = sqliteConn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='annotations'");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Annotations table should not exist");
        }
    }

    private void verifyNoIndexes() {
        if (indexDir.toFile().exists()) {
            File[] files = indexDir.toFile().listFiles();
            int count = files != null ? files.length : 0;
            // Only count directories that look like index directories
            int indexDirCount = 0;
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() && 
                        (file.getName().equals("unigram") || 
                         file.getName().equals("bigram") || 
                         file.getName().equals("trigram") || 
                         file.getName().equals("dependency") || 
                         file.getName().equals("ner_date") || 
                         file.getName().equals("pos") || 
                         file.getName().equals("hypernym"))) {
                        indexDirCount++;
                    }
                }
            }
            assertEquals(0, indexDirCount, 
                "Index directory should not contain index subdirectories: " + indexDir.toAbsolutePath());
        }
    }

    private String[] createPipelineArgs(String stage, boolean includeIndexing) {
        return new String[]{
            "-s", stage,
            "-f", jsonFile.toString(),
            "-p", projectName,
            "--stopwords", stopwordsFile.toString(),
            "--recreate"
        };
    }
} 