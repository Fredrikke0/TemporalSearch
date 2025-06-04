package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

@ExtendWith(MockitoExtension.class)
class PosExecutorTest {

    private static final Logger logger = LoggerFactory.getLogger(PosExecutorTest.class);

    @Mock private IndexAccessInterface posIndex;
    @Mock private RocksIterator posIterator;
    @Mock private ConditionExecutorFactory factory;
    @Mock private SynonymManager synonymManager;
    @InjectMocks private PosExecutor executor;

    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements;
    private static final String POS_INDEX_NAME = "pos";

    @BeforeEach
    void setUp() throws IndexAccessException, RocksDBException {
        indexes = Map.of(POS_INDEX_NAME, posIndex);
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsSynonymIds = true;
        defaultTestRequirements.needsConceptualRowIds = true;

        lenient().when(posIterator.isValid()).thenReturn(false);
        lenient().when(posIndex.getRaw(any(byte[].class))).thenReturn(Optional.empty());

        lenient().when(synonymManager.getId(anyString())).thenReturn(-1);
        lenient().when(synonymManager.getTerm(anyInt())).thenReturn(Optional.empty());
    }

    private byte[] soaToBlob(PositionListSoA soa) throws IOException {
        if (soa == null) return new byte[0];
        return soa.serializeToCompositeBlob();
    }

    @Test
    void testExecuteSpecificTermDocumentGranularity() throws Exception {
        Pos condition = new Pos("NN", "test");
        byte[] expectedTagKeyBytes = "NN".getBytes();
        int testTermSynonymId = 123;

        when(synonymManager.getId("test")).thenReturn(testTermSynonymId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 0, 5, 10, testTermSynonymId);
        positions.add(2, 1, 15, 20, testTermSynonymId);
        positions.add(1, 1, 25, 30, 456);
        when(posIndex.getRaw(eq(expectedTagKeyBytes))).thenReturn(Optional.of(positions.serializeToCompositeBlob()));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(1, result.getConceptualRowCount(), "Specific term search should yield 1 conceptual row for 'test'");
        assertEquals(2, result.size());
        Set<Integer> docIds = new HashSet<>();
        for(int i=0; i < result.size(); i++) docIds.add(result.getDocumentIdAt(i));
        assertTrue(docIds.containsAll(Set.of(1, 2)));
        for(int i=0; i < result.size(); i++) {
            assertEquals("test", result.getValueAt(i));
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
            assertEquals(testTermSynonymId, result.getSynonymIdAt(i));
        }
        verify(synonymManager).getId("test");
        verify(posIndex).getRaw(eq(expectedTagKeyBytes));
    }

    @Test
    void testExecuteSpecificTermSentenceGranularity() throws Exception {
        Pos condition = new Pos("VB", "run");
        byte[] expectedTagKeyBytes = "VB".getBytes();
        int runTermSynonymId = 789;

        when(synonymManager.getId("run")).thenReturn(runTermSynonymId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 1, 2, runTermSynonymId);
        positions.add(1, 2, 3, 4, runTermSynonymId);
        positions.add(2, 1, 5, 6, runTermSynonymId);
        positions.add(1, 1, 10, 15, runTermSynonymId);
        positions.add(1, 3, 20, 25, 999);

        when(posIndex.getRaw(eq(expectedTagKeyBytes))).thenReturn(Optional.of(positions.serializeToCompositeBlob()));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(1, result.getConceptualRowCount(), "Specific term search should yield 1 conceptual row for 'run'");
        assertEquals(4, result.size());

        boolean match1_1 = false, match1_2 = false, match2_1 = false;
        int foundRunTermSynonymIdCount = 0;
        for(int i=0; i < result.size(); i++) {
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            assertEquals("run", result.getValueAt(i));
            assertEquals(runTermSynonymId, result.getSynonymIdAt(i));
            foundRunTermSynonymIdCount++;
            if(result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 1) match1_1 = true;
            if(result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 2) match1_2 = true;
            if(result.getDocumentIdAt(i) == 2 && result.getSentenceIdAt(i) == 1) match2_1 = true;
        }
        assertTrue(match1_1);
        assertTrue(match1_2);
        assertTrue(match2_1);
        assertEquals(4, foundRunTermSynonymIdCount, "All 4 results should be for 'run'");

        verify(synonymManager).getId("run");
        verify(posIndex).getRaw(eq(expectedTagKeyBytes));
    }

