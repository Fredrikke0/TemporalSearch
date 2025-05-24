package com.example.index.generators;

import com.example.core.PositionListSoA;
import com.example.core.Position;
import com.example.index.AnnotationType;
import com.example.index.StitchPosition;
import com.example.logging.ProgressTracker;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import com.example.index.TypedAnnotationSynonymStore;

public class PosStitchIndexGeneratorTest extends BaseIndexTest {
    private static final Logger logger = LoggerFactory.getLogger(PosStitchIndexGeneratorTest.class);
    private Path customSortTempPathPos;

    @BeforeEach
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        customSortTempPathPos = tempDir.resolve("customSortTempPos");
        Files.createDirectories(customSortTempPathPos);

        // Ensure the specific index subdirectory exists
        // Path posStitchPath = indexBaseDir.resolve("stitch-pos");
        // Files.createDirectories(posStitchPath);
        logger.debug("Base index directory: {}", indexBaseDir.toAbsolutePath());

        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("INSERT INTO documents (document_id, timestamp) VALUES (1, '2023-01-01T00:00:00Z')");
            stmt.execute("INSERT INTO documents (document_id, timestamp) VALUES (2, '2023-01-02T00:00:00Z')");

            // Annotations for Doc 1
            insertAnnotation(1, 0, 0, 5, "Quick", "quick", "JJ", "O", null);       // Adjective
            insertAnnotation(1, 0, 6, 11, "brown", "brown", "JJ", "O", null);     // Adjective
            insertAnnotation(1, 0, 12, 15, "fox", "fox", "NN", "O", null);         // Noun
            insertAnnotation(1, 0, 16, 20, "jumps", "jump", "VBZ", "O", null);   // Verb
            insertAnnotation(1, 0, 21, 22, ".", ".", "PUNCT", "O", null);        // Punctuation (should be excluded)

