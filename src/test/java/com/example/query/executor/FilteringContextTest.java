package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.query.model.Query;

@ExtendWith(MockitoExtension.class)
class FilteringContextTest {

    // --- Helper methods for constructing/analyzing cell keys ---

    /** Pack a docId into a DOCUMENT-granularity cell key. */
    private static long docCell(int docId) {
        return (long) docId << 32;
    }

    /** Pack a (docId, sentId) into a SENTENCE-granularity cell key. */
    private static long sentCell(int docId, int sentId) {
        return ((long) docId << 32) | (sentId & 0xFFFF_FFFFL);
    }

    /**
     * Build a Roaring64NavigableMap from a set of document IDs (document-level
     * cells).
     */
    private static Roaring64NavigableMap cellsFromDocIds(Set<Integer> docIds) {
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        for (int docId : docIds) {
            cells.add(docCell(docId));
        }
        return cells;
    }

    /**
     * Build a Roaring64NavigableMap from a doc→sentences map (sentence-level
     * cells).
     */
    private static Roaring64NavigableMap cellsFromDocSentIds(Map<Integer, Set<Integer>> docSentIds) {
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        for (var entry : docSentIds.entrySet()) {
            for (int sentId : entry.getValue()) {
                cells.add(sentCell(entry.getKey(), sentId));
            }
        }
        return cells;
    }

    /** Extract the set of document IDs from cell keys. */
    private static Set<Integer> extractDocIds(Roaring64NavigableMap cells) {
        Set<Integer> docIds = new TreeSet<>();
        cells.forEach((long cell) -> docIds.add((int) (cell >>> 32)));
        return docIds;
    }

    /**
     * Extract a doc→sentences map from cell keys (sentId=0 entries are included).
     */
    private static Map<Integer, Set<Integer>> extractDocSentIds(Roaring64NavigableMap cells) {
        Map<Integer, Set<Integer>> result = new TreeMap<>();
        cells.forEach((long cell) -> {
            int docId = (int) (cell >>> 32);
            int sentId = (int) cell;
            result.computeIfAbsent(docId, k -> new TreeSet<>()).add(sentId);
        });
        return result;
    }

    // --- Tests for isUnrestricted() ---

    @Test
    void isUnrestricted_trueWhenNoIdsPresent() {
        FilteringContext context = new FilteringContext(Optional.empty(), Query.Granularity.DOCUMENT);
        assertTrue(context.isUnrestricted());
    }

    @Test
    void isUnrestricted_falseWhenDocIdsPresent() {
        FilteringContext context = new FilteringContext(
                Optional.of(cellsFromDocIds(Set.of(1))), Query.Granularity.DOCUMENT);
        assertFalse(context.isUnrestricted());
    }

    @Test
    void isUnrestricted_falseWhenDocSentIdsPresent_sentenceGranularity() {
        FilteringContext context = new FilteringContext(
                Optional.of(cellsFromDocSentIds(Map.of(1, Set.of(10)))), Query.Granularity.SENTENCE);
        assertFalse(context.isUnrestricted());
    }

    @Test
    void isUnrestricted_trueWhenDocSentIdsIsEmptyMap_sentenceGranularity_andDocIdsEmpty() {
        // An empty cells map makes the context unrestricted.
        FilteringContext context = new FilteringContext(
                Optional.of(new Roaring64NavigableMap()), Query.Granularity.SENTENCE);
        assertTrue(context.isUnrestricted());
    }

    @Test
    void isUnrestricted_falseWhenDocIdsPresent_evenIfDocSentIdsIsEmptyMap_sentenceGranularity() {
        FilteringContext context = new FilteringContext(
                Optional.of(cellsFromDocIds(Set.of(1))), Query.Granularity.SENTENCE);
        assertFalse(context.isUnrestricted());
    }

    // --- Tests for unrestricted() factory ---

    @Test
    void unrestrictedFactory_createsCorrectly() {
        FilteringContext contextDoc = FilteringContext.unrestricted(Query.Granularity.DOCUMENT);
        assertTrue(contextDoc.isUnrestricted());
        assertEquals(Query.Granularity.DOCUMENT, contextDoc.granularity());
        assertFalse(contextDoc.allowedCells().isPresent());

        FilteringContext contextSent = FilteringContext.unrestricted(Query.Granularity.SENTENCE);
        assertTrue(contextSent.isUnrestricted());
        assertEquals(Query.Granularity.SENTENCE, contextSent.granularity());
        assertFalse(contextSent.allowedCells().isPresent());
    }

    // --- Nested tests for intersect() method ---

    @Nested
    class IntersectTests {

        @Mock
        private CellResult mockCellResult;

        @BeforeEach
        void setUp() {
            // Common setup if needed; configure mock in each test.
        }

