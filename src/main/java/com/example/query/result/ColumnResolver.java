package com.example.query.result;

import java.util.List;
import java.util.Map;

import com.example.query.executor.CellResult;

/**
 * Resolves a single column value for a given result cell.
 *
 * <p>
 * Implementations are stateless lambdas or method references. Per-document
 * caching is handled externally via {@code docCache}.
 */
@FunctionalInterface
public interface ColumnResolver {

    /**
     * @param cellKey        the packed (docId, sentId) for this row
     * @param result         the full CellResult (for bindings access)
     * @param bindingIndices the bindings rows belonging to this cell
     * @param source         the corpus/source name
     * @param docCache       mutable cache shared across all resolvers for the same
     *                       materialization pass; keys are typically "title_123" or
     *                       "timestamp_123"
     * @return the resolved column value, or {@code null} for missing
     */
    Object resolve(long cellKey, CellResult result, List<Integer> bindingIndices,
            String source, Map<String, Object> docCache);
}
