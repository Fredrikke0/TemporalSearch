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
import com.example.index.generators.IndexGenerator;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

// Assuming BaseIndexTest provides sqliteConn and some tempDir functionality (e.g., indexBaseDir)
public class NerVsUnigramStitchEquivalenceTest extends BaseIndexTest {

    private record IdentifiedEntity(String entityType, int entityValueSynonymId, int docId, int sentId, int beginChar, int endChar) {}

    private SynonymManager synonymManager;
    private NerIndexGenerator nerIndexGenerator;
    private UnigramNerStitchGenerator unigramStitchGenerator;

    @Mock
    private IndexAccessInterface mockIndexAccess; // NerIndexGenerator and Unigram need this
    @Mock
    private ProgressTracker mockProgressTracker;

    @TempDir
    Path classLevelTempDir;

    private Path synonymDbPath;
    private Path dummyStopwordsFile;

    @BeforeEach
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.openMocks(this);

        synonymDbPath = classLevelTempDir.resolve("synonyms_equivalence_test");
        Files.createDirectories(synonymDbPath.getParent());
        synonymManager = new SynonymManager(synonymDbPath);

        dummyStopwordsFile = classLevelTempDir.resolve("stopwords-equiv-test.txt");
        Files.createFile(dummyStopwordsFile);

        nerIndexGenerator = new NerIndexGenerator(mockIndexAccess, dummyStopwordsFile.toString(), sqliteConn, mockProgressTracker, 10, synonymManager);
        unigramStitchGenerator = new UnigramNerStitchGenerator(mockIndexAccess, dummyStopwordsFile.toString(), sqliteConn, mockProgressTracker, 10, classLevelTempDir.resolve("stitchtemp_equiv"), synonymManager);

        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS annotations");
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
        }
        super.tearDown();
    }

    @SuppressWarnings("unchecked")
    private List<AnnotationEntry> invokeNerFetchBatch(AnnotationEntry lastProcessed) throws Exception {
        Method method = NerIndexGenerator.class.getDeclaredMethod("fetchBatch", AnnotationEntry.class);
        method.setAccessible(true);
        return (List<AnnotationEntry>) method.invoke(nerIndexGenerator, lastProcessed);
    }

    @SuppressWarnings("unchecked")
    private ListMultimap<String, PositionListSoA> invokeNerProcessBatch(List<AnnotationEntry> batch) throws Exception {
        Method method = NerIndexGenerator.class.getDeclaredMethod("processBatch", List.class);
        method.setAccessible(true);
        return (ListMultimap<String, PositionListSoA>) method.invoke(nerIndexGenerator, batch);
    }

    @SuppressWarnings("unchecked")
    private List<AbstractNgramStitchGenerator.AnnotationData> invokeUnigramFetchAnnotations(int docId) throws Exception {
        Method method = UnigramNerStitchGenerator.class.getDeclaredMethod("fetchAnnotationsForDocument", int.class);
        method.setAccessible(true);
        return (List<AbstractNgramStitchGenerator.AnnotationData>) method.invoke(unigramStitchGenerator, docId);
    }

    @Test
    void testEntityEquivalence() throws Exception {
        insertAnnotation(1, 1, 0, 5, "Alice", "PERSON", "alice");
        insertAnnotation(1, 1, 6, 9, "met", "O", "meet");
        insertAnnotation(1, 1, 10, 13, "Bob", "PERSON", "bob");
        insertAnnotation(1, 2, 0, 6, "Google", "ORGANIZATION", "google");
        insertAnnotation(1, 2, 7, 10, "Inc.", "ORGANIZATION", "inc.");
        insertAnnotation(1, 2, 11, 12, ".", "O", ".");
        insertAnnotation(1, 3, 0, 3, "NYC", "LOCATION", "nyc");
        insertAnnotation(1, 4, 0, 9, "2023-01-01", "DATE", "2023-01-01");
        insertAnnotation(1, 4, 13, 16, "fun", "O", "fun");
        insertAnnotation(2, 1, 0, 6, "George", "PERSON", "g. washington");
        insertAnnotation(2, 1, 7, 17, "Washington", "PERSON", "g. washington");
        insertAnnotation(2, 1, 18, 21, "and", "O", "and");
        insertAnnotation(2, 1, 22, 26, "John", "PERSON", "j. adams");
        insertAnnotation(2, 1, 27, 32, "Adams", "PERSON", "j. adams");
        insertAnnotation(3, 1, 0, 5, "Paris", "LOCATION", "paris");
        insertAnnotation(3, 1, 20, 26, "France", "LOCATION", "france");
        insertAnnotation(4,1,0,5, "Apple", "ORGANIZATION", "apple_org");
        insertAnnotation(4,1,6,10, "Inc.", "ORGANIZATION", "apple_org");
        insertAnnotation(4,1,11,16, "Swift", "LANGUAGE", "swift_lang");

        Set<IdentifiedEntity> nerGeneratorEntities = new HashSet<>();
        AnnotationEntry lastProcessed = null;
        List<AnnotationEntry> batch;
        do {
            batch = invokeNerFetchBatch(lastProcessed);
            if (!batch.isEmpty()) {
                ListMultimap<String, PositionListSoA> processedBatch = invokeNerProcessBatch(batch);
                assertNotNull(processedBatch);

                for (String key : processedBatch.keySet()) {
                    List<PositionListSoA> lists = processedBatch.get(key);
                    String[] parts = key.split(java.util.regex.Pattern.quote(String.valueOf(IndexAccessInterface.DELIMITER)));
                    String type = parts[0];
                    int synId = Integer.parseInt(parts[1]);
                    for (PositionListSoA pl : lists) {
                        for (int i = 0; i < pl.getNumPositions(); i++) {
                            nerGeneratorEntities.add(new IdentifiedEntity(
                                type,
                                synId,
                                pl.getDocIdAt(i),
                                pl.getSentenceIdAt(i),
                                pl.getBeginCharAt(i),
                                pl.getEndCharAt(i)
                            ));
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    lastProcessed = batch.get(batch.size() - 1);
                }
            }
        } while (!batch.isEmpty());

        Set<IdentifiedEntity> unigramStitchEntities = new HashSet<>();
        int[] docIdsToTest = {1, 2, 3, 4};
        for (int docId : docIdsToTest) {
            List<AbstractNgramStitchGenerator.AnnotationData> annotationDataList = invokeUnigramFetchAnnotations(docId);
            assertNotNull(annotationDataList);
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

        assertEquals(nerGeneratorEntities.size(), unigramStitchEntities.size(), "Number of identified entities should be the same.");
        assertEquals(nerGeneratorEntities, unigramStitchEntities, "The set of identified entities should be identical.");
    }
}