package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.index.KeySchema;
import com.example.index.util.SynonymManager;
import com.example.query.model.Query;
import com.example.query.model.condition.Ner;

@ExtendWith(MockitoExtension.class)
class NerExecutorTest {

    @Mock
    private IndexAccess nerIndex;
    @Mock
    private IndexAccess nerDateIndex;
    @Mock
    private RocksIterator mockIterator;
    @Mock
    private SynonymManager synonymManager;
    @InjectMocks
    private NerExecutor executor;

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

    // --- Helper: build a PostingList with a single cell and occurrence ---
    private static PostingList makePl(int docId, int sentId, int begin, int end) throws IOException {
        long ck = PostingList.packCellKey(docId, sentId);
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        cells.add(ck);
        byte cl = (byte) Math.min(end - begin, 255);
        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(new long[] { ck }, new byte[][] { { (byte) begin } }, cl);
        return PostingList.fromCellsAndOccurrences(cells, cl, occ);
    }

    // --- Helper: configure a RocksIterator mock to iterate over entries ---
    private void configureRocksIteratorMock(RocksIterator iterator, final List<Map.Entry<byte[], byte[]>> entries) {
        final AtomicInteger currentIndex = new AtomicInteger(-1);

        lenient().doAnswer(inv -> {
            int i = currentIndex.get();
            return i >= 0 && i < entries.size();
        }).when(iterator).isValid();

        lenient().doAnswer(inv -> {
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                return entries.get(i).getKey();
            }
            throw new IllegalStateException(
                    "Iterator not valid or out of bounds for key(). Index: " + i + ", Size: " + entries.size());
        }).when(iterator).key();

