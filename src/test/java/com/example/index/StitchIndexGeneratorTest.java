package com.example.index;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyLong;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.logging.ProgressTracker;
import com.example.core.IndexAccess;
import com.example.core.PositionList;
import com.example.index.StitchEntry;
import com.example.index.StitchIndexGenerator;
import com.example.index.StitchPosition;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.logging.ProgressTracker;
import org.junit.jupiter.api.io.TempDir;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@ExtendWith(MockitoExtension.class)
class StitchIndexGeneratorTest extends BaseIndexTest {
    private static final Logger logger = LoggerFactory.getLogger(StitchIndexGeneratorTest.class);
    private Path stopwordsPath;
    private StitchIndexGenerator generator;

    @Mock
    private ProgressTracker progress;

    @TempDir
    Path tempDir;
    
    @Mock
    private Connection mockConnection;
    
    @Mock
    private PreparedStatement mockPreparedStatement;
    
    @Mock
    private Statement mockStatement;
    
    @Mock
    private ResultSet mockResultSet;

    @BeforeEach
    @Override
    void setUp() throws Exception {
        // Call parent setup
        super.setUp();
        
        // Create stopwords path
        stopwordsPath = tempDir.resolve("stopwords.txt");

        // Create empty stopwords file
        Files.writeString(stopwordsPath, "the\na\nan\n");

        // Create generator with new index base directory
        generator = new StitchIndexGenerator(
            indexBaseDir.toString(),
            stopwordsPath.toString(),
            sqliteConn,
            progress,
            1000
        );
    }

    @AfterEach
    void cleanUpGenerator() throws Exception {
        // Call parent tearDown 
        if (generator != null) {
            try {
                generator.close();
                logger.info("Closed generator in cleanUpGenerator");
            } catch (Exception e) {
                logger.warn("Error closing generator: {}", e.getMessage());
            } finally {
                generator = null;
            }
        }
    }

    private void insertBasicTestData() throws SQLException {
        try (Statement stmt = sqliteConn.createStatement()) {
            // Insert document
            stmt.execute("INSERT INTO documents (document_id, timestamp) VALUES (1, '2024-03-20')");
            
            // Insert all tokens with their POS tags
            stmt.execute("""
                INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos)
                VALUES 
                (1, 1, 0, 7, 'Company', 'company', 'NN'),
                (1, 1, 8, 15, 'founded', 'found', 'VBD'),
                (1, 1, 16, 18, 'in', 'in', 'IN'),
                (1, 1, 19, 24, 'March', 'March', 'NNP'),
                (1, 1, 25, 29, '2024', '2024', 'CD')
            """);
            
            // Add NER date annotation for "March 2024"
            stmt.execute("""
                UPDATE annotations 
                SET ner = 'DATE', normalized_ner = '2024-03-01'
                WHERE document_id = 1 AND sentence_id = 1 AND token IN ('March', '2024')
            """);
            
            // Insert dependency relations
            stmt.execute("""
                INSERT INTO dependencies (document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation)
                VALUES 
                (1, 1, 0, 15, 'Company', 'founded', 'nsubj'),
                (1, 1, 8, 29, 'founded', 'March', 'tmod')
            """);
        }
    }

    @Test
    void testFetchBatch() throws SQLException {
        // Insert test data
        insertBasicTestData();

        // Fetch batch
        List<StitchEntry> entries = generator.fetchBatch(0);

        // Verify results
        assertEquals(40, entries.size(), "Expected 40 stitch entries");
        
        // Verify DATE stitch entry is present
        boolean foundDateStitch = false;
        for (StitchEntry entry : entries) {
            if (entry.type() == AnnotationType.DATE) {
                if (entry.value().equals("march")) {
                    foundDateStitch = true;
                    assertEquals(1, entry.documentId());
                    assertEquals(1, entry.sentenceId());
                    assertEquals(LocalDate.parse("2024-03-20"), entry.timestamp());
                    assertEquals(1, entry.synonymId()); // The synonymId should be 1 for the date
                    break;
                }
            }
        }
        assertTrue(foundDateStitch, "Expected to find at least one DATE stitch entry for 'march'");
    }

