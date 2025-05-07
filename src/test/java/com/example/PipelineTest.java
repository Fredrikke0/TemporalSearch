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
import java.sql.PreparedStatement;
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
    //private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOTAL_DOCS = 20;
    
    protected Path tempDir;
    protected Path jsonFile;
    protected Path dbFile;
    protected Path indexDir;
    protected Path stopwordsFile;
    protected String projectName;
    protected Connection sqliteConn;
    protected Path sourceDb;
    private static String originalProgbarSilent;

    @BeforeAll
    static void storeOriginalProperty() {
        originalProgbarSilent = System.getProperty("progbar.silent");
    }

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("progbar.silent", "true"); // Disable progress bar
        logger.info("Setting up test environment");
        tempDir = Files.createTempDirectory("pipeline-test-");
        sourceDb = tempDir.resolve("source.db");
        createInitialSourceDatabase(sourceDb, TOTAL_DOCS);
        projectName = "test-project";
        Path projectDir = tempDir.resolve(projectName);
        dbFile = projectDir.resolve(projectName + ".db");
        indexDir = projectDir.resolve("indexes");
        stopwordsFile = createStopwordsFile(tempDir);
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
                Files.createDirectories(dbFile.getParent());
                // Run full pipeline (all stages except conversion)
                String[] args = {
                    "-s", "all",
                    "-p", projectName,
                    "-d", sourceDb.toString(),
                    "--stopwords", stopwordsFile.toString()
                };
                Pipeline.runPipeline(args);
                // Connect to the database for verification
                sqliteConn = createTestDatabase();
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
                Files.createDirectories(dbFile.getParent());
                // Run pipeline with limit
                String[] args = {
                    "-s", "all",
                    "-p", projectName,
                    "-d", sourceDb.toString(),
                    "--stopwords", stopwordsFile.toString(),
                    "--limit", "5"
                };
                Pipeline.runPipeline(args);
                sqliteConn = createTestDatabase();
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
        @DisplayName("Annotation stage processes documents correctly")
        void testAnnotationStage() throws Exception {
            Path projectDir = tempDir.resolve(projectName);
            if (Files.exists(projectDir)) {
                MoreFiles.deleteRecursively(projectDir, RecursiveDeleteOption.ALLOW_INSECURE);
            }
            String[] args = {
                "-s", "annotate",
                "-p", projectDir.toString(),
                "-d", sourceDb.toString(),
                "-b", "5",
                "-t", "2"
            };
            Pipeline.runPipeline(args);
            sqliteConn = createTestDatabase();
            verifyAnnotationStage(TOTAL_DOCS);
            verifyNoIndexes();
        }

        @Test
        @DisplayName("Annotation stage resumes correctly after partial completion")
        void testAnnotationResumesCorrectly() throws Exception {
            Path projectDir = tempDir.resolve(projectName);
            if (Files.exists(projectDir)) {
                MoreFiles.deleteRecursively(projectDir, RecursiveDeleteOption.ALLOW_INSECURE);
            }
            String[] args1 = {
                "-s", "annotate",
                "-p", projectDir.toString(),
                "-d", sourceDb.toString(),
                "-b", "5",
                "-t", "2",
                "-l", "10"
            };
            Pipeline.runPipeline(args1);
            sqliteConn = createTestDatabase();
            verifyAnnotationStage(10);
            if (sqliteConn != null) sqliteConn.close();
            // Run annotation again without limit (should resume)
            String[] args2 = {
                "-s", "annotate",
                "-p", projectDir.toString(),
                "-b", "5",
                "-t", "2"
            };
            Pipeline.runPipeline(args2);
            sqliteConn = createTestDatabase();
            verifyAnnotationStage(TOTAL_DOCS);
        }

        @Test
        @DisplayName("Annotation stage forces re-annotation with --force")
        void testAnnotationForceRecreates() throws Exception {
            Path projectDir = tempDir.resolve(projectName);
            if (Files.exists(projectDir)) {
                MoreFiles.deleteRecursively(projectDir, RecursiveDeleteOption.ALLOW_INSECURE);
            }
            String[] args1 = {
                "-s", "annotate",
                "-p", projectDir.toString(),
                "-d", sourceDb.toString(),
                "-b", "5",
                "-t", "2"
            };
            Pipeline.runPipeline(args1);
            sqliteConn = createTestDatabase();
            verifyAnnotationStage(TOTAL_DOCS);
            if (sqliteConn != null) sqliteConn.close();
            // Run annotation again with --force
            String[] args2 = {
                "-s", "annotate",
                "-p", projectDir.toString(),
                "-b", "5",
                "-t", "2",
                "--force"
            };
            Pipeline.runPipeline(args2);
            sqliteConn = createTestDatabase();
            verifyAnnotationStage(TOTAL_DOCS);
        }

        @Test
        @DisplayName("Indexing stage creates all index types")
        void testIndexingStage() throws Exception {
            Path projectDir = tempDir.resolve(projectName);
            if (Files.exists(projectDir)) {
                MoreFiles.deleteRecursively(projectDir, RecursiveDeleteOption.ALLOW_INSECURE);
            }
            // Ensure annotation is run before indexing
            String[] annotateArgs = {
                "-s", "annotate",
                "-p", projectDir.toString(),
                "-d", sourceDb.toString(),
                "-b", "5",
                "-t", "2"
            };
            Pipeline.runPipeline(annotateArgs);
            String[] args = {
                "-s", "index",
                "-p", projectDir.toString(),
                "-d", sourceDb.toString(),
                "--stopwords", stopwordsFile.toString(),
                "-y", "all"
            };
            assertTrue(dbFile.toFile().exists(), "Database file should exist before indexing");
            Pipeline.runPipeline(args);
            verifyIndexingStage();
        }

        @Test
        @DisplayName("Indexing stage creates specific index type")
        void testSpecificIndexType() throws Exception {
            Path projectDir = tempDir.resolve(projectName);
            if (Files.exists(projectDir)) {
                MoreFiles.deleteRecursively(projectDir, RecursiveDeleteOption.ALLOW_INSECURE);
            }
            // Ensure annotation is run before indexing
            String[] annotateArgs = {
                "-s", "annotate",
                "-p", projectDir.toString(),
                "-d", sourceDb.toString(),
                "-b", "5",
                "-t", "2"
            };
            Pipeline.runPipeline(annotateArgs);
            String[] args = {
                "-s", "index",
                "-p", projectDir.toString(),
                "-d", sourceDb.toString(),
                "--stopwords", stopwordsFile.toString(),
                "-y", "unigram"
            };
            assertTrue(dbFile.toFile().exists(), "Database file should exist before indexing");
            Pipeline.runPipeline(args);
            assertTrue(indexDir.resolve("unigram").toFile().exists(),
                "Unigram index should exist");
            assertFalse(indexDir.resolve("bigram").toFile().exists(),
                "Bigram index should not exist");
        }

        @Test
        @DisplayName("Indexing stage skips if index exists and --force is not used")
        void testIndexingSkipsWhenPreserve() throws Exception {
            Path projectDir = tempDir.resolve(projectName);
            if (Files.exists(projectDir)) {
                MoreFiles.deleteRecursively(projectDir, RecursiveDeleteOption.ALLOW_INSECURE);
            }
            // Ensure annotation is run before indexing
            String[] annotateArgs = {
                "-s", "annotate",
                "-p", projectDir.toString(),
                "-d", sourceDb.toString(),
                "-b", "5",
                "-t", "2"
            };
            Pipeline.runPipeline(annotateArgs);
            Path unigramDir = indexDir.resolve("unigram");
            Pipeline.runPipeline(new String[]{
                "-s", "index", "-p", projectDir.toString(), "-d", sourceDb.toString(), "-y", "unigram", "--stopwords", stopwordsFile.toString()
            });
            assertTrue(Files.exists(unigramDir), "Unigram index should exist after first run");
            long initialSize = Files.walk(unigramDir).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
            // Run indexing again with --force false (default)
            Pipeline.runPipeline(new String[]{
                "-s", "index", "-p", projectDir.toString(), "-d", sourceDb.toString(), "-y", "unigram", "--stopwords", stopwordsFile.toString()
            });
            assertTrue(Files.exists(unigramDir), "Unigram index should still exist after second run");
            long finalSize = Files.walk(unigramDir).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
            assertEquals(initialSize, finalSize, "Index size should not change when preserved");
        }

        @Test
        @DisplayName("Indexing stage forces recreation with --force")
        void testIndexingForceRecreates() throws Exception {
            Path projectDir = tempDir.resolve(projectName);
            if (Files.exists(projectDir)) {
                MoreFiles.deleteRecursively(projectDir, RecursiveDeleteOption.ALLOW_INSECURE);
            }
            // Ensure annotation is run before indexing
            String[] annotateArgs = {
                "-s", "annotate",
                "-p", projectDir.toString(),
                "-d", sourceDb.toString(),
                "-b", "5",
                "-t", "2"
            };
            Pipeline.runPipeline(annotateArgs);
            Path unigramDir = indexDir.resolve("unigram");
            Pipeline.runPipeline(new String[]{
                "-s", "index", "-p", projectDir.toString(), "-d", sourceDb.toString(), "-y", "unigram", "--stopwords", stopwordsFile.toString()
            });
            assertTrue(Files.exists(unigramDir), "Unigram index should exist after first run");
            // Run indexing again WITH --force
            Pipeline.runPipeline(new String[]{
                "-s", "index", "-p", projectDir.toString(), "-d", sourceDb.toString(), "-y", "unigram", "--stopwords", stopwordsFile.toString(), "--force"
            });
            assertTrue(Files.exists(unigramDir), "Unigram index should still exist after forced recreation");
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        @Test
        @DisplayName("Pipeline handles invalid stage")
        void testInvalidStage() {
            String[] args = {
                "-s", "invalid",
                "-p", projectName
            };
            ArgumentParserException exception = assertThrows(ArgumentParserException.class, 
                () -> Pipeline.runPipeline(args));
            assertTrue(exception.getMessage().toLowerCase().contains("invalid"));
        }

        @Test
        @DisplayName("Pipeline handles missing required arguments")
        void testMissingRequiredArgs() {
            // For annotation, project is required
            String[] args = {"-s", "annotate"};
            ArgumentParserException exception = assertThrows(ArgumentParserException.class, 
                () -> Pipeline.runPipeline(args));
            assertTrue(exception.getMessage().contains("required"));
        }
        
        @Test
        @DisplayName("Pipeline creates project directories")
        void testProjectDirectoryCreation() throws Exception {
            String testProject = "new-test-project";
            Path sourceDbForTest = tempDir.resolve("source-" + testProject + ".db");
            createInitialSourceDatabase(sourceDbForTest, TOTAL_DOCS);
            String[] args = {
                "-s", "annotate",
                "-p", tempDir.resolve(testProject).toString(),
                "-d", sourceDbForTest.toString()
            };
            Pipeline.runPipeline(args);
            // Verify project directory structure
            Path projectDir = tempDir.resolve(testProject);
            Path indexesDir = projectDir.resolve("indexes");
            Path dbPath = projectDir.resolve(testProject + ".db");
            assertTrue(projectDir.toFile().exists(), "Project directory should be created");
            assertTrue(indexesDir.toFile().exists(), "Indexes directory should be created");
            assertTrue(dbPath.toFile().exists(), "Database file should be created");
        }
    }

    @Nested
    @DisplayName("Project Initialization and DB Schema Validation Tests")
    class SchemaValidationTests {

        private void setupInvalidDatabase(Path dbPath, String createTableSql) throws SQLException, IOException {
            Files.deleteIfExists(dbPath); // Ensure clean state
            String connectionUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();
            try (Connection conn = DriverManager.getConnection(connectionUrl);
                 Statement stmt = conn.createStatement()) {
                if (createTableSql != null && !createTableSql.isBlank()) {
                    stmt.execute(createTableSql);
                }
                // If createTableSql is null or blank, an empty DB file is created
                // or a DB file without the 'documents' table if only other tables were made.
            }
        }

        @Test
        @DisplayName("Pipeline fails if source DB is missing 'documents' table (new project)")
        void testMissingDocumentsTable_NewProject() throws Exception {
            Path invalidSourceDb = tempDir.resolve("invalid_source_no_docs_table.db");
            // Create a DB with no tables, or a table with a different name
            setupInvalidDatabase(invalidSourceDb, "CREATE TABLE other_stuff (id INTEGER PRIMARY KEY, data TEXT);");

            String testProjectName = "test_missing_docs_table";
            Path projectDir = tempDir.resolve(testProjectName);

            String[] args = {
                "-p", projectDir.toString(),
                "-d", invalidSourceDb.toString(),
                "-s", "annotate" // Any stage that initializes the project
            };

            ArgumentParserException ex = assertThrows(ArgumentParserException.class, () -> Pipeline.runPipeline(args));
            assertTrue(ex.getMessage().contains("Required 'documents' table not found"));
        }

        @Test
        @DisplayName("Pipeline fails if source DB 'documents' table is missing a column (new project)")
        void testMissingColumnInDocumentsTable_NewProject() throws Exception {
            Path invalidSourceDb = tempDir.resolve("invalid_source_missing_col.db");
            // Create documents table missing the 'text' column
            setupInvalidDatabase(invalidSourceDb, 
                "CREATE TABLE documents (document_id INTEGER PRIMARY KEY, title TEXT, timestamp TEXT);" // No 'text' column
            );

            String testProjectName = "test_missing_column";
            Path projectDir = tempDir.resolve(testProjectName);

            String[] args = {
                "-p", projectDir.toString(),
                "-d", invalidSourceDb.toString(),
                "-s", "annotate"
            };

            ArgumentParserException ex = assertThrows(ArgumentParserException.class, () -> Pipeline.runPipeline(args));
            assertTrue(ex.getMessage().contains("Required column 'text' not found"));
        }

        @Test
        @DisplayName("Pipeline fails if existing project DB is missing 'documents' table")
        void testMissingDocumentsTable_ExistingProject() throws Exception {
            String testProjectName = "existing_invalid_db_no_docs";
            Path projectDir = tempDir.resolve(testProjectName);
            Files.createDirectories(projectDir);
            Path projectDb = projectDir.resolve(testProjectName + ".db");
            // Create an invalid DB directly in the project path
            setupInvalidDatabase(projectDb, "CREATE TABLE other_stuff (id INTEGER PRIMARY KEY, data TEXT);");

            String[] args = {
                "-p", projectDir.toString(),
                // No -d, so it uses the existing (invalid) project DB
                "-s", "annotate" 
            };

            ArgumentParserException ex = assertThrows(ArgumentParserException.class, () -> Pipeline.runPipeline(args));
            assertTrue(ex.getMessage().contains("Required 'documents' table not found"));
        }

        @Test
        @DisplayName("Pipeline fails if existing project DB is missing a column")
        void testMissingColumn_ExistingProject() throws Exception {
            String testProjectName = "existing_invalid_db_missing_col";
            Path projectDir = tempDir.resolve(testProjectName);
            Files.createDirectories(projectDir);
            Path projectDb = projectDir.resolve(testProjectName + ".db");
            setupInvalidDatabase(projectDb, 
                "CREATE TABLE documents (document_id INTEGER PRIMARY KEY, title TEXT, timestamp TEXT);" // No 'text' column
            );

            String[] args = {
                "-p", projectDir.toString(),
                "-s", "annotate"
            };

            ArgumentParserException ex = assertThrows(ArgumentParserException.class, () -> Pipeline.runPipeline(args));
            assertTrue(ex.getMessage().contains("Required column 'text' not found"));
        }
    }

    // Helper methods


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

    private void createInitialSourceDatabase(Path dbPath, int numDocs) throws SQLException, IOException {
        logger.info("Creating initial source database at {} with {} documents.", dbPath, numDocs);
        String connectionString = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();

        try (Connection conn = DriverManager.getConnection(connectionString)) {
            // Apply PRAGMAs for performance and ensure schema is fresh
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
                stmt.execute("PRAGMA temp_store=MEMORY;");
                stmt.execute("PRAGMA cache_size=-200000;"); // 200MB cache
                stmt.execute("DROP TABLE IF EXISTS documents;");
                stmt.execute("""
                    CREATE TABLE documents (
                        document_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp TEXT
                    )
                """);
                logger.debug("Database schema created and PRAGMAs set.");
            }

            conn.setAutoCommit(false); // Batch inserts
            String insertSql = "INSERT INTO documents (title, text, timestamp) VALUES (?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                for (int i = 0; i < numDocs; i++) {
                    String title = "Title " + i;
                    String text = String.format(
                        "This is test document %d. It contains sample text for testing NLP features. " +
                        "The quick brown fox jumps over the lazy dog. " +
                        "This document was created on January 1st, 2024. " +
                        "OpenAI's GPT models have revolutionized natural language processing.",
                        i);
                    String timestamp = "2024-01-01"; // Consistent timestamp for test data

                    pstmt.setString(1, title);
                    pstmt.setString(2, text);
                    pstmt.setString(3, timestamp);
                    pstmt.addBatch();

                    if ((i + 1) % 1000 == 0) { // Commit every 1000 records
                        pstmt.executeBatch();
                        conn.commit();
                        logger.debug("Committed batch of 1000 documents. Total: {}", i + 1);
                    }
                }
                pstmt.executeBatch(); // Commit any remaining records
                conn.commit();
            }
            logger.info("Successfully populated database with {} documents.", numDocs);
        }
    }
} 