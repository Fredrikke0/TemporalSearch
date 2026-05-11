package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.rocksdb.RocksIterator;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
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
    private IndexAccess mockUnigramIndex;
    @Mock
    private RocksIterator mockDBIterator;

    private NotExecutor notExecutor;
    private Map<String, IndexAccessInterface> indexes;
    private Query.Granularity granularity;
    private String corpusName = "test_corpus";
    private Contains subCondition;
    private CellResult emptySubResult;
    private CellResult nonEmptySubResult;

    @BeforeEach
    void setUp() throws Exception {
        notExecutor = new NotExecutor(mockFactory);
        indexes = Map.of("unigram", mockUnigramIndex);
        granularity = Query.Granularity.DOCUMENT;
        subCondition = new Contains("test");

        emptySubResult = CellResult.empty(granularity);

        Roaring64NavigableMap nonEmptyCells = new Roaring64NavigableMap();
        nonEmptyCells.add(PostingList.packCellKey(1, 0));
        nonEmptySubResult = CellResult.of(nonEmptyCells, granularity);

        lenient().when(mockFactory.getExecutor(any(Contains.class))).thenReturn(mockSubExecutor);
        lenient().when(mockUnigramIndex.iterateFromFirst()).thenReturn(mockDBIterator);
        lenient().when(mockDBIterator.isValid()).thenReturn(false);
    }

    private void mockUnigramIndexForUniverse(int[]... docSents) throws Exception {
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        for (int[] ds : docSents) {
            cells.add(PostingList.packCellKey(ds[0], ds[1]));
        }
        PostingList pl = PostingList.fromCells(cells, (byte) 0);
        byte[] universeBlob = pl.serialize();

        if (docSents.length == 0) {
            when(mockDBIterator.isValid()).thenReturn(false);
        } else {
            when(mockDBIterator.isValid()).thenReturn(true, false);
            when(mockDBIterator.key()).thenReturn("any_key".getBytes());
            when(mockDBIterator.value()).thenReturn(universeBlob);
        }
    }

    @Test
    void testExecute_subConditionReturnsEmpty() throws Exception {
        Not notCondition = new Not(subCondition);
        when(mockSubExecutor.execute(eq(subCondition), any(), eq(granularity), anyInt(), eq(corpusName),
                any(AttributeRequirements.class), any()))
                .thenReturn(emptySubResult);

        mockUnigramIndexForUniverse(new int[][] { { 100, 0 } });

        CellResult finalResult = notExecutor.execute(notCondition, indexes, granularity, 0, corpusName,
                new AttributeRequirements(), Optional.empty());

        assertNotNull(finalResult);
        assertEquals(1, finalResult.cellCount());
        assertEquals(100, PostingList.docIdFromCellKey(finalResult.cells().first()));
    }

    @Test
    void testExecute_subConditionReturnsMatch_universeExcludesMatch() throws Exception {
        Not notCondition = new Not(subCondition);
        when(mockSubExecutor.execute(eq(subCondition), any(), eq(granularity), anyInt(), eq(corpusName),
                any(AttributeRequirements.class), any()))
                .thenReturn(nonEmptySubResult);

        mockUnigramIndexForUniverse(new int[][] { { 1, 0 }, { 2, 0 } });

        CellResult finalResult = notExecutor.execute(notCondition, indexes, granularity, 0, corpusName,
                new AttributeRequirements(), Optional.empty());

        assertNotNull(finalResult);
        assertEquals(1, finalResult.cellCount());
        assertEquals(2, PostingList.docIdFromCellKey(finalResult.cells().first()));
    }

    @Test
    void testExecute_subConditionReturnsAll_emptyUniverseLeadsToError() throws Exception {
        Not notCondition = new Not(subCondition);
        lenient().when(mockSubExecutor.execute(eq(subCondition), any(), eq(granularity), anyInt(), eq(corpusName),
                any(AttributeRequirements.class), any()))
                .thenReturn(nonEmptySubResult);

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            notExecutor.execute(notCondition, indexes, granularity, 0, corpusName, new AttributeRequirements(),
                    Optional.empty());
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
    }

    @Test
    void testExecute_sentenceGranularity() throws Exception {
        granularity = Query.Granularity.SENTENCE;

        emptySubResult = CellResult.empty(granularity);
        Roaring64NavigableMap sentNonEmptyCells = new Roaring64NavigableMap();
        sentNonEmptyCells.add(PostingList.packCellKey(1, 1));
        nonEmptySubResult = CellResult.of(sentNonEmptyCells, granularity);

        Not notCondition = new Not(subCondition);

        when(mockSubExecutor.execute(eq(subCondition), any(), eq(granularity), anyInt(), eq(corpusName),
                any(AttributeRequirements.class), any()))
                .thenReturn(nonEmptySubResult);

        mockUnigramIndexForUniverse(new int[][] { { 1, 1 }, { 1, 2 }, { 2, 1 } });

        CellResult finalResult = notExecutor.execute(notCondition, indexes, granularity, 0, corpusName,
                new AttributeRequirements(), Optional.empty());

        assertNotNull(finalResult);
        assertEquals(2, finalResult.cellCount());
        Set<String> remainingSentenceKeys = new HashSet<>();
        var iter = finalResult.cells().getLongIterator();
        while (iter.hasNext()) {
            long ck = iter.next();
            remainingSentenceKeys.add(PostingList.docIdFromCellKey(ck) + ":" + PostingList.sentIdFromCellKey(ck));
        }
        assertTrue(remainingSentenceKeys.contains("1:2"));
        assertTrue(remainingSentenceKeys.contains("2:1"));
        assertFalse(remainingSentenceKeys.contains("1:1"));
    }
}
