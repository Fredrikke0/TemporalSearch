package com.example.query.parser.util;

/**
 * Utility class for common validation operations and error message formatting
 * used throughout the query parsing process.
 */
public class ValidationUtils {

    /**
     * Validates that a result object is a single condition.
     *
     * @param visitResult the result from visiting a condition node
     * @param operandDescription description of the operand for error messages
     * @return the single condition
     * @throws IllegalStateException if the result is not a single condition
     */
    public static com.example.query.model.condition.Condition extractSingleCondition(
            Object visitResult, String operandDescription) {
        if (visitResult instanceof com.example.query.model.condition.Condition condition) {
            return condition;
        } else if (visitResult instanceof java.util.List<?> list) {
            @SuppressWarnings("unchecked")
            java.util.List<com.example.query.model.condition.Condition> conditions =
                (java.util.List<com.example.query.model.condition.Condition>) list;
            if (conditions.size() == 1) {
                return conditions.get(0);
            } else {
                throw new IllegalStateException(
                    String.format("Logical operator %s unexpectedly resolved to multiple conditions (%d).",
                                operandDescription, conditions.size())
                );
            }
        } else {
            throw new IllegalStateException(
                String.format("Logical operator %s resolved to unexpected type: %s",
                            operandDescription,
                            visitResult != null ? visitResult.getClass().getName() : "null")
            );
        }
    }
}