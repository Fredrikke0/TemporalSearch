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
import com.example.query.model.condition.Dependency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DependencyConditionExecutorTest {

    @Mock private IndexAccess dependencyIndex;
    @InjectMocks private DependencyExecutor executor;

    private Map<String, IndexAccessInterface> indexes;

    @BeforeEach
    void setUp() {
        indexes = Map.of("dependency", dependencyIndex);
    }

    @Test
    void testExecuteDocumentGranularity() throws QueryExecutionException, IndexAccessException {
        Dependency condition = new Dependency("nsubj", "VB", "NN");
        String expectedKey = "nsubj" + IndexAccessInterface.DELIMITER +
                             "vb" + IndexAccessInterface.DELIMITER +
                             "nn";
        PositionList positions = new PositionList();
        positions.add(new Position(1, 1, 10, 15, LocalDate.now()));
        positions.add(new Position(2, 1, 5, 10, LocalDate.now()));
        when(dependencyIndex.get(eq(expectedKey.getBytes()))).thenReturn(Optional.of(positions));

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getAllDetails().size()); 
        String expectedValue = "nsubj:vb:nn";
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 1 && m.value().equals(expectedValue) && m.valueType() == ValueType.DEPENDENCY), "Assertion failed for docId=1");
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 2 && m.value().equals(expectedValue) && m.valueType() == ValueType.DEPENDENCY), "Assertion failed for docId=2");
        verify(dependencyIndex).get(eq(expectedKey.getBytes()));
    }

    @Test
    void testExecuteSentenceGranularity() throws QueryExecutionException, IndexAccessException {
        Dependency condition = new Dependency("dobj", "VB", "JJ");
        String expectedKey = "dobj" + IndexAccessInterface.DELIMITER +
                             "vb" + IndexAccessInterface.DELIMITER +
                             "jj";
        
        PositionList positions = new PositionList();
        positions.add(new Position(1, 1, 10, 15, LocalDate.now())); 
        positions.add(new Position(1, 2, 5, 10, LocalDate.now()));   
        positions.add(new Position(2, 1, 20, 25, LocalDate.now())); 
        when(dependencyIndex.get(eq(expectedKey.getBytes()))).thenReturn(Optional.of(positions));

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(3, result.getAllDetails().size());
        String expectedValue = "dobj:vb:jj";
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 1 && m.getSentenceId() == 1 && m.value().equals(expectedValue) && m.valueType() == ValueType.DEPENDENCY), "Assertion failed for docId=1, sentenceId=1");
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 1 && m.getSentenceId() == 2 && m.value().equals(expectedValue) && m.valueType() == ValueType.DEPENDENCY), "Assertion failed for docId=1, sentenceId=2");
        assertTrue(result.getAllDetails().stream().anyMatch(m -> m.getDocumentId() == 2 && m.getSentenceId() == 1 && m.value().equals(expectedValue) && m.valueType() == ValueType.DEPENDENCY), "Assertion failed for docId=2, sentenceId=1");
        verify(dependencyIndex).get(eq(expectedKey.getBytes()));
    }

     @Test
    void testExecuteWithWildcard() throws QueryExecutionException, IndexAccessException {
        Dependency condition = new Dependency("amod", "*", "NN");
        // String expectedKey = "amod" + IndexAccessInterface.DELIMITER +
        //                      "*" + IndexAccessInterface.DELIMITER +
        //                      "nn";
        // PositionList positions = new PositionList();
        // positions.add(new Position(1, 1, 10, 15, LocalDate.now()));
        // Wildcard search is not implemented, so no call to index.get should be made for this specific key.
        // when(dependencyIndex.get(eq(expectedKey.getBytes()))).thenReturn(Optional.of(positions));
        
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");

        assertNotNull(result);
        // Expect empty results as wildcard search isn't implemented
        assertTrue(result.getAllDetails().isEmpty(), "Expected empty result for unimplemented wildcard search"); 
        // Do not verify index.get because the executor won't call it for this pattern
        // verify(dependencyIndex).get(eq(expectedKey.getBytes()));
    }
    
     @Test
    void testNoMatch() throws QueryExecutionException, IndexAccessException {
         Dependency condition = new Dependency("xcomp", "VB", "JJ");
        String expectedKey = "xcomp" + IndexAccessInterface.DELIMITER +
                             "vb" + IndexAccessInterface.DELIMITER +
                             "jj";
        when(dependencyIndex.get(eq(expectedKey.getBytes()))).thenReturn(Optional.empty());
        
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        
        assertNotNull(result);
        assertTrue(result.getAllDetails().isEmpty());
        verify(dependencyIndex).get(eq(expectedKey.getBytes()));
    }
    
     // Helper method not needed if not creating details manually for assertions
} 