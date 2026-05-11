package com.example.query.executor;

import java.util.Optional;

import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.OccurrencesBlock;
import com.example.query.model.JoinCondition;
import com.example.query.model.Query;
import com.example.query.model.TemporalPredicate;

/**
 * Handles JOIN operations between CellResult objects.
 * For INNER joins, cells are intersected via bitmap AND.
 * For value-based equality, bindings are matched within joined cells.
 */
public class JoinHandler {
    private static final Logger logger = LoggerFactory.getLogger(JoinHandler.class);

    public JoinHandler() {
    }

    /**
     * Performs a binary join between two CellResult objects.
     */
    public CellResult performBinaryJoin(
            CellResult lhsResult,
            String lhsAlias,
            CellResult rhsResult,
            String rhsAlias,
            JoinCondition condition,
            JoinCondition.JoinType joinType,
            Query.Granularity outputGranularity,
            int outputGranularitySize,
            AttributeRequirements outputRequirements)
            throws QueryExecutionException {

        if (lhsResult == null) {
            throw new QueryExecutionException(
                    "LHS CellResult for alias '" + lhsAlias + "' is null in performBinaryJoin.",
                    "join", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
        if (rhsResult == null) {
            throw new QueryExecutionException(
                    "RHS CellResult for alias '" + rhsAlias + "' is null in performBinaryJoin.",
                    "join", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        logger.debug("Performing binary {} JOIN between LHS ('{}', {} cells) and RHS ('{}', {} cells) ON {}",
                joinType, lhsAlias, lhsResult.cellCount(), rhsAlias, rhsResult.cellCount(), condition);

        JoinCondition.JoinOperatorType operatorType = condition.operatorType();
        Optional<TemporalPredicate> temporalPredicateOpt = condition.temporalPredicate();

        if (joinType == JoinCondition.JoinType.INNER) {
            if (operatorType == JoinCondition.JoinOperatorType.EQUALITY) {
                return performEqualityJoin(lhsResult, rhsResult, condition, outputGranularity);
            } else if (operatorType == JoinCondition.JoinOperatorType.TEMPORAL) {
                TemporalPredicate predicate = temporalPredicateOpt
                        .orElseThrow(() -> new QueryExecutionException("Temporal predicate required for TEMPORAL join",
                                "join", QueryExecutionException.ErrorType.INTERNAL_ERROR));
                return performTemporalJoin(lhsResult, rhsResult, condition, predicate, outputGranularity);
            } else {
                throw new QueryExecutionException("Unsupported join operator: " + operatorType,
                        "join", QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
            }
        } else {
            throw new QueryExecutionException("Unsupported join type: " + joinType,
                    "join", QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }
    }

    /**
     * Equality join: match bindings by value across all cells from both sides.
     * If neither side has bindings, falls back to cell-level intersection.
     * For INNER joins, if either side has empty cells, the result is empty.
     */
    private CellResult performEqualityJoin(CellResult lhs, CellResult rhs,
            JoinCondition condition, Query.Granularity granularity) {

        // INNER join: if either side is empty, result is empty
        if (lhs.isEmpty() || rhs.isEmpty()) {
            return CellResult.empty(granularity);
        }

        Bindings lhsBindings = lhs.bindings();
        Bindings rhsBindings = rhs.bindings();

        // If neither has bindings, fall back to cell-level intersection
        if (lhsBindings == null && rhsBindings == null) {
            Roaring64NavigableMap joinedCells = lhs.cells().clone();
            joinedCells.and(rhs.cells());
            if (joinedCells.isEmpty()) {
                return CellResult.empty(granularity);
            }
            OccurrencesBlock joinedOcc = null;
            if (lhs.occurrences() != null) {
                joinedOcc = lhs.occurrences().intersect(joinedCells);
            }
            return CellResult.of(joinedCells, joinedOcc, null, granularity);
        }

        // If one side lacks bindings, we can still do cell-level cross-product
        // of the side that has bindings with the cells of the other side
        if (lhsBindings == null || rhsBindings == null) {
            long[] lhsCellKeys = extractCellKeys(lhs.cells());
            long[] rhsCellKeys = extractCellKeys(rhs.cells());
            Bindings mergedBindings = CsrIntersectHelper.mergeBindings(
                    lhsBindings, lhsCellKeys, rhsBindings, rhsCellKeys);
            return CellResult.of(lhs.cells(), null, mergedBindings, granularity);
        }

        // Both sides have bindings: match by value across ALL cells
        // Use full column name (alias.key) to match variable names in bindings
        String leftCol = condition.leftColumn();
        String rightCol = condition.rightColumn();
        long[] lhsCellKeys = extractCellKeys(lhs.cells());
        long[] rhsCellKeys = extractCellKeys(rhs.cells());

        Bindings mergedBindings;
        if (hasMatchingKey(lhsBindings, leftCol) && hasMatchingKey(rhsBindings, rightCol)) {
            mergedBindings = matchBindingsByValue(lhsBindings, leftCol, lhsCellKeys,
                    rhsBindings, rightCol, rhsCellKeys);
        } else {
            mergedBindings = CsrIntersectHelper.mergeBindings(
                    lhsBindings, lhsCellKeys, rhsBindings, rhsCellKeys);
        }

        return CellResult.of(lhs.cells(), null, mergedBindings, granularity);
    }

    /**
     * Temporal join: cross-product bindings from both sides and evaluate
     * the temporal predicate on date values to filter matching rows.
     * For INNER joins, if either side has empty cells, the result is empty.
     */
    private CellResult performTemporalJoin(CellResult lhs, CellResult rhs,
            JoinCondition condition, TemporalPredicate predicate,
            Query.Granularity granularity) {

        // INNER join: if either side is empty, result is empty
        if (lhs.isEmpty() || rhs.isEmpty()) {
            return CellResult.empty(granularity);
        }

        Bindings lhsBindings = lhs.bindings();
        Bindings rhsBindings = rhs.bindings();

        // If neither has bindings, fall back to cell intersection with
        // generic cross-product merge
        if (lhsBindings == null && rhsBindings == null) {
            Roaring64NavigableMap joinedCells = lhs.cells().clone();
            joinedCells.and(rhs.cells());
            if (joinedCells.isEmpty()) {
                return CellResult.empty(granularity);
            }
            return CellResult.of(joinedCells, null, null, granularity);
        }

        // Use full column name (alias.key) to match variable names in bindings
        String leftCol = condition.leftColumn();
        String rightCol = condition.rightColumn();
        long[] lhsCellKeys = extractCellKeys(lhs.cells());
        long[] rhsCellKeys = extractCellKeys(rhs.cells());

        Bindings mergedBindings;
        if (lhsBindings != null && rhsBindings != null
                && hasMatchingKey(lhsBindings, leftCol)
                && hasMatchingKey(rhsBindings, rightCol)) {
            mergedBindings = matchBindingsByTemporal(lhsBindings, leftCol, lhsCellKeys,
                    rhsBindings, rightCol, rhsCellKeys, predicate);
        } else {
            // One side lacks bindings or lacks the join key:
            // fall back to generic cross-product merge
            mergedBindings = CsrIntersectHelper.mergeBindings(
                    lhsBindings, lhsCellKeys, rhsBindings, rhsCellKeys);
        }

        return CellResult.of(lhs.cells(), null, mergedBindings, granularity);
    }

    private static long[] extractCellKeys(Roaring64NavigableMap cells) {
        long[] keys = new long[(int) cells.getLongCardinality()];
        int i = 0;
        var iter = cells.getLongIterator();
        while (iter.hasNext()) {
            keys[i++] = iter.next();
        }
        return keys;
    }

    private static boolean hasMatchingKey(Bindings bindings, String key) {
        for (int i = 0; i < bindings.size(); i++) {
            String varName = bindings.variableNameAt(i);
            if (varName != null && varName.equals(key))
                return true;
        }
        return false;
    }

    /**
     * Matches bindings from two sides where the join key values are equal.
     * Cross-products all rows from both sides regardless of cell membership.
     * Produces a merged binding with both sides' values.
     */
    private static Bindings matchBindingsByValue(
            Bindings left, String leftKey, long[] leftCellKeys,
            Bindings right, String rightKey, long[] rightCellKeys) {

        Bindings.Builder builder = Bindings.builder();

        for (int li = 0; li < left.size(); li++) {
            if (!leftKey.equals(left.variableNameAt(li)))
                continue;
            Object leftVal = left.valueAt(li);

            for (int ri = 0; ri < right.size(); ri++) {
                if (!rightKey.equals(right.variableNameAt(ri)))
                    continue;
                Object rightVal = right.valueAt(ri);

                if (java.util.Objects.equals(leftVal, rightVal)) {
                    addAllBindings(builder, left, li);
                    addAllBindings(builder, right, ri);
                }
            }
        }

        return builder.isEmpty() ? null : builder.build();
    }

    /**
     * Matches bindings from two sides by evaluating a temporal predicate
     * on date values. Cross-products all rows from both sides.
     */
    private static Bindings matchBindingsByTemporal(
            Bindings left, String leftKey, long[] leftCellKeys,
            Bindings right, String rightKey, long[] rightCellKeys,
            TemporalPredicate predicate) {

        Bindings.Builder builder = Bindings.builder();

        for (int li = 0; li < left.size(); li++) {
            if (!leftKey.equals(left.variableNameAt(li)))
                continue;
            Object leftVal = left.valueAt(li);
            if (!(leftVal instanceof java.time.LocalDate))
                continue;
            java.time.LocalDate leftDate = (java.time.LocalDate) leftVal;

            for (int ri = 0; ri < right.size(); ri++) {
                if (!rightKey.equals(right.variableNameAt(ri)))
                    continue;
                Object rightVal = right.valueAt(ri);
                if (!(rightVal instanceof java.time.LocalDate))
                    continue;
                java.time.LocalDate rightDate = (java.time.LocalDate) rightVal;

                if (evaluateTemporalPredicate(leftDate, rightDate, predicate)) {
                    addAllBindings(builder, left, li);
                    addAllBindings(builder, right, ri);
                }
            }
        }

        return builder.isEmpty() ? null : builder.build();
    }

    /**
     * Evaluates a temporal predicate between two LocalDates.
     */
    private static boolean evaluateTemporalPredicate(
            java.time.LocalDate left, java.time.LocalDate right,
            TemporalPredicate predicate) {
        return switch (predicate) {
            case BEFORE -> left.isBefore(right);
            case AFTER -> left.isAfter(right);
            case BEFORE_EQUAL -> !left.isAfter(right);
            case AFTER_EQUAL -> !left.isBefore(right);
            case EQUAL -> left.isEqual(right);
            default -> false;
        };
    }

    private static void addAllBindings(Bindings.Builder builder, Bindings bindings, int row) {
        builder.add(bindings.valueAt(row), bindings.valueTypeAt(row), bindings.variableNameAt(row));
    }

    // --- Column name utilities (kept from original) ---
    public static String extractAliasFromColumnName(String qualifiedColumnName) {
        if (qualifiedColumnName == null)
            return null;
        int dotIndex = qualifiedColumnName.lastIndexOf('.');
        return dotIndex >= 0 ? qualifiedColumnName.substring(0, dotIndex) : qualifiedColumnName;
    }

    public static String extractKeyFromColumnName(String qualifiedColumnName) {
        if (qualifiedColumnName == null)
            return null;
        int dotIndex = qualifiedColumnName.lastIndexOf('.');
        return dotIndex >= 0 ? qualifiedColumnName.substring(dotIndex + 1) : qualifiedColumnName;
    }
}
