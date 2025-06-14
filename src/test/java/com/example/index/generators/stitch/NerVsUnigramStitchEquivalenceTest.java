package com.example.index.generators.stitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.rocksdb.RocksDBException;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.index.generators.BaseIndexTest; // Assuming this class exists and provides setup
import com.example.index.generators.NerIndexGenerator;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

// Assuming BaseIndexTest provides sqliteConn and some tempDir functionality (e.g., indexBaseDir)
public class NerVsUnigramStitchEquivalenceTest extends BaseIndexTest {

    private record IdentifiedEntity(String entityType, int entityValueSynonymId, int docId, int sentId, int beginChar, int endChar) {}

    private SynonymManager synonymManager;
    private NerIndexGenerator nerIndexGenerator;
    private UnigramNerStitchIndexGenerator unigramStitchGenerator;

    @Mock
    private IndexAccessInterface mockIndexAccess; // NerIndexGenerator and Unigram need this
    @Mock
    private ProgressTracker mockProgressTracker;

    @TempDir // JUnit Jupiter creates and cleans this temp directory, BaseIndexTest might also have one.
    Path classLevelTempDir;

    private Path synonymDbPath;
    private Path dummyStopwordsFile;

    @BeforeEach
    @Override
    protected void setUp() throws Exception {
        super.setUp(); // Sets up sqliteConn from BaseIndexTest
        MockitoAnnotations.openMocks(this);

        // Setup SynonymManager with a temporary RocksDB path within the class-level temp dir
        synonymDbPath = classLevelTempDir.resolve("synonyms_equivalence_test");
        Files.createDirectories(synonymDbPath.getParent());
        synonymManager = new SynonymManager(synonymDbPath);

        // Create a dummy stopwords file
        dummyStopwordsFile = classLevelTempDir.resolve("stopwords-equiv-test.txt");
        Files.createFile(dummyStopwordsFile);

        // Initialize generators (they are final, so no subclassing)
        nerIndexGenerator = new NerIndexGenerator(mockIndexAccess, dummyStopwordsFile.toString(), sqliteConn, mockProgressTracker, 10, synonymManager);
        unigramStitchGenerator = new UnigramNerStitchIndexGenerator(mockIndexAccess, dummyStopwordsFile.toString(), sqliteConn, mockProgressTracker, 10, classLevelTempDir.resolve("stitchtemp_equiv"), synonymManager);

        // Create annotations table (if not handled by BaseIndexTest setUp)
        // Assuming BaseIndexTest only provides connection, not schema.
        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS annotations"); // Clear if exists
            stmt.execute("CREATE TABLE annotations (" +
                         "annotation_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "document_id INTEGER, " +
                         "sentence_id INTEGER, " +
                         "begin_char INTEGER, " +
                         "end_char INTEGER, " +
                         "token TEXT, " +
                         "pos TEXT, " +
                         "ner TEXT, " +
                         "normalized_ner TEXT, " +
                         "lemma TEXT)");
        }
    }

    private void insertAnnotation(int docId, int sentId, int begin, int end, String token, String ner, String normalizedNer) throws SQLException {
        String nerToInsert = (ner == null || ner.isEmpty()) ? "O" : ner;
        String sql = "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner, lemma) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(sql)) {
            pstmt.setInt(1, docId);
            pstmt.setInt(2, sentId);
            pstmt.setInt(3, begin);
            pstmt.setInt(4, end);
            pstmt.setString(5, token);
            pstmt.setString(6, "NN");
            pstmt.setString(7, nerToInsert);
            pstmt.setString(8, normalizedNer != null ? normalizedNer : token.toLowerCase());
            pstmt.setString(9, token.toLowerCase());
            pstmt.executeUpdate();
        }
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        if (synonymManager != null) {
            synonymManager.close();
            // Attempt to delete if needed, though @TempDir should handle its contents.
            // For robust cleanup of RocksDB specifically if it lives outside @TempDir controlled area.
            // If synonymDbPath is inside classLevelTempDir, JUnit manages it.
        }
        // sqliteConn is managed by BaseIndexTest.tearDown()
        super.tearDown();
    }

    // Helper method for reflection to call protected NerIndexGenerator.fetchBatch
    @SuppressWarnings("unchecked")
    private List<AnnotationEntry> invokeNerFetchBatch(AnnotationEntry lastProcessed) throws Exception {
        Method method = NerIndexGenerator.class.getDeclaredMethod("fetchBatch", AnnotationEntry.class);
        method.setAccessible(true);
        return (List<AnnotationEntry>) method.invoke(nerIndexGenerator, lastProcessed);
    }

    // Helper method for reflection to call protected NerIndexGenerator.processBatch
    @SuppressWarnings("unchecked")
    private ListMultimap<String, PositionListSoA> invokeNerProcessBatch(List<AnnotationEntry> batch) throws Exception {
        Method method = NerIndexGenerator.class.getDeclaredMethod("processBatch", List.class);
        method.setAccessible(true);
        try {
            return (ListMultimap<String, PositionListSoA>) method.invoke(nerIndexGenerator, batch);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap the actual exception if it\'s a RocksDBException or other relevant exception
            if (e.getCause() instanceof RocksDBException) throw (RocksDBException) e.getCause();
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw e; // Re-throw if it is not one of the expected wrapped exceptions
        }
    }

    // Helper method for reflection to call protected UnigramNerStitchIndexGenerator.fetchAnnotationsForDocument
    @SuppressWarnings("unchecked")
    private List<AbstractNgramStitchGenerator.AnnotationData> invokeUnigramFetchAnnotations(int docId) throws Exception {
        Method method = UnigramNerStitchIndexGenerator.class.getDeclaredMethod("fetchAnnotationsForDocument", int.class);
        method.setAccessible(true);
        return (List<AbstractNgramStitchGenerator.AnnotationData>) method.invoke(unigramStitchGenerator, docId);
    }

    @Test
    void testEntityEquivalence() throws Exception { // Allow general Exception due to reflection
        // --- Populate Test Data ---
        // Doc 1: Basic cases and multi-token for NerIndexGenerator
        insertAnnotation(1, 1, 0, 5, "Alice", "PERSON", "alice");
        insertAnnotation(1, 1, 6, 9, "met", "O", "meet");
        insertAnnotation(1, 1, 10, 13, "Bob", "PERSON", "bob");
        insertAnnotation(1, 2, 0, 6, "Google", "ORGANIZATION", "google");
        insertAnnotation(1, 2, 7, 10, "Inc.", "ORGANIZATION", "inc.");  // Forms "Google Inc."
        insertAnnotation(1, 2, 11, 12, ".", "O", "."); // Adjacency check needs correct end_char for "Inc."
        insertAnnotation(1, 3, 0, 3, "NYC", "LOCATION", "nyc");

        // Date and O should be ignored by both
        insertAnnotation(1, 4, 0, 9, "2023-01-01", "DATE", "2023-01-01");
        insertAnnotation(1, 4, 13, 16, "fun", "O", "fun");

        // Doc 2: Multi-token entities handled by the grouping logic in both generators
        insertAnnotation(2, 1, 0, 6, "George", "PERSON", "g. washington");
        insertAnnotation(2, 1, 7, 17, "Washington", "PERSON", "g. washington"); // Forms "George Washington"
        insertAnnotation(2, 1, 18, 21, "and", "O", "and");
        insertAnnotation(2, 1, 22, 26, "John", "PERSON", "j. adams");
        insertAnnotation(2, 1, 27, 32, "Adams", "PERSON", "j. adams");       // Forms "John Adams"

        // Doc 3: Single token entities and gaps which should result in separate entities
        insertAnnotation(3, 1, 0, 5, "Paris", "LOCATION", "paris");
        // Deliberate gap, should not merge "Paris" and "France"
        insertAnnotation(3, 1, 20, 26, "France", "LOCATION", "france"); // Note: begin_char 20 implies a gap from Paris (ends 5)

        // Doc 4: Entity break due to different NER tag
        insertAnnotation(4,1,0,5, "Apple", "ORGANIZATION", "apple_org");
        insertAnnotation(4,1,6,10, "Inc.", "ORGANIZATION", "apple_org"); // Forms Apple Inc.
        insertAnnotation(4,1,11,16, "Swift", "LANGUAGE", "swift_lang"); // Different NER type, breaks entity

        // --- Process with NerIndexGenerator ---
        Set<IdentifiedEntity> nerGeneratorEntities = new HashSet<>();
        AnnotationEntry lastProcessed = null;
        List<AnnotationEntry> batch;
        do {
            batch = invokeNerFetchBatch(lastProcessed);
            if (!batch.isEmpty()) {
                ListMultimap<String, PositionListSoA> processedBatch = invokeNerProcessBatch(batch);
                assertNotNull(processedBatch, "Processed batch from NerIndexGenerator should not be null");

                for (String entityType : processedBatch.keySet()) {
                    List<PositionListSoA> plsList = processedBatch.get(entityType);
                    for (PositionListSoA pls : plsList) {
                        for (int i = 0; i < pls.getNumPositions(); i++) {
                            nerGeneratorEntities.add(new IdentifiedEntity(
                                entityType,
                                pls.getSynonymIdAt(i),
                                pls.getDocIdAt(i),
                                pls.getSentenceIdAt(i),
                                pls.getBeginCharAt(i),
                                pls.getEndCharAt(i)
                            ));
                        }
                    }
                }
                if (!batch.isEmpty()) { // Ensure batch is not empty before getting last element
                    lastProcessed = batch.get(batch.size() - 1);
                }
            }
        } while (!batch.isEmpty());

        // --- Process with UnigramNerStitchGenerator ---
        Set<IdentifiedEntity> unigramStitchEntities = new HashSet<>();
        int[] docIdsToTest = {1, 2, 3, 4};

        for (int docId : docIdsToTest) {
            List<AbstractNgramStitchGenerator.AnnotationData> annotationDataList = invokeUnigramFetchAnnotations(docId);
            assertNotNull(annotationDataList, "AnnotationData list from UnigramNerStitchGenerator should not be null for doc " + docId);

            for (AbstractNgramStitchGenerator.AnnotationData ad : annotationDataList) {
                int entityValueSynonymId = synonymManager.getId(ad.specificValueForSynonym());
                unigramStitchEntities.add(new IdentifiedEntity(
                    ad.annotationKeyComponent(),
                    entityValueSynonymId,
                    docId,
                    ad.sentenceId(),
                    ad.beginChar(),
                    ad.endChar()
                ));
            }
        }

        // --- Assertions ---
        System.out.println("NerIndexGenerator Entities (" + nerGeneratorEntities.size() + "):");
        nerGeneratorEntities.stream().sorted(java.util.Comparator.comparing(IdentifiedEntity::docId).thenComparing(IdentifiedEntity::sentId).thenComparing(IdentifiedEntity::beginChar)).forEach(System.out::println);

        System.out.println("\\nUnigramNerStitchGenerator Entities (" + unigramStitchEntities.size() + "):");
        unigramStitchEntities.stream().sorted(java.util.Comparator.comparing(IdentifiedEntity::docId).thenComparing(IdentifiedEntity::sentId).thenComparing(IdentifiedEntity::beginChar)).forEach(System.out::println);

        assertEquals(nerGeneratorEntities.size(), unigramStitchEntities.size(), "Number of identified entities should be the same.");
        assertEquals(nerGeneratorEntities, unigramStitchEntities, "The set of identified entities should be identical.");
    }
}