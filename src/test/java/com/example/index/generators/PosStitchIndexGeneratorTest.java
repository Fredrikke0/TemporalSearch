package com.example.index.generators;

import com.example.core.PositionList;
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
        Path posStitchPath = indexBaseDir.resolve("stitch-pos");
        Files.createDirectories(posStitchPath);
        logger.debug("Ensured directory exists: {}", posStitchPath.toAbsolutePath());

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
        
        Path posStitchPath = indexBaseDir.resolve(PosStitchIndexGenerator.MY_INDEX_NAME);

        try {
            generator.generateIndex();
        } finally {
            if (generator != null) {
                generator.close(); // Generator's synonym store is saved here.
            }
        }
        
        // Initialize verification synonym store AFTER the generator has run and closed.
        TypedAnnotationSynonymStore verificationSynonymStore = new TypedAnnotationSynonymStore(posStitchPath, AnnotationType.POS);
        
        assertTrue(Files.exists(posStitchPath), "POS stitch index directory ('" + PosStitchIndexGenerator.MY_INDEX_NAME + "') should exist. Path: " + posStitchPath.toAbsolutePath());

        Options options = createTestOptions();
        options.createIfMissing(false);

        try (DB db = Iq80DBFactory.factory.open(posStitchPath.toFile(), options)) {
            // Test 1: "quick" (JJ) stitched with "fox" (NN) in Doc 1, Sent 0
            byte[] quickBytes = db.get(Iq80DBFactory.bytes("quick"));
            assertNotNull(quickBytes, "Entry for unigram 'quick' should exist.");
            PositionList plQuick = PositionList.deserialize(quickBytes);
            
            int nnFoxSynonymId = verificationSynonymStore.getOrCreateId("NN" + com.example.core.IndexAccessInterface.DELIMITER + "fox");
            Optional<StitchPosition> quickStitchedWithFoxNN = plQuick.getPositions().stream()
                .filter(p -> p instanceof StitchPosition).map(p -> (StitchPosition) p)
                .filter(sp -> sp.getDocumentId() == 1 && sp.getSynonymId() == nnFoxSynonymId && 
                               sp.getType() == AnnotationType.POS && 
                               sp.getAnnotationBeginChar() == 12 && sp.getAnnotationEndChar() == 15) // fox
                .findFirst();
            assertTrue(quickStitchedWithFoxNN.isPresent(), "'quick' (JJ) should be stitched with 'fox' (NN).");
            assertEquals(0, quickStitchedWithFoxNN.get().getBeginPosition()); // quick
            assertEquals(5, quickStitchedWithFoxNN.get().getEndPosition());

            // Test 2: "jumps" (VBZ) stitched with "brown" (JJ) in Doc 1, Sent 0
            byte[] jumpsBytes = db.get(Iq80DBFactory.bytes("jump")); // lemma
            assertNotNull(jumpsBytes, "Entry for unigram 'jump' should exist.");
            PositionList plJumps = PositionList.deserialize(jumpsBytes);

            int jjBrownSynonymId = verificationSynonymStore.getOrCreateId("JJ" + com.example.core.IndexAccessInterface.DELIMITER + "brown"); 
            Optional<StitchPosition> jumpStitchedWithBrownJJ = plJumps.getPositions().stream()
                .filter(p -> p instanceof StitchPosition).map(p -> (StitchPosition) p)
                .filter(sp -> sp.getDocumentId() == 1 && sp.getSynonymId() == jjBrownSynonymId && 
                               sp.getType() == AnnotationType.POS && 
                               sp.getAnnotationBeginChar() == 6 && sp.getAnnotationEndChar() == 11) // brown
                .findFirst();
            assertTrue(jumpStitchedWithBrownJJ.isPresent(), "'jump' (VBZ) should be stitched with 'brown' (JJ).");

            // Test 3: Check that "fox" (NN) is NOT stitched with "." (PUNCT)
            byte[] foxBytes = db.get(Iq80DBFactory.bytes("fox"));
            assertNotNull(foxBytes, "Entry for unigram 'fox' should exist.");
            PositionList plFox = PositionList.deserialize(foxBytes);
            
            boolean foxStitchedWithPunct = plFox.getPositions().stream()
                .filter(p -> p instanceof StitchPosition).map(p -> (StitchPosition)p)
                .anyMatch(sp -> sp.getDocumentId() == 1 && 
                                 sp.getType() == AnnotationType.POS && 
                                 sp.getAnnotationBeginChar() == 21 && sp.getAnnotationEndChar() == 22 ); // coords of PUNCT
            assertFalse(foxStitchedWithPunct, "'fox' (NN) should NOT be stitched with '.' (PUNCT).");
        } finally {
            if (verificationSynonymStore != null) {
                verificationSynonymStore.close();
            }
        }
    }
} 