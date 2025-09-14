package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
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
            // Return last key defensively if called when invalid
            return entries.isEmpty() ? new byte[0] : entries.get(entries.size() - 1).getKey();
        });

        lenient().when(iterator.value()).thenAnswer(inv -> {
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                return entries.get(i).getValue();
            }
            // Return last value defensively if called when invalid
            return entries.isEmpty() ? new byte[0] : entries.get(entries.size() - 1).getValue();
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
            int found = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (Arrays.compare(entries.get(i).getKey(), targetKey) >= 0) { found = i; break; }
            }
            currentIndex.set(found >= 0 ? found : 0);
            return null;
        }).when(iterator).seek(any(byte[].class));
    }

    private void setupIteratorMockForSeekWithBounds(RocksIterator iteratorToConfigure, IndexAccessInterface targetIndex, String prefix, List<Map.Entry<byte[], PositionListSoA>> conceptualEntries) throws IOException, IndexAccessException {
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<Map.Entry<byte[], byte[]>> rawEntries = conceptualEntries.stream()
            .map(e -> {
                try { return Map.entry(e.getKey(), soaToBlob(e.getValue())); } catch (IOException ioe) { throw new RuntimeException(ioe); }
            }).toList();
        configureRocksIteratorMock(iteratorToConfigure, rawEntries);
        lenient().when(nerIndex.seekWithBounds(eq(prefixBytes), any(byte[].class), org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(inv -> {
                iteratorToConfigure.seek(prefixBytes);
                return iteratorToConfigure;
            });
    }

    @Test
    void testExecuteSingleTypeDocument() throws Exception {
        Ner condition = new Ner("PERSON");

        PositionListSoA p1 = new PositionListSoA();
        p1.add(1, 1, 0, 5);
        PositionListSoA p2 = new PositionListSoA();
        p2.add(3, 1, 10, 15);
        List<Map.Entry<byte[], PositionListSoA>> entries = List.of(
            Map.entry(("PERSON" + IndexAccessInterface.DELIMITER + "101").getBytes(java.nio.charset.StandardCharsets.UTF_8), p1),
            Map.entry(("PERSON" + IndexAccessInterface.DELIMITER + "102").getBytes(java.nio.charset.StandardCharsets.UTF_8), p2)
        );
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "PERSON" + IndexAccessInterface.DELIMITER, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getConceptualRowCount());
        assertEquals(2, result.size());
    }

    @Test
    void testExecuteWithMultipleTypes() throws Exception {
        Ner conditionPerson = new Ner("PERSON");
        PositionListSoA personSoa = new PositionListSoA();
        personSoa.add(1, 1, 0, 5);
        List<Map.Entry<byte[], PositionListSoA>> personEntries = List.of(
            Map.entry(("PERSON" + IndexAccessInterface.DELIMITER + "201").getBytes(java.nio.charset.StandardCharsets.UTF_8), personSoa)
        );
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "PERSON" + IndexAccessInterface.DELIMITER, personEntries);

        QueryResultSoA resultPerson = executor.execute(conditionPerson, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(resultPerson);
        assertEquals(1, resultPerson.getConceptualRowCount());
        assertEquals(1, resultPerson.size());
        assertEquals("PERSON", resultPerson.getValueAt(0));
        assertEquals(ValueType.ENTITY_TYPE, resultPerson.getValueTypeAt(0));
        assertEquals(1, resultPerson.getDocumentIdAt(0));

        Ner conditionLocation = new Ner("LOCATION");
        PositionListSoA loc1 = new PositionListSoA();
        loc1.add(2, 1, 10, 15);
        PositionListSoA loc2 = new PositionListSoA();
        loc2.add(2, 2, 20, 25);
        List<Map.Entry<byte[], PositionListSoA>> locEntries = List.of(
            Map.entry(("LOCATION" + IndexAccessInterface.DELIMITER + "301").getBytes(java.nio.charset.StandardCharsets.UTF_8), loc1),
            Map.entry(("LOCATION" + IndexAccessInterface.DELIMITER + "302").getBytes(java.nio.charset.StandardCharsets.UTF_8), loc2)
        );
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "LOCATION" + IndexAccessInterface.DELIMITER, locEntries);

        QueryResultSoA resultLocation = executor.execute(conditionLocation, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(resultLocation);
        assertEquals(2, resultLocation.getConceptualRowCount());
        assertEquals(2, resultLocation.size());
        Set<Integer> locDocIds = new HashSet<>();
        for(int i=0; i < resultLocation.size(); i++){
            locDocIds.add(resultLocation.getDocumentIdAt(i));
            assertEquals("LOCATION", resultLocation.getValueAt(i));
            assertEquals(ValueType.ENTITY_TYPE, resultLocation.getValueTypeAt(i));
        }
        assertTrue(locDocIds.stream().allMatch(id -> id == 2));
    }

    @Test
    void testVariableBindingDocumentGranularity() throws Exception {
        Ner condition = new Ner("PERSON", List.of(), "?p", true);

        // Two synIds under PERSON
        int acmeId = 1, globexId = 2;
        PositionListSoA acme = new PositionListSoA(); acme.add(4, 1, 0, 10);
        PositionListSoA globex = new PositionListSoA(); globex.add(4, 2, 15, 25); globex.add(4, 3, 30, 40);
        List<Map.Entry<byte[], PositionListSoA>> entries = List.of(
            Map.entry(("PERSON" + IndexAccessInterface.DELIMITER + String.valueOf(acmeId)).getBytes(java.nio.charset.StandardCharsets.UTF_8), acme),
            Map.entry(("PERSON" + IndexAccessInterface.DELIMITER + String.valueOf(globexId)).getBytes(java.nio.charset.StandardCharsets.UTF_8), globex)
        );
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "PERSON" + IndexAccessInterface.DELIMITER, entries);

        lenient().when(synonymManager.getTerm(acmeId)).thenReturn(Optional.of("acmeinc"));
        lenient().when(synonymManager.getTerm(globexId)).thenReturn(Optional.of("globexcorp"));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(3, result.getConceptualRowCount());
        assertEquals(3, result.size());
    }

    @Test
    void testExecuteEntityTypeSearch_allTypesWithVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("PERSON", List.of(), "?anytype", true);
        int johnDoeId = 1;
        PositionListSoA personPositions = new PositionListSoA();
        personPositions.add(1, 1, 0, 8);
        List<Map.Entry<byte[], PositionListSoA>> entries = List.of(
            Map.entry(("PERSON" + IndexAccessInterface.DELIMITER + johnDoeId).getBytes(java.nio.charset.StandardCharsets.UTF_8), personPositions)
        );
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "PERSON" + IndexAccessInterface.DELIMITER, entries);
        when(synonymManager.getTerm(johnDoeId)).thenReturn(Optional.of("john doe"));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount());
        assertEquals(1, result.size());
        assertEquals("john doe", result.getValueAt(0));
        assertEquals("?anytype", result.getVariableNameAt(0));
        assertEquals(ValueType.ENTITY, result.getValueTypeAt(0));
        assertEquals(johnDoeId, result.getSynonymIdAt(0));
    }

    @Test
    void testExecuteEntitySearchWithTarget_noVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("LOCATION", "New York");
        int newYorkId = 1;
        lenient().when(synonymManager.getId("new york")).thenReturn(newYorkId);
        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 10, 18);
        when(nerIndex.getMergedPositions(eq("LOCATION" + IndexAccessInterface.DELIMITER + newYorkId), eq(Optional.empty()), eq(defaultTestRequirements))).thenReturn(Optional.of(positions));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount());
        assertEquals(1, result.size());
        assertEquals("New York", result.getValueAt(0));
        assertEquals(ValueType.ENTITY, result.getValueTypeAt(0));
        assertNull(result.getVariableNameAt(0));
        assertEquals(1, result.getDocumentIdAt(0));
        assertEquals(newYorkId, result.getSynonymIdAt(0));
    }

    @Test
    void testExecuteEntityTypeSearch_noVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION");
        PositionListSoA org1 = new PositionListSoA(); org1.add(1, 1, 0, 10);
        PositionListSoA org2 = new PositionListSoA(); org2.add(2, 1, 5, 15);
        List<Map.Entry<byte[], PositionListSoA>> entries = List.of(
            Map.entry(("ORGANIZATION" + IndexAccessInterface.DELIMITER + "123").getBytes(java.nio.charset.StandardCharsets.UTF_8), org1),
            Map.entry(("ORGANIZATION" + IndexAccessInterface.DELIMITER + "456").getBytes(java.nio.charset.StandardCharsets.UTF_8), org2)
        );
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "ORGANIZATION" + IndexAccessInterface.DELIMITER, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount());
        assertEquals(2, result.size());
    }

    @Test
    void testExecuteEntityTypeSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("LOCATION", List.of(), "?loc", true);
        int parisId = 10, londonId = 11;
        PositionListSoA p = new PositionListSoA(); p.add(1, 1, 0, 5);
        PositionListSoA l = new PositionListSoA(); l.add(1, 2, 10, 16);
        PositionListSoA p2 = new PositionListSoA(); p2.add(2, 1, 0, 6);
        List<Map.Entry<byte[], PositionListSoA>> entries = List.of(
            Map.entry(("LOCATION" + IndexAccessInterface.DELIMITER + parisId).getBytes(java.nio.charset.StandardCharsets.UTF_8), p),
            Map.entry(("LOCATION" + IndexAccessInterface.DELIMITER + londonId).getBytes(java.nio.charset.StandardCharsets.UTF_8), l),
            Map.entry(("LOCATION" + IndexAccessInterface.DELIMITER + parisId).getBytes(java.nio.charset.StandardCharsets.UTF_8), p2)
        );
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "LOCATION" + IndexAccessInterface.DELIMITER, entries);
        lenient().when(synonymManager.getTerm(parisId)).thenReturn(Optional.of("paris"));
        lenient().when(synonymManager.getTerm(londonId)).thenReturn(Optional.of("london"));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertEquals(3, result.getConceptualRowCount());
        assertEquals(3, result.size());
    }

    @Test
    void testExecute_noMatchFound_iterator() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("PERSON", List.of(), "?p", true);
        List<Map.Entry<byte[], PositionListSoA>> entries = Collections.emptyList();
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "PERSON" + IndexAccessInterface.DELIMITER, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExecute_noMatchFound_get() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION");
        List<Map.Entry<byte[], PositionListSoA>> entries = Collections.emptyList();
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "ORGANIZATION" + IndexAccessInterface.DELIMITER, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertTrue(result.isEmpty());
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
        Ner condition = new Ner("PER*");
        assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        });
    }

    @Test
    @DisplayName("NER condition with multiple targets should filter correctly")
    void testExecuteMultipleTargets() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        List<String> targets = List.of("alice", "bob");
        Ner condition = new Ner("PERSON", targets);

        int aliceId = 10;
        int bobId = 20;
        lenient().when(synonymManager.getId("alice")).thenReturn(aliceId);
        lenient().when(synonymManager.getId("bob")).thenReturn(bobId);

        PositionListSoA alice = new PositionListSoA(); alice.add(1, 1, 0, 5);
        PositionListSoA bob = new PositionListSoA(); bob.add(1, 2, 10, 13);
        when(nerIndex.getMergedPositions(eq("PERSON" + IndexAccessInterface.DELIMITER + aliceId), eq(Optional.empty()), eq(defaultTestRequirements))).thenReturn(Optional.of(alice));
        when(nerIndex.getMergedPositions(eq("PERSON" + IndexAccessInterface.DELIMITER + bobId), eq(Optional.empty()), eq(defaultTestRequirements))).thenReturn(Optional.of(bob));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount());
        assertEquals(2, result.size());
        Set<String> foundValues = new HashSet<>();
        Set<Integer> foundSynonymIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            foundValues.add((String) result.getValueAt(i));
            foundSynonymIds.add(result.getSynonymIdAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
        }
        assertTrue(foundValues.containsAll(Set.of("alice", "bob")));
        assertTrue(foundSynonymIds.containsAll(Set.of(aliceId, bobId)));
    }

    @Test
    @DisplayName("NER condition with multiple targets and variable binding should work correctly")
    void testExecuteMultipleTargetsWithVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        List<String> targets = List.of("paris", "london");
        Ner condition = new Ner("LOCATION", targets, "?loc", true);

        int parisId = 100;
        int londonId = 200;
        lenient().when(synonymManager.getId("paris")).thenReturn(parisId);
        lenient().when(synonymManager.getId("london")).thenReturn(londonId);
        lenient().when(synonymManager.getTerm(parisId)).thenReturn(Optional.of("paris"));
        lenient().when(synonymManager.getTerm(londonId)).thenReturn(Optional.of("london"));

        PositionListSoA paris = new PositionListSoA(); paris.add(1, 1, 0, 5);
        PositionListSoA london = new PositionListSoA(); london.add(1, 2, 10, 16);
        List<Map.Entry<byte[], PositionListSoA>> entries = List.of(
            Map.entry(("LOCATION" + IndexAccessInterface.DELIMITER + parisId).getBytes(java.nio.charset.StandardCharsets.UTF_8), paris),
            Map.entry(("LOCATION" + IndexAccessInterface.DELIMITER + londonId).getBytes(java.nio.charset.StandardCharsets.UTF_8), london)
        );
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "LOCATION" + IndexAccessInterface.DELIMITER, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount());
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("NER condition with empty targets should match any entity of that type")
    void testExecuteEmptyTargets() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION", List.of());
        PositionListSoA o1 = new PositionListSoA(); o1.add(1, 1, 0, 6);
        PositionListSoA o2 = new PositionListSoA(); o2.add(2, 1, 5, 14);
        List<Map.Entry<byte[], PositionListSoA>> entries = List.of(
            Map.entry(("ORGANIZATION" + IndexAccessInterface.DELIMITER + "101").getBytes(java.nio.charset.StandardCharsets.UTF_8), o1),
            Map.entry(("ORGANIZATION" + IndexAccessInterface.DELIMITER + "102").getBytes(java.nio.charset.StandardCharsets.UTF_8), o2)
        );
        setupIteratorMockForSeekWithBounds(mockIterator, nerIndex, "ORGANIZATION" + IndexAccessInterface.DELIMITER, entries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount());
        assertEquals(2, result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals("ORGANIZATION", result.getValueAt(i));
            assertEquals(ValueType.ENTITY_TYPE, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
            assertEquals(-1, result.getSynonymIdAt(i));
        }
    }

    @Test
    @DisplayName("NER condition with targets preserves original casing")
    void testExecuteTargetsPreservesOriginalCasing() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        List<String> targets = List.of("New York", "Los Angeles");
        Ner condition = new Ner("LOCATION", targets);
        int newYorkId = 50;
        int losAngelesId = 60;
        lenient().when(synonymManager.getId("new york")).thenReturn(newYorkId);
        lenient().when(synonymManager.getId("los angeles")).thenReturn(losAngelesId);
        PositionListSoA ny = new PositionListSoA(); ny.add(1, 1, 0, 8);
        PositionListSoA la = new PositionListSoA(); la.add(2, 1, 10, 21);
        when(nerIndex.getMergedPositions(eq("LOCATION" + IndexAccessInterface.DELIMITER + newYorkId), eq(Optional.empty()), eq(defaultTestRequirements))).thenReturn(Optional.of(ny));
        when(nerIndex.getMergedPositions(eq("LOCATION" + IndexAccessInterface.DELIMITER + losAngelesId), eq(Optional.empty()), eq(defaultTestRequirements))).thenReturn(Optional.of(la));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount());
        assertEquals(2, result.size());
        Set<String> foundValues = new HashSet<>();
        for (int i = 0; i < result.size(); i++) foundValues.add((String) result.getValueAt(i));
        assertTrue(foundValues.contains("New York"));
        assertTrue(foundValues.contains("Los Angeles"));
        verify(synonymManager).getId("new york");
        verify(synonymManager).getId("los angeles");
    }

    @Test
    @DisplayName("NER condition with targets that don't exist should return empty results")
    void testExecuteTargetsNotFound() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        List<String> targets = List.of("nonexistent");
        Ner condition = new Ner("PERSON", targets);
        int nonexistentId = 999;
        lenient().when(synonymManager.getId("nonexistent")).thenReturn(nonexistentId);
        when(nerIndex.getMergedPositions(eq("PERSON" + IndexAccessInterface.DELIMITER + nonexistentId), eq(Optional.empty()), eq(defaultTestRequirements))).thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertEquals(0, result.getConceptualRowCount());
        assertEquals(0, result.size());
        assertTrue(result.isEmpty());
    }
}