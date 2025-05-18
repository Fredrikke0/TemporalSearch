package com.example.query.executor;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.executor.QueryResult;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Not;

import org.iq80.leveldb.DBIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotConditionExecutorTest {

    @Mock private ConditionExecutorFactory executorFactory;
    @Mock private IndexAccess unigramIndex;
    @Mock private DBIterator unigramIterator;
    @Mock private IndexAccess mockIndexAccess;
    @Mock private ConditionExecutorFactory factory;
    private Map<String, IndexAccessInterface> indexes;

    @InjectMocks private NotExecutor notExecutor;

    @BeforeEach
    void setUp() throws Exception {
        indexes = Map.of("unigram", unigramIndex);
        lenient().when(unigramIndex.iterator()).thenReturn(unigramIterator);
        lenient().when(unigramIterator.hasNext()).thenReturn(false);
        lenient().when(unigramIterator.next()).thenReturn(null);
        
        notExecutor = new NotExecutor(executorFactory);
    }

    @Test
    void testExecuteDocumentGranularity() throws Exception {
        Contains containsCondition = new Contains("test");
        Not notCondition = new Not(containsCondition);

        QueryResult innerResult = new QueryResult(Query.Granularity.DOCUMENT, 0, List.of(
            createMatchDetail(1, "test"), 
            createMatchDetail(2, "test")
        ));
        ContainsExecutor mockContainsExecutor = mock(ContainsExecutor.class);
        when(executorFactory.getExecutor(any(Contains.class))).thenReturn(mockContainsExecutor);
        when(mockContainsExecutor.execute(eq(containsCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
            .thenReturn(innerResult);

        PositionList posList1 = createPositionList(new Position(1, 0, 0, 0));
        PositionList posList2 = createPositionList(new Position(2, 0, 0, 0));
        PositionList posList3 = createPositionList(new Position(3, 0, 0, 0));
        PositionList posList4 = createPositionList(new Position(4, 0, 0, 0));
        
        when(unigramIterator.hasNext()).thenReturn(true, true, true, true, false);
        when(unigramIterator.next()).thenReturn(
            Map.entry("key1".getBytes(), posList1.serialize()),
            Map.entry("key2".getBytes(), posList2.serialize()),
            Map.entry("key3".getBytes(), posList3.serialize()),
            Map.entry("key4".getBytes(), posList4.serialize())
        );

        QueryResult result = notExecutor.execute(notCondition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getAllDetails().size());
        Set<Integer> docIds = result.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertTrue(docIds.containsAll(Set.of(3, 4)), "Expected documents 3 and 4 only");
        assertFalse(docIds.contains(1), "Document 1 should be excluded");
        assertFalse(docIds.contains(2), "Document 2 should be excluded");
        
        verify(unigramIndex).iterator();
        verify(unigramIterator, times(5)).hasNext();
        verify(unigramIterator, times(4)).next();
    }

    @Test
    void testExecuteSentenceGranularity() throws Exception {
        Contains containsCondition = new Contains("test");
        Not notCondition = new Not(containsCondition);
        
        QueryResult innerResult = new QueryResult(Query.Granularity.SENTENCE, 0, List.of(
            createMatchDetail(1, 1, "test"),
            createMatchDetail(2, 1, "test")
        ));
        ContainsExecutor mockContainsExecutor = mock(ContainsExecutor.class);
        when(executorFactory.getExecutor(any(Contains.class))).thenReturn(mockContainsExecutor);
        when(mockContainsExecutor.execute(eq(containsCondition), eq(indexes), eq(Query.Granularity.SENTENCE), anyInt(), anyString()))
            .thenReturn(innerResult);

        PositionList posListD1S0 = createPositionList(new Position(1, 0, 0, 0)); 
        PositionList posListD1S1 = createPositionList(new Position(1, 1, 0, 0)); 
        PositionList posListD2S0 = createPositionList(new Position(2, 0, 0, 0)); 
        PositionList posListD2S1 = createPositionList(new Position(2, 1, 0, 0)); 
        PositionList posListD2S2 = createPositionList(new Position(2, 2, 0, 0)); 
        PositionList posListD3S0 = createPositionList(new Position(3, 0, 0, 0)); 
        
        when(unigramIterator.hasNext()).thenReturn(true, true, true, true, true, true, false);
        when(unigramIterator.next()).thenReturn(
            Map.entry("k10".getBytes(), posListD1S0.serialize()),
            Map.entry("k11".getBytes(), posListD1S1.serialize()),
            Map.entry("k20".getBytes(), posListD2S0.serialize()),
            Map.entry("k21".getBytes(), posListD2S1.serialize()),
            Map.entry("k22".getBytes(), posListD2S2.serialize()),
            Map.entry("k30".getBytes(), posListD3S0.serialize())
        );

        QueryResult result = notExecutor.execute(notCondition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        
        assertEquals(4, result.getAllDetails().size());
        
        Set<String> resultSetIds = result.getAllDetails().stream()
            .map(d -> d.getDocumentId() + "|" + d.getSentenceId())
            .collect(Collectors.toSet());
            
        assertTrue(resultSetIds.contains("1|0"), "Expected 1|0");
        assertTrue(resultSetIds.contains("2|0"), "Expected 2|0");
        assertTrue(resultSetIds.contains("2|2"), "Expected 2|2");
        assertTrue(resultSetIds.contains("3|0"), "Expected 3|0");

        assertFalse(resultSetIds.contains("1|1"), "Should not contain 1|1");
        assertFalse(resultSetIds.contains("2|1"), "Should not contain 2|1");

        verify(unigramIndex).iterator();
        verify(unigramIterator, times(7)).hasNext();
        verify(unigramIterator, times(6)).next();
    }

    @Test
    void testExecuteSentenceGranularityWithWindow() throws Exception {
        Contains containsCondition = new Contains("test");
        Not notCondition = new Not(containsCondition);
        int windowSize = 1;

        QueryResult innerResult = new QueryResult(Query.Granularity.SENTENCE, windowSize, List.of(
            createMatchDetail(1, 0, "test"),
            createMatchDetail(1, 1, "test"),
            createMatchDetail(1, 2, "test")
        ));
        ContainsExecutor mockContainsExecutor = mock(ContainsExecutor.class);
        when(executorFactory.getExecutor(any(Contains.class))).thenReturn(mockContainsExecutor);
        when(mockContainsExecutor.execute(eq(containsCondition), eq(indexes), eq(Query.Granularity.SENTENCE), eq(windowSize), anyString()))
            .thenReturn(innerResult);

        PositionList posListD1S0 = createPositionList(new Position(1, 0, 0, 0)); 
        PositionList posListD1S1 = createPositionList(new Position(1, 1, 0, 0)); 
        PositionList posListD1S2 = createPositionList(new Position(1, 2, 0, 0)); 

        when(unigramIterator.hasNext()).thenReturn(true, true, true, false);
        when(unigramIterator.next()).thenReturn(
            Map.entry("k10".getBytes(), posListD1S0.serialize()),
            Map.entry("k11".getBytes(), posListD1S1.serialize()),
            Map.entry("k12".getBytes(), posListD1S2.serialize())
        );

        QueryResult result = notExecutor.execute(notCondition, indexes, Query.Granularity.SENTENCE, windowSize, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        
        assertEquals(0, result.getAllDetails().size(), "Expected empty result as inner result covered the universe");

        verify(unigramIndex).iterator();
        verify(unigramIterator, times(4)).hasNext();
        verify(unigramIterator, times(3)).next();
    }

    @Test
    void testVariableBinding() throws Exception {
        Contains containsCondition = new Contains("test", "?termVar", true);
        Not notCondition = new Not(containsCondition);

        QueryResult innerResult = new QueryResult(Query.Granularity.DOCUMENT, 0, List.of(
            createMatchDetail(1, "test"), 
            createMatchDetail(2, "test")
        ));
        ContainsExecutor mockContainsExecutor = mock(ContainsExecutor.class);
        when(executorFactory.getExecutor(any(Contains.class))).thenReturn(mockContainsExecutor);
        Position pos1 = new Position(1, 1, 0, 5);
        Position pos2 = new Position(2, 1, 0, 5);
        when(mockContainsExecutor.execute(eq(containsCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
                .thenReturn(new QueryResult(Query.Granularity.DOCUMENT, 0,
                    List.of(createMatchDetail(pos1.getDocumentId(), "test"), 
                            createMatchDetail(pos2.getDocumentId(), "test"))
                ));

        PositionList posList1 = createPositionList(new Position(1, 0, 0, 0));
        PositionList posList2 = createPositionList(new Position(2, 0, 0, 0));
        PositionList posList3 = createPositionList(new Position(3, 0, 0, 0));
        PositionList posList4 = createPositionList(new Position(4, 0, 0, 0));
        
        when(unigramIterator.hasNext()).thenReturn(true, true, true, true, false);
        when(unigramIterator.next()).thenReturn(
            Map.entry("key1".getBytes(), posList1.serialize()),
            Map.entry("key2".getBytes(), posList2.serialize()),
            Map.entry("key3".getBytes(), posList3.serialize()),
            Map.entry("key4".getBytes(), posList4.serialize())
        );

        QueryResult result = notExecutor.execute(notCondition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getAllDetails().size());
        Set<Integer> docIds = result.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertTrue(docIds.containsAll(Set.of(3, 4)), "Expected documents 3 and 4 only");
        assertFalse(docIds.contains(1), "Document 1 should be excluded");
        assertFalse(docIds.contains(2), "Document 2 should be excluded");
        
        verify(unigramIndex).iterator();
        verify(unigramIterator, times(5)).hasNext();
        verify(unigramIterator, times(4)).next();
    }

    private MatchDetail createMatchDetail(int docId, int sentenceId, String value) {
        Position pos = new Position(docId, sentenceId, 0, 0);
        return new MatchDetail(value, ValueType.TERM, pos, (String) null);
    }

    private MatchDetail createMatchDetail(int docId, String value) {
        return createMatchDetail(docId, -1, value);
    }

    private PositionList createPositionList(Position... positions) {
        PositionList list = new PositionList();
        for (Position p : positions) {
            list.add(p);
        }
        return list;
    }
} 