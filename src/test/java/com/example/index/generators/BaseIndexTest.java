package com.example.index.generators;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import org.iq80.leveldb.Options;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.io.*;

import com.example.logging.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base test class providing common utilities for index testing.
 * Handles database setup, cleanup, and common test data creation.
 */
public abstract class BaseIndexTest {
    private static final Logger logger = LoggerFactory.getLogger(BaseIndexTest.class);
    protected Path tempDir;
    protected Path indexBaseDir;
    protected Connection sqliteConn;
    protected static final String TEST_STOPWORDS_PATH = "test-stopwords.txt";

    @BeforeEach
    protected void setUp() throws Exception {
        // Create temp directory
        tempDir = Files.createTempDirectory("index-test-");
        logger.info("Created temp directory: {}", tempDir);

        // Create index base directory
        indexBaseDir = tempDir.resolve("indexes");
        Files.createDirectories(indexBaseDir);
        logger.info("Created index directory: {}", indexBaseDir);

        // Set up SQLite
        sqliteConn = createTestDatabase();
        createBasicTables();
        logger.info("Created test database at: {}", tempDir.resolve("test.db"));

        // Create test stopwords file
        try (PrintWriter writer = new PrintWriter(TEST_STOPWORDS_PATH)) {
            writer.println("the");
            writer.println("a");
            writer.println("is");
        }
        logger.info("Created stopwords file: {}", TEST_STOPWORDS_PATH);
    }

    @AfterEach
    protected void tearDown() throws Exception {
        logger.info("Starting test cleanup...");

        // Close SQLite connection
        if (sqliteConn != null && !sqliteConn.isClosed()) {
            sqliteConn.close();
            sqliteConn = null;
            logger.info("Closed SQLite connection");
        }

        // Delete test files
        if (tempDir.toFile().exists()) {
            try {
                MoreFiles.deleteRecursively(tempDir, RecursiveDeleteOption.ALLOW_INSECURE);
                logger.info("Deleted temp directory: {}", tempDir);
            } catch (IOException e) {
                logger.warn("Could not delete temp directory: {}", e.getMessage());
            }
        }

        File stopwordsFile = new File(TEST_STOPWORDS_PATH);
        if (stopwordsFile.exists() && stopwordsFile.delete()) {
            logger.info("Deleted stopwords file: {}", TEST_STOPWORDS_PATH);
        }

        // Reset instance variables
        tempDir = null;
        indexBaseDir = null;
        sqliteConn = null;
    }

    protected Connection createTestDatabase() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test.db"));
    }

    protected void createBasicTables() throws Exception {
        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("""
                CREATE TABLE documents (
                    document_id INTEGER PRIMARY KEY,
                    timestamp TEXT NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE annotations (
                    annotation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    document_id INTEGER NOT NULL,
                    sentence_id INTEGER,
                    begin_char INTEGER,
                    end_char INTEGER,
                    token TEXT,
                    lemma TEXT,
                    pos TEXT,
                    ner TEXT,
                    normalized_ner TEXT,
                    FOREIGN KEY (document_id) REFERENCES documents(document_id)
                )
            """);

            stmt.execute("""
                CREATE TABLE dependencies (
                    dependency_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    document_id INTEGER NOT NULL,
                    sentence_id INTEGER,
                    begin_char INTEGER,
                    end_char INTEGER,
                    head_token TEXT,
                    dependent_token TEXT,
                    relation TEXT,
                    FOREIGN KEY (document_id) REFERENCES documents(document_id)
                )
            """);
        }
    }

    /**
     * Creates optimized LevelDB options for testing.
     * @return Options configured for testing
     */
    protected Options createTestOptions() {
        Options options = new Options();
        options.createIfMissing(true);
        options.errorIfExists(false);
        options.cacheSize(16 * 1024 * 1024); // 16MB cache for testing
        options.writeBufferSize(4 * 1024 * 1024); // 4MB write buffer
        options.blockSize(4 * 1024); // 4KB block size
        options.compressionType(org.iq80.leveldb.CompressionType.SNAPPY);
        return options;
    }

    /**
     * Gets the path for a specific index type.
     * @param indexType The type of index
     * @return Path to the index directory
     */
    protected Path getIndexPath(String indexType) {
        return indexBaseDir.resolve(indexType);
    }
}