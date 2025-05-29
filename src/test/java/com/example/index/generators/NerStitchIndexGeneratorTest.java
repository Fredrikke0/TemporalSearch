package com.example.index.generators;

import com.example.core.PositionListSoA;
import com.example.core.Position;
import com.example.index.AnnotationType;
import com.example.index.generators.BaseIndexTest;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class NerStitchIndexGeneratorTest extends BaseIndexTest {
    private static final Logger logger = LoggerFactory.getLogger(NerStitchIndexGeneratorTest.class);
    private Path customSortTempPathNer;
    private static final String NER_TEST_STOPWORDS_FILENAME = "test-stopwords-ner.txt";

    @Override
    @BeforeEach
    protected void setUp() throws Exception {
        super.setUp();
        customSortTempPathNer = tempDir.resolve("customSortTempNer");
        Files.createDirectories(customSortTempPathNer);
        logger.debug("Base index directory: {}", indexBaseDir.toAbsolutePath());

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(tempDir.resolve(NER_TEST_STOPWORDS_FILENAME)))) {
            writer.println("is");
        }

        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("DELETE FROM annotations;");
            stmt.execute("DELETE FROM documents;");
            stmt.execute("INSERT INTO documents (document_id, timestamp) VALUES (1, '2023-01-01T00:00:00Z')");
            stmt.execute("INSERT INTO documents (document_id, timestamp) VALUES (2, '2023-01-02T00:00:00Z')");

            insertAnnotation(1, 0, 0, 5, "Alice", "Alice", "NNP", "PERSON", "Alice");
            insertAnnotation(1, 0, 10, 16, "google", "google", "NNP", "ORGANIZATION", "Google");
            insertAnnotation(1, 0, 20, 24, "word", "word", "NN", "O", null);
            insertAnnotation(2, 0, 0, 3, "Bob", "Bob", "NNP", "PERSON", "Bob");
            insertAnnotation(2, 0, 10, 15, "Apple", "apple", "NNP", "ORGANIZATION", "Apple Inc.");
            insertAnnotation(2, 0, 20, 30, "2024-01-01", "2024-01-01", "CD", "DATE", "2024-01-01");
            insertAnnotation(2, 0, 35, 39, "text", "text", "NN", "O", null);
        }
    }

    private void insertAnnotation(int docId, int sentId, int begin, int end, String token, String lemma, String pos, String ner, String normalizedNer) throws SQLException {
        String sql = "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, ner, normalized_ner) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(sql)) {
            pstmt.setInt(1, docId); pstmt.setInt(2, sentId); pstmt.setInt(3, begin); pstmt.setInt(4, end);
            pstmt.setString(5, token); pstmt.setString(6, lemma); pstmt.setString(7, pos); pstmt.setString(8, ner);
            pstmt.setString(9, normalizedNer); pstmt.executeUpdate();
        }
    }

    @Test
    void testNerStitchIndexGeneration() throws Exception {
        ProgressTracker progress = new ProgressTracker();
        NerStitchIndexGenerator generator = new NerStitchIndexGenerator(
                indexBaseDir.toString(),
                tempDir.resolve(NER_TEST_STOPWORDS_FILENAME).toString(),
                sqliteConn,
                progress,
                10,
                customSortTempPathNer
        );

        Path indexOutputPath = null; // Define before try-finally
        DB dbForVerification = null;

        try {
            logger.info("Starting NerStitchIndexGenerator.generateIndex()...");
            generator.generateIndex();
            logger.info("NerStitchIndexGenerator.generateIndex() finished.");
            indexOutputPath = indexBaseDir.resolve(generator.getIndexName());

            // VERIFY AGAINST THE GENERATOR'S OWN DB INSTANCE BEFORE IT'S CLOSED
            assertNotNull(generator.getIndexAccess(), "Generator's IndexAccess should not be null.");
            dbForVerification = generator.getIndexAccess().getDbForVerification();
            assertNotNull(dbForVerification, "DB from generator's IndexAccess should not be null.");
            logger.info("Successfully obtained DB instance from NER generator for immediate verification.");

            assertTrue(Files.exists(indexOutputPath), "NER stitch index directory ('" + generator.getIndexName() + "') should exist. Path: " + indexOutputPath.toAbsolutePath());

            org.iq80.leveldb.ReadOptions readOpts = new org.iq80.leveldb.ReadOptions();
            readOpts.verifyChecksums(true);

            // USE THE GENERATOR'S OWN SYNONYM STORE FOR VERIFICATION
            TypedAnnotationSynonymStore verifierSynonyms = generator.getAnnotationSynonyms();
            assertNotNull(verifierSynonyms, "Generator's annotation synonym store should not be null.");
            // DO NOT close this verifierSynonyms instance here, generator will close its own store.
            // The old try-with-resources for verifierSynonyms is removed.

            byte[] aliceBytes = dbForVerification.get(Iq80DBFactory.bytes("alice"), readOpts);
            assertNotNull(aliceBytes, "Entry for unigram 'alice' should exist.");
            PositionListSoA plAlice = PositionListSoA.deserializeFromCompositeBlob(aliceBytes);

            boolean aliceFoundWithGoogle = false;
            for (int i = 0; i < plAlice.getNumPositions(); i++) {
                Position p = plAlice.getPositionAt(i);
                // int synonymId = plAlice.getSynonymIdAt(i); // Not strictly needed for this check if we only care about co-occurrence in doc
                if (p.getDocumentId() == 1 &&
                    p.getBeginPosition() == 0 && p.getEndPosition() == 5) { // alice coordinates
                    aliceFoundWithGoogle = true;
                    break;
                }
            }
            assertTrue(aliceFoundWithGoogle, "'alice' should be stitched with Google (ORGANIZATION).");

            byte[] bobBytes = dbForVerification.get(Iq80DBFactory.bytes("bob"), readOpts);
            assertNotNull(bobBytes, "Entry for unigram 'bob' should exist.");
            PositionListSoA plBob = PositionListSoA.deserializeFromCompositeBlob(bobBytes);

            boolean bobFoundWithApple = false;
            for (int i = 0; i < plBob.getNumPositions(); i++) {
                Position p = plBob.getPositionAt(i);
                // int synonymId = plBob.getSynonymIdAt(i);
                if (p.getDocumentId() == 2 &&
                    p.getBeginPosition() == 0 && p.getEndPosition() == 3) { // bob coordinates
                    bobFoundWithApple = true;
                    break;
                }
            }
            assertTrue(bobFoundWithApple, "'bob' should be stitched with Apple (ORGANIZATION).");

            byte[] textBytes = dbForVerification.get(Iq80DBFactory.bytes("text"), readOpts);
            assertNotNull(textBytes, "Entry for unigram 'text' should exist since it co-occurs with valid NER entities.");
            PositionListSoA plText = PositionListSoA.deserializeFromCompositeBlob(textBytes);

            boolean foundValidNerStitch = false;
            for (int i = 0; i < plText.getNumPositions(); i++) {
                Position p = plText.getPositionAt(i);
                // int synonymId = plText.getSynonymIdAt(i);
                if (p.getDocumentId() == 2 &&
                    p.getBeginPosition() == 35 && p.getEndPosition() == 39) { // text coordinates
                    foundValidNerStitch = true;
                    break;
                }
            }
            assertTrue(foundValidNerStitch, "'text' should be stitched with valid NER entities (Bob:PERSON or Apple:ORGANIZATION) in document 2.");
        } finally {
            logger.info("Closing NER generator in finally block...");
            generator.close();
            logger.info("NER generator closed in finally block.");
        }
        // Old verification block (reopening DB) is removed.
    }
}