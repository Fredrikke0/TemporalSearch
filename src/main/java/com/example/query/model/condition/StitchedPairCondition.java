package com.example.query.model.condition;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.example.query.binding.VariableRegistry;

/**
 * Represents a pair of conditions (typically Contains + Annotation)
 * that have been identified as suitable for a combined "stitch" execution.
 */
public record StitchedPairCondition(
    Contains containsCondition,
    Condition annotationCondition
    // TODO: Consider adding pre-calculated stitchIndexName or stitchLookupKeyPrefix if beneficial
) implements Condition {

    public StitchedPairCondition {
        Objects.requireNonNull(containsCondition, "containsCondition cannot be null");
        Objects.requireNonNull(annotationCondition, "annotationCondition cannot be null");
    }

    @Override
    public String getType() {
        return "STITCHED_PAIR";
    }

    @Override
    public Set<String> getProducedVariables() {
        Set<String> produced = new HashSet<>(containsCondition.getProducedVariables());
        produced.addAll(annotationCondition.getProducedVariables());
        return produced;
    }

    @Override
    public Set<String> getConsumedVariables() {
        Set<String> consumed = new HashSet<>(containsCondition.getConsumedVariables());
        consumed.addAll(annotationCondition.getConsumedVariables());
        return consumed;
    }

    @Override
    public void registerVariables(VariableRegistry registry) {
        containsCondition.registerVariables(registry);
        annotationCondition.registerVariables(registry);
    }

    @Override
    public String toString() {
        return "STITCH(" + containsCondition.toString() + " AND " + annotationCondition.toString() + ")";
    }
}