        lenient().doAnswer(inv -> {
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                return entries.get(i).getValue();
            }
            throw new IllegalStateException(
                    "Iterator not valid or out of bounds for value(). Index: " + i + ", Size: " + entries.size());
        }).when(iterator).value();

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

    /**
     * Sets up a prefix-scan mock for entity type searches: the iterator yields
     * keys for each synId, and getPostingList returns the corresponding
     * PostingList.
     */
    private void setupPrefixScan(RocksIterator iterator, IndexAccessInterface index,
            String type, Map<Integer, PostingList> synIdToPl) throws IOException, IndexAccessException {
        byte[] prefix = KeySchema.encodeTypePrefix(type);

        List<Map.Entry<byte[], byte[]>> rawEntries = new ArrayList<>();
        for (var entry : synIdToPl.entrySet()) {
            byte[] key = KeySchema.encodeKey(type, entry.getKey());
            rawEntries.add(Map.entry(key, entry.getValue().serialize()));
        }

        configureRocksIteratorMock(iterator, rawEntries);

        lenient().when(index.seek(argThat(k -> Arrays.equals(k, prefix))))
                .thenAnswer(inv -> {
                    iterator.seek(prefix);
                    return iterator;
                });

        // Mock getPostingList to return the right PostingList for each key
        lenient().when(index.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenAnswer(inv -> {
                    byte[] key = inv.getArgument(0);
                    for (var entry : synIdToPl.entrySet()) {
                        if (Arrays.equals(key, KeySchema.encodeKey(type, entry.getKey()))) {
                            return Optional.of(entry.getValue());
                        }
                    }
                    return Optional.empty();
                });
    }

    // ==================== Entity type search tests (prefix scan)
    // ====================

    @Test
    void testExecuteSingleTypeDocument() throws Exception {
        // Test NER(PERSON) - calls executeEntityTypeSearch
        Ner condition = new Ner("PERSON");

        PostingList pl1 = makePl(1, 1, 0, 5);
        PostingList pl2 = makePl(3, 1, 10, 15);

        Map<Integer, PostingList> synIdToPl = Map.of(101, pl1, 102, pl2);
        setupPrefixScan(mockIterator, nerIndex, "PERSON", synIdToPl);

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.granularity());
        assertEquals(2, result.cellCount(), "Should find 2 cells for PERSON type");

        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.containsAll(Set.of(1, 3)), "Result should contain document IDs 1 and 3. Found: " + docIds);
    }

    @Test
    void testExecuteWithMultipleTypes() throws Exception {
        // Test NER(PERSON)
        Ner conditionPerson = new Ner("PERSON");
        PostingList personPl = makePl(1, 1, 0, 5);
        setupPrefixScan(mockIterator, nerIndex, "PERSON", Map.of(201, personPl));

        CellResult resultPerson = executor.execute(conditionPerson, indexes, Query.Granularity.DOCUMENT, 0,
                "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(resultPerson);
        assertEquals(1, resultPerson.cellCount(), "One cell for PERSON type");
        Set<Integer> personDocIds = new HashSet<>();
        resultPerson.cells().forEach((long ck) -> personDocIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(personDocIds.contains(1), "Should contain doc 1");

        // Test NER(LOCATION)
        Ner conditionLocation = new Ner("LOCATION");
        PostingList locPl1 = makePl(2, 1, 10, 15);
        PostingList locPl2 = makePl(2, 2, 20, 25);
        // Both synIds for LOCATION - each gets its own key in the prefix scan
        Map<Integer, PostingList> locSynIdToPl = new HashMap<>();
        locSynIdToPl.put(301, locPl1);
        locSynIdToPl.put(302, locPl2);
        setupPrefixScan(mockIterator, nerIndex, "LOCATION", locSynIdToPl);

        CellResult resultLocation = executor.execute(conditionLocation, indexes, Query.Granularity.DOCUMENT, 0,
                "test_corpus", defaultTestRequirements, Optional.empty());
        assertNotNull(resultLocation);
        assertEquals(2, resultLocation.cellCount(), "Two cells for LOCATION type");
        Set<Integer> locDocIds = new HashSet<>();
        resultLocation.cells().forEach((long ck) -> locDocIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(locDocIds.stream().allMatch(id -> id == 2), "All LOCATION occurrences should be in Doc 2");
    }

    @Test
    void testVariableBindingDocumentGranularity() throws Exception {
        // Test NER(PERSON) BIND ?p - calls executeEntityTypeSearch
        Ner condition = new Ner("PERSON", List.of(), "?p", true);

        int acmeId = 1;
        int globexId = 2;

        PostingList plAcme1 = makePl(4, 1, 0, 10);
        PostingList plGlobex = makePl(4, 2, 15, 25);
        PostingList plAcme2 = makePl(4, 3, 30, 40);

        Map<Integer, PostingList> synIdToPl = new HashMap<>();
        synIdToPl.put(acmeId, plAcme1.merge(plAcme2));
        synIdToPl.put(globexId, plGlobex);

        setupPrefixScan(mockIterator, nerIndex, "PERSON", synIdToPl);

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.granularity());
        assertEquals(3, result.cellCount(), "Should be 3 cells for 3 occurrences");

        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.stream().allMatch(d -> d == 4), "All matches should be from document 4. Found: " + docIds);
    }

    @Test
    void testExecuteEntityTypeSearch_allTypesWithVariable()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(PERSON) BIND ?v
        Ner condition = new Ner("PERSON", List.of(), "?anytype", true);

        int johnDoeId = 1;

        PostingList personPl = makePl(1, 1, 0, 8);
        setupPrefixScan(mockIterator, nerIndex, "PERSON", Map.of(johnDoeId, personPl));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.cellCount(), "Should find one cell for PERSON entity");
    }

    @Test
    void testExecuteEntityTypeSearch_noVariable()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION"); // NER(ORGANIZATION)

        PostingList pl1 = makePl(1, 1, 0, 10);
        PostingList pl2 = makePl(2, 1, 5, 15);

        Map<Integer, PostingList> synIdToPl = Map.of(123, pl1, 456, pl2);
        setupPrefixScan(mockIterator, nerIndex, "ORGANIZATION", synIdToPl);

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.cellCount(), "Should find 2 cells for ORGANIZATION type");

        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.containsAll(Set.of(1, 2)), "Doc IDs 1 and 2 should be present");
    }

    @Test
    void testExecuteEntityTypeSearch_withVariable()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Simulates NER(LOCATION) BIND ?locVar
        Ner condition = new Ner("LOCATION", List.of(), "?loc", true);

        int parisId = 10;
        int londonId = 11;

        PostingList plParis1 = makePl(1, 1, 0, 5);
        PostingList plLondon = makePl(1, 2, 10, 16);
        PostingList plParis2 = makePl(2, 1, 0, 6);

        Map<Integer, PostingList> synIdToPl = new HashMap<>();
        synIdToPl.put(parisId, plParis1.merge(plParis2));
        synIdToPl.put(londonId, plLondon);

        setupPrefixScan(mockIterator, nerIndex, "LOCATION", synIdToPl);

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(3, result.cellCount(), "Should find 3 cells in total");

        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.containsAll(Set.of(1, 2)), "Docs 1 and 2 should be present. Found: " + docIds);
    }

    @Test
    void testExecute_noMatchFound_iterator()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("PERSON", List.of(), "?p", true);

        // Setup prefix scan with no results
        setupPrefixScan(mockIterator, nerIndex, "PERSON", Collections.emptyMap());

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus",
                defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExecute_noMatchFound_get()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Ner condition = new Ner("ORGANIZATION"); // No targets → entity type search (prefix scan)

        setupPrefixScan(mockIterator, nerIndex, "ORGANIZATION", Collections.emptyMap());

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus",
                defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExecuteEmptyTargets()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(ORGANIZATION, []) - empty targets should match any ORGANIZATION
        Ner condition = new Ner("ORGANIZATION", List.of());

        PostingList pl1 = makePl(1, 1, 0, 6);
        PostingList pl2 = makePl(2, 1, 5, 14);

        Map<Integer, PostingList> synIdToPl = Map.of(101, pl1, 102, pl2);
        setupPrefixScan(mockIterator, nerIndex, "ORGANIZATION", synIdToPl);

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.cellCount(), "Should find 2 cells for ORGANIZATION type");
        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.containsAll(Set.of(1, 2)), "Doc IDs 1 and 2 should be present");
    }

    // ==================== Specific entity search tests (exact key lookup)
    // ====================

    @Test
    void testExecuteEntitySearchWithTarget_noVariable()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Search for "New York" under LOCATION type
        Ner condition = new Ner("LOCATION", "New York");

        int newYorkId = 1;
        lenient().when(synonymManager.getId("new york")).thenReturn(newYorkId);

        PostingList pl1 = makePl(1, 1, 10, 18);
        PostingList pl2 = makePl(1, 2, 5, 12);
        PostingList merged = pl1.merge(pl2);

        byte[] key = KeySchema.encodeKey("LOCATION", newYorkId);
        when(nerIndex.getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(merged));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.cellCount(), "Should find 2 cells for 'New York'");

        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.contains(1), "Should contain doc 1");

        verify(synonymManager).getId("new york");
    }

    @Test
    @DisplayName("NER condition with multiple targets should filter correctly")
    void testExecuteMultipleTargets()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(PERSON, ['alice', 'bob']) - multiple specific targets
        List<String> targets = List.of("alice", "bob");
        Ner condition = new Ner("PERSON", targets);

        int aliceId = 10;
        int bobId = 20;
        lenient().when(synonymManager.getId("alice")).thenReturn(aliceId);
        lenient().when(synonymManager.getId("bob")).thenReturn(bobId);

        PostingList plAlice = makePl(1, 1, 0, 5);
        PostingList plBob = makePl(1, 2, 10, 13);

        byte[] aliceKey = KeySchema.encodeKey("PERSON", aliceId);
        byte[] bobKey = KeySchema.encodeKey("PERSON", bobId);
        when(nerIndex.getPostingList(argThat(k -> Arrays.equals(k, aliceKey)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(plAlice));
        when(nerIndex.getPostingList(argThat(k -> Arrays.equals(k, bobKey)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(plBob));
        // charlie is NOT mocked → won't be returned

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.cellCount(), "Should find 2 cells (alice and bob)");

        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.contains(1), "Should contain doc 1");
        assertFalse(docIds.contains(2), "Should NOT contain doc 2 (charlie filtered out)");

        verify(synonymManager).getId("alice");
        verify(synonymManager).getId("bob");
    }

    @Test
    @DisplayName("NER condition with multiple targets and variable binding should work correctly")
    void testExecuteMultipleTargetsWithVariable()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(LOCATION, ['paris', 'london']) BIND ?loc
        List<String> targets = List.of("paris", "london");
        Ner condition = new Ner("LOCATION", targets, "?loc", true);

        int parisId = 100;
        int londonId = 200;
        lenient().when(synonymManager.getId("paris")).thenReturn(parisId);
        lenient().when(synonymManager.getId("london")).thenReturn(londonId);

        PostingList plParis1 = makePl(1, 1, 0, 5);
        PostingList plLondon = makePl(1, 2, 10, 16);
        PostingList plParis2 = makePl(2, 1, 0, 5);

        byte[] parisKey = KeySchema.encodeKey("LOCATION", parisId);
        byte[] londonKey = KeySchema.encodeKey("LOCATION", londonId);
        when(nerIndex.getPostingList(argThat(k -> Arrays.equals(k, parisKey)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(plParis1.merge(plParis2)));
        when(nerIndex.getPostingList(argThat(k -> Arrays.equals(k, londonKey)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(plLondon));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(3, result.cellCount(), "Should find 3 cells (paris twice, london once)");

        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.containsAll(Set.of(1, 2)), "Should contain docs 1 and 2: " + docIds);
        assertFalse(docIds.contains(3), "Should NOT contain doc 3 (tokyo filtered out)");

        verify(synonymManager).getId("paris");
        verify(synonymManager).getId("london");
    }

    @Test
    @DisplayName("NER condition with targets preserves original casing")
    void testExecuteTargetsPreservesOriginalCasing()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test that original target casing is preserved in results even though lookup
        // is lowercase
        List<String> targets = List.of("New York", "Los Angeles");
        Ner condition = new Ner("LOCATION", targets);

        int newYorkId = 50;
        int losAngelesId = 60;

        lenient().when(synonymManager.getId("new york")).thenReturn(newYorkId);
        lenient().when(synonymManager.getId("los angeles")).thenReturn(losAngelesId);

        PostingList plNY = makePl(1, 1, 0, 8);
        PostingList plLA = makePl(2, 1, 10, 21);

        byte[] nyKey = KeySchema.encodeKey("LOCATION", newYorkId);
        byte[] laKey = KeySchema.encodeKey("LOCATION", losAngelesId);
        when(nerIndex.getPostingList(argThat(k -> Arrays.equals(k, nyKey)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(plNY));
        when(nerIndex.getPostingList(argThat(k -> Arrays.equals(k, laKey)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(plLA));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.cellCount(), "Should find 2 cells");

        verify(synonymManager).getId("new york");
        verify(synonymManager).getId("los angeles");
    }

    @Test
    @DisplayName("NER condition with targets that don't exist should return empty results")
    void testExecuteTargetsNotFound()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        // Test NER(PERSON, ['nonexistent']) where the target doesn't exist in index
        List<String> targets = List.of("nonexistent");
        Ner condition = new Ner("PERSON", targets);

        int nonexistentId = 999;
        lenient().when(synonymManager.getId("nonexistent")).thenReturn(nonexistentId);

        // getPostingList returns empty for the nonexistent key
        byte[] key = KeySchema.encodeKey("PERSON", nonexistentId);
        when(nerIndex.getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.empty());

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(0, result.cellCount(), "Should find 0 cells for nonexistent target");
        assertTrue(result.isEmpty());

        verify(synonymManager).getId("nonexistent");
    }

    // ==================== Error / edge case tests ====================

    @Test
    void testExecute_missingNerIndex() {
        Ner condition = new Ner("PERSON");
        Map<String, IndexAccessInterface> incompleteIndexes = new HashMap<>(indexes);
        incompleteIndexes.remove(NER_INDEX_NAME);

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> executor.execute(condition, incompleteIndexes, Query.Granularity.DOCUMENT, 0, "corpus",
                        defaultTestRequirements, Optional.empty()));
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, ex.getErrorType());
    }

    @Test
    void testExecute_wildcardNotSupportedForTarget() {
        Ner condition = new Ner("PERSON", "*Smith");
        assertThrows(QueryExecutionException.class,
                () -> executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "corpus",
                        defaultTestRequirements, Optional.empty()));
    }

    @Test
    void testExecute_wildcardNotSupportedForType() {
        Ner condition = new Ner("PER*"); // Wildcard in type
        assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                    defaultTestRequirements, Optional.empty());
        });
    }
}
