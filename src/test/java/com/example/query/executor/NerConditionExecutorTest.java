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
import com.example.query.model.condition.Ner;

import org.iq80.leveldb.DBIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NerConditionExecutorTest {

    @Mock private IndexAccess nerIndex;
    @Mock private DBIterator mockIterator; 
    @InjectMocks private NerExecutor executor;

    private Map<String, IndexAccessInterface> indexes;
    private final LocalDate testDate = LocalDate.now();

    @BeforeEach
    void setUp() throws IndexAccessException {
        indexes = Map.of("ner", nerIndex);
        lenient().when(nerIndex.iterator()).thenReturn(mockIterator);
        lenient().doNothing().when(mockIterator).seek(any(byte[].class));
        lenient().when(mockIterator.hasNext()).thenReturn(false); 
    }

    private void setupIteratorMock(DBIterator iterator, String prefix, List<Map.Entry<byte[], PositionList>> entries) throws IOException, IndexAccessException {
        lenient().when(nerIndex.iterator()).thenReturn(iterator);
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        doNothing().when(iterator).seek(argThat(k -> Arrays.equals(k, prefixBytes)));

        Boolean[] hasNextValues = new Boolean[entries.size() + 1];
        Arrays.fill(hasNextValues, 0, entries.size(), true);
        hasNextValues[entries.size()] = false;
        OngoingStubbing<Boolean> hasNextStubbing = when(iterator.hasNext()).thenReturn(true);
        for(int i = 1; i < hasNextValues.length; i++) {
             hasNextStubbing = hasNextStubbing.thenReturn(hasNextValues[i]);
        }

        if (!entries.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map.Entry<byte[], byte[]>[] entryArray = entries.stream()
                 .map(e -> {
                    try {
                        return Map.entry(e.getKey(), e.getValue().serialize());
                    } catch (IOException ioe) {
                        // For test setup, rethrow as unchecked if direct handling is too verbose
                        throw new RuntimeException("Failed to serialize PositionList in test setup", ioe);
                    }
                 })
                 .toArray(Map.Entry[]::new);
            OngoingStubbing<Map.Entry<byte[], byte[]>> nextStubbing = when(iterator.next()).thenReturn(entryArray[0]);
            for(int i = 1; i < entryArray.length; i++) {
                 nextStubbing = nextStubbing.thenReturn(entryArray[i]);
            }
            nextStubbing.thenThrow(new java.util.NoSuchElementException());
        } else {
            when(iterator.next()).thenThrow(new java.util.NoSuchElementException());
        }
        

    }

    @Test
    void testExecuteSingleTypeDocument() throws Exception {
        Ner condition = new Ner("PERSON");
        String expectedKeyPrefix = "PERSON" + IndexAccessInterface.DELIMITER;
        
        PositionList posList1 = new PositionList(); posList1.add(new Position(1, 1, 0, 5, testDate));
        PositionList posList2 = new PositionList(); posList2.add(new Position(3, 1, 10, 15, testDate));
        
        List<Map.Entry<byte[], PositionList>> mockEntries = List.of(
            Map.entry((expectedKeyPrefix + "Alice").getBytes(), posList1),
            Map.entry((expectedKeyPrefix + "Bob").getBytes(), posList2)
        );

        setupIteratorMock(mockIterator, expectedKeyPrefix, mockEntries);

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getAllDetails().size());
        
        Set<Integer> docIds = result.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertTrue(docIds.containsAll(Set.of(1, 3)), "Result should contain document IDs 1 and 3");
        
        assertTrue(result.getAllDetails().stream().allMatch(d -> "PERSON".equals(d.value()) && d.valueType() == ValueType.ENTITY), "All results should be PERSON entities"); 
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.variableName().isEmpty()), "Variable name should be null for non-variable query");

        verify(nerIndex).iterator(); 
        verify(mockIterator).seek(argThat(k -> Arrays.equals(k, expectedKeyPrefix.getBytes())));
        verify(mockIterator, times(3)).hasNext(); 
        verify(mockIterator, times(2)).next();
    }

    @Test
    void testExecuteWithMultipleTypes() throws Exception {
        Ner conditionPerson = new Ner("PERSON"); 
        Ner conditionLocation = new Ner("LOCATION");
        
        String personPrefix = "PERSON" + IndexAccessInterface.DELIMITER;
        PositionList personPos = new PositionList(); personPos.add(new Position(1, 1, 0, 5, testDate));
        List<Map.Entry<byte[], PositionList>> personEntries = List.of(
            Map.entry((personPrefix + "Alice").getBytes(), personPos)
        );
        DBIterator personIterator = mock(DBIterator.class, "personIterator");
        when(nerIndex.iterator()).thenReturn(personIterator); 
        setupIteratorMock(personIterator, personPrefix, personEntries);
        QueryResult resultPerson = executor.execute(conditionPerson, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");

        String locationPrefix = "LOCATION" + IndexAccessInterface.DELIMITER;
        PositionList locPos1 = new PositionList(); locPos1.add(new Position(2, 1, 10, 15, testDate));
        PositionList locPos2 = new PositionList(); locPos2.add(new Position(2, 2, 20, 25, testDate));
        List<Map.Entry<byte[], PositionList>> locationEntries = List.of(
            Map.entry((locationPrefix + "Paris").getBytes(), locPos1),
            Map.entry((locationPrefix + "London").getBytes(), locPos2)
        );
        DBIterator locationIterator = mock(DBIterator.class, "locationIterator");
        when(nerIndex.iterator()).thenReturn(personIterator, locationIterator);
        setupIteratorMock(locationIterator, locationPrefix, locationEntries);
        QueryResult resultLocation = executor.execute(conditionLocation, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        
        List<MatchDetail> combinedDetails = new ArrayList<>(resultPerson.getAllDetails());
        combinedDetails.addAll(resultLocation.getAllDetails());
        QueryResult finalResult = new QueryResult(Query.Granularity.DOCUMENT, combinedDetails);

        assertNotNull(finalResult);
        assertEquals(3, finalResult.getAllDetails().size());
        Set<Integer> docIds = finalResult.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertTrue(docIds.containsAll(Set.of(1, 2)), "Combined result should contain doc IDs 1 and 2");
        
        Map<String, Long> countsByType = finalResult.getAllDetails().stream()
            .filter(d -> d.valueType() == ValueType.ENTITY)
            .collect(Collectors.groupingBy(d -> (String) d.value(), Collectors.counting()));
            
        assertEquals(1L, countsByType.getOrDefault("PERSON", 0L));
        assertEquals(2L, countsByType.getOrDefault("LOCATION", 0L));

        verify(personIterator).seek(argThat(k -> Arrays.equals(k, personPrefix.getBytes())));
        verify(locationIterator).seek(argThat(k -> Arrays.equals(k, locationPrefix.getBytes())));
        verify(nerIndex, times(2)).iterator(); 
    }

    @Test
    void testVariableBindingDocumentGranularity() throws Exception {
        Ner condition = new Ner("ORGANIZATION", "?orgVar", true); 
        String expectedKeyPrefix = "ORGANIZATION" + IndexAccessInterface.DELIMITER;
        
        PositionList posList1 = new PositionList(); posList1.add(new Position(4, 1, 0, 10, testDate));
        PositionList posList2 = new PositionList(); posList2.add(new Position(4, 2, 15, 25, testDate)); 
        
        List<Map.Entry<byte[], PositionList>> mockEntries = List.of(
            Map.entry((expectedKeyPrefix + "AcmeInc").getBytes(), posList1),
            Map.entry((expectedKeyPrefix + "Globex").getBytes(), posList2)
        );
        
        setupIteratorMock(mockIterator, expectedKeyPrefix, mockEntries);
        
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getAllDetails().size(), "Should have 2 match details (one for each position)"); 
        
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.getDocumentId() == 4), "All matches should be from document 4");
        
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.variableName().filter(v -> v.equals("?orgVar")).isPresent()), "Variable name should be '?orgVar'");
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.valueType() == ValueType.ENTITY), "ValueType should be ENTITY");

        Set<String> capturedValues = result.getAllDetails().stream().map(d -> (String) d.value()).collect(Collectors.toSet());
        assertTrue(capturedValues.containsAll(Set.of("AcmeInc", "Globex")), "Captured values should include 'AcmeInc' and 'Globex'");
        
        verify(nerIndex).iterator();
        verify(mockIterator).seek(argThat(k -> Arrays.equals(k, expectedKeyPrefix.getBytes())));
        verify(mockIterator, times(3)).hasNext();
        verify(mockIterator, times(2)).next();
    }
    
} 