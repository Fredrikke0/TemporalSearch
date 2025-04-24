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
        
        logger.debug("Subquery aliases available: {}", subqueryMap.keySet());
        logger.debug("SELECT columns: {}", query.selectColumns().stream().map(SelectColumn::getColumnName).toList());
        
        String mainAlias = query.mainAlias().orElse("$main"); // Use $main if no explicit alias

        for (SelectColumn column : query.selectColumns()) {
            String fullColumnName; // This will hold the name as it appears in SELECT/SNIPPET
            String context; // For error messages

            if (column instanceof VariableColumn variableColumn) {
                fullColumnName = variableColumn.getColumnName();
                context = "SELECT";
            } else if (column instanceof SnippetColumn snippetColumn) {
                fullColumnName = snippetColumn.getVariableName();
                context = "SNIPPET";
            } else {
                continue; // Skip non-variable columns (COUNT, TITLE, etc.)
            }

            String alias; // Alias part extracted from fullColumnName
            String plainVariableName; // Variable part extracted
            String registryKey; // The key expected in the VariableRegistry (internally qualified)
            VariableRegistry targetRegistry; // The registry to check

            if (fullColumnName.contains(".")) {
                // Qualified variable: alias.var
                String[] parts = fullColumnName.split("\\.", 2);
                if (parts.length != 2) {
                    throw new QueryParseException(String.format(
                        "Invalid qualified variable format in %s: %s. Expected format: alias.variable",
                        context, fullColumnName));
                }
                alias = parts[0];
                plainVariableName = parts[1];

                logger.debug("Validating qualified {} variable: {} from alias {}", context, plainVariableName, alias);

                // Determine target registry and registry key
                if (subqueryMap.isEmpty()) {
                    // We are inside the subquery's own validation context.
                    // The mainRegistry *is* the subquery's registry (already requalified).
                    // The fullColumnName (e.g., "q2.person") should exist directly in this registry.
                    targetRegistry = mainRegistry;
                    registryKey = fullColumnName; // Use the full qualified name as the key
                    validateVariableInRegistry(registryKey, targetRegistry, String.format("subquery '%s' internal validation for %s", alias, context));
                } else if (alias.equals(mainAlias) || (alias.equals("$main") && !query.mainAlias().isPresent())) { 
                    // Belongs to main query (explicitly or implicitly via $main)
                    targetRegistry = mainRegistry;
                    registryKey = mainAlias + "." + plainVariableName; // Key uses main alias ($main or explicit)
                    validateVariableInRegistry(registryKey, targetRegistry, String.format("main query (alias %s) for %s", mainAlias, context));
                } else if (subqueryMap.containsKey(alias)) {
                    // Belongs to a subquery (validation running in the main query context)
                    SubquerySpec subquerySpec = subqueryMap.get(alias);
                    targetRegistry = subquerySpec.subquery().variableRegistry();
                    registryKey = alias + "." + plainVariableName; // Key uses subquery alias
                    validateVariableInRegistry(registryKey, targetRegistry, String.format("subquery '%s' for %s", alias, context));
                } else {
                    // This case should ideally not be reached if the logic above is correct
                    throw new QueryParseException(String.format("Unknown alias '%s' in %s column: %s", alias, context, fullColumnName));
                }

            } else {
                // Unqualified variable: var - must belong to main query's scope
                alias = mainAlias; // Implicitly belongs to main scope
                plainVariableName = fullColumnName;
                targetRegistry = mainRegistry;
                registryKey = mainAlias + "." + plainVariableName; // Key uses main alias ($main or explicit)
                logger.debug("Validating unqualified {} variable: {} (using alias {})", context, plainVariableName, alias);
                validateVariableInRegistry(registryKey, targetRegistry, String.format("main query (alias %s) for %s", mainAlias, context));
            }
        }
    }
    
    /**
     * Validates that a specific internally qualified variable name is produced within the given registry.
     *
     * @param internalQualifiedName The fully qualified name expected in the registry (e.g., $main.var, alias.var)
     * @param registry The VariableRegistry to check.
     * @param contextDescription A description of the context for error messages.
     * @throws QueryParseException If the variable is not found or not produced.
     */
    private void validateVariableInRegistry(String internalQualifiedName, VariableRegistry registry, String contextDescription) throws QueryParseException {
        // Check if the variable exists and is produced in the registry using isProduced
        if (!registry.isProduced(internalQualifiedName)) {
            // Need to differentiate between "not found" and "found but not produced"
            // Let's check existence using getAllVariableNames for a better error message
            if (!registry.getAllVariableNames().contains(internalQualifiedName)) {
                 throw new QueryParseException(String.format(
                    "Variable '%s' not found in its scope (%s). Available: %s",
                    internalQualifiedName, contextDescription, registry.getAllVariableNames()
                ));
            } else {
                 throw new QueryParseException(String.format(
                    "Variable '%s' is consumed in %s but is never produced (bound).",
                    internalQualifiedName, contextDescription
                ));
            }
        }
        
        logger.debug("Variable '{}' validated successfully in context: {}", internalQualifiedName, contextDescription);
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
        
        // Create a map for quick lookup of subqueries by alias
        Map<String, SubquerySpec> subqueryMap = query.subqueries().stream()
                .collect(Collectors.toMap(SubquerySpec::alias, sq -> sq));

        // Validate the join condition if present
        query.joinCondition().ifPresent(joinCondition -> {
            try {
                // Validate left column exists in main query
                String leftQualified = joinCondition.leftColumn();
                String rightQualified = joinCondition.rightColumn();

                // Validate left column: must be qualified as mainAlias.var or $main.var
                if (leftQualified == null || !leftQualified.contains(".")) {
                    throw new QueryParseException("Left join column must be qualified (alias.var): " + leftQualified);
                }
                String[] leftParts = leftQualified.split("\\.", 2);
                if (leftParts.length != 2) {
                    throw new QueryParseException("Invalid qualified variable format for left join column: " + leftQualified);
                }
                String leftAlias = leftParts[0];
                String leftVar = leftParts[1];
                VariableRegistry mainRegistry = query.variableRegistry();
                if (query.mainAlias().isPresent() && query.mainAlias().get().equals(leftAlias)) {
                    validateVariableInRegistry(leftQualified, mainRegistry, "main query (aliased as " + leftAlias + " for JOIN)");
                } else if ("$main".equals(leftAlias)) {
                    validateVariableInRegistry(leftQualified, mainRegistry, "main query ($main for JOIN)");
                } else {
                    throw new QueryParseException("Unknown alias '" + leftAlias + "' for left join column: " + leftQualified);
                }

                // Validate right column: must be qualified as subqueryAlias.var
                if (rightQualified == null || !rightQualified.contains(".")) {
                    throw new QueryParseException("Right join column must be qualified (alias.var): " + rightQualified);
                }
                String[] rightParts = rightQualified.split("\\.", 2);
                if (rightParts.length != 2) {
                    throw new QueryParseException("Invalid qualified variable format for right join column: " + rightQualified);
                }
                String rightAlias = rightParts[0];
                String rightVar = rightParts[1];
                if (!subqueryMap.containsKey(rightAlias)) {
                    throw new QueryParseException("Unknown alias '" + rightAlias + "' for right join column: " + rightQualified);
                }
                VariableRegistry subqueryRegistry = subqueryMap.get(rightAlias).subquery().variableRegistry();
                validateVariableInRegistry(rightQualified, subqueryRegistry, "subquery '" + rightAlias + "' (for JOIN)");

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
            } catch (QueryParseException e) {
                throw new RuntimeException(e);
            }
        });
    }
} 