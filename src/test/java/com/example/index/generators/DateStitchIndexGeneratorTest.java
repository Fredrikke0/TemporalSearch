package com.example.index.generators;

import com.example.core.PositionListSoA;
import com.example.core.Position;
import com.example.index.AnnotationType;
import com.example.index.TypedAnnotationSynonymStore;
import com.example.index.StitchPosition;
import com.example.logging.ProgressTracker;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class DateStitchIndexGeneratorTest extends BaseIndexTest {
    private static final Logger logger = LoggerFactory.getLogger(DateStitchIndexGeneratorTest.class);
    private Path customSortTempPath;
    private static final String DATE_TEST_STOPWORDS_FILENAME = "test-stopwords-date.txt";

    @Override
    @BeforeEach
    protected void setUp() throws Exception {
        super.setUp();
        customSortTempPath = tempDir.resolve("customSortTempDate");
        Files.createDirectories(customSortTempPath);
        Path dateStitchPath = indexBaseDir.resolve("stitch-date");
        Files.createDirectories(dateStitchPath);
        logger.debug("Ensured directory exists: {}", dateStitchPath.toAbsolutePath());

        // Create specific stopwords file for this test
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(tempDir.resolve(DATE_TEST_STOPWORDS_FILENAME)))) {
            writer.println("is");
            writer.println("a");
        }
        
        // Minimal data, specific data population will be in test methods
        try (Statement stmt = sqliteConn.createStatement()) {
            // Ensure tables exist, data will be added per test
            stmt.execute("DELETE FROM annotations;");
            stmt.execute("DELETE FROM documents;");
        }
    }

    @Test
    void testDateStitchIndexGeneration() throws Exception {
        ProgressTracker progress = new ProgressTracker();
        DateStitchIndexGenerator generator = new DateStitchIndexGenerator(
                indexBaseDir.toString(),
                tempDir.resolve(DATE_TEST_STOPWORDS_FILENAME).toString(),
                sqliteConn,
                progress,
                10, 
                customSortTempPath
        );

        // Populate SQLite with data for this specific test
        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("INSERT INTO documents (document_id, timestamp) VALUES (1, '2023-01-15T10:00:00Z')");
            stmt.execute("INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, ner, normalized_ner) VALUES " +
                         "(1, 1, 0, 3, 'The', 'the', 'DT', 'O', null)," +
                         "(1, 1, 4, 7, 'cat', 'cat', 'NN', 'O', null)," +
                         "(1, 1, 10, 19, '2023-01-15', '2023-01-15', 'CD', 'DATE', '2023-01-15')");
            stmt.execute("INSERT INTO documents (document_id, timestamp) VALUES (2, '2023-01-16T11:00:00Z')");
            stmt.execute("INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, ner, normalized_ner) VALUES " +
                         "(2, 1, 0, 5, 'A', 'a', 'DT', 'O', null)," +
                         "(2, 1, 6, 11, 'mouse', 'mouse', 'NN', 'O', null)," +
                         "(2, 1, 14, 23, '2023-01-15', '2023-01-15', 'CD', 'DATE', '2023-01-15')");
        }

        Path indexOutputPath = null; // Define before try-finally for access in finally for verifier
        try {
            logger.info("Starting generator.generateIndex()...");
            generator.generateIndex();
            logger.info("generator.generateIndex() finished.");
            indexOutputPath = indexBaseDir.resolve(generator.getIndexName()); // Assign here after successful generation
        } finally {
            logger.info("Closing generator in finally block...");
            generator.close();
            logger.info("Generator closed in finally block.");
        }

        assertNotNull(indexOutputPath, "Index output path should have been set.");
        assertTrue(Files.exists(indexOutputPath), "Date stitch index directory ('" + generator.getIndexName() + "') should exist. Path: " + indexOutputPath.toAbsolutePath());

        Options verifyOptions = createTestOptions(); 
        verifyOptions.createIfMissing(false); // DB MUST exist

        logger.info("Attempting to open DB for verification at: {}", indexOutputPath.toAbsolutePath());
        try (DB db = Iq80DBFactory.factory.open(indexOutputPath.toFile(), verifyOptions)) {
            logger.info("Successfully opened DB for verification.");
            
            byte[] catBytes = db.get(Iq80DBFactory.bytes("cat"));
            assertNotNull(catBytes, "Entry for unigram 'cat' should exist.");
            PositionListSoA plCat = PositionListSoA.deserializeFromCompositeBlob(catBytes);
            
            // Use TypedAnnotationSynonymStore for verification, pointing to the specific index's directory
            TypedAnnotationSynonymStore verifierSynonyms = new TypedAnnotationSynonymStore(indexOutputPath, AnnotationType.DATE);
            try {
                // The synonym store loads itself if the file exists. If generation was correct, it will have the needed IDs.
                // No need to manually populate it from DB again for verification if it was created by the generator.

                int dateSynonymId1 = verifierSynonyms.getOrCreateId("2023-01-15"); // No type needed

                // Look for StitchPosition by checking for the correct document and the expected synonym ID
                // Since PositionListSoA returns base Position objects, we need to check synonym IDs separately
                Optional<StitchPosition> catStitched = Optional.empty();
                for (int i = 0; i < plCat.getNumPositions(); i++) {
                    Position p = plCat.getPositionAt(i);
                    int synonymId = plCat.getSynonymIdAt(i);
                    if (p.getDocumentId() == 1 && synonymId == dateSynonymId1) {
                        // Create a StitchPosition for verification
                        StitchPosition sp = new StitchPosition(
                            p.getDocumentId(), p.getSentenceId(), p.getBeginPosition(), p.getEndPosition(),
                            AnnotationType.DATE, synonymId, 10, 19  // annotation begin/end chars
                        );
                        catStitched = Optional.of(sp);
                        break;
                    }
                }
                assertTrue(catStitched.isPresent(), "'cat' should be stitched with 2023-01-15 in Doc 1.");
                assertEquals(4, catStitched.get().getBeginPosition(), "cat unigram beginChar");
                assertEquals(7, catStitched.get().getEndPosition(), "cat unigram endChar");
                assertEquals(10, catStitched.get().getAnnotationBeginChar(), "2023-01-15 annotation beginChar in Doc 1");
                assertEquals(19, catStitched.get().getAnnotationEndChar(), "2023-01-15 annotation endChar in Doc 1");

                byte[] mouseBytes = db.get(Iq80DBFactory.bytes("mouse"));
                assertNotNull(mouseBytes, "Entry for unigram 'mouse' should exist.");
                PositionListSoA plMouse = PositionListSoA.deserializeFromCompositeBlob(mouseBytes);
                // Look for StitchPosition for mouse
                Optional<StitchPosition> mouseStitched = Optional.empty();
                for (int i = 0; i < plMouse.getNumPositions(); i++) {
                    Position p = plMouse.getPositionAt(i);
                    int synonymId = plMouse.getSynonymIdAt(i);
                    if (p.getDocumentId() == 2 && synonymId == dateSynonymId1) {
                        // Create a StitchPosition for verification
                        StitchPosition sp = new StitchPosition(
                            p.getDocumentId(), p.getSentenceId(), p.getBeginPosition(), p.getEndPosition(),
                            AnnotationType.DATE, synonymId, 14, 23  // annotation begin/end chars for doc 2
                        );
                        mouseStitched = Optional.of(sp);
                        break;
                    }
                }
                assertTrue(mouseStitched.isPresent(), "'mouse' should be stitched with 2023-01-15 in Doc 2.");
            } finally {
                verifierSynonyms.close();
            }
        } catch (IOException e) {
            logger.error("IOException during DB verification: {}", e.getMessage(), e);
            throw e;
        }
    }
} 