    @Test
    void testSentenceGranularityWithWindow() throws Exception {
        Pos condition = new Pos("NN", "noun");
        byte[] expectedTagKeyBytes = "NN".getBytes();
        int nounTermSynonymId = 101;

        when(synonymManager.getId("noun")).thenReturn(nounTermSynonymId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 0, 1, 2, nounTermSynonymId);
        positions.add(1, 2, 3, 4, nounTermSynonymId);
        positions.add(1, 3, 5, 6, nounTermSynonymId);
        positions.add(2, 1, 7, 8, nounTermSynonymId);

        when(posIndex.getRaw(eq(expectedTagKeyBytes))).thenReturn(Optional.of(positions.serializeToCompositeBlob()));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(1, result.getConceptualRowCount(), "Specific term search should yield 1 conceptual row for 'noun'");
        assertEquals(4, result.size());

        boolean match1_0 = false, match1_2 = false, match1_3 = false, match2_1 = false;
        for(int i=0; i < result.size(); i++) {
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            assertEquals("noun", result.getValueAt(i));
            assertEquals(nounTermSynonymId, result.getSynonymIdAt(i));
            if(result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 0) match1_0 = true;
            if(result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 2) match1_2 = true;
            if(result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 3) match1_3 = true;
            if(result.getDocumentIdAt(i) == 2 && result.getSentenceIdAt(i) == 1) match2_1 = true;
        }
        assertTrue(match1_0);
        assertTrue(match1_2);
        assertTrue(match1_3);
        assertTrue(match2_1);

        verify(synonymManager).getId("noun");
        verify(posIndex).getRaw(eq(expectedTagKeyBytes));
    }

    @Test
    void testVariableBindingDocumentGranularityNoSpecificTerm() throws Exception {
        Pos condition = new Pos("JJ", null, "?adjVar");
        byte[] expectedTagKeyBytes = "JJ".getBytes();

        int goodSynId = 10;
        int badSynId = 20;
        int uglySynId = 30;

        PositionListSoA positionsForJJ = new PositionListSoA();
        positionsForJJ.add(1, 1, 5, 10, goodSynId);
        positionsForJJ.add(2, 1, 15, 20, badSynId);
        positionsForJJ.add(1, 2, 25, 30, uglySynId);
        positionsForJJ.add(3, 0, 35, 40, goodSynId);

        when(posIndex.getRaw(eq(expectedTagKeyBytes))).thenReturn(Optional.of(positionsForJJ.serializeToCompositeBlob()));

        when(synonymManager.getTerm(goodSynId)).thenReturn(Optional.of("good"));
        when(synonymManager.getTerm(badSynId)).thenReturn(Optional.of("bad"));
        when(synonymManager.getTerm(uglySynId)).thenReturn(Optional.of("ugly"));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(3, result.getConceptualRowCount(), "Should be 3 conceptual rows for 'good', 'bad', 'ugly'");
        assertEquals(4, result.size());

        Set<Integer> docIds = new HashSet<>();
        Map<String, Integer> valueCounts = new HashMap<>();
        for(int i=0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
            assertEquals("?adjVar", result.getVariableNameAt(i));
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            String term = (String) result.getValueAt(i);
            valueCounts.put(term, valueCounts.getOrDefault(term, 0) + 1);

            if ("good".equals(term)) assertEquals(goodSynId, result.getSynonymIdAt(i));
            else if ("bad".equals(term)) assertEquals(badSynId, result.getSynonymIdAt(i));
            else if ("ugly".equals(term)) assertEquals(uglySynId, result.getSynonymIdAt(i));
        }
        assertTrue(docIds.containsAll(Set.of(1, 2, 3)), "Docs 1, 2, and 3 should be present. Found: " + docIds);
        assertEquals(2, (int)valueCounts.get("good"));
        assertEquals(1, (int)valueCounts.get("bad"));
        assertEquals(1, (int)valueCounts.get("ugly"));

        verify(posIndex).getRaw(eq(expectedTagKeyBytes));
        verify(synonymManager, times(1)).getTerm(goodSynId);
        verify(synonymManager, times(1)).getTerm(badSynId);
        verify(synonymManager, times(1)).getTerm(uglySynId);
    }

