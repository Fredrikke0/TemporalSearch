package com.example.query.executor.presence;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.presence.RBGroupValueBlob;
import com.example.index.presence.RBPresenceIndex;
import com.example.query.executor.FilteringContext;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Logical.LogicalOperator;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.model.condition.Pos;

/**
 * PresenceReducer computes an initial FilteringContext by evaluating a subset of
 * conditions directly against Roaring presence indexes and combining them with
 * bitmap operations. The resulting context can be used to reduce subsequent work
 * in the full SoA-based executors.
 *
 * Supported condition types (presence-only path):
 *  - CONTAINS (1-3 gram), exact key match or prefix '*'
 *  - NER(entityType) without targets (uses RBGroupValueBlob presence)
 *  - POS(tag) without term/variables (uses RBGroupValueBlob presence)
 *  - Logical AND/OR over supported subconditions
 *  - NOT of a supported subcondition (universe approximated via rb_unigram)
 *
 * Unsupported conditions will cause the reduction to stop and return empty.
 */
public final class PresenceReducer {
    private static final Logger logger = LoggerFactory.getLogger(PresenceReducer.class);

    private static final String UNIGRAM_INDEX = "rb_unigram";
    private static final String BIGRAM_INDEX = "rb_bigram";
    private static final String TRIGRAM_INDEX = "rb_trigram";
    private static final String NER_INDEX = "rb_ner";
    private static final String POS_INDEX = "rb_pos";

    private PresenceReducer() {}

