package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.query.binding.ValueType;
import com.example.query.model.Query;

@ExtendWith(MockitoExtension.class)
class FilteringContextTest {

    // --- Tests for isUnrestricted() ---
    @Test
    void isUnrestricted_trueWhenNoIdsPresent() {
        FilteringContext context = new FilteringContext(Optional.empty(), Optional.empty(), Query.Granularity.DOCUMENT);
        assertTrue(context.isUnrestricted());
    }

    @Test
    void isUnrestricted_falseWhenDocIdsPresent() {
        FilteringContext context = new FilteringContext(Optional.of(Set.of(1)), Optional.empty(), Query.Granularity.DOCUMENT);
        assertFalse(context.isUnrestricted());
    }

    @Test
    void isUnrestricted_falseWhenDocSentIdsPresent_sentenceGranularity() {
        FilteringContext context = new FilteringContext(Optional.empty(), Optional.of(Map.of(1, Set.of(10))), Query.Granularity.SENTENCE);
        assertFalse(context.isUnrestricted());
    }

     @Test
    void isUnrestricted_trueWhenDocSentIdsIsEmptyMap_sentenceGranularity_andDocIdsEmpty() {
        // This case is important: an empty map for sentence IDs doesn't make it restricted if doc IDs are also absent.
        FilteringContext context = new FilteringContext(Optional.empty(), Optional.of(Collections.emptyMap()), Query.Granularity.SENTENCE);
        assertTrue(context.isUnrestricted());
    }

    @Test
    void isUnrestricted_falseWhenDocIdsPresent_evenIfDocSentIdsIsEmptyMap_sentenceGranularity() {
        FilteringContext context = new FilteringContext(Optional.of(Set.of(1)), Optional.of(Collections.emptyMap()), Query.Granularity.SENTENCE);
        assertFalse(context.isUnrestricted());
    }

    // --- Tests for unrestricted() factory ---
    @Test
    void unrestrictedFactory_createsCorrectly() {
        FilteringContext contextDoc = FilteringContext.unrestricted(Query.Granularity.DOCUMENT);
        assertTrue(contextDoc.isUnrestricted());
        assertEquals(Query.Granularity.DOCUMENT, contextDoc.granularity());
        assertFalse(contextDoc.allowedDocumentIds().isPresent()); // Changed from isPresent() to isEmpty() for Optional
        assertFalse(contextDoc.allowedDocumentSentenceIds().isPresent()); // Changed

        FilteringContext contextSent = FilteringContext.unrestricted(Query.Granularity.SENTENCE);
        assertTrue(contextSent.isUnrestricted());
        assertEquals(Query.Granularity.SENTENCE, contextSent.granularity());
        assertFalse(contextSent.allowedDocumentIds().isPresent()); // Changed
        assertFalse(contextSent.allowedDocumentSentenceIds().isPresent()); // Changed
    }

    // --- Nested tests for intersect() method ---
    @Nested
    class IntersectTests {

        @Mock
        private QueryResultSoA mockQueryResultSoA;
        @Mock
        private AttributeRequirements mockAttributeRequirements;


        @BeforeEach
        void setUp() {
            // Common setup for mockQueryResultSoA if needed, or configure in each test.
        }

        private QueryResultSoA createRealQueryResultSoA(Set<Integer> docIds, Map<Integer, Set<Integer>> docSentIds, boolean needsSentenceId) {
            AttributeRequirements reqs = new AttributeRequirements();
            reqs.needsDocumentId = true; // Assume always needed if we have docIds
            reqs.needsSentenceId = needsSentenceId;

            QueryResultSoA realSoA = new QueryResultSoA(
                needsSentenceId ? Query.Granularity.SENTENCE : Query.Granularity.DOCUMENT,
                0,
                reqs
            );

            if (docIds != null) {
                for (Integer docId : docIds) {
                    if (needsSentenceId && docSentIds != null && docSentIds.containsKey(docId)) {
                        for (Integer sentId : docSentIds.get(docId)) {
                            // Add a dummy entry; value, varName, etc., don't matter for FilteringContext.intersect
                            realSoA.add("dummyValue", ValueType.TERM, "dummyVar", docId, sentId, -1, -1, -1, 0);
                        }
                    } else if (!needsSentenceId || docSentIds == null || !docSentIds.containsKey(docId)) {
                         // Add only with docId if not sentence granularity or no sentences for this doc
                        realSoA.add("dummyValue", ValueType.TERM, "dummyVar", docId, -1, -1, -1, -1, 0);
                    }
                }
            }
            return realSoA;
        }


