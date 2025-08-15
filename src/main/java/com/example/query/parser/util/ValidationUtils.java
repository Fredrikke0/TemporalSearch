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


    /**
     * Validates that a comparison operator is supported.
     *
     * @param operator the operator to validate
     * @throws IllegalArgumentException if the operator is invalid
     */
    public static void validateComparisonOperator(String operator) {
        if (operator == null) {
            throw new IllegalArgumentException("Comparison operator cannot be null");
        }

        switch (operator) {
            case ">", "<", ">=", "<=", "=", "!=" -> {
                // Valid operators
            }
            default -> throw new IllegalArgumentException("Invalid comparison operator: " + operator);
        }
    }

    /**
     * Validates that a temporal operator is supported.
     *
     * @param operator the temporal operator to validate
     * @throws IllegalArgumentException if the operator is invalid
     */
    public static void validateTemporalOperator(String operator) {
        if (operator == null) {
            throw new IllegalArgumentException("Temporal operator cannot be null");
        }

        switch (operator) {
            case ">", "<", ">=", "<=", "=", "!=" -> {
                // Valid temporal operators
            }
            default -> throw new IllegalArgumentException("Invalid temporal operator: " + operator);
        }
    }

    /**
     * Validates that a logical operator is supported.
     *
     * @param operator the logical operator to validate
     * @throws IllegalArgumentException if the operator is invalid
     */
    public static void validateLogicalOperator(String operator) {
        if (operator == null) {
            throw new IllegalArgumentException("Logical operator cannot be null");
        }

        if (!operator.equalsIgnoreCase("AND") && !operator.equalsIgnoreCase("OR")) {
            throw new IllegalArgumentException("Invalid logical operator: " + operator +
                                             ". Supported operators: AND, OR");
        }
    }

    /**
     * Creates a formatted error message for missing BIND clauses.
     *
     * @param expressionType the type of expression that requires BIND
     * @return formatted error message
     */
    public static String formatMissingBindError(String expressionType) {
        return String.format("%s requires an explicit BIND clause specifying the variable name.",
                           expressionType);
    }

    /**
     * Creates a formatted error message for qualification requirements.
     *
     * @param variableName the unqualified variable name
     * @param context the context where qualification is required
     * @return formatted error message
     */
    public static String formatQualificationRequiredError(String variableName, String context) {
        return String.format("Unqualified variable '%s' used in %s where qualification is required. " +
                           "Use 'alias.%s' format.", variableName, context, variableName);
    }
}