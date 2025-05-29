package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.iq80.leveldb.DBIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Not;

@ExtendWith(MockitoExtension.class)
public class NotConditionExecutorTest {

    @Mock
    private ConditionExecutorFactory mockFactory;
    @Mock
    private ContainsExecutor mockSubExecutor;
    @Mock
    private IndexAccessInterface mockUnigramIndex;
    @Mock
    private DBIterator mockDBIterator;

    private NotExecutor notExecutor;
    private Map<String, IndexAccessInterface> indexes;
    private Query.Granularity granularity;
    private String corpusName = "test_corpus";
    private Contains subCondition;
    private QueryResultSoA emptySubResult;
    private QueryResultSoA nonEmptySubResult;
    private AttributeRequirements defaultTestRequirements;

    @BeforeEach
    void setUp() throws Exception {
        notExecutor = new NotExecutor(mockFactory);
        indexes = Map.of("unigram", mockUnigramIndex);
        granularity = Query.Granularity.DOCUMENT;
        subCondition = new Contains("test");
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsConceptualRowIds = true;
        defaultTestRequirements.needsSentenceId = true;

        emptySubResult = new QueryResultSoA(granularity, 0, defaultTestRequirements);

        nonEmptySubResult = new QueryResultSoA(granularity, 0, defaultTestRequirements);
        nonEmptySubResult.add("test", ValueType.TERM, null, 1, -1, 0, 4, -1, 0);

        when(mockFactory.getExecutor(any(Contains.class))).thenReturn(mockSubExecutor);
        when(mockUnigramIndex.iterateFromFirst()).thenReturn(mockDBIterator);
    }

    private void mockUnigramIndexForUniverse(List<Position> positionsInUniverse) throws Exception {
        PositionListSoA universePositions = new PositionListSoA();
        for (Position p : positionsInUniverse) {
            universePositions.add(p);
        }
        byte[] universeBlob = universePositions.serializeToCompositeBlob();

        when(mockDBIterator.hasNext()).thenReturn(true, false);
        when(mockDBIterator.next()).thenReturn(new java.util.AbstractMap.SimpleEntry<>("any_key".getBytes(), universeBlob));
    }

    @Test
    void testExecute_subConditionReturnsEmpty() throws Exception {
        Not notCondition = new Not(subCondition);
        when(mockSubExecutor.execute(eq(subCondition), any(), eq(granularity), anyInt(), eq(corpusName), any(AttributeRequirements.class)))
            .thenReturn(emptySubResult);

        mockUnigramIndexForUniverse(List.of(new Position(100, 0, 0, 1)));

        QueryResultSoA finalResult = notExecutor.execute(notCondition, indexes, granularity, 0, corpusName, defaultTestRequirements);

        assertNotNull(finalResult);
        assertEquals(1, finalResult.size());
        assertEquals(100, finalResult.getDocumentIdAt(0));
    }

    @Test
    void testExecute_subConditionReturnsMatch_universeExcludesMatch() throws Exception {
        Not notCondition = new Not(subCondition);
        when(mockSubExecutor.execute(eq(subCondition), any(), eq(granularity), anyInt(), eq(corpusName), any(AttributeRequirements.class)))
            .thenReturn(nonEmptySubResult);

        mockUnigramIndexForUniverse(List.of(new Position(1, 0, 0, 1), new Position(2, 0, 0, 1)));

        QueryResultSoA finalResult = notExecutor.execute(notCondition, indexes, granularity, 0, corpusName, defaultTestRequirements);

        assertNotNull(finalResult);
        assertEquals(1, finalResult.size());
        assertEquals(2, finalResult.getDocumentIdAt(0));
    }

    @Test
    void testExecute_subConditionReturnsAll_emptyUniverseLeadsToError() throws Exception {
        Not notCondition = new Not(subCondition);
        when(mockSubExecutor.execute(eq(subCondition), any(), eq(granularity), anyInt(), eq(corpusName), any(AttributeRequirements.class)))
            .thenReturn(nonEmptySubResult);

        when(mockDBIterator.hasNext()).thenReturn(false);

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            notExecutor.execute(notCondition, indexes, granularity, 0, corpusName, defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
    }

    @Test
    void testExecute_sentenceGranularity() throws Exception {
        granularity = Query.Granularity.SENTENCE;
        AttributeRequirements sentenceGranularityRequirements = new AttributeRequirements();
        sentenceGranularityRequirements.needsConceptualRowIds = true;
        sentenceGranularityRequirements.needsSentenceId = true;
        sentenceGranularityRequirements.needsDocumentId = true;

        emptySubResult = new QueryResultSoA(granularity, 0, sentenceGranularityRequirements);
        nonEmptySubResult = new QueryResultSoA(granularity, 0, sentenceGranularityRequirements);
        nonEmptySubResult.add("test", ValueType.TERM, null, 1, 1, 0, 4, -1, 0);

        Not notCondition = new Not(subCondition);

        when(mockSubExecutor.execute(eq(subCondition), any(), eq(granularity), anyInt(), eq(corpusName), any(AttributeRequirements.class)))
            .thenReturn(nonEmptySubResult);

        mockUnigramIndexForUniverse(List.of(
            new Position(1, 1, 0, 1),
            new Position(1, 2, 0, 1),
            new Position(2, 1, 0, 1)
        ));

        QueryResultSoA finalResult = notExecutor.execute(notCondition, indexes, granularity, 0, corpusName, sentenceGranularityRequirements);

        assertNotNull(finalResult);
        assertEquals(2, finalResult.size());
        Set<String> remainingSentenceKeys = new HashSet<>();
        for (int i = 0; i < finalResult.size(); i++) {
            remainingSentenceKeys.add(finalResult.getDocumentIdAt(i) + ":" + finalResult.getSentenceIdAt(i));
    }
        assertTrue(remainingSentenceKeys.contains("1:2"));
        assertTrue(remainingSentenceKeys.contains("2:1"));
        assertFalse(remainingSentenceKeys.contains("1:1"));
    }
}