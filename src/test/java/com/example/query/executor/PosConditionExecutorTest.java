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
import com.example.query.model.condition.Pos;

import org.iq80.leveldb.DBIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Disabled;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PosConditionExecutorTest {

    @Mock private IndexAccess posIndex;
    @Mock private DBIterator posIterator;
    @Mock private ConditionExecutorFactory factory;
    @InjectMocks private PosExecutor executor;

    private Map<String, IndexAccessInterface> indexes;

    @BeforeEach
    void setUp() throws IndexAccessException {
        indexes = Map.of("pos", posIndex);
        lenient().when(posIndex.iterator()).thenReturn(posIterator);
        lenient().when(posIterator.hasNext()).thenReturn(false);
        lenient().when(posIndex.get(any(byte[].class))).thenReturn(Optional.empty());
    }

    @Test
    @Disabled("Basic POS index doesn't support term specification (POS(tag, 'term')). Requires stitch index.")
    void testExecuteSpecificTermDocumentGranularity() throws Exception {
        Pos condition = new Pos("NN", "test"); 
        String expectedKey = "nn" + IndexAccessInterface.DELIMITER + "test";

        PositionList positions = new PositionList();
        positions.add(new Position(1, 0, 5, 10));
        positions.add(new Position(2, 1, 15, 20));
        positions.add(new Position(1, 1, 25, 30));
        when(posIndex.get(eq(expectedKey.getBytes()))).thenReturn(Optional.of(positions));

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(3, result.getAllDetails().size());
        Set<Integer> docIds = result.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertTrue(docIds.containsAll(Set.of(1, 2)));
        assertTrue(result.getAllDetails().stream().allMatch(d -> "test/NN".equals(d.value()) && d.valueType() == ValueType.POS_TERM)); 
        assertNull(result.getAllDetails().get(0).variableName()); 
        verify(posIndex).get(eq(expectedKey.getBytes())); 
    }
    
    @Test
    @Disabled("Basic POS index doesn't support term specification (POS(tag, 'term')). Requires stitch index.")
    void testExecuteSpecificTermSentenceGranularity() throws Exception {
        Pos condition = new Pos("VB", "run");
        String expectedKey = "vb" + IndexAccessInterface.DELIMITER + "run";

        PositionList positions = new PositionList();
        positions.add(new Position(1, 1, 1, 2));
        positions.add(new Position(1, 2, 3, 4));
        positions.add(new Position(2, 1, 5, 6));
        positions.add(new Position(1, 1, 10, 15));
        
        when(posIndex.get(eq(expectedKey.getBytes()))).thenReturn(Optional.of(positions));

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus");
        
        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(4, result.getAllDetails().size());
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 1 && m.getSentenceId() == 1 && "run/VB".equals(m.value())));
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 1 && m.getSentenceId() == 2 && "run/VB".equals(m.value())));
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 2 && m.getSentenceId() == 1 && "run/VB".equals(m.value())));
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.valueType() == ValueType.POS_TERM));
        
        verify(posIndex).get(eq(expectedKey.getBytes()));
    }

    @Test
    @Disabled("Basic POS index doesn't support term specification (POS(tag, 'term')). Requires stitch index.")
    void testSentenceGranularityWithWindow() throws Exception {
        Pos condition = new Pos("NN", "noun");
        String expectedKey = "nn" + IndexAccessInterface.DELIMITER + "noun";

        PositionList positions = new PositionList();
        positions.add(new Position(1, 0, 1, 2));
        positions.add(new Position(1, 2, 3, 4));
        positions.add(new Position(1, 3, 5, 6));
        positions.add(new Position(2, 1, 7, 8));
        
        when(posIndex.get(eq(expectedKey.getBytes()))).thenReturn(Optional.of(positions));

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 1, "test_corpus"); 

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(4, result.getAllDetails().size()); 
        
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 1 && m.getSentenceId() == 0 && "noun/NN".equals(m.value())));
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 1 && m.getSentenceId() == 2 && "noun/NN".equals(m.value())));
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 2 && m.getSentenceId() == 1 && "noun/NN".equals(m.value())));

        assertTrue(result.getAllDetails().stream().allMatch(d -> d.valueType() == ValueType.POS_TERM));
        verify(posIndex).get(eq(expectedKey.getBytes()));
    }

    @Test
    void testVariableBindingDocumentGranularity() throws Exception {
        Pos condition = new Pos("JJ", null, "?adjVar"); 
        // Executor now uses direct get() with the normalized tag
        String expectedKey = "jj"; 
        byte[] expectedKeyBytes = expectedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Define the expected positions combined into one list for the 'jj' tag
        PositionList positions = new PositionList(); 
        positions.add(new Position(1, 1, 5, 10)); // From original posList1
        positions.add(new Position(2, 1, 15, 20)); // From original posList2
        positions.add(new Position(1, 2, 25, 30)); // From original posList3
        
        // Mock the direct get() call
        when(posIndex.get(eq(expectedKeyBytes))).thenReturn(Optional.of(positions));
        
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        // We expect 3 details because the combined list has 3 positions
        assertEquals(3, result.getAllDetails().size()); 
        
        Set<Integer> docIds = result.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertTrue(docIds.containsAll(Set.of(1, 2)), "Docs 1 and 2 should be present");
        
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.variableName().isPresent() && d.variableName().get().equals("?adjVar")), "Variable name mismatch");
        // Assert the correct ValueType based on the modified executor
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.valueType() == ValueType.POS_TERM), "ValueType mismatch");

        // Check captured values (should be the original tag "JJ")
        Set<String> capturedValues = result.getAllDetails().stream().map(d -> (String) d.value()).collect(Collectors.toSet());
        assertEquals(Set.of("JJ"), capturedValues, "Captured value should be the tag itself");

        // Verify interactions: Should call get(), not iterator methods
        verify(posIndex).get(eq(expectedKeyBytes)); 
        verify(posIndex, times(0)).iterator(); 
        verify(posIterator, times(0)).seek(any());
        verify(posIterator, times(0)).hasNext(); 
        verify(posIterator, times(0)).next(); 
    }
} 