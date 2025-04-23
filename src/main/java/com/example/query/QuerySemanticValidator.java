package com.example.query;

import com.example.query.model.*;
import com.example.query.binding.VariableRegistry;
import com.example.query.binding.Variable;
import com.example.query.binding.VariableType;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Ner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates the semantic correctness of a query using the new VariableRegistry system.
 * This validator relies entirely on the VariableRegistry for validation rather than
 * keeping its own state of variable bindings.
 */
public class QuerySemanticValidator {
    private static final Logger logger = LoggerFactory.getLogger(QuerySemanticValidator.class);
    
    // Maximum allowed snippet window size (sentences)
    private static final int MAX_SNIPPET_WINDOW_SIZE = 5;
    
    // Maximum proximity window for temporal joins
    private static final int MAX_TEMPORAL_PROXIMITY_WINDOW = 365;

    // Define the set of valid NER entity types (uppercase)
    private static final Set<String> VALID_NER_TYPES = Set.of(
        "PERSON", "ORGANIZATION", "LOCATION", "DATE", "TIME", 
        "DURATION", "MONEY", "NUMBER", "ORDINAL", "PERCENT", "SET", "*"
    );

    /**
     * Validates a query for semantic correctness.
     *
     * @param query The query to validate
     * @throws QueryParseException if the query has semantic errors
     */
    public void validate(Query query) throws QueryParseException {
        logger.debug("Starting semantic validation for query: {}", query);
        
        // Get the variable registry from the query
        VariableRegistry registry = query.variableRegistry();
        if (registry == null) {
            throw new QueryParseException("Query does not have a variable registry");
        }
        
        // Validate NER types in conditions *before* validating variable usage
        validateNerTypes(query.conditions());
        
        // Validate variable dependencies and types
        validateVariableDependencies(registry);
        
        // Validate select columns
        validateSelectColumns(query, registry);
        
        // Validate limit value
        query.limit().ifPresent(limit -> {
            try {
                validateLimit(limit);
            } catch (QueryParseException e) {
                throw new RuntimeException(e);
            }
        });
        
        // Validate snippet window sizes
        validateSnippetWindowSizes(query);
        
        // Validate subqueries and join conditions if present
        if (query.hasSubqueries()) {
            validateSubqueries(query);
            validateJoinConditions(query);
        }
        
        logger.debug("Semantic validation completed successfully");
    }
    
    /**
     * Validates that any NER conditions use a recognized entity type.
     * 
     * @param conditions The list of conditions to check.
     * @throws QueryParseException If an invalid NER type is found.
     */
    private void validateNerTypes(List<Condition> conditions) throws QueryParseException {
        for (Condition condition : conditions) {
            if (condition instanceof Ner nerCondition) {
                String entityType = nerCondition.entityType();
                // Use uppercase for case-insensitive comparison, except for wildcard '*'
                String comparisonType = "*".equals(entityType) ? "*" : entityType.toUpperCase(); 
                
                if (!VALID_NER_TYPES.contains(comparisonType)) {
                    String validTypesString = String.join(", ", VALID_NER_TYPES);
                    throw new QueryParseException(String.format(
                        "Invalid NER entity type '%s' used in condition %s. Valid types are: %s",
                        entityType, nerCondition.toString(), validTypesString
                    ));
                }
            }
            // Recursively check nested conditions (e.g., inside NOT or logical operators)
            // This part might need adjustment based on how complex condition structures are handled.
            // For now, assuming a flat list or structure handled by the Query object itself.
            // If conditions can be nested (e.g., AND(NER(...), NOT(POS(...)))), 
            // a recursive traversal might be needed here.
        }
    }
    
    /**
     * Validates variable dependencies ensuring all consumed variables are produced.
     * Also performs type checking on variable usage.
     */
    private void validateVariableDependencies(VariableRegistry registry) throws QueryParseException {
        // The registry's validate method checks that all consumed variables are produced
        Set<String> registryErrors = registry.validate();
        if (!registryErrors.isEmpty()) {
            throw new QueryParseException("Variable validation errors: " + String.join(", ", registryErrors));
        }
        
        // Additional dependency checks could be added here if needed
        // For now, we rely on the registry's validate method
    }
    
