package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.iq80.leveldb.DBIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Ner;

@ExtendWith(MockitoExtension.class)
class NerExecutorTest {

    @Mock private IndexAccess nerIndex;
    @Mock private IndexAccess nerDateIndex;
    @Mock private DBIterator mockIterator;
    @InjectMocks private NerExecutor executor;

    private Map<String, IndexAccessInterface> indexes;
    private final LocalDate testDate = LocalDate.now();
    private AttributeRequirements defaultTestRequirements;

    private static final String NER_INDEX_NAME = "ner";
    private static final String NER_DATE_INDEX_NAME = "ner_date";

    @BeforeEach
    void setUp() throws IndexAccessException {
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsSynonymIds = true;
        defaultTestRequirements.needsDateValues = true;

        indexes = new HashMap<>();
        indexes.put(NER_INDEX_NAME, nerIndex);
        indexes.put(NER_DATE_INDEX_NAME, nerDateIndex);
    }

    private void setupIteratorMockForSeek(DBIterator iterator, IndexAccessInterface targetIndex, String prefix, List<Map.Entry<byte[], PositionListSoA>> entries) throws IOException, IndexAccessException {
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        lenient().when(targetIndex.seek(argThat(k -> Arrays.equals(k, prefixBytes)))).thenReturn(iterator);

        Boolean[] hasNextValues = new Boolean[entries.size() + 1];
        Arrays.fill(hasNextValues, 0, entries.size(), true);
        hasNextValues[entries.size()] = false;

        OngoingStubbing<Boolean> hasNextStubbing = when(iterator.hasNext());
        for (Boolean val : hasNextValues) {
            hasNextStubbing = hasNextStubbing.thenReturn(val);
        }

        if (!entries.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map.Entry<byte[], byte[]>[] entryArray = entries.stream()
                 .map(e -> {
                    try {
                        return Map.entry(e.getKey(), e.getValue().serializeToCompositeBlob());
                    } catch (IOException ioe) {
                        throw new RuntimeException("Failed to serialize PositionListSoA in test setup", ioe);
                    }
                 })
                 .toArray(Map.Entry[]::new);

            OngoingStubbing<Map.Entry<byte[], byte[]>> nextStubbing = when(iterator.next());
            for (Map.Entry<byte[], byte[]> entry : entryArray) {
                nextStubbing = nextStubbing.thenReturn(entry);
            }
            nextStubbing.thenThrow(new java.util.NoSuchElementException("No more mock entries"));
        } else {
            lenient().when(iterator.next()).thenThrow(new java.util.NoSuchElementException("No mock entries provided"));
        }
    }

    private void setupIteratorMockForIterateFromFirst(DBIterator iterator, IndexAccessInterface targetIndex, List<Map.Entry<byte[], PositionListSoA>> entries) throws IOException, IndexAccessException {
        lenient().when(targetIndex.iterateFromFirst()).thenReturn(iterator);

        Boolean[] hasNextValues = new Boolean[entries.size() + 1];
        Arrays.fill(hasNextValues, 0, entries.size(), true);
        hasNextValues[entries.size()] = false;

        OngoingStubbing<Boolean> hasNextStubbing = when(iterator.hasNext());
        for (Boolean val : hasNextValues) {
            hasNextStubbing = hasNextStubbing.thenReturn(val);
        }
         if (!entries.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map.Entry<byte[], byte[]>[] entryArray = entries.stream()
                 .map(e -> {
                    try {
                        return Map.entry(e.getKey(), e.getValue().serializeToCompositeBlob());
                    } catch (IOException ioe) {
                        throw new RuntimeException("Failed to serialize PositionListSoA in test setup", ioe);
                    }
                 })
                 .toArray(Map.Entry[]::new);

            OngoingStubbing<Map.Entry<byte[], byte[]>> nextStubbing = when(iterator.next());
            for (Map.Entry<byte[], byte[]> entry : entryArray) {
                nextStubbing = nextStubbing.thenReturn(entry);
            }
            nextStubbing.thenThrow(new java.util.NoSuchElementException("No more mock entries"));
        } else {
            lenient().when(iterator.next()).thenThrow(new java.util.NoSuchElementException("No mock entries provided"));
        }
    }

