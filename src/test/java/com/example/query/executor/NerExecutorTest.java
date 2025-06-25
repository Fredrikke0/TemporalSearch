package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Ner;

@ExtendWith(MockitoExtension.class)
class NerExecutorTest {

    @Mock private IndexAccess nerIndex;
    @Mock private IndexAccess nerDateIndex;
    @Mock private RocksIterator mockIterator;
    @Mock private SynonymManager synonymManager;
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
        // Test NER(PERSON) - calls executeEntityTypeOnlySearch
        Ner condition = new Ner("PERSON");

        PositionListSoA posList = new PositionListSoA();
        // Add positions. Synonym IDs are present in the blob but not directly used for ENTITY_TYPE value.
        posList.add(1, 1, 0, 5, 101); // Doc 1, Sent 1, (e.g. "Alice")
        posList.add(3, 1, 10, 15, 102); // Doc 3, Sent 1, (e.g. "Bob")

        byte[] personBlob = soaToBlob(posList); // numPositions will be 2
        // Mock nerIndex.getRaw("PERSON") to return this blob
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(personBlob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        // For NER(TYPE) queries, conceptualRowCount is 1 (for the type itself)
        assertEquals(1, result.getConceptualRowCount(), "For type-only search, one conceptual row for 'PERSON'");
        // Size is the number of actual occurrences/positions
        assertEquals(2, result.size(), "Should find 2 occurrences of PERSON type");

        Set<Integer> docIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
            assertEquals("PERSON", result.getValueAt(i), "Value should be the entity type string 'PERSON'");
            assertEquals(ValueType.ENTITY_TYPE, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
            assertEquals(-1, result.getSynonymIdAt(i)); // For ENTITY_TYPE, synonymId is -1
        }
        assertTrue(docIds.containsAll(Set.of(1, 3)), "Result should contain document IDs 1 and 3. Found: " + docIds);

        verify(nerIndex).getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void testExecuteWithMultipleTypes() throws Exception {
        // Test NER(PERSON)
        Ner conditionPerson = new Ner("PERSON");
        PositionListSoA personSoa = new PositionListSoA();
        personSoa.add(1, 1, 0, 5, 201); // Doc 1, "Alice" (synId 201)
        byte[] personBlob = soaToBlob(personSoa);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(personBlob));

        QueryResultSoA resultPerson = executor.execute(conditionPerson, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(resultPerson);
        assertEquals(1, resultPerson.getConceptualRowCount(), "Conceptual row count for PERSON type");
        assertEquals(1, resultPerson.size(), "One occurrence of PERSON type");
        assertEquals("PERSON", resultPerson.getValueAt(0));
        assertEquals(ValueType.ENTITY_TYPE, resultPerson.getValueTypeAt(0));
        assertEquals(1, resultPerson.getDocumentIdAt(0));

        // Test NER(LOCATION)
        Ner conditionLocation = new Ner("LOCATION");
        PositionListSoA locSoa = new PositionListSoA();
        locSoa.add(2, 1, 10, 15, 301); // Doc 2, "Paris" (synId 301)
        locSoa.add(2, 2, 20, 25, 302); // Doc 2, "London" (synId 302)
        byte[] locationBlob = soaToBlob(locSoa);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "LOCATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(locationBlob));

        QueryResultSoA resultLocation = executor.execute(conditionLocation, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(resultLocation);
        assertEquals(1, resultLocation.getConceptualRowCount(), "Conceptual row count for LOCATION type");
        assertEquals(2, resultLocation.size(), "Two occurrences of LOCATION type");
        Set<Integer> locDocIds = new HashSet<>();
        for(int i=0; i < resultLocation.size(); i++){
            locDocIds.add(resultLocation.getDocumentIdAt(i));
            assertEquals("LOCATION", resultLocation.getValueAt(i));
            assertEquals(ValueType.ENTITY_TYPE, resultLocation.getValueTypeAt(i));
        }
        assertTrue(locDocIds.stream().allMatch(id -> id == 2), "All LOCATION occurrences should be in Doc 2");


        verify(nerIndex).getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        verify(nerIndex).getRaw(argThat(key -> Arrays.equals(key, "LOCATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void testVariableBindingDocumentGranularity() throws Exception {
        // Test NER(PERSON) BIND ?p - calls executeVariableBindingSearch
        Ner condition = new Ner("PERSON", List.of(), "?p", true);

        // Mock SynonymManager for term resolution
        int acmeId = 1;
        int globexId = 2;

        Map<Integer, String> expectedTerms = new HashMap<>();
        expectedTerms.put(acmeId, "acmeinc");
        expectedTerms.put(globexId, "globexcorp");
        when(synonymManager.getTerms(eq(new HashSet<>(Arrays.asList(acmeId, globexId)))))
            .thenReturn(expectedTerms);

        PositionListSoA positions = new PositionListSoA();
        positions.add(4, 1, 0, 10, acmeId);    // Doc 4, "AcmeInc"
        positions.add(4, 2, 15, 25, globexId); // Doc 4, "GlobexCorp"
        // Add another instance of AcmeInc to test conceptual grouping
        positions.add(4, 3, 30, 40, acmeId);   // Doc 4, "AcmeInc" again

        byte[] personBlob = soaToBlob(positions); // numPositions will be 3
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(personBlob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        // Two distinct entities: "acmeinc", "globexcorp"
        assertEquals(2, result.getConceptualRowCount(), "Should be 2 conceptual rows for 'acmeinc', 'globexcorp'");
        // Three actual occurrences
        assertEquals(3, result.size(), "Should be 3 positions in total");

        Set<Integer> docIds = new HashSet<>();
        Set<Object> values = new HashSet<>(); // Will store lowercase resolved terms
        Set<String> varNames = new HashSet<>();
        Map<String, Integer> valueCounts = new HashMap<>();

        for(int i=0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
            String val = (String) result.getValueAt(i);
            values.add(val);
            valueCounts.put(val, valueCounts.getOrDefault(val, 0) + 1);
            varNames.add(result.getVariableNameAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
            assertTrue(result.getSynonymIdAt(i) == acmeId || result.getSynonymIdAt(i) == globexId);
        }

        assertTrue(docIds.stream().allMatch(d -> d == 4), "All matches should be from document 4. Found: " + docIds);
        assertTrue(varNames.stream().allMatch(v -> "?p".equals(v)), "Variable name should be '?p'. Found: " + varNames);
        assertTrue(values.containsAll(Set.of("acmeinc", "globexcorp")), "Captured values should include 'acmeinc' and 'globexcorp'. Found: " + values);
        assertEquals(2, valueCounts.get("acmeinc").intValue(), "Count for 'acmeinc'");
        assertEquals(1, valueCounts.get("globexcorp").intValue(), "Count for 'globexcorp'");

        verify(nerIndex).getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        verify(synonymManager).getTerms(eq(new HashSet<>(Arrays.asList(acmeId, globexId))));
    }

    @Test
    void testExecuteEntityTypeSearch_allTypesWithVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(PERSON) BIND ?v - Changed from * to PERSON as wildcard type is not supported
        Ner condition = new Ner("PERSON", List.of(), "?anytype", true);

        int johnDoeId = 1;
        // when(synonymManager.getTerm(johnDoeId)).thenReturn(Optional.of("john doe")); // OLD MOCKING
        when(synonymManager.getTerms(eq(Set.of(johnDoeId))))
            .thenReturn(Map.of(johnDoeId, "john doe")); // NEW MOCKING

        PositionListSoA personPositions = new PositionListSoA();
        personPositions.add(1, 1, 0, 8, johnDoeId);

        byte[] personBlob = soaToBlob(personPositions);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(personBlob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount(), "Should find one distinct PERSON entity");
        assertEquals(1, result.size());
        assertEquals("john doe", result.getValueAt(0));
        assertEquals("?anytype", result.getVariableNameAt(0));
        assertEquals(ValueType.ENTITY, result.getValueTypeAt(0));
        assertEquals(johnDoeId, result.getSynonymIdAt(0));
    }

    @Test
    void testExecuteEntitySearchWithTarget_noVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Search for "New York" under LOCATION type. Changed from * as wildcard type is not supported.
        Ner condition = new Ner("LOCATION", "New York");

        // Mock synonym manager
        int newYorkId = 1;
        // Expect lowercase for getId as NerExecutor typically lowercases targets
        // Make it lenient to avoid conflicts if other stubs exist or if called multiple ways.
        lenient().when(synonymManager.getId("new york")).thenReturn(newYorkId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 10, 18, newYorkId);
        positions.add(1, 2, 5, 12, 99);

        byte[] blob = soaToBlob(positions);
        // Mock for specific type LOCATION instead of iterating or using a generic key
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "LOCATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(blob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount(), "Should find one conceptual row for 'New York'");
        assertEquals(1, result.size(), "Should find one occurrence of 'New York'");

        assertEquals("New York", result.getValueAt(0));
        assertEquals(ValueType.ENTITY, result.getValueTypeAt(0));
        assertNull(result.getVariableNameAt(0));
        assertEquals(1, result.getDocumentIdAt(0));
        assertEquals(newYorkId, result.getSynonymIdAt(0));

        verify(synonymManager).getId("new york"); // Verify with lowercase
        // Verify for specific type LOCATION
        verify(nerIndex).getRaw(argThat(key -> Arrays.equals(key, "LOCATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void testExecuteEntityTypeSearch_noVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION"); // NER(ORGANIZATION)

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 10, 123);
        positions.add(2, 1, 5, 15, 456);

        byte[] blob = soaToBlob(positions);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "ORGANIZATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(blob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount(), "Should be 1 conceptual row for the type itself");
        assertEquals(2, result.size(), "Should find 2 occurrences of the type");

        for (int i = 0; i < result.size(); i++) {
            assertEquals("ORGANIZATION", result.getValueAt(i));
            assertEquals(ValueType.ENTITY_TYPE, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
        }
        Set<Integer> docIds = new HashSet<>();
        for(int i=0; i<result.size(); i++) docIds.add(result.getDocumentIdAt(i));
        assertTrue(docIds.containsAll(Set.of(1,2)), "Doc IDs 1 and 2 should be present");

        verify(nerIndex).getRaw(argThat(key -> Arrays.equals(key, "ORGANIZATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void testExecuteEntityTypeSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Simulates NER(LOCATION) BIND ?locVar
        Ner condition = new Ner("LOCATION", List.of(), "?loc", true); // NER(LOCATION) BIND ?loc

        int parisId = 10;
        int londonId = 11;
        // when(synonymManager.getTerm(parisId)).thenReturn(Optional.of("paris")); // OLD MOCKING
        // when(synonymManager.getTerm(londonId)).thenReturn(Optional.of("london")); // OLD MOCKING

        Map<Integer, String> expectedLocationTerms = new HashMap<>();
        expectedLocationTerms.put(parisId, "paris");
        expectedLocationTerms.put(londonId, "london");
        when(synonymManager.getTerms(eq(new HashSet<>(Arrays.asList(parisId, londonId)))))
            .thenReturn(expectedLocationTerms);
        // Add mocks for individual sets if they could be requested due to some filtering logic (not in this simple test)
        // when(synonymManager.getTerms(eq(Set.of(parisId)))).thenReturn(Map.of(parisId, "paris"));
        // when(synonymManager.getTerms(eq(Set.of(londonId)))).thenReturn(Map.of(londonId, "london"));

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 5, parisId);
        positions.add(1, 2, 10, 16, londonId);
        positions.add(2, 1, 0, 6, parisId);

        byte[] blob = soaToBlob(positions);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "LOCATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(blob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount(), "Should find two distinct entities: paris, london");
        assertEquals(3, result.size(), "Should find 3 occurrences in total");

        Set<String> foundValues = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            assertEquals("?loc", result.getVariableNameAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
            foundValues.add((String) result.getValueAt(i));
            assertTrue(result.getSynonymIdAt(i) == parisId || result.getSynonymIdAt(i) == londonId);
        }
        assertTrue(foundValues.containsAll(Set.of("paris", "london")), "Expected values 'paris' and 'london'. Found: " + foundValues);

        // verify(synonymManager).getTerm(parisId); // OLD VERIFICATION
        // verify(synonymManager).getTerm(londonId); // OLD VERIFICATION
        verify(synonymManager).getTerms(eq(new HashSet<>(Arrays.asList(parisId, londonId)))); // NEW VERIFICATION
        verify(nerIndex).getRaw(argThat(key -> Arrays.equals(key, "LOCATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    @Disabled("NER(DATE) queries are handled by TemporalExecutor, not NerExecutor. This test is invalid for NerExecutor.")
    void testExecuteDateSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("DATE", List.of(), "?when", true);

        // Mock for nerDateIndex.iterateFromFirst()
        List<Map.Entry<byte[], PositionListSoA>> dateEntries = Collections.emptyList();
        setupIteratorMockForIterateFromFirst(mockIterator, nerDateIndex, dateEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.size()); // Two different dates

        verify(nerDateIndex).iterateFromFirst();
    }

    @Test
    void testExecute_noMatchFound_iterator() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("PERSON", List.of(), "?p", true); // Uses iterator

        // Setup mock iterator to return no results for the prefix "PERSON" + DELIMITER
        List<Map.Entry<byte[], PositionListSoA>> entries = Collections.emptyList();
        setupIteratorMockForSeek(mockIterator, nerIndex, "PERSON" + IndexAccessInterface.DELIMITER, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertTrue(result.isEmpty());

        verify(nerIndex).getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void testExecute_noMatchFound_get() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION"); // Uses getRaw
        when(nerIndex.getRaw(eq("ORGANIZATION".getBytes()))).thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertTrue(result.isEmpty());

        verify(nerIndex).getRaw(argThat(key -> Arrays.equals(key, "ORGANIZATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void testExecute_missingNerIndex() {
        Ner condition = new Ner("PERSON");
        Map<String, IndexAccessInterface> incompleteIndexes = new HashMap<>(indexes);
        incompleteIndexes.remove(NER_INDEX_NAME);

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
            () -> executor.execute(condition, incompleteIndexes, Query.Granularity.DOCUMENT, 0, "corpus", defaultTestRequirements, Optional.empty()));
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, ex.getErrorType());
    }

    @Test
    @Disabled("NER(DATE) queries are handled by TemporalExecutor. NerExecutor correctly throws UNSUPPORTED_OPERATION first.")
    void testExecute_missingNerDateIndex() {
        Ner condition = new Ner("DATE", "2023-01-01");
        Map<String, IndexAccessInterface> incompleteIndexes = new HashMap<>(indexes);
        incompleteIndexes.remove(NER_DATE_INDEX_NAME);

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
            () -> executor.execute(condition, incompleteIndexes, Query.Granularity.DOCUMENT, 0, "corpus", defaultTestRequirements, Optional.empty()));
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, ex.getErrorType());
    }

    @Test
    void testExecute_wildcardNotSupportedForTarget() {
        Ner condition = new Ner("PERSON", "*Smith");
        assertThrows(QueryExecutionException.class,
            () -> executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus", defaultTestRequirements, Optional.empty()));
    }

    @Test
    void testExecute_wildcardNotSupportedForType() {
        Ner condition = new Ner("PER*"); // Wildcard in type
        // This should throw an exception because wildcards are not supported for entity type
        assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        });
    }

    // ==================== NER-DEPENDENT JOIN PUSHDOWN TESTS ====================

    @Test
    @DisplayName("NER condition with multiple targets should filter correctly")
    void testExecuteMultipleTargets() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(PERSON, ['alice', 'bob']) - multiple specific targets
        List<String> targets = List.of("alice", "bob");
        Ner condition = new Ner("PERSON", targets);

        // Mock synonym manager for the targets
        int aliceId = 10;
        int bobId = 20;
        int charlieId = 30; // This should be filtered out

        lenient().when(synonymManager.getId("alice")).thenReturn(aliceId);
        lenient().when(synonymManager.getId("bob")).thenReturn(bobId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 5, aliceId);    // Doc 1, "alice" - should match
        positions.add(1, 2, 10, 13, bobId);    // Doc 1, "bob" - should match
        positions.add(2, 1, 0, 7, charlieId);  // Doc 2, "charlie" - should be filtered out

        byte[] blob = soaToBlob(positions);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(blob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount(), "Should find 2 conceptual rows for 'alice' and 'bob'");
        assertEquals(2, result.size(), "Should find 2 occurrences (alice and bob)");

        // Verify the results contain only alice and bob
        Set<String> foundValues = new HashSet<>();
        Set<Integer> foundSynonymIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            foundValues.add((String) result.getValueAt(i));
            foundSynonymIds.add(result.getSynonymIdAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
        }

        assertTrue(foundValues.containsAll(Set.of("alice", "bob")), "Should contain both alice and bob: " + foundValues);
        assertTrue(foundSynonymIds.containsAll(Set.of(aliceId, bobId)), "Should contain synonym IDs for alice and bob");
        assertFalse(foundSynonymIds.contains(charlieId), "Should not contain charlie's synonym ID");

        verify(synonymManager).getId("alice");
        verify(synonymManager).getId("bob");
    }

    @Test
    @DisplayName("NER condition with multiple targets and variable binding should work correctly")
    void testExecuteMultipleTargetsWithVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(LOCATION, ['paris', 'london']) BIND ?loc
        List<String> targets = List.of("paris", "london");
        Ner condition = new Ner("LOCATION", targets, "?loc", true);

        // Mock synonym manager for the targets
        int parisId = 100;
        int londonId = 200;
        int tokyoId = 300; // This should be filtered out

        lenient().when(synonymManager.getId("paris")).thenReturn(parisId);
        lenient().when(synonymManager.getId("london")).thenReturn(londonId);

        // Mock batch term resolution for variable binding
        when(synonymManager.getTerms(eq(Set.of(parisId, londonId))))
            .thenReturn(Map.of(parisId, "paris", londonId, "london"));

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 5, parisId);    // Doc 1, "paris" - should match
        positions.add(1, 2, 10, 16, londonId); // Doc 1, "london" - should match
        positions.add(2, 1, 0, 5, parisId);    // Doc 2, "paris" again - should match
        positions.add(3, 1, 0, 5, tokyoId);    // Doc 3, "tokyo" - should be filtered out

        byte[] blob = soaToBlob(positions);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "LOCATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(blob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount(), "Should find 2 conceptual rows for 'paris' and 'london'");
        assertEquals(3, result.size(), "Should find 3 occurrences (paris appears twice, london once)");

        // Verify the results contain only paris and london, with correct variable binding
        Set<String> foundValues = new HashSet<>();
        Set<Integer> foundSynonymIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            foundValues.add((String) result.getValueAt(i));
            foundSynonymIds.add(result.getSynonymIdAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
            assertEquals("?loc", result.getVariableNameAt(i));
        }

        assertTrue(foundValues.containsAll(Set.of("paris", "london")), "Should contain both paris and london: " + foundValues);
        assertTrue(foundSynonymIds.containsAll(Set.of(parisId, londonId)), "Should contain synonym IDs for paris and london");
        assertFalse(foundSynonymIds.contains(tokyoId), "Should not contain tokyo's synonym ID");

        verify(synonymManager).getId("paris");
        verify(synonymManager).getId("london");
        verify(synonymManager).getTerms(eq(Set.of(parisId, londonId)));
    }

    @Test
    @DisplayName("NER condition with empty targets should match any entity of that type")
    void testExecuteEmptyTargets() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(ORGANIZATION, []) - empty targets should match any ORGANIZATION
        Ner condition = new Ner("ORGANIZATION", List.of());

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 6, 101);  // Doc 1, any org
        positions.add(2, 1, 5, 14, 102); // Doc 2, any org

        byte[] blob = soaToBlob(positions);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "ORGANIZATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(blob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount(), "Should be 1 conceptual row for the type itself");
        assertEquals(2, result.size(), "Should find 2 occurrences of ORGANIZATION type");

        // Verify results are type-based (not entity-specific)
        for (int i = 0; i < result.size(); i++) {
            assertEquals("ORGANIZATION", result.getValueAt(i));
            assertEquals(ValueType.ENTITY_TYPE, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
            assertEquals(-1, result.getSynonymIdAt(i)); // For ENTITY_TYPE, synonymId is -1
        }
    }

    @Test
    @DisplayName("NER condition with targets preserves original casing")
    void testExecuteTargetsPreservesOriginalCasing() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test that original target casing is preserved in results even though lookup is lowercase
        List<String> targets = List.of("New York", "Los Angeles");
        Ner condition = new Ner("LOCATION", targets);

        // Mock synonym manager - lookups should be lowercase but original casing preserved
        int newYorkId = 50;
        int losAngelesId = 60;

        lenient().when(synonymManager.getId("new york")).thenReturn(newYorkId);
        lenient().when(synonymManager.getId("los angeles")).thenReturn(losAngelesId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 8, newYorkId);      // Doc 1, "New York"
        positions.add(2, 1, 10, 21, losAngelesId); // Doc 2, "Los Angeles"

        byte[] blob = soaToBlob(positions);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "LOCATION".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(blob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount(), "Should find 2 conceptual rows");
        assertEquals(2, result.size(), "Should find 2 occurrences");

        // Verify original casing is preserved
        Set<String> foundValues = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            foundValues.add((String) result.getValueAt(i));
        }

        assertTrue(foundValues.contains("New York"), "Should preserve 'New York' casing");
        assertTrue(foundValues.contains("Los Angeles"), "Should preserve 'Los Angeles' casing");

        // Verify lookups were done in lowercase
        verify(synonymManager).getId("new york");
        verify(synonymManager).getId("los angeles");
    }

    @Test
    @DisplayName("NER condition with targets that don't exist should return empty results")
    void testExecuteTargetsNotFound() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(PERSON, ['nonexistent']) where the target doesn't exist in index
        List<String> targets = List.of("nonexistent");
        Ner condition = new Ner("PERSON", targets);

        // Mock synonym manager to return ID for nonexistent
        int nonexistentId = 999;
        lenient().when(synonymManager.getId("nonexistent")).thenReturn(nonexistentId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 5, 123);  // Doc 1, different entity (ID 123, not 999)
        positions.add(2, 1, 0, 5, 456);  // Doc 2, different entity (ID 456, not 999)

        byte[] blob = soaToBlob(positions);
        when(nerIndex.getRaw(argThat(key -> Arrays.equals(key, "PERSON".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).thenReturn(Optional.ofNullable(blob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(0, result.getConceptualRowCount(), "Should find 0 conceptual rows for nonexistent target");
        assertEquals(0, result.size(), "Should find 0 occurrences");
        assertTrue(result.isEmpty());

        verify(synonymManager).getId("nonexistent");
    }
}