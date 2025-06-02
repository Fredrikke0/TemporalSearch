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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.RocksIterator;

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
    @Mock private RocksIterator mockIterator;
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
        defaultTestRequirements.needsConceptualRowIds = true;

        indexes = new HashMap<>();
        indexes.put(NER_INDEX_NAME, nerIndex);
        indexes.put(NER_DATE_INDEX_NAME, nerDateIndex);
    }

    private byte[] soaToBlob(PositionListSoA soa) throws IOException {
        if (soa == null) return null;
        return soa.serializeToCompositeBlob();
    }

    private void configureRocksIteratorMock(RocksIterator iterator, final List<Map.Entry<byte[], byte[]>> entries) {
        final AtomicInteger currentIndex = new AtomicInteger(-1);

        lenient().when(iterator.isValid()).thenAnswer(inv -> {
            int i = currentIndex.get();
            return i >= 0 && i < entries.size();
        });

        lenient().when(iterator.key()).thenAnswer(inv -> {
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                return entries.get(i).getKey();
            }
            throw new IllegalStateException("Iterator not valid or out of bounds for key(). Index: " + i + ", Size: " + entries.size());
        });

        lenient().when(iterator.value()).thenAnswer(inv -> {
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                return entries.get(i).getValue();
            }
            throw new IllegalStateException("Iterator not valid or out of bounds for value(). Index: " + i + ", Size: " + entries.size());
        });

        lenient().doAnswer(inv -> {
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                currentIndex.incrementAndGet();
            }
            return null;
        }).when(iterator).next();

        lenient().doAnswer(inv -> {
            currentIndex.set(entries.isEmpty() ? 0 : 0);
            return null;
        }).when(iterator).seekToFirst();

        lenient().doAnswer(inv -> {
            byte[] targetKey = inv.getArgument(0);
            currentIndex.set(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                if (Arrays.compare(entries.get(i).getKey(), targetKey) >= 0) {
                    currentIndex.set(i);
                    break;
                }
            }
            return null;
        }).when(iterator).seek(any(byte[].class));
    }

    private void setupIteratorMockForSeek(RocksIterator iteratorToConfigure, IndexAccessInterface targetIndex, String prefix, List<Map.Entry<byte[], PositionListSoA>> conceptualEntries) throws IOException, IndexAccessException {
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        List<Map.Entry<byte[], byte[]>> rawEntries = conceptualEntries.stream()
            .map(e -> {
                try {
                    return Map.entry(e.getKey(), soaToBlob(e.getValue()));
                } catch (IOException ioe) {
                    throw new RuntimeException("Serialization failed in test setup", ioe);
                }
            })
            .toList();

        configureRocksIteratorMock(iteratorToConfigure, rawEntries);

        lenient().when(targetIndex.seek(argThat(k -> Arrays.equals(k, prefixBytes)))).thenAnswer(invocation -> {
            iteratorToConfigure.seek(prefixBytes);
            return iteratorToConfigure;
        });
    }

    private void setupIteratorMockForIterateFromFirst(RocksIterator iteratorToConfigure, IndexAccessInterface targetIndex, List<Map.Entry<byte[], PositionListSoA>> conceptualEntries) throws IOException, IndexAccessException {
        List<Map.Entry<byte[], byte[]>> rawEntries = conceptualEntries.stream()
            .map(e -> {
                try {
                    return Map.entry(e.getKey(), soaToBlob(e.getValue()));
                } catch (IOException ioe) {
                    throw new RuntimeException("Serialization failed in test setup", ioe);
                }
            })
            .toList();

        configureRocksIteratorMock(iteratorToConfigure, rawEntries);

        lenient().when(targetIndex.iterateFromFirst()).thenAnswer(invocation -> {
            iteratorToConfigure.seekToFirst();
            return iteratorToConfigure;
        });
    }

    @Test
    void testExecuteSingleTypeDocument() throws Exception {
        Ner condition = new Ner("PERSON");
        String expectedKeyPrefix = "PERSON" + IndexAccessInterface.DELIMITER;

        PositionListSoA posList1 = new PositionListSoA(); posList1.add(new Position(1, 1, 0, 5));
        PositionListSoA posList2 = new PositionListSoA(); posList2.add(new Position(3, 1, 10, 15));

        List<Map.Entry<byte[], PositionListSoA>> mockConceptualEntries = List.of(
            Map.entry((expectedKeyPrefix + "Alice").getBytes(), posList1),
            Map.entry((expectedKeyPrefix + "Bob").getBytes(), posList2)
        );

        RocksIterator specificMockIterator = mock(RocksIterator.class, "singleTypeIterator");
        setupIteratorMockForSeek(specificMockIterator, nerIndex, expectedKeyPrefix, mockConceptualEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getConceptualRowCount());
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
    }

    @Test
    void testExecuteWithMultipleTypes() throws Exception {
        Ner conditionPerson = new Ner("PERSON");
        String personPrefix = "PERSON" + IndexAccessInterface.DELIMITER;
        PositionListSoA personPos = new PositionListSoA(); personPos.add(new Position(1, 1, 0, 5));
        List<Map.Entry<byte[], PositionListSoA>> personConceptualEntries = List.of(
            Map.entry((personPrefix + "Alice").getBytes(), personPos)
        );
        RocksIterator personIterator = mock(RocksIterator.class, "personIterator");
        setupIteratorMockForSeek(personIterator, nerIndex, personPrefix, personConceptualEntries);
        QueryResultSoA resultPerson = executor.execute(conditionPerson, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        Ner conditionLocation = new Ner("LOCATION");
        String locationPrefix = "LOCATION" + IndexAccessInterface.DELIMITER;
        PositionListSoA locPos1 = new PositionListSoA(); locPos1.add(new Position(2, 1, 10, 15));
        PositionListSoA locPos2 = new PositionListSoA(); locPos2.add(new Position(2, 2, 20, 25));
        List<Map.Entry<byte[], PositionListSoA>> locationConceptualEntries = List.of(
            Map.entry((locationPrefix + "Paris").getBytes(), locPos1),
            Map.entry((locationPrefix + "London").getBytes(), locPos2)
        );
        RocksIterator locationIterator = mock(RocksIterator.class, "locationIterator");
        setupIteratorMockForSeek(locationIterator, nerIndex, locationPrefix, locationConceptualEntries);
        QueryResultSoA resultLocation = executor.execute(conditionLocation, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertEquals(1, resultPerson.size());
        assertEquals("Alice", resultPerson.getValueAt(0));
        assertEquals(1, resultPerson.getDocumentIdAt(0));

        assertEquals(2, resultLocation.getConceptualRowCount());
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

        List<Map.Entry<byte[], PositionListSoA>> mockConceptualEntries = List.of(
            Map.entry((expectedKeyPrefix + "AcmeInc").getBytes(), posList1),
            Map.entry((expectedKeyPrefix + "GlobexCorp").getBytes(), posList2)
        );

        RocksIterator iterator = mock(RocksIterator.class, "orgIterator");
        setupIteratorMockForSeek(iterator, nerIndex, expectedKeyPrefix, mockConceptualEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getConceptualRowCount());
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
    }

    @Test
    void testExecuteEntityTypeSearch_allTypesWithVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("?_", "?anyEntityTerm", true);

        RocksIterator allNerIterator = mock(RocksIterator.class, "allNerIterator");

        String personKey = "PERSON" + IndexAccessInterface.DELIMITER + "John Doe";
        PositionListSoA personPositions = new PositionListSoA(); personPositions.add(new Position(1, 1, 0, 8));

        String orgKey = "ORGANIZATION" + IndexAccessInterface.DELIMITER + "MegaCorp";
        PositionListSoA orgPositions = new PositionListSoA(); orgPositions.add(new Position(2,1,5,12));

        List<Map.Entry<byte[], PositionListSoA>> allConceptualEntries = List.of(
            Map.entry(personKey.getBytes(), personPositions),
            Map.entry(orgKey.getBytes(), orgPositions)
        );
        setupIteratorMockForIterateFromFirst(allNerIterator, nerIndex, allConceptualEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount(), "Should find two distinct entities (John Doe, MegaCorp)");
        assertEquals(2, result.size(), "Should have two binding entries");

        Set<String> foundTerms = new HashSet<>();
        Set<String> foundVarNames = new HashSet<>();
        Set<ValueType> foundValueTypes = new HashSet<>();

        for(int i=0; i<result.size(); i++) {
            foundTerms.add((String) result.getValueAt(i));
            foundVarNames.add(result.getVariableNameAt(i));
            foundValueTypes.add(result.getValueTypeAt(i));
        }

        assertTrue(foundTerms.contains("John Doe"), "Expected term 'John Doe' to be bound. Found: " + foundTerms);
        assertTrue(foundTerms.contains("MegaCorp"), "Expected term 'MegaCorp' to be bound. Found: " + foundTerms);
        assertTrue(foundVarNames.stream().allMatch("?anyEntityTerm"::equals), "Variable name should be '?anyEntityTerm' for all. Found: " + foundVarNames);
        assertTrue(foundValueTypes.stream().allMatch(ValueType.ENTITY::equals), "All value types should be ENTITY. Found: " + foundValueTypes);

        verify(nerIndex).iterateFromFirst();
    }

    @Test
    void testExecuteEntitySearchWithTarget_noVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("PERSON", "John Doe", false);

        String key = "PERSON" + IndexAccessInterface.DELIMITER + "john doe";
        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(4, 1, 0, 8));
        positions.add(new Position(4, 3, 5, 12));

        when(nerIndex.getRaw(key.getBytes())).thenReturn(Optional.of(soaToBlob(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount());
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
        Ner condition = new Ner("PERSON", null, false);

        String keyPrefix = "PERSON" + IndexAccessInterface.DELIMITER;
        PositionListSoA positions = new PositionListSoA(); positions.add(new Position(1, 1, 0, 8));

        List<Map.Entry<byte[], PositionListSoA>> conceptualEntries = Collections.singletonList(
            Map.entry((keyPrefix + "John Doe").getBytes(), positions)
        );
        RocksIterator specificMockIterator = mock(RocksIterator.class, "entityTypeNoVarIterator");
        setupIteratorMockForSeek(specificMockIterator, nerIndex, keyPrefix, conceptualEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount());
        assertEquals(1, result.size());
        assertEquals("John Doe", result.getValueAt(0));
        assertEquals(ValueType.ENTITY, result.getValueTypeAt(0));
        assertNull(result.getVariableNameAt(0));
        assertEquals(1, result.getDocumentIdAt(0));
        verify(nerIndex).seek(argThat(k -> Arrays.equals(k, keyPrefix.getBytes())));
    }

    @Test
    void testExecuteEntityTypeSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("LOCATION", "?loc", true);

        String keyPrefix = "LOCATION" + IndexAccessInterface.DELIMITER;
        PositionListSoA positions = new PositionListSoA(); positions.add(new Position(2, 1, 10, 18));

        List<Map.Entry<byte[], PositionListSoA>> conceptualEntries = Collections.singletonList(
            Map.entry((keyPrefix + "New York").getBytes(), positions)
        );
        RocksIterator specificMockIterator = mock(RocksIterator.class, "entityTypeVarIterator");
        setupIteratorMockForSeek(specificMockIterator, nerIndex, keyPrefix, conceptualEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount());
        assertEquals(1, result.size());
        assertEquals("New York", result.getValueAt(0));
        assertEquals(ValueType.ENTITY, result.getValueTypeAt(0));
        assertEquals("?loc", result.getVariableNameAt(0));
        assertEquals(2, result.getDocumentIdAt(0));
        verify(nerIndex).seek(argThat(k -> Arrays.equals(k, keyPrefix.getBytes())));
    }

    @Test
    void testExecuteDateSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("DATE", "?actualDate", true);

        RocksIterator dateIterator = mock(RocksIterator.class, "dateIterator");

        PositionListSoA posListDate1 = new PositionListSoA(); posListDate1.add(new Position(10, 1, 0, 5));
        PositionListSoA posListDate2 = new PositionListSoA(); posListDate2.add(new Position(11, 1, 0, 5));

        String dateIndexKey1 = "20230115";
        String dateIndexKey2 = "20240220";

        String expectedFormattedDate1 = "2023-01-15";
        String expectedFormattedDate2 = "2024-02-20";

        List<Map.Entry<byte[], PositionListSoA>> dateConceptualEntries = List.of(
            Map.entry(dateIndexKey1.getBytes(), posListDate1),
            Map.entry(dateIndexKey2.getBytes(), posListDate2)
        );

        setupIteratorMockForIterateFromFirst(dateIterator, nerDateIndex, dateConceptualEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount(), "Should find two date entities");
        assertEquals(2, result.size());

        Set<String> foundDateStringValues = new HashSet<>();
        Set<LocalDate> foundDateObjects = new HashSet<>();

        for(int i=0; i < result.size(); i++) {
            assertEquals(ValueType.DATE, result.getValueTypeAt(i), "ValueType should be DATE");
            assertEquals("?actualDate", result.getVariableNameAt(i), "Variable name should be ?actualDate");
            String storedDateString = (String)result.getValueAt(i);
            foundDateStringValues.add(storedDateString);

            if (defaultTestRequirements.needsDateValues) {
                 try {
                    foundDateObjects.add(LocalDate.parse(storedDateString));
                 } catch (Exception e) {
                    throw new AssertionError("Failed to parse date string '"+ storedDateString +"' from result (expected yyyy-MM-dd format)", e);
                 }
            }
        }
        assertTrue(foundDateStringValues.contains(expectedFormattedDate1), "Result should contain formatted date string: " + expectedFormattedDate1 + ". Found: " + foundDateStringValues);
        assertTrue(foundDateStringValues.contains(expectedFormattedDate2), "Result should contain formatted date string: " + expectedFormattedDate2 + ". Found: " + foundDateStringValues);

        if (defaultTestRequirements.needsDateValues) {
            assertTrue(foundDateObjects.contains(LocalDate.of(2023,1,15)), "Parsed LocalDate objects should contain 2023-01-15. Found: " + foundDateObjects);
            assertTrue(foundDateObjects.contains(LocalDate.of(2024,2,20)), "Parsed LocalDate objects should contain 2024-02-20. Found: " + foundDateObjects);
        }
        verify(nerDateIndex).iterateFromFirst();
    }

    @Test
    void testExecute_noMatchFound_iterator() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("PERSON", null, false);
        String expectedKeyPrefix = "PERSON" + IndexAccessInterface.DELIMITER;

        RocksIterator specificMockIterator = mock(RocksIterator.class, "noMatchIterator");
        setupIteratorMockForSeek(specificMockIterator, nerIndex, expectedKeyPrefix, Collections.emptyList());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(nerIndex).seek(argThat(k -> Arrays.equals(k, expectedKeyPrefix.getBytes())));
    }

    @Test
    void testExecute_noMatchFound_get() throws QueryExecutionException, IndexAccessException, IOException {
        Ner condition = new Ner("PERSON", "NonExistentPerson", false);
        String searchKey = "PERSON" + IndexAccessInterface.DELIMITER + "nonexistentperson";
        lenient().when(nerIndex.getRaw(searchKey.getBytes())).thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(nerIndex).getRaw(searchKey.getBytes());
    }

    @Test
    void testExecute_missingNerIndex() {
        Ner condition = new Ner("PERSON");
        Map<String, IndexAccessInterface> incompleteIndexes = new HashMap<>(indexes);
        incompleteIndexes.remove(NER_INDEX_NAME);

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, incompleteIndexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
        assertTrue(exception.getMessage().contains(NER_INDEX_NAME));
    }

    @Test
    void testExecute_missingNerDateIndex() {
        Ner condition = new Ner("DATE");
        Map<String, IndexAccessInterface> incompleteIndexes = new HashMap<>(indexes);
        incompleteIndexes.remove(NER_DATE_INDEX_NAME);

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, incompleteIndexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
        assertTrue(exception.getMessage().contains(NER_DATE_INDEX_NAME));
    }

    // @Test // Commented out as per user request
    void testExecute_wildcardNotSupportedForTarget() {
        // ... existing code ...
    }

    // @Test // Commented out as per user request
    void testExecute_wildcardNotSupportedForType() {
        // ... existing code ...
    }
}