    @Test
    void testTagOnlySearchDocumentGranularity() throws Exception {
        String tag = "NNP";
        Pos condition = new Pos(tag, null); // Term is null, no variable, so isVariable will be false
        logger.debug("Created condition in testTagOnlySearchDocumentGranularity: {}", condition.toString());

        PositionListSoA positionsForTag = new PositionListSoA();
        positionsForTag.add(1, 0, 10, 15, 1001); // Doc 1, Sent 0, Chars 10-15, arbitrary synID
        positionsForTag.add(1, 1, 20, 25, 1002); // Doc 1, Sent 1, Chars 20-25
        positionsForTag.add(2, 0, 30, 35, 1003); // Doc 2, Sent 0, Chars 30-35

        when(posIndex.getRaw(eq(tag.toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                 .thenReturn(Optional.of(soaToBlob(positionsForTag)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(1, result.getConceptualRowCount(), "Should be 1 conceptual row for the tag '" + tag + "'");
        assertEquals(3, result.size(), "Should find 3 occurrences of tag '" + tag + "'");

        assertTrue(result.size() > 0, "Result should not be empty");
        int expectedConceptualRowId = result.getConceptualRowIdAt(0);

        boolean found_1_0 = false;
        boolean found_1_1 = false;
        boolean found_2_0 = false;

        for (int i = 0; i < result.size(); i++) {
            assertEquals(tag.toUpperCase(), result.getValueAt(i)); // Executor stores uppercase tag
            assertEquals(ValueType.POS_TAG_TYPE, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
            assertEquals(-1, result.getSynonymIdAt(i));
            assertEquals(expectedConceptualRowId, result.getConceptualRowIdAt(i));

            if (result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 0) {
                assertEquals(10, result.getBeginCharAt(i));
                assertEquals(15, result.getEndCharAt(i));
                found_1_0 = true;
            } else if (result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 1) {
                assertEquals(20, result.getBeginCharAt(i));
                assertEquals(25, result.getEndCharAt(i));
                found_1_1 = true;
            } else if (result.getDocumentIdAt(i) == 2 && result.getSentenceIdAt(i) == 0) {
                assertEquals(30, result.getBeginCharAt(i));
                assertEquals(35, result.getEndCharAt(i));
                found_2_0 = true;
            }
        }
        assertTrue(found_1_0, "Did not find the expected entry for Doc 1, Sent 0");
        assertTrue(found_1_1, "Did not find the expected entry for Doc 1, Sent 1");
        assertTrue(found_2_0, "Did not find the expected entry for Doc 2, Sent 0");

        verify(posIndex).getRaw(eq(tag.toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        verify(synonymManager, times(0)).getId(anyString());
        verify(synonymManager, times(0)).getTerm(anyInt());
    }

    @Test
    void testExecuteWildcardSearch_allPosTags() throws QueryExecutionException {
        Pos condition = new Pos("*", null, null, false);

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION, exception.getErrorType());
        assertTrue(exception.getMessage().contains("Wildcard POS tag (*) is not supported"));
    }

    @Test
    void testExecuteSpecificSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        String tag = "JJ";
        String term = "happy";
        String variableName = "?adj";
        int happySynonymId = 456;

        Pos condition = new Pos(tag, term, variableName, true);

        when(synonymManager.getId(term.toLowerCase())).thenReturn(happySynonymId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(3, 1, 10, 15, happySynonymId);
        positions.add(4, 1, 0, 5, 999);

        when(posIndex.getRaw(eq(tag.toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                 .thenReturn(Optional.of(soaToBlob(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.size(), "Should find 1 occurrence of 'happy' with tag 'JJ'");
        assertEquals(1, result.getConceptualRowCount(), "Should be 1 conceptual row for 'happy' with tag 'JJ' bound to variable");

        assertEquals(term, result.getValueAt(0));
        assertEquals(ValueType.POS_TERM, result.getValueTypeAt(0));
        assertEquals(variableName, result.getVariableNameAt(0));
        assertEquals(3, result.getDocumentIdAt(0));
        assertEquals(happySynonymId, result.getSynonymIdAt(0));

        verify(synonymManager).getId(term.toLowerCase());
        verify(posIndex).getRaw(eq(tag.toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void testExecute_noMatchFound_specificTerm() throws QueryExecutionException, IndexAccessException, RocksDBException, IOException {
        String tag = "XYZ";
        String term = "term";
        int termSynonymId = 777;
        int otherSynonymId = 888;

        Pos condition = new Pos(tag, term, null, false);
        when(synonymManager.getId(term.toLowerCase())).thenReturn(termSynonymId);

        PositionListSoA positionsForTag = new PositionListSoA();
        positionsForTag.add(1, 1, 0, 5, otherSynonymId);

        when(posIndex.getRaw(eq(tag.toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                 .thenReturn(Optional.of(soaToBlob(positionsForTag)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Result should be empty as 'term' (synonymId " + termSynonymId + ") is not in the data for tag 'XYZ'");
        assertEquals(0, result.getConceptualRowCount());

        verify(synonymManager).getId(term.toLowerCase());
        verify(posIndex).getRaw(eq(tag.toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void testExecute_variableSearch_tagNotFound() throws QueryExecutionException, IndexAccessException, RocksDBException, IOException {
        String tag = "ABC";
        String variableName = "?someVar";
        Pos condition = new Pos(tag, null, variableName, true);

        when(posIndex.getRaw(eq(tag.toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                 .thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Result should be empty as the tag 'ABC' is not found.");
        assertEquals(0, result.getConceptualRowCount());

        verify(posIndex).getRaw(eq(tag.toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        verify(synonymManager, times(0)).getTerm(anyInt());
    }

    @Test
    void testExecute_missingPosIndex() throws QueryExecutionException {
        Pos condition = new Pos("NN", "noun", null, false);
        Map<String, IndexAccessInterface> incompleteIndexes = Collections.emptyMap();

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, incompleteIndexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
        assertTrue(exception.getMessage().contains("POS index not found"));
    }
}