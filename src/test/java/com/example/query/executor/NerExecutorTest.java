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
import java.nio.charset.StandardCharsets;
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
import com.example.index.presence.RBGroupValueBlob;
import com.example.index.presence.RBPresenceIndex;
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

    private static final String NER_INDEX_NAME = "rb_ner";
    private static final String NER_DATE_INDEX_NAME = "rb_ner_date";

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

    private byte[] buildBlobFromSoA(PositionListSoA soa) throws IOException {
        RBPresenceIndex presence = new RBPresenceIndex();
        Map<Integer, Map<Integer, List<Integer>>> docSentVals = new HashMap<>();
        int n = soa.getNumPositions();
        for (int i = 0; i < n; i++) {
            int d = soa.getDocIdAt(i);
            int s = soa.getSentenceIdAt(i);
            int synId = soa.getSynonymIdAt(i);
            presence.add(d, s);
            docSentVals.computeIfAbsent(d, k -> new HashMap<>())
                       .computeIfAbsent(s, k -> new java.util.ArrayList<>())
                       .add(synId);
        }
        Map<Integer, RBGroupValueBlob.DocBlock> blocks = RBGroupValueBlob.buildDocBlocksFromPresenceAndValues(presence, docSentVals);
        RBGroupValueBlob blob = new RBGroupValueBlob(presence, blocks);
        return blob.toBytes();
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

    @Test
    void testExecuteSingleTypeDocument() throws Exception {
        Ner condition = new Ner("PERSON");

        PositionListSoA posList = new PositionListSoA();
        posList.add(1, 1, 0, 5, 101);
        posList.add(3, 1, 10, 15, 102);

        when(nerIndex.getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(posList)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(2, result.getConceptualRowCount());
        assertEquals(2, result.size());

        Set<Integer> docIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
            assertEquals("PERSON", result.getValueAt(i));
            assertEquals(ValueType.ENTITY_TYPE, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
            assertEquals(-1, result.getSynonymIdAt(i));
        }
        assertTrue(docIds.containsAll(Set.of(1, 3)));

        verify(nerIndex).getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testExecuteWithMultipleTypes() throws Exception {
        Ner conditionPerson = new Ner("PERSON");
        PositionListSoA personSoa = new PositionListSoA();
        personSoa.add(1, 1, 0, 5, 201);
        when(nerIndex.getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(personSoa)));

        QueryResultSoA resultPerson = executor.execute(conditionPerson, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(resultPerson);
        assertEquals(1, resultPerson.getConceptualRowCount());
        assertEquals(1, resultPerson.size());
        assertEquals("PERSON", resultPerson.getValueAt(0));
        assertEquals(ValueType.ENTITY_TYPE, resultPerson.getValueTypeAt(0));
        assertEquals(1, resultPerson.getDocumentIdAt(0));

        Ner conditionLocation = new Ner("LOCATION");
        PositionListSoA locSoa = new PositionListSoA();
        locSoa.add(2, 1, 10, 15, 301);
        locSoa.add(2, 2, 20, 25, 302);
        when(nerIndex.getRaw(eq("LOCATION".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(locSoa)));

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

        verify(nerIndex).getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)));
        verify(nerIndex).getRaw(eq("LOCATION".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testVariableBindingDocumentGranularity() throws Exception {
        Ner condition = new Ner("PERSON", List.of(), "?p", true);

        int acmeId = 1;
        int globexId = 2;

        Map<Integer, String> expectedTerms = new HashMap<>();
        expectedTerms.put(acmeId, "acmeinc");
        expectedTerms.put(globexId, "globexcorp");
        lenient().when(synonymManager.getTerms(eq(new HashSet<>(Arrays.asList(acmeId, globexId)))))
            .thenReturn(expectedTerms);
        // Also stub singleton fetches used by resolveTermSafe
        when(synonymManager.getTerms(eq(Set.of(acmeId)))).thenReturn(Map.of(acmeId, "acmeinc"));
        when(synonymManager.getTerms(eq(Set.of(globexId)))).thenReturn(Map.of(globexId, "globexcorp"));

        PositionListSoA positions = new PositionListSoA();
        positions.add(4, 1, 0, 10, acmeId);
        positions.add(4, 2, 15, 25, globexId);
        positions.add(4, 3, 30, 40, acmeId);

        when(nerIndex.getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertEquals(3, result.getConceptualRowCount());
        assertEquals(3, result.size());

        Set<Integer> docIds = new HashSet<>();
        Set<Object> values = new HashSet<>();
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

        assertTrue(docIds.stream().allMatch(d -> d == 4));
        assertTrue(varNames.stream().allMatch(v -> "?p".equals(v)));
        assertTrue(values.containsAll(Set.of("acmeinc", "globexcorp")));
        assertEquals(2, valueCounts.get("acmeinc").intValue());
        assertEquals(1, valueCounts.get("globexcorp").intValue());

        verify(nerIndex).getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)));
        // No strict verification of batch getTerms; implementation may call singleton lookups
    }

    @Test
    void testExecuteEntityTypeSearch_allTypesWithVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("PERSON", List.of(), "?anytype", true);

        int johnDoeId = 1;
        when(synonymManager.getTerms(eq(Set.of(johnDoeId))))
            .thenReturn(Map.of(johnDoeId, "john doe"));

        PositionListSoA personPositions = new PositionListSoA();
        personPositions.add(1, 1, 0, 8, johnDoeId);

        when(nerIndex.getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(personPositions)));

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
        positions.add(1, 1, 10, 18, newYorkId);
        positions.add(1, 2, 5, 12, 99);

        when(nerIndex.getRaw(eq("LOCATION".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.getConceptualRowCount());
        assertEquals(1, result.size());

        assertEquals("New York", result.getValueAt(0));
        assertEquals(ValueType.ENTITY, result.getValueTypeAt(0));
        assertNull(result.getVariableNameAt(0));
        assertEquals(1, result.getDocumentIdAt(0));
        assertEquals(newYorkId, result.getSynonymIdAt(0));

        org.mockito.Mockito.verify(synonymManager, org.mockito.Mockito.atLeastOnce()).getId("new york");
    }

    @Test
    void testExecuteEntityTypeSearch_noVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION");

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 10, 123);
        positions.add(2, 1, 5, 15, 456);

        when(nerIndex.getRaw(eq("ORGANIZATION".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount());
        assertEquals(2, result.size());

        for (int i = 0; i < result.size(); i++) {
            assertEquals("ORGANIZATION", result.getValueAt(i));
            assertEquals(ValueType.ENTITY_TYPE, result.getValueTypeAt(i));
            assertNull(result.getVariableNameAt(i));
        }
        Set<Integer> docIds = new HashSet<>();
        for(int i=0; i<result.size(); i++) docIds.add(result.getDocumentIdAt(i));
        assertTrue(docIds.containsAll(Set.of(1,2)));
    }

    @Test
    void testExecuteEntityTypeSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("LOCATION", List.of(), "?loc", true);

        int parisId = 10;
        int londonId = 11;

        Map<Integer, String> expectedLocationTerms = new HashMap<>();
        expectedLocationTerms.put(parisId, "paris");
        expectedLocationTerms.put(londonId, "london");
        lenient().when(synonymManager.getTerms(eq(new HashSet<>(Arrays.asList(parisId, londonId)))))
            .thenReturn(expectedLocationTerms);
        // also allow singleton fetches
        when(synonymManager.getTerms(eq(Set.of(parisId)))).thenReturn(Map.of(parisId, "paris"));
        when(synonymManager.getTerms(eq(Set.of(londonId)))).thenReturn(Map.of(londonId, "london"));

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 5, parisId);
        positions.add(1, 2, 10, 16, londonId);
        positions.add(2, 1, 0, 6, parisId);

        when(nerIndex.getRaw(eq("LOCATION".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(3, result.getConceptualRowCount());
        assertEquals(3, result.size());

        Set<String> foundValues = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            assertEquals("?loc", result.getVariableNameAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
            foundValues.add((String) result.getValueAt(i));
            assertTrue(result.getSynonymIdAt(i) == parisId || result.getSynonymIdAt(i) == londonId);
        }
        assertTrue(foundValues.containsAll(Set.of("paris", "london")));

        // No strict verification of batch getTerms; implementation may call singleton lookups
    }

    @Test
    @Disabled("NER(DATE) queries are handled by TemporalExecutor, not NerExecutor. This test is invalid for NerExecutor.")
    void testExecuteDateSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("DATE", List.of(), "?when", true);

        List<Map.Entry<byte[], byte[]>> dateEntries = Collections.emptyList();
        configureRocksIteratorMock(mockIterator, dateEntries);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    void testExecute_noMatchFound_iterator() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("PERSON", List.of(), "?p", true);

        when(nerIndex.getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.empty());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
            () -> executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus", defaultTestRequirements, Optional.empty()));
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, ex.getErrorType());

        verify(nerIndex).getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testExecute_noMatchFound_get() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION");
        when(nerIndex.getRaw(eq("ORGANIZATION".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.empty());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
            () -> executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus", defaultTestRequirements, Optional.empty()));
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, ex.getErrorType());

        verify(nerIndex).getRaw(eq("ORGANIZATION".getBytes(StandardCharsets.UTF_8)));
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
        int charlieId = 30;

        lenient().when(synonymManager.getId("alice")).thenReturn(aliceId);
        lenient().when(synonymManager.getId("bob")).thenReturn(bobId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 5, aliceId);
        positions.add(1, 2, 10, 13, bobId);
        positions.add(2, 1, 0, 7, charlieId);

        when(nerIndex.getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(positions)));

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
        assertFalse(foundSynonymIds.contains(charlieId));

        org.mockito.Mockito.verify(synonymManager, org.mockito.Mockito.atLeastOnce()).getId("alice");
        org.mockito.Mockito.verify(synonymManager, org.mockito.Mockito.atLeastOnce()).getId("bob");
    }

    @Test
    @DisplayName("NER condition with multiple targets and variable binding should work correctly")
    void testExecuteMultipleTargetsWithVariable() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        List<String> targets = List.of("paris", "london");
        Ner condition = new Ner("LOCATION", targets, "?loc", true);

        int parisId = 100;
        int londonId = 200;
        int tokyoId = 300;

        lenient().when(synonymManager.getId("paris")).thenReturn(parisId);
        lenient().when(synonymManager.getId("london")).thenReturn(londonId);

        lenient().when(synonymManager.getTerms(eq(Set.of(parisId, londonId))))
            .thenReturn(Map.of(parisId, "paris", londonId, "london"));

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 5, parisId);
        positions.add(1, 2, 10, 16, londonId);
        positions.add(2, 1, 0, 5, parisId);
        positions.add(3, 1, 0, 5, tokyoId);

        when(nerIndex.getRaw(eq("LOCATION".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(3, result.getConceptualRowCount());
        assertEquals(3, result.size());

        Set<String> foundValues = new HashSet<>();
        Set<Integer> foundSynonymIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            foundValues.add((String) result.getValueAt(i));
            foundSynonymIds.add(result.getSynonymIdAt(i));
            assertEquals(ValueType.ENTITY, result.getValueTypeAt(i));
            assertEquals("?loc", result.getVariableNameAt(i));
        }

        assertTrue(foundValues.containsAll(Set.of("paris", "london")));
        assertTrue(foundSynonymIds.containsAll(Set.of(parisId, londonId)));
        assertFalse(foundSynonymIds.contains(tokyoId));

        org.mockito.Mockito.verify(synonymManager, org.mockito.Mockito.atLeastOnce()).getId("paris");
        org.mockito.Mockito.verify(synonymManager, org.mockito.Mockito.atLeastOnce()).getId("london");
    }

    @Test
    @DisplayName("NER condition with empty targets should match any entity of that type")
    void testExecuteEmptyTargets() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION", List.of());

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 6, 101);
        positions.add(2, 1, 5, 14, 102);

        when(nerIndex.getRaw(eq("ORGANIZATION".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(positions)));

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

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 8, newYorkId);
        positions.add(2, 1, 10, 21, losAngelesId);

        when(nerIndex.getRaw(eq("LOCATION".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.getConceptualRowCount());
        assertEquals(2, result.size());

        Set<String> foundValues = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            foundValues.add((String) result.getValueAt(i));
        }

        assertTrue(foundValues.contains("New York"));
        assertTrue(foundValues.contains("Los Angeles"));

        org.mockito.Mockito.verify(synonymManager, org.mockito.Mockito.atLeastOnce()).getId("new york");
        org.mockito.Mockito.verify(synonymManager, org.mockito.Mockito.atLeastOnce()).getId("los angeles");
    }

    @Test
    @DisplayName("NER condition with targets that don't exist should return empty results")
    void testExecuteTargetsNotFound() throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        List<String> targets = List.of("nonexistent");
        Ner condition = new Ner("PERSON", targets);

        int nonexistentId = 999;
        lenient().when(synonymManager.getId("nonexistent")).thenReturn(nonexistentId);

        PositionListSoA positions = new PositionListSoA();
        positions.add(1, 1, 0, 5, 123);
        positions.add(2, 1, 0, 5, 456);

        when(nerIndex.getRaw(eq("PERSON".getBytes(StandardCharsets.UTF_8)))).thenReturn(Optional.of(buildBlobFromSoA(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(0, result.getConceptualRowCount());
        assertEquals(0, result.size());
        assertTrue(result.isEmpty());

        org.mockito.Mockito.verify(synonymManager, org.mockito.Mockito.atLeastOnce()).getId("nonexistent");
    }
}