    @Test
    void testProcessBatch() throws IOException {
        // Create test entries with both unigram value, annotation type, and synonym ID
        List<StitchEntry> batch = List.of(
            new StitchEntry(1, 1, 0, 20, LocalDate.parse("2024-03-20"), "company", AnnotationType.DATE, 1),
            new StitchEntry(1, 1, 25, 45, LocalDate.parse("2024-03-20"), "founded", AnnotationType.DATE, 2)
        );

        // Process batch
        var result = generator.processBatch(batch);

        // Verify results
        assertEquals(2, result.keySet().size(), "Expected entries for both unigrams");
        assertTrue(result.containsKey("company\0DATE"));
        assertTrue(result.containsKey("founded\0DATE"));

        // Verify positions for 'company' including associated date
        var companyPositions = result.get("company\0DATE").get(0);
        assertEquals(1, companyPositions.getPositions().size(), "Expected one position for company");
        
        var companyPos = companyPositions.getPositions().get(0);
        assertEquals(0, companyPos.getBeginPosition());
        assertEquals(20, companyPos.getEndPosition());
        assertEquals(1, ((StitchPosition)companyPos).getSynonymId());
        
        // Verify positions for 'founded' including associated date
        var foundedPositions = result.get("founded\0DATE").get(0);
        assertEquals(1, foundedPositions.getPositions().size(), "Expected one position for founded");
        
        var foundedPos = foundedPositions.getPositions().get(0);
        assertEquals(25, foundedPos.getBeginPosition());
        assertEquals(45, foundedPos.getEndPosition());
        assertEquals(2, ((StitchPosition)foundedPos).getSynonymId());
    }

    @Test
    void testGenerateIndex() throws Exception {
        // Insert test data
        insertBasicTestData();

        try {
            // Generate index
            generator.generateIndex();
            
            // Close the generator to release the LevelDB lock before creating the IndexAccess
            generator.close();
            generator = null;
            
            // Create a new IndexAccess instance for verification
            try (IndexAccess indexAccess = new IndexAccess(indexBaseDir, "stitch", createTestOptions())) {
                // Verify index contents for 'company\0DATE'
                var companyPositions = indexAccess.get("company\0DATE".getBytes());
                assertTrue(companyPositions.isPresent(), "Expected positions for 'company\0DATE'");
                
                PositionList positions = companyPositions.get();
                assertEquals(1, positions.getPositions().size(), "Expected one stitch position");
                
                // Verify the stitch position properties
                var pos = positions.getPositions().get(0);
                // Verify the position matches what we expect
                assertEquals(0, pos.getBeginPosition());
                assertEquals(7, pos.getEndPosition()); // Now using the unigram's end position
                
                // Verify the synonym ID is correctly associated
                if (pos instanceof StitchPosition) {
                    StitchPosition stitchPos = (StitchPosition) pos;
                    assertEquals(1, stitchPos.getSynonymId(), "Expected synonym ID 1 for 2024-03-01");
                    assertEquals(AnnotationType.DATE, stitchPos.getType(), "Expected DATE annotation type");
                    logger.info("Found StitchPosition with synonymId: {}", stitchPos.getSynonymId());
                } else {
                    fail("Position is not a StitchPosition, but a " + pos.getClass().getSimpleName());
                }

                // Count the total entries in the index
                int entryCount = 0;
                for (AnnotationType type : AnnotationType.values()) {
                    for (String unigram : new String[]{"Company", "founded", "in", "March", "2024"}) {
                        String key = unigram + "\0" + type;
                        var positions2 = indexAccess.get(key.getBytes());
                        if (positions2.isPresent()) {
                            entryCount += positions2.get().size();
                            logger.info("Found entry for {}: {} positions", key, positions2.get().size());
                        }
                    }
                }
                
                // With our complete sentence, we now expect entries after filtering
                assertEquals(16, entryCount, "Expected 16 total stitch entries after filtering (4 unigrams (-1 since 'in' is a stopword) * 4 annotations)");
            }
    
            // Verify progress tracking
            verify(progress, atLeastOnce()).updateIndex(anyLong());
        } finally {
            // Ensure generator is closed in case of exception
            if (generator != null) {
                generator.close();
                generator = null;
            }
        }
    }
} 