    @Test
    void testExecuteSingleTypeDocument() throws Exception {
        Ner condition = new Ner("PERSON");
        String expectedKeyPrefix = "PERSON" + IndexAccessInterface.DELIMITER;

        PositionListSoA posList1 = new PositionListSoA(); posList1.add(new Position(1, 1, 0, 5));
        PositionListSoA posList2 = new PositionListSoA(); posList2.add(new Position(3, 1, 10, 15));

        List<Map.Entry<byte[], PositionListSoA>> mockEntries = List.of(
            Map.entry((expectedKeyPrefix + "Alice").getBytes(), posList1),
            Map.entry((expectedKeyPrefix + "Bob").getBytes(), posList2)
        );

        setupIteratorMockForSeek(mockIterator, nerIndex, expectedKeyPrefix, mockEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.size());

        Set<Integer> docIds = new HashSet<>();
        Set<Object> values = new HashSet<>();
        Set<String> varNames = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
            values.add(result.getValueAt(i));
            varNames.add(result.getVariableNameAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
        }
        assertTrue(docIds.containsAll(Set.of(1, 3)), "Result should contain document IDs 1 and 3. Found: " + docIds);
        assertTrue(values.containsAll(Set.of("Alice", "Bob")), "All values should be specific entities 'Alice', 'Bob'. Found: " + values);
        assertTrue(varNames.stream().allMatch(Objects::isNull), "All variable names should be null. Found: " + varNames);

        verify(nerIndex).seek(argThat(k -> Arrays.equals(k, expectedKeyPrefix.getBytes())));
        verify(mockIterator, times(mockEntries.size() + 1)).hasNext();
        verify(mockIterator, times(mockEntries.size())).next();
    }

