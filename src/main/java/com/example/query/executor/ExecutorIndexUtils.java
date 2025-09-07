package com.example.query.executor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.rocksdb.RocksIterator;

import com.example.core.PositionListSoA;

/**
 * Shared helpers for executors to work efficiently with index iterators and segment merging.
 */
final class ExecutorIndexUtils {

    private ExecutorIndexUtils() {}

    static String stripSegmentSuffix(String key) {
        int hashPos = key.lastIndexOf('#');
        if (hashPos <= 0 || hashPos == key.length() - 1) return key;
        for (int i = hashPos + 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') return key;
        }
        return key.substring(0, hashPos);
    }

    /**
     * Iterates over a RocksIterator and groups consecutive entries by base key (stripping '#<digits>').
     * For each group, collects the value blobs (current entry and any subsequent segment entries) and invokes the callback.
     * Stops when the iterator moves beyond the requiredPrefix (if provided).
     * The iterator is left positioned at the first entry after the processed group.
     *
     * @return number of groups processed.
     */
    static int iterateGroupedByBase(RocksIterator iterator, String requiredPrefix,
                                    GroupConsumer consumer) {
        int groups = 0;
        while (iterator.isValid()) {
            String key = new String(iterator.key(), java.nio.charset.StandardCharsets.UTF_8);
            if (requiredPrefix != null && !key.startsWith(requiredPrefix)) {
                break;
            }
            String baseKey = stripSegmentSuffix(key);
            List<byte[]> blobs = new ArrayList<>();

            // Collect all consecutive entries for this base
            while (iterator.isValid()) {
                String currentKey = new String(iterator.key(), java.nio.charset.StandardCharsets.UTF_8);
                if (requiredPrefix != null && !currentKey.startsWith(requiredPrefix)) {
                    break;
                }
                String currentBase = stripSegmentSuffix(currentKey);
                if (!currentBase.equals(baseKey)) {
                    break;
                }
                blobs.add(iterator.value());
                iterator.next();
            }

            consumer.accept(baseKey, blobs);
            groups++;
        }
        return groups;
    }

    @FunctionalInterface
    interface GroupConsumer {
        void accept(String baseKey, List<byte[]> blobs);
    }

    /**
     * Deserializes each blob with selective attributes and optional context, merges non-empty SoAs.
     */
    static Optional<PositionListSoA> mergeAndFilter(List<byte[]> blobs,
                                                    Optional<FilteringContext> context,
                                                    AttributeRequirements requirements) {
        PositionListSoA merged = null;
        for (byte[] blob : blobs) {
            if (blob == null || blob.length == 0) continue;
            PositionListSoA part;
            try {
                part = PositionListSoA.deserializeWithFilters(blob, context, requirements);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (part == null || part.isEmpty()) continue;
            if (merged == null) merged = part;
            else merged.addAll(part);
        }
        return (merged != null && !merged.isEmpty()) ? Optional.of(merged) : Optional.empty();
    }
}


