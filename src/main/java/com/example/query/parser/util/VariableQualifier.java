package com.example.query.parser.util;

import java.util.Optional;

/**
 * Utility class for handling variable qualification in query parsing.
 * Provides methods to determine qualification requirements and qualify variable names
 * according to query context.
 */
public class VariableQualifier {

    /**
     * Default alias for main query when no explicit alias is provided.
     * References the same constant used throughout the query parsing system.
     */
    public static final String DEFAULT_MAIN_ALIAS = com.example.query.parser.QueryModelBuilder.DEFAULT_MAIN_ALIAS;

    /**
     * Determines if variable qualification is required based on context.
     *
     * @param explicitMainAlias whether the main query has an explicit alias
     * @param hasJoins whether the query contains JOIN clauses
     * @param hasSubqueries whether the query contains subqueries
     * @return true if qualification is required
     */
    public static boolean isQualificationRequired(Optional<String> explicitMainAlias,
                                                 boolean hasJoins,
                                                 boolean hasSubqueries) {
        return explicitMainAlias.isPresent() || hasJoins || hasSubqueries;
    }

    /**
     * Qualifies a variable name if needed.
     *
     * @param variableName the variable name to qualify
     * @param qualificationRequired whether qualification is required
     * @param explicitMainAlias the explicit main alias if present
     * @return qualified variable name
     * @throws IllegalArgumentException if qualification is required but variable is unqualified
     */
    public static String qualifyVariable(String variableName,
                                       boolean qualificationRequired,
                                       Optional<String> explicitMainAlias) {
        if (qualificationRequired) {
            if (!variableName.contains(".")) {
                throw new IllegalArgumentException(
                    String.format("Unqualified variable '%s' used where qualification is required. " +
                                "Use 'alias.%s' format.", variableName, variableName)
                );
            }
            return variableName; // Already qualified
        } else {
            if (variableName.contains(".")) {
                return variableName; // Already qualified
            }
            // Implicitly qualify with appropriate alias
            String alias = explicitMainAlias.orElse(DEFAULT_MAIN_ALIAS);
            return alias + "." + variableName;
        }
    }

    /**
     * Qualifies a variable with a specific scope alias.
     *
     * @param variableName the variable name to qualify
     * @param scopeAlias the scope alias to use for qualification
     * @return qualified variable name
     */
    public static String qualifyWithScope(String variableName, String scopeAlias) {
        if (variableName.contains(".")) {
            return variableName; // Already qualified
        }
        return scopeAlias + "." + variableName;
    }

    /**
     * Extracts the plain name from a qualified variable.
     *
     * @param qualifiedName the qualified variable name
     * @return the plain variable name without alias
     */
    public static String extractPlainName(String qualifiedName) {
        return qualifiedName.contains(".")
            ? qualifiedName.substring(qualifiedName.indexOf(".") + 1)
            : qualifiedName;
    }


}