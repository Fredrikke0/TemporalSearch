package com.example.query.model.condition;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.query.binding.VariableRegistry;

/**
 * A condition that represents a "fused" operation between a Contains condition
 * and an annotation-based condition (e.g., Ner, Pos, Temporal).
 * This allows the query executor to treat this fused operation as a single unit,
 * often leveraging specialized stitch indexes for performance.
 */
public record StitchedCondition(
        Contains containsCondition,
        Condition annotationCondition,
        String stitchType
) implements Condition {

    /**
     * Constructs a StitchedCondition.
     *
     * @param containsCondition   The Contains condition part.
     * @param annotationCondition The annotation condition part (e.g., Ner, Pos, Temporal).
     *                            It's assumed this condition produces variables.
     */
    public StitchedCondition {
        if (containsCondition == null) {
            throw new IllegalArgumentException("Contains condition cannot be null");
        }
        if (annotationCondition == null) {
            throw new IllegalArgumentException("Annotation condition cannot be null");
        }
        if (stitchType == null || stitchType.isBlank()) {
            throw new IllegalArgumentException("Stitch type cannot be null or blank");
        }
    }

    @Override
    public String getType() {
        return stitchType;
    }

    @Override
    public Set<String> getProducedVariables() {
        return Stream.concat(
                containsCondition.getProducedVariables().stream(),
                annotationCondition.getProducedVariables().stream()
        ).collect(Collectors.toSet());
    }

    @Override
    public Set<String> getConsumedVariables() {
        return Stream.concat(
                containsCondition.getConsumedVariables().stream(),
                annotationCondition.getConsumedVariables().stream()
        ).collect(Collectors.toSet());
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

    /**
     * Requalifies the variables in the nested conditions.
     *
     * @param oldPrefix The old variable prefix.
     * @param newPrefix The new variable prefix.
     * @return A new StitchedCondition with requalified variables.
     */
    public StitchedCondition requalifyVariables(String oldPrefix, String newPrefix) {
        Contains requalifiedContains = (Contains) Logical.requalifyCondition(containsCondition, oldPrefix, newPrefix);
        Condition requalifiedAnnotation = Logical.requalifyCondition(annotationCondition, oldPrefix, newPrefix);
        return new StitchedCondition(requalifiedContains, requalifiedAnnotation, stitchType);
    }
}