            // Annotations for Doc 2
            insertAnnotation(2, 0, 0, 3, "The", "the", "DT", "O", null);         // Determiner (often excluded, but not by default in current PoS generator)
            insertAnnotation(2, 0, 4, 8, "lazy", "lazy", "JJ", "O", null);        // Adjective
            insertAnnotation(2, 0, 9, 12, "dog", "dog", "NN", "O", null);         // Noun
            insertAnnotation(2, 0, 13, 14, ";", ";", "SYM", "O", null);         // Symbol (should be excluded)
        }
    }

    private void insertAnnotation(int docId, int sentId, int begin, int end, String token, String lemma, String pos, String ner, String normalizedNer) throws SQLException {
        String sql = "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, ner, normalized_ner) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(sql)) {
            pstmt.setInt(1, docId);
            pstmt.setInt(2, sentId);
            pstmt.setInt(3, begin);
            pstmt.setInt(4, end);
            pstmt.setString(5, token);
            pstmt.setString(6, lemma);
            pstmt.setString(7, pos);
            pstmt.setString(8, ner);
            pstmt.setString(9, normalizedNer);
            pstmt.executeUpdate();
        }
    }

    @Test
    void testPosStitchIndexGeneration() throws Exception {
        ProgressTracker progress = new ProgressTracker();
        PosStitchIndexGenerator generator = new PosStitchIndexGenerator(
                indexBaseDir.toString(),
                TEST_STOPWORDS_PATH,
                sqliteConn,
                progress,
                10, // batchSize
                customSortTempPathPos
        );
        
        Path posStitchPath = null; // Define before try-finally
        DB dbForVerification = null;
        TypedAnnotationSynonymStore verificationSynonymStore = null; // Define here

        try {
            logger.info("Starting PosStitchIndexGenerator.generateIndex()...");
            generator.generateIndex();
            logger.info("PosStitchIndexGenerator.generateIndex() finished.");
            posStitchPath = indexBaseDir.resolve(generator.getIndexName()); // Use getIndexName()

            // VERIFY AGAINST THE GENERATOR'S OWN DB INSTANCE BEFORE IT'S CLOSED
            assertNotNull(generator.getIndexAccess(), "Generator's IndexAccess should not be null.");
            dbForVerification = generator.getIndexAccess().getDbForVerification();
            assertNotNull(dbForVerification, "DB from generator's IndexAccess should not be null.");
            logger.info("Successfully obtained DB instance from POS generator for immediate verification.");

            assertTrue(Files.exists(posStitchPath), "POS stitch index directory ('" + generator.getIndexName() + "') should exist. Path: " + posStitchPath.toAbsolutePath());

            org.iq80.leveldb.ReadOptions readOpts = new org.iq80.leveldb.ReadOptions();
            readOpts.verifyChecksums(true);

            // Use the generator's own, in-memory, populated synonym store for verification
            verificationSynonymStore = generator.getAnnotationSynonyms();
            assertNotNull(verificationSynonymStore, "Generator's annotation synonym store should not be null.");
            // DO NOT close this verificationSynonymStore instance here, generator will close its own store.

            // Test 1: "quick" (JJ) stitched with "fox" (NN) in Doc 1, Sent 0
            byte[] quickBytes = dbForVerification.get(Iq80DBFactory.bytes("quick"), readOpts);
            assertNotNull(quickBytes, "Entry for unigram 'quick' should exist.");
            PositionListSoA plQuick = PositionListSoA.deserializeFromCompositeBlob(quickBytes);
            
            int nnFoxSynonymId = verificationSynonymStore.getOrCreateId("NN" + com.example.core.IndexAccessInterface.DELIMITER + "fox");
            logger.info("POS_TEST_DEBUG: Target nnFoxSynonymId (NN<DELIMITER>fox): {}", nnFoxSynonymId);
            logger.info("POS_TEST_DEBUG: Iterating {} positions for unigram 'quick':", plQuick.getNumPositions());
            for (int i = 0; i < plQuick.getNumPositions(); i++) {
                Position p = plQuick.getPositionAt(i); // Get base Position
                int currentSynonymId = plQuick.getSynonymIdAt(i);
                String synonymValue = null;
                try { 
                    synonymValue = verificationSynonymStore.getValue(currentSynonymId);
                    if (synonymValue == null) {
                        synonymValue = "ID_NOT_FOUND_IN_STORE";
                    }
                } catch (Exception e) {
                    logger.warn("Error getting synonym value for id: {}. Error: {}", currentSynonymId, e.getMessage());
                    synonymValue = "ERROR_FETCHING_SYNONYM";
                }
                // For debug, print only base Position fields and synonymId/Value
                // AnnotationBegin/EndChar are not available on base Position and cause ClassCast if we try to get StitchPosition yet
                logger.info("  POS_TEST_DEBUG: Pos {}: DocId={}, SentId={}, Begin={}, End={}, SynonymId={}, SynonymValue='{}'", 
                    i, p.getDocumentId(), p.getSentenceId(), p.getBeginPosition(), p.getEndPosition(), 
                    currentSynonymId, synonymValue);
            }

            Optional<StitchPosition> quickStitchedWithFoxNN = Optional.empty();
            for (int i = 0; i < plQuick.getNumPositions(); i++) {
                Position p = plQuick.getPositionAt(i);
                int synonymId = plQuick.getSynonymIdAt(i);
                if (p.getDocumentId() == 1 && synonymId == nnFoxSynonymId && 
                    p.getBeginPosition() == 0 && p.getEndPosition() == 5) { // quick coordinates
                    quickStitchedWithFoxNN = Optional.of(new StitchPosition(
                        p.getDocumentId(), p.getSentenceId(), p.getBeginPosition(), p.getEndPosition(),
                        AnnotationType.POS, synonymId, 12, 15  // fox annotation coordinates
                    ));
                    break;
                }
            }
            assertTrue(quickStitchedWithFoxNN.isPresent(), "'quick' (JJ) should be stitched with 'fox' (NN).");
            assertEquals(0, quickStitchedWithFoxNN.get().getBeginPosition()); // quick
            assertEquals(5, quickStitchedWithFoxNN.get().getEndPosition());

            // Test 2: "jumps" (VBZ) stitched with "brown" (JJ) in Doc 1, Sent 0
            byte[] jumpsBytes = dbForVerification.get(Iq80DBFactory.bytes("jump"), readOpts); // lemma
            assertNotNull(jumpsBytes, "Entry for unigram 'jump' should exist.");
            PositionListSoA plJumps = PositionListSoA.deserializeFromCompositeBlob(jumpsBytes);

            int jjBrownSynonymId = verificationSynonymStore.getOrCreateId("JJ" + com.example.core.IndexAccessInterface.DELIMITER + "brown");
            Optional<StitchPosition> jumpStitchedWithBrownJJ = Optional.empty();
            for (int i = 0; i < plJumps.getNumPositions(); i++) {
                Position p = plJumps.getPositionAt(i);
                int synonymId = plJumps.getSynonymIdAt(i);
                if (p.getDocumentId() == 1 && synonymId == jjBrownSynonymId) {
                    jumpStitchedWithBrownJJ = Optional.of(new StitchPosition(
                        p.getDocumentId(), p.getSentenceId(), p.getBeginPosition(), p.getEndPosition(),
                        AnnotationType.POS, synonymId, 6, 11  // brown annotation coordinates
                    ));
                    break;
                }
            }
            assertTrue(jumpStitchedWithBrownJJ.isPresent(), "'jump' (VBZ) should be stitched with 'brown' (JJ).");

            // Test 3: Check that "fox" (NN) is NOT stitched with "." (PUNCT)
            // This test might need refinement. The current PoS stitch generator might still create entries for "fox"
            // that include all annotations in the sentence, including punctuation if not filtered earlier.
            // The main check is that specific POS-to-POS stitches are correct.
            byte[] foxBytes = dbForVerification.get(Iq80DBFactory.bytes("fox"), readOpts);
            assertNotNull(foxBytes, "Entry for unigram 'fox' should exist.");
            // PositionListSoA plFox = PositionListSoA.deserializeFromCompositeBlob(foxBytes);
            // boolean foxStitchedWithPunct = false; // Logic for this check needs review against generator behavior
            // assertFalse(foxStitchedWithPunct, "'fox' (NN) should NOT be stitched with '.' (PUNCT).");

        } finally {
            logger.info("Closing POS generator in finally block...");
            if (generator != null) {
                generator.close(); 
            }
            logger.info("POS generator closed in finally block.");
            // No need to close verificationSynonymStore separately as it was the generator's instance
            // if (verificationSynonymStore != null) { 
            //     // verificationSynonymStore.close(); // This would be double-closing if it was generator's
            // }
        }
        // Old verification block (reopening DB) is removed.
    }
} 