package com.example.query.executor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Logical.LogicalOperator;

/**
 * Executor for logical conditions (AND, OR).
 *
 * <p>
 * Uses {@link CellResult#and(CellResult)} and {@link CellResult#or(CellResult)}
 * to combine sub-condition results. No merge-join or stitch fusion is needed
 * &mdash; cell intersection, occurrence narrowing, and binding merging are
 * handled by {@code CellResult} itself.
 */
public final class LogicalExecutor implements ConditionExecutor<Logical> {
    private static final Logger logger = LoggerFactory.getLogger(LogicalExecutor.class);

    private final ConditionExecutorFactory executorFactory;

    public LogicalExecutor(ConditionExecutorFactory executorFactory, String stitchStrategy,
            Query.Granularity queryGranularity) {
        this.executorFactory = executorFactory;
        // stitchStrategy and queryGranularity are accepted for backward
        // compatibility with existing callers but are no longer used.
        logger.info("LogicalExecutor initialized (stitch fusion is now handled by CellResult).");
    }

    @Override
    public CellResult execute(Logical condition, Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException {

        List<Condition> subConditions = condition.conditions();
        if (subConditions.isEmpty()) {
            logger.debug("Logical condition has no sub-conditions, returning empty CellResult");
            return CellResult.empty(granularity);
        }

        LogicalOperator operator = condition.operator();
        CellResult result;
        if (operator == LogicalOperator.AND) {
            result = executeAnd(subConditions, indexes, granularity, granularitySize,
                    corpusName, requirements);
        } else if (operator == LogicalOperator.OR) {
            result = executeOr(subConditions, indexes, granularity, granularitySize,
                    corpusName, requirements);
        } else {
            throw new QueryExecutionException("Unsupported logical operator: " + operator,
                    condition.toString(), QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }

        // Apply allowedCells filter at the end if present
        if (allowedCells.isPresent() && !result.isEmpty()) {
            Roaring64NavigableMap filtered = result.cells().clone();
            filtered.and(allowedCells.get());
            result = CellResult.of(filtered, granularity);
            logger.debug("Applied allowedCells filter: {} cells remain", filtered.getLongCardinality());
        }

        return result;
    }

    /**
     * Executes AND by sorting sub-conditions cheapest-first and folding via
     * {@link CellResult#and(CellResult)}.
     */
    private CellResult executeAnd(List<Condition> conditions,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements)
            throws QueryExecutionException {

        // Sort by estimated cost: Contains (cheapest) first, then by type
        List<Condition> sorted = new ArrayList<>(flattenAndConditions(conditions));
        sorted.sort(COST_COMPARATOR);

        // Execute cheapest first
        ConditionExecutor<Condition> firstExecutor = executorFactory.getExecutor(sorted.get(0));
        CellResult cumulative = firstExecutor.execute(sorted.get(0), indexes, granularity,
                granularitySize, corpusName, requirements, Optional.empty());
        logger.debug("AND: first condition ({} cells) type={}",
                cumulative.cellCount(), sorted.get(0).getClass().getSimpleName());

        if (cumulative.isEmpty()) {
            return cumulative;
        }

        // Fold remaining conditions
        for (int i = 1; i < sorted.size(); i++) {
            ConditionExecutor<Condition> executor = executorFactory.getExecutor(sorted.get(i));
            CellResult next = executor.execute(sorted.get(i), indexes, granularity,
                    granularitySize, corpusName, requirements, Optional.empty());
            logger.debug("AND: condition {}/{} returned {} cells (type={})",
                    i + 1, sorted.size(), next.cellCount(),
                    sorted.get(i).getClass().getSimpleName());

            cumulative = cumulative.and(next);
            logger.debug("AND: after folding condition {}, cumulative={} cells",
                    i + 1, cumulative.cellCount());

            if (cumulative.isEmpty()) {
                return cumulative;
            }
        }

        return cumulative;
    }

    /**
     * Executes OR by folding sub-conditions via
     * {@link CellResult#or(CellResult)}.
     */
    private CellResult executeOr(List<Condition> conditions,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements)
            throws QueryExecutionException {

        ConditionExecutor<Condition> firstExecutor = executorFactory.getExecutor(conditions.get(0));
        CellResult cumulative = firstExecutor.execute(conditions.get(0), indexes, granularity,
                granularitySize, corpusName, requirements, Optional.empty());

        for (int i = 1; i < conditions.size(); i++) {
            ConditionExecutor<Condition> executor = executorFactory.getExecutor(conditions.get(i));
            CellResult next = executor.execute(conditions.get(i), indexes, granularity,
                    granularitySize, corpusName, requirements, Optional.empty());
            if (next.isEmpty()) {
                continue;
            }
            if (cumulative.isEmpty()) {
                cumulative = next;
                continue;
            }
            cumulative = cumulative.or(next);
        }

        return cumulative;
    }

    /**
     * Flattens nested AND conditions so that
     * {@code AND(AND(a,b), c)} becomes {@code [a, b, c]}.
     */
    private List<Condition> flattenAndConditions(List<Condition> conditions) {
        List<Condition> flattened = new ArrayList<>();
        for (Condition cond : conditions) {
            if (cond instanceof Logical logicalCond
                    && logicalCond.operator() == LogicalOperator.AND) {
                flattened.addAll(flattenAndConditions(logicalCond.conditions()));
            } else {
                flattened.add(cond);
            }
        }
        return flattened;
    }

    /**
     * Cost comparator that ranks {@link Contains} conditions as cheapest
     * (lowest cost first), with a tie-break on class name for determinism.
     */
    private static final Comparator<Condition> COST_COMPARATOR = (a, b) -> {
        int costA = costRank(a);
        int costB = costRank(b);
        if (costA != costB) {
            return Integer.compare(costA, costB);
        }
        return a.getClass().getSimpleName().compareTo(b.getClass().getSimpleName());
    };

    private static int costRank(Condition c) {
        if (c instanceof Contains) {
            return 0; // cheapest
        }
        return 1; // everything else
    }
}