    @Test
    void testExecuteWithMultipleTypes() throws Exception {
        Ner conditionPerson = new Ner("PERSON");
        String personPrefix = "PERSON" + IndexAccessInterface.DELIMITER;
        PositionListSoA personPos = new PositionListSoA(); personPos.add(new Position(1, 1, 0, 5));
        List<Map.Entry<byte[], PositionListSoA>> personEntries = List.of(
            Map.entry((personPrefix + "Alice").getBytes(), personPos)
        );
        DBIterator personIterator = mock(DBIterator.class, "personIterator");
        setupIteratorMockForSeek(personIterator, nerIndex, personPrefix, personEntries);
        QueryResultSoA resultPerson = executor.execute(conditionPerson, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        Ner conditionLocation = new Ner("LOCATION");
        String locationPrefix = "LOCATION" + IndexAccessInterface.DELIMITER;
        PositionListSoA locPos1 = new PositionListSoA(); locPos1.add(new Position(2, 1, 10, 15));
        PositionListSoA locPos2 = new PositionListSoA(); locPos2.add(new Position(2, 2, 20, 25));
        List<Map.Entry<byte[], PositionListSoA>> locationEntries = List.of(
            Map.entry((locationPrefix + "Paris").getBytes(), locPos1),
            Map.entry((locationPrefix + "London").getBytes(), locPos2)
        );
        DBIterator locationIterator = mock(DBIterator.class, "locationIterator");
        setupIteratorMockForSeek(locationIterator, nerIndex, locationPrefix, locationEntries);
        QueryResultSoA resultLocation = executor.execute(conditionLocation, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertEquals(1, resultPerson.size());
        assertEquals("Alice", resultPerson.getValueAt(0));
        assertEquals(1, resultPerson.getDocumentIdAt(0));

        assertEquals(2, resultLocation.size());
        Set<String> locValues = new HashSet<>();
        for(int i=0; i < resultLocation.size(); i++){
            assertEquals(2, resultLocation.getDocumentIdAt(i));
            locValues.add((String)resultLocation.getValueAt(i));
        }
        assertTrue(locValues.containsAll(Set.of("Paris", "London")), "Expected location values Paris and London");

        verify(nerIndex).seek(argThat(k -> Arrays.equals(k, personPrefix.getBytes())));
        verify(nerIndex).seek(argThat(k -> Arrays.equals(k, locationPrefix.getBytes())));
    }

    @Test
    void testVariableBindingDocumentGranularity() throws Exception {
        Ner condition = new Ner("ORGANIZATION", "?orgVar", true);
        String expectedKeyPrefix = "ORGANIZATION" + IndexAccessInterface.DELIMITER;

        PositionListSoA posList1 = new PositionListSoA(); posList1.add(new Position(4, 1, 0, 10));
        PositionListSoA posList2 = new PositionListSoA(); posList2.add(new Position(4, 2, 15, 25));

        List<Map.Entry<byte[], PositionListSoA>> mockEntries = List.of(
            Map.entry((expectedKeyPrefix + "AcmeInc").getBytes(), posList1),
            Map.entry((expectedKeyPrefix + "GlobexCorp").getBytes(), posList2)
        );

        setupIteratorMockForSeek(mockIterator, nerIndex, expectedKeyPrefix, mockEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.size());

        Set<Integer> docIds = new HashSet<>();
        Set<Object> values = new HashSet<>();
        Set<String> varNames = new HashSet<>();

        for(int i=0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
            values.add(result.getValueAt(i));
            varNames.add(result.getVariableNameAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
        }

        assertTrue(docIds.stream().allMatch(d -> d == 4), "All matches should be from document 4. Found: " + docIds);
        assertTrue(varNames.stream().allMatch(v -> "?orgVar".equals(v)), "Variable name should be '?orgVar'. Found: " + varNames);
        assertTrue(values.containsAll(Set.of("AcmeInc", "GlobexCorp")), "Captured values should include 'AcmeInc' and 'GlobexCorp'. Found: " + values);

        verify(nerIndex).seek(argThat(k -> Arrays.equals(k, expectedKeyPrefix.getBytes())));
        verify(mockIterator, times(mockEntries.size() + 1)).hasNext();
        verify(mockIterator, times(mockEntries.size())).next();
    }

    @Test
    void testExecuteEntityTypeSearch_allTypesWithVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Ner conditionPerson = new Ner("PERSON", "?entity", true);

        DBIterator allNerIterator = mock(DBIterator.class, "allNerIterator");

        String personPrefix = "PERSON" + IndexAccessInterface.DELIMITER;
        PositionListSoA personPositions1 = new PositionListSoA(); personPositions1.add(new Position(1, 1, 0, 8));
        PositionListSoA personPositions2 = new PositionListSoA(); personPositions2.add(new Position(1, 2, 10, 15));

        String orgPrefix = "ORGANIZATION" + IndexAccessInterface.DELIMITER;
        PositionListSoA orgPositions1 = new PositionListSoA(); orgPositions1.add(new Position(2,1,5,12));

        List<Map.Entry<byte[], PositionListSoA>> allEntries = List.of(
            Map.entry((personPrefix + "John Doe").getBytes(), personPositions1),
            Map.entry((personPrefix + "Jane Doe").getBytes(), personPositions2),
            Map.entry((orgPrefix + "OrgName").getBytes(), orgPositions1)
        );
        setupIteratorMockForSeek(allNerIterator, nerIndex, personPrefix, allEntries);

        QueryResultSoA resultPerson = executor.execute(conditionPerson, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(resultPerson);
        assertEquals(2, resultPerson.size());

        Set<Object> values = new HashSet<>();
        for(int i=0; i<resultPerson.size(); i++) {
            values.add(resultPerson.getValueAt(i));
            assertEquals("?entity", resultPerson.getVariableNameAt(i));
            assertEquals(ValueType.ENTITY, resultPerson.getValueTypeAt(i));
            assertEquals(1, resultPerson.getDocumentIdAt(i));
        }
        assertTrue(values.containsAll(Set.of("John Doe", "Jane Doe")), "Expected John Doe and Jane Doe. Found: " + values);
    }

    @Test
    void testExecuteEntitySearchWithTarget_noVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("PERSON", "John Doe");

        String key = "PERSON" + IndexAccessInterface.DELIMITER + "John Doe";
        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(4, 1, 0, 8));
        positions.add(new Position(4, 3, 5, 12));

        when(nerIndex.getRaw(key.getBytes())).thenReturn(Optional.of(positions.serializeToCompositeBlob()));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(2, result.size());

        for(int i=0; i < result.size(); i++) {
            assertEquals("John Doe", result.getValueAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
            assertEquals(4, result.getDocumentIdAt(i));
        }
        verify(nerIndex).getRaw(key.getBytes());
    }

    @Test
    void testExecuteEntityTypeSearch_noVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("PERSON");

        String keyPrefix = "PERSON" + IndexAccessInterface.DELIMITER;
        PositionListSoA positions = new PositionListSoA(); positions.add(new Position(1, 1, 0, 8));

        List<Map.Entry<byte[], PositionListSoA>> entries = Collections.singletonList(
            Map.entry((keyPrefix + "John Doe").getBytes(), positions)
        );
        setupIteratorMockForSeek(mockIterator, nerIndex, keyPrefix, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("John Doe", result.getValueAt(0));
        assertEquals(ValueType.ENTITY, result.getValueTypeAt(0));
        assertNull(result.getVariableNameAt(0));
        assertEquals(1, result.getDocumentIdAt(0));
    }

    @Test
    void testExecuteEntityTypeSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("LOCATION", "?loc", true);

        String keyPrefix = "LOCATION" + IndexAccessInterface.DELIMITER;
        PositionListSoA positions = new PositionListSoA(); positions.add(new Position(2, 1, 10, 18));

        List<Map.Entry<byte[], PositionListSoA>> entries = Collections.singletonList(
            Map.entry((keyPrefix + "New York").getBytes(), positions)
        );
        setupIteratorMockForSeek(mockIterator, nerIndex, keyPrefix, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("New York", result.getValueAt(0));
        assertEquals(ValueType.ENTITY, result.getValueTypeAt(0));
        assertEquals("?loc", result.getVariableNameAt(0));
        assertEquals(2, result.getDocumentIdAt(0));
    }

    @Test
    void testExecuteDateSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("DATE", null, "?actualDate", true);

        String expectedKeyPrefix = "DATE" + IndexAccessInterface.DELIMITER;

        DBIterator dateIterator = mock(DBIterator.class, "dateIterator");

        PositionListSoA posListDate1 = new PositionListSoA(); posListDate1.add(new Position(10, 1, 0, 5));
        PositionListSoA posListDate2 = new PositionListSoA(); posListDate2.add(new Position(11, 1, 0, 5));

        String dateString1 = "20230115";
        String dateString2 = "20240220";

        List<Map.Entry<byte[], PositionListSoA>> dateEntries = List.of(
            Map.entry((expectedKeyPrefix + dateString1).getBytes(), posListDate1),
            Map.entry((expectedKeyPrefix + dateString2).getBytes(), posListDate2)
        );

        setupIteratorMockForSeek(dateIterator, nerDateIndex, expectedKeyPrefix, dateEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(2, result.size(), "Should find two date entities");

        Set<String> foundDates = new HashSet<>();
        for(int i=0; i < result.size(); i++) {
            assertEquals(ValueType.DATE, result.getValueTypeAt(i), "ValueType should be DATE");
            assertEquals("?actualDate", result.getVariableNameAt(i), "Variable name should be ?actualDate");
            foundDates.add((String)result.getValueAt(i));
        }
        assertTrue(foundDates.contains(dateString1), "Result should contain date string: " + dateString1);
        assertTrue(foundDates.contains(dateString2), "Result should contain date string: " + dateString2);

        verify(nerDateIndex).seek(argThat(k -> Arrays.equals(k, expectedKeyPrefix.getBytes())));
        verify(dateIterator, times(dateEntries.size() + 1)).hasNext();
        verify(dateIterator, times(dateEntries.size())).next();
    }

    @Test
    void testExecute_noMatchFound_iterator() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("PERSON");
        String expectedKeyPrefix = "PERSON" + IndexAccessInterface.DELIMITER;

        setupIteratorMockForSeek(mockIterator, nerIndex, expectedKeyPrefix, Collections.emptyList());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(nerIndex).seek(argThat(k -> Arrays.equals(k, expectedKeyPrefix.getBytes())));
    }

    @Test
    void testExecute_noMatchFound_get() throws QueryExecutionException, IndexAccessException {
        Ner condition = new Ner("PERSON", "NonExistentPerson");
        lenient().when(nerIndex.getRaw(any(byte[].class))).thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExecute_missingNerIndex() {
        Ner condition = new Ner("PERSON");
        Map<String, IndexAccessInterface> incompleteIndexes = Map.of(NER_DATE_INDEX_NAME, nerDateIndex);

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, incompleteIndexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
        assertTrue(exception.getMessage().contains(NER_INDEX_NAME));
    }

    @Test
    void testExecute_missingNerDateIndex() {
        Ner condition = new Ner("DATE");
        Map<String, IndexAccessInterface> incompleteIndexes = Map.of(NER_INDEX_NAME, nerIndex);

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, incompleteIndexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
        assertTrue(exception.getMessage().contains(NER_DATE_INDEX_NAME));
    }

    @Test
    void testExecute_wildcardNotSupported() {
        Ner condition = new Ner("*");
        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION, exception.getErrorType());
        assertTrue(exception.getMessage().contains("Wildcard entity type (*) is not currently supported"));
    }
}