        /**
         * Creates a CellResult from document IDs and/or document→sentence IDs.
         *
         * @param docIds      the document IDs (may be {@code null})
         * @param docSentIds  the document→sentence IDs (used when granularity is
         *                    {@code SENTENCE}; may be {@code null})
         * @param granularity the granularity of the CellResult
         */
        private CellResult createCellResult(Set<Integer> docIds,
                Map<Integer, Set<Integer>> docSentIds,
                Query.Granularity granularity) {
            Roaring64NavigableMap cells;
            if (granularity == Query.Granularity.SENTENCE && docSentIds != null) {
                cells = cellsFromDocSentIds(docSentIds);
            } else if (docIds != null) {
                cells = cellsFromDocIds(docIds);
            } else {
                cells = new Roaring64NavigableMap();
            }
            return CellResult.of(cells, granularity);
        }

        // --- Document Granularity Tests for intersect() ---

        @Test
        void intersect_docGranularity_unrestrictedContext_withNewConstraints() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.DOCUMENT);
            CellResult newConstraints = createCellResult(Set.of(1, 2), null, Query.Granularity.DOCUMENT);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertFalse(resultContext.isUnrestricted());
            assertTrue(resultContext.allowedCells().isPresent());
            assertEquals(Set.of(1, 2), extractDocIds(resultContext.allowedCells().get()));
            assertEquals(Query.Granularity.DOCUMENT, resultContext.granularity());
        }

        @Test
        void intersect_docGranularity_restrictedContext_withOverlappingNewConstraints() {
            FilteringContext initialContext = new FilteringContext(
                    Optional.of(cellsFromDocIds(Set.of(1, 2, 3))), Query.Granularity.DOCUMENT);
            CellResult newConstraints = createCellResult(Set.of(2, 3, 4), null, Query.Granularity.DOCUMENT);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedCells().isPresent());
            assertEquals(Set.of(2, 3), extractDocIds(resultContext.allowedCells().get()));
        }

        @Test
        void intersect_docGranularity_restrictedContext_withNonOverlappingNewConstraints() {
            FilteringContext initialContext = new FilteringContext(
                    Optional.of(cellsFromDocIds(Set.of(1, 2))), Query.Granularity.DOCUMENT);
            CellResult newConstraints = createCellResult(Set.of(3, 4), null, Query.Granularity.DOCUMENT);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedCells().isPresent());
            assertTrue(resultContext.allowedCells().get().isEmpty());
        }

        @Test
        void intersect_docGranularity_withEmptyNewConstraints_resultsInEmptyFilter() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.DOCUMENT);

            when(mockCellResult.isEmpty()).thenReturn(true);

            FilteringContext intersectedContext = initialContext.intersect(mockCellResult);

            assertTrue(intersectedContext.allowedCells().isPresent());
            assertTrue(intersectedContext.allowedCells().get().isEmpty(),
                    "Allowed cells should be an empty bitmap");
            assertEquals(Query.Granularity.DOCUMENT, intersectedContext.granularity());
            verify(mockCellResult).isEmpty();
        }

        @Test
        void intersect_docGranularity_withNullNewConstraints_resultsInEmptyFilter() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.DOCUMENT);

            FilteringContext resultContext = initialContext.intersect(null);

            assertTrue(resultContext.allowedCells().isPresent());
            assertTrue(resultContext.allowedCells().get().isEmpty(),
                    "Allowed cells should be an empty bitmap on null input");
            assertEquals(Query.Granularity.DOCUMENT, resultContext.granularity());
        }

        // --- Sentence Granularity Tests for intersect() ---

        @Test
        void intersect_sentGranularity_unrestrictedContext_withNewConstraints_sentenceGranularity() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.SENTENCE);
            CellResult newConstraints = createCellResult(
                    Set.of(1, 2),
                    Map.of(1, Set.of(10, 11), 2, Set.of(20)),
                    Query.Granularity.SENTENCE);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertFalse(resultContext.isUnrestricted());
            assertTrue(resultContext.allowedCells().isPresent());
            assertEquals(Set.of(1, 2), extractDocIds(resultContext.allowedCells().get()));
            assertEquals(Map.of(1, Set.of(10, 11), 2, Set.of(20)),
                    extractDocSentIds(resultContext.allowedCells().get()));
        }

        @Test
        void intersect_sentGranularity_unrestrictedContext_withDocumentGranularityConstraints() {
            // Intersecting a SENTENCE-granularity unrestricted context with
            // DOCUMENT-level constraints yields document-level cell keys (sentId=0).
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.SENTENCE);
            CellResult newConstraints = createCellResult(
                    Set.of(1, 2), null, Query.Granularity.DOCUMENT);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertFalse(resultContext.isUnrestricted());
            assertTrue(resultContext.allowedCells().isPresent());
            assertEquals(Set.of(1, 2), extractDocIds(resultContext.allowedCells().get()));
            assertEquals(Map.of(1, Set.of(0), 2, Set.of(0)),
                    extractDocSentIds(resultContext.allowedCells().get()));
        }

        @Test
        void intersect_sentGranularity_restrictedDoc_restrictedSent_withOverlap() {
            FilteringContext initialContext = new FilteringContext(
                    Optional.of(cellsFromDocSentIds(Map.of(
                            1, Set.of(10, 12),
                            2, Set.of(20, 21)))),
                    Query.Granularity.SENTENCE);
            CellResult newConstraints = createCellResult(
                    Set.of(1, 3),
                    Map.of(1, Set.of(12, 13), 3, Set.of(30)),
                    Query.Granularity.SENTENCE);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedCells().isPresent());
            assertEquals(Set.of(1), extractDocIds(resultContext.allowedCells().get()),
                    "Only doc 1 should remain");
            assertEquals(Map.of(1, Set.of(12)),
                    extractDocSentIds(resultContext.allowedCells().get()),
                    "Only sent 12 in doc 1 should remain");
        }

        @Test
        void intersect_sentGranularity_restrictedDoc_restrictedSent_withFullOverlap() {
            // Initial context restricts to specific sentences in docs 1 and 2.
            FilteringContext initialContext = new FilteringContext(
                    Optional.of(cellsFromDocSentIds(Map.of(
                            1, Set.of(10, 11),
                            2, Set.of(20)))),
                    Query.Granularity.SENTENCE);
            CellResult newConstraints = createCellResult(
                    Set.of(1, 3),
                    Map.of(1, Set.of(10, 11), 3, Set.of(30)),
                    Query.Granularity.SENTENCE);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedCells().isPresent());
            assertEquals(Set.of(1), extractDocIds(resultContext.allowedCells().get()),
                    "Only doc 1 overlaps");
            // Doc 1 sentences {10,11} fully overlap with new constraints.
            assertEquals(Map.of(1, Set.of(10, 11)),
                    extractDocSentIds(resultContext.allowedCells().get()));
        }

        @Test
        void intersect_sentGranularity_docIntersectionBecomesEmpty() {
            FilteringContext initialContext = new FilteringContext(
                    Optional.of(cellsFromDocSentIds(Map.of(1, Set.of(10)))),
                    Query.Granularity.SENTENCE);
            CellResult newConstraints = createCellResult(
                    Set.of(2),
                    Map.of(2, Set.of(20)),
                    Query.Granularity.SENTENCE);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedCells().isPresent());
            assertTrue(resultContext.allowedCells().get().isEmpty(),
                    "Cell bitmap should be empty");
        }

        @Test
        void intersect_sentGranularity_newConstraintsHaveNoSentencesForIntersectedDoc() {
            // Initial context allows doc 1, sent 10.
            FilteringContext initialContext = new FilteringContext(
                    Optional.of(cellsFromDocSentIds(Map.of(1, Set.of(10)))),
                    Query.Granularity.SENTENCE);
            // New constraints allow doc 1, but provide no sentences for it.
            CellResult newConstraints = createCellResult(
                    Set.of(1, 2),
                    Map.of(2, Set.of(20)),
                    Query.Granularity.SENTENCE);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedCells().isPresent());
            // Doc 1 is present as a document in newConstraints' docIds, but has
            // no sentence entries. Since newConstraints has SENTENCE granularity
            // the createCellResult helper only adds cells for docSentIds entries,
            // so doc 1 cells are absent → intersection with initial doc 1 cells
            // yields empty.
            assertTrue(resultContext.allowedCells().get().isEmpty(),
                    "No overlapping cells should remain");
        }

        @Test
        void intersect_sentGranularity_disjointCells_resultsInEmpty() {
            // Initial context has cells only for doc 2.
            FilteringContext initialContext = new FilteringContext(
                    Optional.of(cellsFromDocSentIds(Map.of(2, Set.of(20)))),
                    Query.Granularity.SENTENCE);
            // New constraints have cells only for doc 1.
            CellResult newConstraints = createCellResult(
                    Set.of(1),
                    Map.of(1, Set.of(10, 11)),
                    Query.Granularity.SENTENCE);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedCells().isPresent());
            assertTrue(resultContext.allowedCells().get().isEmpty(),
                    "No overlap between doc 2 and doc 1 cells");
        }

        @Test
        void intersect_sentGranularity_emptyNewConstraints_resultsInEmptyFilter() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.SENTENCE);

            when(mockCellResult.isEmpty()).thenReturn(true);

            FilteringContext intersectedContext = initialContext.intersect(mockCellResult);

            assertTrue(intersectedContext.allowedCells().isPresent());
            assertTrue(intersectedContext.allowedCells().get().isEmpty());
            assertEquals(Query.Granularity.SENTENCE, intersectedContext.granularity());
            verify(mockCellResult).isEmpty();
        }
    }
}
