package com.example.query.executor;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.query.model.Query;

public record FilteringContext(
    Optional<Set<Integer>> allowedDocumentIds,
    Optional<Map<Integer, Set<Integer>>> allowedDocumentSentenceIds, // Map<DocID, Set<SentenceID>>
    Query.Granularity granularity
) {
    public static FilteringContext unrestricted(Query.Granularity granularity) {
        return new FilteringContext(Optional.empty(), Optional.empty(), granularity);
    }

    public boolean isUnrestricted() {
        // True if no document IDs are specified (or the set is empty)
        // AND no document-sentence ID pairs are specified (or the map is empty)
        boolean docsUnrestricted = allowedDocumentIds.map(Set::isEmpty).orElse(true);
        boolean sentsUnrestricted = allowedDocumentSentenceIds.map(Map::isEmpty).orElse(true);
        return docsUnrestricted && sentsUnrestricted;
    }

    // Derives a new context by intersecting with new constraints from a QueryResultSoA
    public FilteringContext intersect(QueryResultSoA newConstraints) {
        if (newConstraints == null || newConstraints.isEmpty()) {
            return new FilteringContext(Optional.of(Collections.emptySet()), Optional.of(Collections.emptyMap()), this.granularity);
        }

        Set<Integer> currentResultDocIds = newConstraints.getUniqueDocumentIds();
        Optional<Set<Integer>> nextDocIds;
        if (this.allowedDocumentIds.isPresent()) {
            Set<Integer> intersection = new HashSet<>(this.allowedDocumentIds.get());
            intersection.retainAll(currentResultDocIds);
            nextDocIds = Optional.of(intersection);
        } else {
            nextDocIds = Optional.of(new HashSet<>(currentResultDocIds));
        }

        if (nextDocIds.isPresent() && nextDocIds.get().isEmpty()) {
            return new FilteringContext(nextDocIds, Optional.of(Collections.emptyMap()), this.granularity);
        }

        Optional<Map<Integer, Set<Integer>>> nextDocSentIds = Optional.empty();
        if (this.granularity == Query.Granularity.SENTENCE && newConstraints.getRequirements().needsSentenceId) {
            Map<Integer, Set<Integer>> finalSentMap = new HashMap<>();
            Map<Integer, Set<Integer>> newConstraintSentences = newConstraints.getUniqueDocumentSentenceIds();

            if (nextDocIds.isPresent()) {
                for (Integer docId : nextDocIds.get()) {
                    Set<Integer> sentencesFromNew = newConstraintSentences.get(docId);

                    if (sentencesFromNew == null || sentencesFromNew.isEmpty()) {
                        // New constraints offer no sentences for this docId, so it implies no sentences can match.
                        // Do not add this docId to finalSentMap, effectively removing it from sentence consideration.
                        continue;
                    }

                    Optional<Set<Integer>> sentencesFromOldOpt = this.allowedDocumentSentenceIds
                        .flatMap(m -> Optional.ofNullable(m.get(docId)));

                    if (this.allowedDocumentSentenceIds.isEmpty() || !sentencesFromOldOpt.isPresent()) {
                        // Case 1: Old context was Optional.empty() for sentences (unrestricted globally at sentence level for allowed docs)
                        // OR Case 3: Old context's allowedDocumentSentenceIds Opt was present, but this docId was NOT in its map (unrestricted for this specific doc)
                        // Take all sentences from the new constraints (which are non-empty here).
                        finalSentMap.put(docId, new HashSet<>(sentencesFromNew));
                    } else {
                        // Case 2: Old context had specific sentence restrictions for this doc (sentencesFromOldOpt.isPresent() is true).
                        // Intersect them with new constraints' sentences (which are non-empty).
                        Set<Integer> intersection = new HashSet<>(sentencesFromOldOpt.get());
                        intersection.retainAll(sentencesFromNew);
                        if (!intersection.isEmpty()) {
                            finalSentMap.put(docId, intersection);
                        }
                        // If intersection is empty, docId is not added to finalSentMap.
                    }
                }
            }
            nextDocSentIds = Optional.of(finalSentMap);
        }
        return new FilteringContext(nextDocIds, nextDocSentIds, this.granularity);
    }

    // Getters are implicitly provided by the record type
    // public Optional<Set<Integer>> getAllowedDocumentIds() { return allowedDocumentIds; }
    // public Optional<Map<Integer, Set<Integer>>> getAllowedDocumentSentenceIds() { return allowedDocumentSentenceIds; }
    // public Query.Granularity getGranularity() { return granularity; }
}