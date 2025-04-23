package com.example.query.binding;

/**
 * Represents a pair of MatchDetail objects resulting from a join operation.
 */
public record JoinedMatch(MatchDetail left, MatchDetail right) {
    public String getLeftVariableName() {
        return left.variableName().orElse(null);
    }
    public Object getLeftValue() {
        return left.value();
    }
    public ValueType getLeftValueType() {
        return left.valueType();
    }
    public String getRightVariableName() {
        return right.variableName().orElse(null);
    }
    public Object getRightValue() {
        return right.value();
    }
    public ValueType getRightValueType() {
        return right.valueType();
    }
} 