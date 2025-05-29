package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.iq80.leveldb.DBIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

@ExtendWith(MockitoExtension.class)
class PosConditionExecutorTest {

    @Mock private IndexAccess posIndex;
    @Mock private DBIterator posIterator;
    @Mock private ConditionExecutorFactory factory;
    @InjectMocks private PosExecutor executor;

    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements;
    private static final String DELIMITER_STR = String.valueOf(IndexAccessInterface.DELIMITER);

    @BeforeEach
    void setUp() throws IndexAccessException {
        indexes = Map.of("pos", posIndex);
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;

        lenient().when(posIterator.hasNext()).thenReturn(false);
        lenient().when(posIndex.getRaw(any(byte[].class))).thenReturn(Optional.empty());
        lenient().when(posIndex.seek(any(byte[].class))).thenReturn(posIterator);
        lenient().when(posIndex.iterateFromFirst()).thenReturn(posIterator);
    }

    @Test
    void testExecuteSpecificTermDocumentGranularity() throws Exception {
        Pos condition = new Pos("NN", "test");
        String expectedKey = "NN" + DELIMITER_STR + "test";

        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(1, 0, 5, 10));
        positions.add(new Position(2, 1, 15, 20));
        positions.add(new Position(1, 1, 25, 30));
        when(posIndex.getRaw(eq(expectedKey.getBytes()))).thenReturn(Optional.of(positions.serializeToCompositeBlob()));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(3, result.size());
        Set<Integer> docIds = new HashSet<>();
        for(int i=0; i < result.size(); i++) docIds.add(result.getDocumentIdAt(i));
        assertTrue(docIds.containsAll(Set.of(1, 2)));
        for(int i=0; i < result.size(); i++) {
            assertEquals("test", result.getValueAt(i));
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
        }
        verify(posIndex).getRaw(eq(expectedKey.getBytes()));
    }

    @Test
    void testExecuteSpecificTermSentenceGranularity() throws Exception {
        Pos condition = new Pos("VB", "run");
        String expectedKey = "VB" + DELIMITER_STR + "run";

        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(1, 1, 1, 2));
        positions.add(new Position(1, 2, 3, 4));
        positions.add(new Position(2, 1, 5, 6));
        positions.add(new Position(1, 1, 10, 15));

        when(posIndex.getRaw(eq(expectedKey.getBytes()))).thenReturn(Optional.of(positions.serializeToCompositeBlob()));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(4, result.size());

        boolean match1_1 = false, match1_2 = false, match2_1 = false;
        for(int i=0; i < result.size(); i++) {
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            assertEquals("run", result.getValueAt(i));
            if(result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 1) match1_1 = true;
            if(result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 2) match1_2 = true;
            if(result.getDocumentIdAt(i) == 2 && result.getSentenceIdAt(i) == 1) match2_1 = true;
        }
        assertTrue(match1_1);
        assertTrue(match1_2);
        assertTrue(match2_1);

        verify(posIndex).getRaw(eq(expectedKey.getBytes()));
    }

    @Test
    void testSentenceGranularityWithWindow() throws Exception {
        Pos condition = new Pos("NN", "noun");
        String expectedKey = "NN" + DELIMITER_STR + "noun";

        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(1, 0, 1, 2));
        positions.add(new Position(1, 2, 3, 4));
        positions.add(new Position(1, 3, 5, 6));
        positions.add(new Position(2, 1, 7, 8));

        when(posIndex.getRaw(eq(expectedKey.getBytes()))).thenReturn(Optional.of(positions.serializeToCompositeBlob()));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(4, result.size());

        boolean match1_0 = false, match1_2 = false, match2_1 = false;
        for(int i=0; i < result.size(); i++) {
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            assertEquals("noun", result.getValueAt(i));
            if(result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 0) match1_0 = true;
            if(result.getDocumentIdAt(i) == 1 && result.getSentenceIdAt(i) == 2) match1_2 = true;
            if(result.getDocumentIdAt(i) == 2 && result.getSentenceIdAt(i) == 1) match2_1 = true;
        }
        assertTrue(match1_0);
        assertTrue(match1_2);
        assertTrue(match2_1);

        verify(posIndex).getRaw(eq(expectedKey.getBytes()));
    }

    @Test
    void testVariableBindingDocumentGranularityNoSpecificTerm() throws Exception {
        Pos condition = new Pos("JJ", null, "?adjVar");
        String expectedKeyPrefix = "JJ" + DELIMITER_STR;
        byte[] expectedKeyPrefixBytes = expectedKeyPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        PositionListSoA positions1 = new PositionListSoA();
        positions1.add(new Position(1, 1, 5, 10));
        PositionListSoA positions2 = new PositionListSoA();
        positions2.add(new Position(2, 1, 15, 20));
        PositionListSoA positions3 = new PositionListSoA();
        positions3.add(new Position(1, 2, 25, 30));

        List<Map.Entry<byte[], byte[]>> mockEntries = new ArrayList<>();
        mockEntries.add(Map.entry((expectedKeyPrefix + "good").getBytes(), positions1.serializeToCompositeBlob()));
        mockEntries.add(Map.entry((expectedKeyPrefix + "bad").getBytes(), positions2.serializeToCompositeBlob()));
        mockEntries.add(Map.entry((expectedKeyPrefix + "ugly").getBytes(), positions3.serializeToCompositeBlob()));

        when(posIndex.seek(eq(expectedKeyPrefixBytes))).thenReturn(posIterator);

        when(posIterator.hasNext()).thenReturn(true, true, true, false);
        when(posIterator.next())
            .thenReturn(mockEntries.get(0))
            .thenReturn(mockEntries.get(1))
            .thenReturn(mockEntries.get(2));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(3, result.size());

        Set<Integer> docIds = new HashSet<>();
        Set<String> capturedValues = new HashSet<>();
        for(int i=0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
            assertEquals("?adjVar", result.getVariableNameAt(i));
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            capturedValues.add((String) result.getValueAt(i));
        }
        assertTrue(docIds.containsAll(Set.of(1, 2)), "Docs 1 and 2 should be present. Found: " + docIds);
        assertEquals(Set.of("good", "bad", "ugly"), capturedValues, "Captured values should be the specific terms.");

        verify(posIndex, times(0)).getRaw(any());
        verify(posIndex).seek(eq(expectedKeyPrefixBytes));
        verify(posIterator, times(4)).hasNext();
        verify(posIterator, times(3)).next();
    }
}