    /**
     * Validates the select columns, ensuring all column references are valid.
     * Handles both unqualified variables (from the main query) and qualified variables
     * (e.g., `alias.var` from subqueries or the aliased main query).
     */
    private void validateSelectColumns(Query query, VariableRegistry mainRegistry) throws QueryParseException {
        if (query.selectColumns().isEmpty()) {
            throw new QueryParseException("Query must select at least one column");
        }

        // Create a map for quick lookup of subqueries by alias
        Map<String, SubquerySpec> subqueryMap = query.subqueries().stream()
                .collect(Collectors.toMap(SubquerySpec::alias, sq -> sq));

        for (SelectColumn column : query.selectColumns()) {
            if (column instanceof VariableColumn variableColumn) {
                String columnName = variableColumn.getColumnName(); // Might be "var" or "alias.var"

                if (columnName.contains(".")) {
                    // Qualified variable: alias.var
                    String[] parts = columnName.split("\\.", 2);
                    // No longer check for '?' prefix on the variable part
                    if (parts.length != 2) { 
                        throw new QueryParseException("Invalid qualified variable format in SELECT: " + columnName + ". Expected format: alias.variable");
                    }
                    String alias = parts[0];
                    String variableName = parts[1]; // Plain variable name

                    logger.debug("Validating qualified SELECT variable: {} from alias {}", variableName, alias);

                    // Pass plain variableName to validation helper
                    if (query.mainAlias().isPresent() && query.mainAlias().get().equals(alias)) {
                        validateVariableInRegistry(variableName, mainRegistry, "main query (aliased as " + alias + ")");
                    } 
                    // Check if the alias refers to a subquery
                    else if (subqueryMap.containsKey(alias)) {
                        SubquerySpec subquerySpec = subqueryMap.get(alias);
                        VariableRegistry subqueryRegistry = subquerySpec.subquery().variableRegistry();
                        validateVariableInRegistry(variableName, subqueryRegistry, "subquery '" + alias + "'");
                    } else {
                        throw new QueryParseException("Unknown alias '" + alias + "' in SELECT column: " + columnName);
                    }

                } else {
                    // Unqualified variable: var - must belong to main query's scope
                    String variableName = columnName; // Already the plain name
                    logger.debug("Validating unqualified SELECT variable: {}", variableName);
                    
                    // Unqualified variables are validated against the main registry.
                     // Pass plain variableName to validation helper
                    validateVariableInRegistry(variableName, mainRegistry, "main query");
                }

            } else if (column instanceof SnippetColumn snippetColumn) {
                // Allow SNIPPET to reference qualified or unqualified variables
                String fullVariableName = snippetColumn.getVariableName(); // Might be "var" or "alias.var"
                
                if (fullVariableName.contains(".")) {
                     // Qualified variable: alias.var
                    String[] parts = fullVariableName.split("\\.", 2);
                     // No longer check for '?' prefix on the variable part
                    if (parts.length != 2) { 
                        throw new QueryParseException("Invalid qualified variable format in SNIPPET: " + fullVariableName + ". Expected format: alias.variable");
                    }
                    String alias = parts[0];
                    String variableName = parts[1]; // Plain variable name

                    logger.debug("Validating qualified SNIPPET variable: {} from alias {}", variableName, alias);

                     // Pass plain variableName to validation helper
                    if (query.mainAlias().isPresent() && query.mainAlias().get().equals(alias)) {
                        validateVariableInRegistry(variableName, mainRegistry, "main query (aliased as " + alias + " for SNIPPET)");
                    } 
                    // Check if the alias refers to a subquery
                    else if (subqueryMap.containsKey(alias)) {
                        SubquerySpec subquerySpec = subqueryMap.get(alias);
                        VariableRegistry subqueryRegistry = subquerySpec.subquery().variableRegistry();
                        validateVariableInRegistry(variableName, subqueryRegistry, "subquery '" + alias + "' (for SNIPPET)");
                    } else {
                        throw new QueryParseException("Unknown alias '" + alias + "' in SNIPPET column: " + fullVariableName);
                    }
                    
                } else {
                    // Unqualified variable: var - must belong to main query's scope
                    String variableName = fullVariableName; // Already the plain name
                    logger.debug("Validating unqualified SNIPPET variable: {}", variableName);
                     // Pass plain variableName to validation helper
                    validateVariableInRegistry(variableName, mainRegistry, "main query (for SNIPPET)");
                }
            
            } else if (column instanceof CountColumn countColumn) {
                // COUNT(UNIQUE var) - variable must be unqualified and belong to the main query registry
                // We need a way to get the variable name only if it's a COUNT(UNIQUE var)
                String variableName = countColumn.getVariableNameForValidation(); // Should return plain name or null
                if (variableName != null) { // Only validate if it's COUNT(UNIQUE var)
                    logger.debug("Validating COUNT(UNIQUE) variable: {}", variableName);
                    // Pass plain variableName to validation helper
                    validateVariableInRegistry(variableName, mainRegistry, "main query (for COUNT)");
                }
            }
            // Other column types (TITLE, TIMESTAMP) don't need variable validation
        }
    }
    