        // --- Document Granularity Tests for intersect() ---

        @Test
        void intersect_docGranularity_unrestrictedContext_withNewConstraints() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.DOCUMENT);
            QueryResultSoA newConstraints = createRealQueryResultSoA(Set.of(1, 2), null, false);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertFalse(resultContext.isUnrestricted());
            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertEquals(Set.of(1, 2), resultContext.allowedDocumentIds().get());
            assertFalse(resultContext.allowedDocumentSentenceIds().isPresent());
        }

        @Test
        void intersect_docGranularity_restrictedContext_withOverlappingNewConstraints() {
            FilteringContext initialContext = new FilteringContext(Optional.of(Set.of(1, 2, 3)), Optional.empty(), Query.Granularity.DOCUMENT);
            QueryResultSoA newConstraints = createRealQueryResultSoA(Set.of(2, 3, 4), null, false);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertEquals(Set.of(2, 3), resultContext.allowedDocumentIds().get());
        }

        @Test
        void intersect_docGranularity_restrictedContext_withNonOverlappingNewConstraints() {
            FilteringContext initialContext = new FilteringContext(Optional.of(Set.of(1, 2)), Optional.empty(), Query.Granularity.DOCUMENT);
            QueryResultSoA newConstraints = createRealQueryResultSoA(Set.of(3, 4), null, false);

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertTrue(resultContext.allowedDocumentIds().get().isEmpty());
        }

        @Test
        void intersect_docGranularity_withEmptyNewConstraints_resultsInEmptyFilter() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.DOCUMENT);

            // Setup mock for this test
            // These are lenient because if isEmpty() is true, they might not be called.
            lenient().when(mockQueryResultSoA.getRequirements()).thenReturn(mockAttributeRequirements);
            mockAttributeRequirements.needsSentenceId = false; // This is a setup for mockAttributeRequirements, not a stubbing on mockQueryResultSoA
            lenient().when(mockQueryResultSoA.getUniqueDocumentIds()).thenReturn(Collections.emptySet());
            when(mockQueryResultSoA.isEmpty()).thenReturn(true); // This causes the early exit in intersect()

            FilteringContext intersectedContext = initialContext.intersect(mockQueryResultSoA);

            // Assertions reverted to original form
            assertTrue(intersectedContext.allowedDocumentIds().isPresent());
            assertTrue(intersectedContext.allowedDocumentIds().get().isEmpty(), "Allowed document IDs should be an empty set");
            assertTrue(intersectedContext.allowedDocumentSentenceIds().isPresent());
            assertTrue(intersectedContext.allowedDocumentSentenceIds().get().isEmpty(), "Allowed document sentence IDs should be an empty map");
            assertEquals(Query.Granularity.DOCUMENT, intersectedContext.granularity());
            verify(mockQueryResultSoA).isEmpty(); // Verify isEmpty was called
        }

        @Test
        void intersect_docGranularity_withNullNewConstraints_resultsInEmptyFilter() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.DOCUMENT);

            FilteringContext resultContext = initialContext.intersect(null);

            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertTrue(resultContext.allowedDocumentIds().get().isEmpty(), "Allowed document IDs should be an empty set on null input");
            assertTrue(resultContext.allowedDocumentSentenceIds().isPresent());
            assertTrue(resultContext.allowedDocumentSentenceIds().get().isEmpty(), "Allowed document sentence IDs should be an empty map on null input");
        }

        // --- Sentence Granularity Tests for intersect() ---

        @Test
        void intersect_sentGranularity_unrestrictedContext_withNewConstraints_needsSentenceIdTrue() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.SENTENCE);
            QueryResultSoA newConstraints = createRealQueryResultSoA(Set.of(1, 2), Map.of(1, Set.of(10, 11), 2, Set.of(20)), true);
            // when(mockAttributeRequirements.needsSentenceId()).thenReturn(true); // Already done if using real QueryResultSoA with needsSentenceId=true

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertFalse(resultContext.isUnrestricted());
            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertEquals(Set.of(1, 2), resultContext.allowedDocumentIds().get());
            assertTrue(resultContext.allowedDocumentSentenceIds().isPresent());
            assertEquals(Map.of(1, Set.of(10, 11), 2, Set.of(20)), resultContext.allowedDocumentSentenceIds().get());
        }

        @Test
        void intersect_sentGranularity_unrestrictedContext_withNewConstraints_needsSentenceIdFalse() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.SENTENCE);
            // Even if QueryResultSoA has sentence IDs, if its requirements say needsSentenceId=false,
            // the intersect method should not populate allowedDocumentSentenceIds.
            QueryResultSoA newConstraints = createRealQueryResultSoA(Set.of(1, 2), Map.of(1, Set.of(10, 11), 2, Set.of(20)), false);
            // when(mockAttributeRequirements.needsSentenceId()).thenReturn(false); // Done by createRealQueryResultSoA

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertFalse(resultContext.isUnrestricted()); // Because doc IDs are present
            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertEquals(Set.of(1, 2), resultContext.allowedDocumentIds().get());
            assertFalse(resultContext.allowedDocumentSentenceIds().isPresent(), "Sentence IDs should not be populated if newConstraints.getRequirements().needsSentenceId is false");
        }


        @Test
        void intersect_sentGranularity_restrictedDoc_restrictedSent_withOverlap() {
            FilteringContext initialContext = new FilteringContext(
                Optional.of(Set.of(1, 2)),
                Optional.of(Map.of(1, Set.of(10, 12), 2, Set.of(20, 21))),
                Query.Granularity.SENTENCE
            );
            QueryResultSoA newConstraints = createRealQueryResultSoA(
                Set.of(1, 3), // Doc 2 won't match, Doc 3 is new
                Map.of(1, Set.of(12, 13), 3, Set.of(30)), // Doc 1: sent 12 overlaps; Doc 3 is new
                true
            );
            // when(mockAttributeRequirements.needsSentenceId()).thenReturn(true); // Done by helper

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertEquals(Set.of(1), resultContext.allowedDocumentIds().get(), "Only doc 1 should remain");

            assertTrue(resultContext.allowedDocumentSentenceIds().isPresent());
            Map<Integer, Set<Integer>> expectedSentIds = Map.of(1, Set.of(12));
            assertEquals(expectedSentIds, resultContext.allowedDocumentSentenceIds().get(), "Only sent 12 in doc 1 should remain");
        }

        @Test
        void intersect_sentGranularity_restrictedDoc_unrestrictedSent_withNewConstraints() {
             // Initial context restricts docs but not specific sentences within those docs (empty Optional for sent IDs)
            FilteringContext initialContext = new FilteringContext(Optional.of(Set.of(1, 2)), Optional.empty(), Query.Granularity.SENTENCE);
            QueryResultSoA newConstraints = createRealQueryResultSoA(
                Set.of(1, 3),
                Map.of(1, Set.of(10, 11), 3, Set.of(30)),
                true
            );
            // when(mockAttributeRequirements.needsSentenceId()).thenReturn(true); // Done by helper

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertEquals(Set.of(1), resultContext.allowedDocumentIds().get()); // Only doc 1 overlaps

            assertTrue(resultContext.allowedDocumentSentenceIds().isPresent());
            // Since initial context had no sentence restrictions for doc 1, newConstraints' sentences for doc 1 are taken.
            assertEquals(Map.of(1, Set.of(10, 11)), resultContext.allowedDocumentSentenceIds().get());
        }


        @Test
        void intersect_sentGranularity_docIntersectionBecomesEmpty() {
            FilteringContext initialContext = new FilteringContext(
                Optional.of(Set.of(1)),
                Optional.of(Map.of(1, Set.of(10))),
                Query.Granularity.SENTENCE
            );
            QueryResultSoA newConstraints = createRealQueryResultSoA(Set.of(2), Map.of(2, Set.of(20)), true);
            // when(mockAttributeRequirements.needsSentenceId()).thenReturn(true); // Done by helper

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertTrue(resultContext.allowedDocumentIds().get().isEmpty(), "Document ID set should be empty");

            assertTrue(resultContext.allowedDocumentSentenceIds().isPresent());
            assertTrue(resultContext.allowedDocumentSentenceIds().get().isEmpty(), "Sentence ID map should be empty when doc IDs are empty");
        }

        @Test
        void intersect_sentGranularity_newConstraintsHaveNoSentencesForIntersectedDoc() {
            // Initial context allows doc 1, sent 10
            FilteringContext initialContext = new FilteringContext(
                Optional.of(Set.of(1)),
                Optional.of(Map.of(1, Set.of(10))),
                Query.Granularity.SENTENCE
            );
            // New constraints allow doc 1, but provide no sentences for it in its map.
            QueryResultSoA newConstraints = createRealQueryResultSoA(
                Set.of(1, 2), // Doc 1 overlaps
                Map.of(2, Set.of(20)), // Doc 1 is MISSING from sentence map
                true
            );
            // when(mockAttributeRequirements.needsSentenceId()).thenReturn(true); // Done by helper

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertEquals(Set.of(1), resultContext.allowedDocumentIds().get()); // Doc 1 still allowed

            assertTrue(resultContext.allowedDocumentSentenceIds().isPresent());
            // Since newConstraints.getUniqueDocumentSentenceIds() would not contain doc 1,
            // the intersection for doc 1's sentences becomes empty.
            assertTrue(resultContext.allowedDocumentSentenceIds().get().getOrDefault(1, Collections.emptySet()).isEmpty(),
                       "Sentences for doc 1 should be empty as new constraints provided no sentences for it");
            assertFalse(resultContext.allowedDocumentSentenceIds().get().containsKey(1), "Doc 1 should not be in the final sentence map if its sentence set became empty.");

        }

         @Test
        void intersect_sentGranularity_initialContextHasNoSentencesForIntersectedDoc() {
            // Initial context allows doc 1, but has no specific sentences listed for it
            FilteringContext initialContext = new FilteringContext(
                Optional.of(Set.of(1)),
                Optional.of(Map.of(2, Set.of(20))), // No entry for doc 1 in sentence map
                Query.Granularity.SENTENCE
            );
             // New constraints allow doc 1, and provide sentences for it.
            QueryResultSoA newConstraints = createRealQueryResultSoA(
                Set.of(1),
                Map.of(1, Set.of(10, 11)),
                true
            );

            FilteringContext resultContext = initialContext.intersect(newConstraints);

            assertTrue(resultContext.allowedDocumentIds().isPresent());
            assertEquals(Set.of(1), resultContext.allowedDocumentIds().get());

            assertTrue(resultContext.allowedDocumentSentenceIds().isPresent());
             // Because initial context didn't have specific sentence restrictions for doc 1 (it was missing from its map),
             // the new constraints' sentences for doc 1 should effectively pass through for that doc.
            assertEquals(Map.of(1, Set.of(10, 11)), resultContext.allowedDocumentSentenceIds().get());
        }

        @Test
        void intersect_sentGranularity_emptyNewConstraints_resultsInEmptyFilter() {
            FilteringContext initialContext = FilteringContext.unrestricted(Query.Granularity.SENTENCE);

            // Setup mock for this test
            // These are lenient because if isEmpty() is true, they might not be called.
            lenient().when(mockQueryResultSoA.getRequirements()).thenReturn(mockAttributeRequirements);
            mockAttributeRequirements.needsSentenceId = true; // This is a setup for mockAttributeRequirements
            lenient().when(mockQueryResultSoA.getUniqueDocumentIds()).thenReturn(Collections.emptySet());

            // THIS IS THE LIKELY CULPRIT based on the error message.
            // It should be: lenient().when(mockQueryResultSoA.getUniqueDocumentSentenceIds()).thenReturn(Collections.emptyMap());
            // Instead of returning an Optional.
            // Let's assume it was previously `thenReturn(Optional.empty())` or `thenReturn(Optional.of(Collections.emptyMap()))`
            lenient().when(mockQueryResultSoA.getUniqueDocumentSentenceIds()).thenReturn(Collections.emptyMap()); // <- CORRECTED LINE

            // Mocking requirements, as intersect uses it.
            lenient().when(mockQueryResultSoA.getRequirements()).thenReturn(mockAttributeRequirements);
            when(mockQueryResultSoA.isEmpty()).thenReturn(true); // This causes the early exit

            FilteringContext intersectedContext = initialContext.intersect(mockQueryResultSoA);

            // Assertions reverted to original form
            assertTrue(intersectedContext.allowedDocumentIds().isPresent());
            assertTrue(intersectedContext.allowedDocumentIds().get().isEmpty());
            assertTrue(intersectedContext.allowedDocumentSentenceIds().isPresent());
            assertTrue(intersectedContext.allowedDocumentSentenceIds().get().isEmpty());
            assertEquals(Query.Granularity.SENTENCE, intersectedContext.granularity());
            verify(mockQueryResultSoA).isEmpty(); // Verify isEmpty was called
        }
    }
}