    /**
     * Attempts to compute a FilteringContext from the given conditions. If any unsupported
     * condition is encountered, returns Optional.empty() to signal no reduction was performed.
     */
    public static Optional<FilteringContext> tryBuildFilteringContext(
            List<Condition> conditions,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity) {
        if (conditions == null || conditions.isEmpty()) {
            return Optional.empty();
        }

        try {
            Optional<RBPresenceIndex> presenceOpt = reduceConditionsToPresence(conditions, indexes);
            if (presenceOpt.isEmpty()) {
                return Optional.empty();
            }
            RBPresenceIndex presence = presenceOpt.get();
            return Optional.of(presenceToFilteringContext(presence, granularity));
        } catch (Exception e) {
            logger.debug("Presence reduction failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Attempts to reduce an arbitrary condition tree to a single RBPresenceIndex.
     * Returns empty if the tree contains unsupported constructs for presence-only evaluation.
     */
    public static Optional<RBPresenceIndex> tryReduceConditionToPresence(
            Condition condition,
            Map<String, IndexAccessInterface> indexes) {
        try {
            return presenceForCondition(condition, indexes);
        } catch (Exception e) {
            logger.debug("tryReduceConditionToPresence failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<RBPresenceIndex> reduceConditionsToPresence(List<Condition> conditions, Map<String, IndexAccessInterface> indexes)
            throws IOException, IndexAccessException {
        if (conditions.size() == 1) {
            return presenceForCondition(conditions.get(0), indexes);
        }
        // Treat multiple top-level conditions as implicit AND
        RBPresenceIndex cumulative = null;
        for (Condition c : conditions) {
            Optional<RBPresenceIndex> next = presenceForCondition(c, indexes);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            cumulative = (cumulative == null) ? next.get() : (RBPresenceIndex) cumulative.and(next.get());
            // Early-out: empty intersection
            if (cumulative.getBitmap().isEmpty()) {
                break;
            }
        }
        return Optional.ofNullable(cumulative);
    }

    private static Optional<RBPresenceIndex> presenceForCondition(Condition condition, Map<String, IndexAccessInterface> indexes)
            throws IOException, IndexAccessException {
        if (condition instanceof Contains contains) {
            return presenceForContains(contains, indexes);
        } else if (condition instanceof Ner ner) {
            // Only support entity-type without targets here
            if (ner.targets() != null && !ner.targets().isEmpty()) return Optional.empty();
            if ("DATE".equalsIgnoreCase(ner.entityType())) return Optional.empty();
            IndexAccessInterface idx = indexes.get(NER_INDEX);
            if (idx == null) return Optional.empty();
            Optional<byte[]> raw = idx.getRaw(ner.entityType().toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (raw.isEmpty()) return Optional.of(new RBPresenceIndex());
            try {
                RBGroupValueBlob blob = RBGroupValueBlob.fromBytes(raw.get());
                return Optional.of(blob.getPresenceIndex());
            } catch (IOException ioe) {
                throw ioe;
            }
        } else if (condition instanceof Pos pos) {
            // Only support tag-only (no term, no bind) here
            if (pos.term() != null || pos.variableName() != null) return Optional.empty();
            IndexAccessInterface idx = indexes.get(POS_INDEX);
            if (idx == null) return Optional.empty();
            Optional<byte[]> raw = idx.getRaw(pos.posTag().toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (raw.isEmpty()) return Optional.of(new RBPresenceIndex());
            try {
                RBGroupValueBlob blob = RBGroupValueBlob.fromBytes(raw.get());
                return Optional.of(blob.getPresenceIndex());
            } catch (IOException ioe) {
                throw ioe;
            }
        } else if (condition instanceof Logical logical) {
            if (logical.operator() == LogicalOperator.AND) {
                RBPresenceIndex left = null;
                for (Condition sub : logical.conditions()) {
                    Optional<RBPresenceIndex> subPresence = presenceForCondition(sub, indexes);
                    if (subPresence.isEmpty()) return Optional.empty();
                    left = (left == null) ? subPresence.get() : (RBPresenceIndex) left.and(subPresence.get());
                    if (left.getBitmap().isEmpty()) break;
                }
                return Optional.ofNullable(left);
            } else if (logical.operator() == LogicalOperator.OR) {
                RBPresenceIndex acc = null;
                for (Condition sub : logical.conditions()) {
                    Optional<RBPresenceIndex> subPresence = presenceForCondition(sub, indexes);
                    if (subPresence.isEmpty()) return Optional.empty();
                    acc = (acc == null) ? subPresence.get() : (RBPresenceIndex) acc.or(subPresence.get());
                }
                return Optional.ofNullable(acc);
            }
            return Optional.empty();
        } else if (condition instanceof Not not) {
            Optional<RBPresenceIndex> inner = presenceForCondition(not.condition(), indexes);
            if (inner.isEmpty()) return Optional.empty();
            Optional<RBPresenceIndex> universe = buildUniversePresence(indexes);
            if (universe.isEmpty()) return Optional.empty();
            return Optional.of((RBPresenceIndex) universe.get().andNot(inner.get()));
        }
        // Unsupported condition type for presence-only path
        return Optional.empty();
    }

    private static Optional<RBPresenceIndex> presenceForContains(Contains condition, Map<String, IndexAccessInterface> indexes)
            throws IOException, IndexAccessException {
        List<String> terms = condition.terms();
        if (terms == null || terms.isEmpty() || terms.size() > 3) return Optional.empty();

        IndexAccessInterface index;
        if (terms.size() == 1) {
            index = indexes.get(UNIGRAM_INDEX);
        } else if (terms.size() == 2) {
            index = indexes.get(BIGRAM_INDEX);
        } else {
            index = indexes.get(TRIGRAM_INDEX);
        }
        if (index == null) return Optional.empty();

        String key = String.join(String.valueOf(IndexAccessInterface.DELIMITER),
                terms.stream().map(String::toLowerCase).toList());

        // Simplified wildcard behavior: if ends with '*', do prefix scan
        if (key.endsWith("*") && key.length() > 1) {
            String prefix = key.substring(0, key.length() - 1);
            return Optional.of(prefixScanPresence(index, prefix));
        }
        Optional<byte[]> raw = index.getRaw(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (raw.isEmpty()) return Optional.of(new RBPresenceIndex());
        try {
            return Optional.of(RBPresenceIndex.fromBytes(raw.get()));
        } catch (IOException ioe) {
            throw ioe;
        }
    }

    private static RBPresenceIndex prefixScanPresence(IndexAccessInterface index, String prefix) throws IndexAccessException {
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] upperBound = java.util.Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
        upperBound[upperBound.length - 1] = (byte) 0xFF;

        RBPresenceIndex accumulator = new RBPresenceIndex();
        try (RocksIterator iterator = index.seekWithBounds(prefixBytes, upperBound, 256 * 1024)) {
            while (iterator.isValid()) {
                byte[] valueBytes = iterator.value();
                if (valueBytes != null && valueBytes.length > 0) {
                    try {
                        RBPresenceIndex p = RBPresenceIndex.fromBytes(valueBytes);
                        accumulator = (RBPresenceIndex) accumulator.or(p);
                    } catch (Exception ignoreMixed) {
                        // Skip non-presence (legacy) entries in mixed datasets
                    }
                }
                iterator.next();
            }
        }
        return accumulator;
    }

    private static Optional<RBPresenceIndex> buildUniversePresence(Map<String, IndexAccessInterface> indexes) {
        IndexAccessInterface unigram = indexes.get(UNIGRAM_INDEX);
        if (unigram == null) return Optional.empty();
        RBPresenceIndex universe = new RBPresenceIndex();
        try (RocksIterator it = unigram.iterateFromFirst()) {
            while (it.isValid()) {
                byte[] value = it.value();
                if (value != null && value.length > 0) {
                    try {
                        RBPresenceIndex p = RBPresenceIndex.fromBytes(value);
                        universe = (RBPresenceIndex) universe.or(p);
                    } catch (Exception ignoreMixed) {
                        // skip legacy
                    }
                }
                it.next();
            }
        } catch (Exception e) {
            logger.debug("Failed to build universe presence: {}", e.getMessage());
            return Optional.empty();
        }
        return Optional.of(universe);
    }

    private static FilteringContext presenceToFilteringContext(RBPresenceIndex presence, Query.Granularity granularity) {
        if (granularity == Query.Granularity.DOCUMENT) {
            org.roaringbitmap.RoaringBitmap docs = presence.toDocBitmap();
            Set<Integer> docIds = new HashSet<>();
            org.roaringbitmap.IntIterator it = docs.getIntIterator();
            while (it.hasNext()) {
                docIds.add(it.next());
            }
            return new FilteringContext(Optional.of(docIds), Optional.empty(), granularity);
        } else {
            Map<Integer, Set<Integer>> docToSentences = new HashMap<>();
            org.roaringbitmap.longlong.LongIterator it = presence.getBitmap().getLongIterator();
            while (it.hasNext()) {
                long pair = it.next();
                int docId = (int)(pair >>> 16);
                int sentId = (int)(pair & 0xFFFFL);
                docToSentences.computeIfAbsent(docId, k -> new HashSet<>()).add(sentId);
            }
            return new FilteringContext(Optional.empty(), Optional.of(docToSentences), granularity);
        }
    }
}