    /**
     * Helper method to validate that a variable exists and is produced within a specific registry.
     * 
     * @param variableName The plain variable name (without '?')
     * @param registry The VariableRegistry to check against
     * @param contextDescription A description of the context (e.g., "main query", "subquery 'sq1'") for error messages
     * @throws QueryParseException If the variable is not found or not produced
     */
    private void validateVariableInRegistry(String variableName, VariableRegistry registry, String contextDescription) throws QueryParseException {
        // Variable name is expected to be plain (no '?' prefix)
        // Remove the starting '?' check
         
        if (!registry.getAllVariableNames().contains(variableName)) {
            throw new QueryParseException(String.format(
                "Unbound variable in SELECT: %s (referenced from %s). Variable not found in its scope.",
                variableName, contextDescription
            ));
        }
        if (!registry.isProduced(variableName)) {
            throw new QueryParseException(String.format(
                "Variable %s in SELECT (referenced from %s) is consumed but not produced within its scope.",
                variableName, contextDescription
            ));
        }
        logger.debug("Variable {} successfully validated in registry for {}", variableName, contextDescription);
    }
    
    /**
     * Validates that all snippet window sizes are within acceptable limits.
     */
    private void validateSnippetWindowSizes(Query query) throws QueryParseException {
        for (SelectColumn column : query.selectColumns()) {
            if (column instanceof SnippetColumn snippetColumn) {
                int windowSize = snippetColumn.getWindowSize();
                
                if (windowSize > MAX_SNIPPET_WINDOW_SIZE) {
                    throw new QueryParseException(String.format(
                        "Snippet window size %d exceeds maximum allowed size of %d sentences",
                        windowSize, MAX_SNIPPET_WINDOW_SIZE
                    ));
                }
            }
        }
    }
    
    /**
     * Validates the limit value.
     */
    private void validateLimit(int limit) throws QueryParseException {
        if (limit <= 0) {
            throw new QueryParseException("LIMIT value must be greater than 0");
        }
    }
    
    /**
     * Validates all subqueries in a query.
     * Each subquery is validated independently.
     */
    private void validateSubqueries(Query query) throws QueryParseException {
        for (SubquerySpec subquery : query.subqueries()) {
            // Validate the subquery itself
            validate(subquery.subquery());
            
            // Validate the alias (should be non-empty, but this is already checked in the constructor)
            if (subquery.alias().isEmpty()) {
                throw new QueryParseException("Subquery alias cannot be empty");
            }
            
            // Validate projected columns if specified
            subquery.projectedColumns().ifPresent(columns -> {
                if (columns.isEmpty()) {
                    throw new RuntimeException(new QueryParseException("Subquery projected columns list cannot be empty"));
                }
                
                // Verify that all projected columns exist in the subquery
                // This would require more context about column availability in subqueries
                // For now, we'll defer this validation until execution time
            });
        }
    }
    
    /**
     * Validates join conditions between the main query and subqueries.
     */
    private void validateJoinConditions(Query query) throws QueryParseException {
        // Join condition is required if there are subqueries
        if (!query.subqueries().isEmpty() && query.joinCondition().isEmpty()) {
            throw new QueryParseException("Query with subqueries must have a join condition");
        }
        
        // Validate the join condition if present
        query.joinCondition().ifPresent(joinCondition -> {
            // Validate left column exists in main query
            // This would require more context about available columns
            // For Phase 1, we'll defer this validation
            
            // Validate right column exists in subquery
            // This would require more context about available columns
            // For Phase 1, we'll defer this validation
            
            // Validate proximity window if applicable
            if (joinCondition.temporalPredicate() == TemporalPredicate.PROXIMITY) {
                joinCondition.proximityWindow().ifPresent(window -> {
                    if (window <= 0) {
                        throw new RuntimeException(new QueryParseException("Proximity window must be greater than 0"));
                    }
                    
                    if (window > MAX_TEMPORAL_PROXIMITY_WINDOW) {
                        throw new RuntimeException(new QueryParseException(
                            String.format("Proximity window %d exceeds maximum allowed size of %d days", 
                                         window, MAX_TEMPORAL_PROXIMITY_WINDOW)));
                    }
                });
            }
        });
    }
} 