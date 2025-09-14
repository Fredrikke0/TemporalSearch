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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

@ExtendWith(MockitoExtension.class)
class PosExecutorTest {

    @Mock private IndexAccessInterface posIndex;
    @Mock private RocksIterator posIterator;
    @Mock private ConditionExecutorFactory factory;
    @Mock private SynonymManager synonymManager;
    @InjectMocks private PosExecutor executor;

    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements;
    private static final String POS_INDEX_NAME = "pos";

    @BeforeEach
    void setUp() throws IndexAccessException, RocksDBException, IOException {
        indexes = Map.of(POS_INDEX_NAME, posIndex);
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsSynonymIds = true;
        defaultTestRequirements.needsConceptualRowIds = true;

        lenient().when(posIterator.isValid()).thenReturn(false);

        lenient().when(synonymManager.getId(anyString())).thenReturn(-1);
        lenient().when(synonymManager.getTerm(anyInt())).thenReturn(Optional.empty());
    }

    private static class Entry { byte[] key; byte[] value; Entry(byte[] k, byte[] v){ key=k; value=v; } }

    private List<Entry> toEntries(List<Map.Entry<byte[], PositionListSoA>> conceptual) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<byte[], PositionListSoA> e : conceptual) {
            out.add(new Entry(e.getKey(), e.getValue().serializeToCompositeBlob()));
        }
        return out;
    }

    private void configureIterator(RocksIterator it, List<Entry> entries) {
        final int[] idx = { -1 };
        lenient().when(it.isValid()).thenAnswer(inv -> idx[0] >= 0 && idx[0] < entries.size());
        lenient().when(it.key()).thenAnswer(inv -> entries.get(idx[0]).key);
        lenient().when(it.value()).thenAnswer(inv -> entries.get(idx[0]).value);
        lenient().doAnswer(inv -> { if (idx[0] >= 0 && idx[0] < entries.size()) idx[0]++; return null; }).when(it).next();
        lenient().doAnswer(inv -> { idx[0] = 0; return null; }).when(it).seekToFirst();
        lenient().doAnswer(inv -> {
            byte[] target = inv.getArgument(0);
            idx[0] = entries.size();
            for (int i = 0; i < entries.size(); i++) {
                if (java.util.Arrays.compare(entries.get(i).key, target) >= 0) { idx[0] = i; break; }
            }
            return null;
        }).when(it).seek(any(byte[].class));
    }

    private void mockSeekWithBounds(String prefix, List<Map.Entry<byte[], PositionListSoA>> conceptual) throws Exception {
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        List<Entry> entries = toEntries(conceptual);
        configureIterator(posIterator, entries);
        lenient().when(posIndex.seekWithBounds(eq(prefixBytes), any(byte[].class), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            posIterator.seek(prefixBytes);
            return posIterator;
        });
    }

    @Test
    void testExecuteSpecificTermDocumentGranularity() throws Exception {
        Pos condition = new Pos("NN", "test");
        String expectedTagString = "NN";
        int testTermSynonymId = 123;

        when(synonymManager.getId("test")).thenReturn(testTermSynonymId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 0, 5, 10);
        positions.add(2, 1, 15, 20);

        when(posIndex.getMergedPositions(eq(expectedTagString + IndexAccessInterface.DELIMITER + testTermSynonymId), any(), eq(defaultTestRequirements))).thenReturn(Optional.of(positions));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getConceptualRowCount(), "Specific term search should yield 2 conceptual rows for 2 matches of 'test'");
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
        verify(posIndex).getMergedPositions(eq(expectedTagString + IndexAccessInterface.DELIMITER + testTermSynonymId), any(), eq(defaultTestRequirements));
    }

    @Test
    void testExecuteSpecificTermSentenceGranularity() throws Exception {
        Pos condition = new Pos("VB", "run");
        String expectedTagString = "VB";
        int runTermSynonymId = 789;

        when(synonymManager.getId("run")).thenReturn(runTermSynonymId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 1, 2);
        positions.add(1, 2, 3, 4);
        positions.add(2, 1, 5, 6);
        positions.add(1, 1, 10, 15);

        when(posIndex.getMergedPositions(eq(expectedTagString + IndexAccessInterface.DELIMITER + runTermSynonymId), any(), eq(defaultTestRequirements))).thenReturn(Optional.of(positions));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(4, result.getConceptualRowCount(), "Specific term search should yield 4 conceptual rows for 4 matches of 'run'");
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
        verify(posIndex).getMergedPositions(eq(expectedTagString + IndexAccessInterface.DELIMITER + runTermSynonymId), any(), eq(defaultTestRequirements));
    }

    @Test
    void testSentenceGranularityWithWindow() throws Exception {
        Pos condition = new Pos("NN", "noun");
        String expectedTagString = "NN";
        int nounTermSynonymId = 101;

        when(synonymManager.getId("noun")).thenReturn(nounTermSynonymId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 0, 1, 2);
        positions.add(1, 2, 3, 4);
        positions.add(1, 3, 5, 6);
        positions.add(2, 1, 7, 8);

        when(posIndex.getMergedPositions(eq(expectedTagString + IndexAccessInterface.DELIMITER + nounTermSynonymId), any(), eq(defaultTestRequirements))).thenReturn(Optional.of(positions));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(4, result.getConceptualRowCount(), "Specific term search should yield 4 conceptual rows for 4 matches of 'noun'");
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
        verify(posIndex).getMergedPositions(eq(expectedTagString + IndexAccessInterface.DELIMITER + nounTermSynonymId), any(), eq(defaultTestRequirements));
    }

    @Test
    void testVariableBindingDocumentGranularityNoSpecificTerm() throws Exception {
        Pos condition = new Pos("JJ", null, "?adjVar");
        String tag = "JJ";

        int goodSynId = 10;
        int badSynId = 20;
        int uglySynId = 30;

        PositionListSoA jj_good1 = new PositionListSoA(); jj_good1.add(1, 1, 5, 10);
        PositionListSoA jj_bad = new PositionListSoA(); jj_bad.add(2, 1, 15, 20);
        PositionListSoA jj_ugly = new PositionListSoA(); jj_ugly.add(1, 2, 25, 30);
        PositionListSoA jj_good2 = new PositionListSoA(); jj_good2.add(3, 0, 35, 40);

        List<Map.Entry<byte[], PositionListSoA>> entries = new ArrayList<>();
        entries.add(Map.entry((tag + IndexAccessInterface.DELIMITER + goodSynId).getBytes(StandardCharsets.UTF_8), jj_good1));
        entries.add(Map.entry((tag + IndexAccessInterface.DELIMITER + badSynId).getBytes(StandardCharsets.UTF_8), jj_bad));
        entries.add(Map.entry((tag + IndexAccessInterface.DELIMITER + uglySynId).getBytes(StandardCharsets.UTF_8), jj_ugly));
        entries.add(Map.entry((tag + IndexAccessInterface.DELIMITER + goodSynId).getBytes(StandardCharsets.UTF_8), jj_good2));

        mockSeekWithBounds(tag + IndexAccessInterface.DELIMITER, entries);

        // No need to stub getTerms here; executor binds per-synId term lazily if needed

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(4, result.getConceptualRowCount(), "Should be 4 conceptual rows for 4 matches");
        assertEquals(4, result.size());
    }

    @Test
    void testTagOnlySearchDocumentGranularity() throws Exception {
        Pos condition = new Pos("NNP", null);
        String tag = "NNP";

        PositionListSoA nnp1 = new PositionListSoA(); nnp1.add(1, 0, 5, 10);
        PositionListSoA nnp2 = new PositionListSoA(); nnp2.add(1, 1, 15, 20);
        PositionListSoA nnp3 = new PositionListSoA(); nnp3.add(2, 0, 25, 30);

        List<Map.Entry<byte[], PositionListSoA>> entries = List.of(
            Map.entry((tag + IndexAccessInterface.DELIMITER + "111").getBytes(StandardCharsets.UTF_8), nnp1),
            Map.entry((tag + IndexAccessInterface.DELIMITER + "222").getBytes(StandardCharsets.UTF_8), nnp2),
            Map.entry((tag + IndexAccessInterface.DELIMITER + "111").getBytes(StandardCharsets.UTF_8), nnp3)
        );
        mockSeekWithBounds(tag + IndexAccessInterface.DELIMITER, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(3, result.getConceptualRowCount(), "Should be 3 conceptual rows for the 3 matches of the tag 'NNP'");
        assertEquals(3, result.size());

        Set<Integer> docIds = new HashSet<>();
        for(int i=0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
            assertEquals("NNP", result.getValueAt(i));
            assertEquals(ValueType.POS_TAG_TYPE, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
            assertEquals(-1, result.getSynonymIdAt(i));
        }
        assertTrue(docIds.containsAll(Set.of(1, 2)));
    }

    @Test
    void testExecuteWildcardSearch_allPosTags() throws Exception {
        Pos condition = new Pos("*", null);
        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        });
        assertEquals(QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION, exception.getErrorType());
        verify(posIndex, times(0)).getMergedPositions(anyString(), any(), any());
    }

    @Test
    void testExecuteSpecificSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Pos condition = new Pos("JJ", "happy", "?adj");
        String tag = "JJ";
        int happySynId = 555;

        when(synonymManager.getId("happy")).thenReturn(happySynId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 10, 15);

        when(posIndex.getMergedPositions(eq(tag + IndexAccessInterface.DELIMITER + happySynId), any(), eq(defaultTestRequirements))).thenReturn(Optional.of(positions));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(1, result.getConceptualRowCount(), "Should find 1 conceptual row for 'happy' with tag 'JJ'");
        assertEquals(1, result.size());
        assertEquals("happy", result.getValueAt(0));
        assertEquals(ValueType.POS_TERM, result.getValueTypeAt(0));
        assertEquals("?adj", result.getVariableNameAt(0));
        assertEquals(happySynId, result.getSynonymIdAt(0));
        assertEquals(1, result.getDocumentIdAt(0));

        verify(synonymManager).getId("happy");
        verify(posIndex).getMergedPositions(eq(tag + IndexAccessInterface.DELIMITER + happySynId), any(), eq(defaultTestRequirements));
    }

    @Test
    void testExecute_noMatchFound_specificTerm() throws QueryExecutionException, IndexAccessException, RocksDBException, IOException {
        Pos condition = new Pos("XYZ", "term_that_does_not_exist");
        String tag = "XYZ";
        int nonExistentTermSynId = 999;

        when(synonymManager.getId("term_that_does_not_exist")).thenReturn(nonExistentTermSynId);
        when(posIndex.getMergedPositions(eq(tag + IndexAccessInterface.DELIMITER + nonExistentTermSynId), any(), eq(defaultTestRequirements))).thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, result.getConceptualRowCount());

        verify(synonymManager).getId("term_that_does_not_exist");
        verify(posIndex).getMergedPositions(eq(tag + IndexAccessInterface.DELIMITER + nonExistentTermSynId), any(), eq(defaultTestRequirements));
    }

    @Test
    void testExecute_variableSearch_tagNotFound() throws Exception {
        Pos condition = new Pos("ABC", null, "?var");
        String tag = "ABC";
        mockSeekWithBounds(tag + IndexAccessInterface.DELIMITER, List.of());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, result.getConceptualRowCount());
    }

    @Test
    void testExecute_missingPosIndex() throws QueryExecutionException {
        Pos condition = new Pos("NN", "noun");
        Map<String, IndexAccessInterface> emptyIndexes = java.util.Collections.emptyMap();

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, emptyIndexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
    }
}