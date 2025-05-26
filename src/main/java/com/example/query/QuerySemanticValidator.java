package com.example.query;

import com.example.query.model.*;
import com.example.query.binding.VariableRegistry;
import com.example.query.binding.Variable;
import com.example.query.binding.VariableType;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
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
        
        // Validate condition structures and constraints
        validateConditionConstraints(query.conditions());
        
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

        // Validate GROUP BY clause if present
        if (!query.groupByColumns().isEmpty()) {
            validateGroupByClause(query);
        }
        
        // Validate ORDER BY clause if present
        if (!query.orderBy().isEmpty()) {
            validateOrderByClause(query);
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
     * Validates structural constraints on conditions.
     * 
     * @param conditions The list of conditions to validate.
     * @throws QueryParseException If a condition violates structural constraints.
     */
    private void validateConditionConstraints(List<Condition> conditions) throws QueryParseException {
        for (Condition condition : conditions) {
            if (condition instanceof Contains containsCondition) {
                // CONTAINS condition can have at most 3 terms (unigram, bigram, trigram)
                List<String> terms = containsCondition.terms();
                if (terms.size() > 3) {
                    throw new QueryParseException(String.format(
                        "CONTAINS condition supports at most 3 terms, but got %d terms: %s",
                        terms.size(), String.join(", ", terms)
                    ));
                }
                
                // Empty terms are also invalid
                if (terms.isEmpty()) {
                    throw new QueryParseException("CONTAINS condition must have at least one term");
                }
            }
            
            // Add other condition constraint validations here as needed
            // For example: POS conditions, temporal conditions, etc.
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

            // Explicitly handle different column types
            if (column instanceof VariableColumn variableColumn) { 
                fullColumnName = variableColumn.getColumnName(); // e.g., q1.date or $main.date
                context = "SELECT (Variable)";
                 logger.trace("Validating VariableColumn: {}", fullColumnName);
                 // Proceed to validation logic below...
            } else if (column instanceof SnippetColumn snippetColumn) { 
                fullColumnName = snippetColumn.getVariableName(); // e.g., q1.person or $main.person
                context = "SNIPPET";
                logger.trace("Validating SnippetColumn for variable: {}", fullColumnName);
                 // Proceed to validation logic below...
            } else if (column instanceof StructuralColumn structuralColumn) {
                // Structural columns (e.g., q1.TITLE) don't rely on VariableRegistry bindings
                 logger.trace("Skipping VariableRegistry validation for StructuralColumn: {}", structuralColumn.getColumnName());
                continue; // Skip VariableRegistry validation for this column
            } else if (column instanceof CountColumn) {
                 // Count columns might need specific validation later, but not VariableRegistry lookup
                 logger.trace("Skipping VariableRegistry validation for CountColumn: {}", column.getColumnName());
                continue; // Skip non-variable columns (COUNT, TITLE, etc.)
            } else {
                // Unknown column type - log a warning or throw an error?
                 logger.warn("Skipping VariableRegistry validation for unknown SelectColumn type: {}", column.getClass().getName());
                 continue;
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
                if (joinCondition.operatorType() == JoinCondition.JoinOperatorType.TEMPORAL &&
                    joinCondition.temporalPredicate().isPresent() &&
                    joinCondition.temporalPredicate().get() == TemporalPredicate.PROXIMITY) {
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

    // New: Method to validate the GROUP BY clause
    private void validateGroupByClause(Query query) throws QueryParseException {
        logger.debug("Validating GROUP BY clause: {}", query.groupByColumns());
        VariableRegistry registry = query.variableRegistry();
        List<String> groupByColumns = query.groupByColumns();
        Set<String> groupBySet = new HashSet<>(groupByColumns); // For efficient lookup

        // 2. Validate that each GROUP BY item is a valid, selectable column (MOVED TO TOP)
        // This means it must be a known variable or a known structural column.
        String mainAlias = query.mainAlias().orElse("$main");
        Map<String, SubquerySpec> subqueryMap = query.subqueries().stream()
                .collect(Collectors.toMap(SubquerySpec::alias, sq -> sq));

        for (String groupByColumnName : groupByColumns) {
            // Attempt to parse as alias.field
            String[] parts = groupByColumnName.split("\\.", 2);
            if (parts.length != 2) {
                throw new QueryParseException(String.format(
                    "Invalid GROUP BY item '%s'. Expected format: alias.variable or alias.FIELD", groupByColumnName));
            }
            String alias = parts[0];
            String field = parts[1];

            boolean isStructural = Set.of("TITLE", "TIMESTAMP", "DOCUMENT_ID", "SENTENCE_ID", "BEGIN", "END").contains(field.toUpperCase());

            if (isStructural) {
                // For structural columns, check if alias is valid (main or a subquery)
                if (!alias.equals(mainAlias) && !subqueryMap.containsKey(alias)) {
                    throw new QueryParseException(String.format(
                        "Invalid alias '%s' in GROUP BY item '%s'. Alias must be the main query alias or a subquery alias.",
                        alias, groupByColumnName));
                }
                // Further validation for structural fields (e.g. field name is valid) is implicitly handled by parser/model
            } else {
                // For variables, first determine the target registry
                VariableRegistry targetRegistry;
                String contextDesc;
                if (alias.equals(mainAlias)) {
                    targetRegistry = registry; // Main query registry
                    contextDesc = String.format("main query (alias %s) for GROUP BY item '%s'", alias, groupByColumnName);
                } else if (subqueryMap.containsKey(alias)) {
                    targetRegistry = subqueryMap.get(alias).subquery().variableRegistry();
                    contextDesc = String.format("subquery '%s' for GROUP BY item '%s'", alias, groupByColumnName);
                } else {
                    throw new QueryParseException(String.format(
                        "Unknown alias '%s' in GROUP BY item '%s'.", alias, groupByColumnName));
                }

                // Now, check if the variable is known in that registry before validating if it's produced
                if (!targetRegistry.getAllVariableNames().contains(groupByColumnName)) {
                    throw new QueryParseException(String.format(
                        "GROUP BY column '%s' is not a known variable or structural column.", groupByColumnName
                    ));
                }
                // If known, then validate it's produced (as it's not structural)
                validateVariableInRegistry(groupByColumnName, targetRegistry, contextDesc);
            }
        }
        // If all GROUP BY items are valid, now proceed to validate SELECT columns.

        // 1. Validate SELECT columns against the GROUP BY clause (NOW SECOND)
        for (SelectColumn selectColumn : query.selectColumns()) {
            if (selectColumn instanceof CountColumn) {
                // Aggregate functions are always allowed with GROUP BY
                continue;
            } else if (selectColumn instanceof SnippetColumn snippetColumn) {
                String snippetVarName = snippetColumn.getVariableName(); // This is qualified, e.g., $main.date
                if (!groupBySet.contains(snippetVarName)) {
                    throw new QueryParseException(String.format(
                        "SNIPPET variable '%s' must be included in the GROUP BY clause when GROUP BY is present.",
                        snippetVarName));
                }
            } else if (selectColumn instanceof VariableColumn variableColumn) {
                String varName = variableColumn.getColumnName(); // This is qualified, e.g., $main.date
                if (!groupBySet.contains(varName)) {
                    throw new QueryParseException(String.format(
                        "SELECT column '%s' must be an aggregate function or appear in the GROUP BY clause.",
                        varName));
                }
            } else if (selectColumn instanceof StructuralColumn structuralColumn) {
                String structName = structuralColumn.getColumnName(); // This is qualified, e.g., $main.TITLE
                if (!groupBySet.contains(structName)) {
                    throw new QueryParseException(String.format(
                        "SELECT column '%s' must be an aggregate function or appear in the GROUP BY clause.",
                        structName));
                }
            } else {
                // Should not happen if all SelectColumn types are handled
                throw new QueryParseException("Unknown SelectColumn type encountered during GROUP BY validation: " + selectColumn.getClass().getName());
            }
        }
        logger.debug("GROUP BY clause validated successfully.");
    }

    // New method: validateOrderByClause
    private void validateOrderByClause(Query query) throws QueryParseException {
        VariableRegistry registry = query.variableRegistry();
        List<String> groupByColumnNames = query.groupByColumns(); // These are already fully qualified
        Set<String> groupByKeySet = new HashSet<>(groupByColumnNames);

        for (String orderSpecifier : query.orderBy()) {
            String rawColumnName = orderSpecifier.startsWith("-") ? orderSpecifier.substring(1) : orderSpecifier;
            
            // crude check for aggregate, expand if more aggregates are supported.
            boolean isAggregate = rawColumnName.startsWith("COUNT("); 

            if (isAggregate) {
                if (groupByColumnNames.isEmpty()) {
                    // If ordering by aggregate without GROUP BY, all SELECT items should also be aggregates.
                    // This is a common SQL rule to prevent ambiguity.
                    boolean allSelectAlsoAggregates = query.selectColumns().stream()
                        .allMatch(sc -> sc instanceof CountColumn /* || other aggregate types like SumColumn, AvgColumn */);
                    
                    // More precise: if there's any non-aggregate in SELECT, it's an error.
                    boolean hasNonAggregateInSelect = query.selectColumns().stream()
                        .anyMatch(sc -> !(sc instanceof CountColumn /* || other aggregate types */));

                    if (hasNonAggregateInSelect) {
                        throw new QueryParseException(String.format(
                            "Cannot ORDER BY aggregate function '%s' without a GROUP BY clause when non-aggregate columns are present in the SELECT list.", rawColumnName
                        ));
                    }
                }
                // If GROUP BY is present, or if no GROUP BY but SELECT list is all aggregates, then it's fine.
                continue; 
            }

            // If not an aggregate, it's a column/variable name.
            // The name `rawColumnName` from `orderColumns` list should be fully qualified by QueryModelBuilder.
            
            boolean isKnownVar = registry.isProduced(rawColumnName);
            boolean isStructCol = isStructuralColumn(rawColumnName, query.mainAlias(), query.subqueries());

            if (!isKnownVar && !isStructCol) {
                // Before throwing, check if it matches a SELECT column alias (if we support that).
                // For now, assuming rawColumnName must be a direct resolvable item or aggregate.
                throw new QueryParseException(String.format(
                    "ORDER BY column '%s' is not a recognized variable, structural column, or aggregate function.", rawColumnName
                ));
            }

            // If GROUP BY is present, non-aggregate ORDER BY items must be in GROUP BY keys.
            if (!groupByColumnNames.isEmpty()) {
                if (!groupByKeySet.contains(rawColumnName)) {
                    throw new QueryParseException(String.format(
                        "ORDER BY column '%s' must be in the GROUP BY clause or be an aggregate function when GROUP BY is present.", rawColumnName
                    ));
                }
            }
            // If no GROUP BY, ordering by a regular valid column (var or struct) is fine.
        }
    }

    // New helper method: isStructuralColumn
    private boolean isStructuralColumn(String qualifiedName, Optional<String> mainAliasOpt, List<SubquerySpec> subqueries) {
        if (qualifiedName == null) return false;
        String[] parts = qualifiedName.split("\\\\.", 2); // Use \\. for literal dot in regex
        if (parts.length != 2) {
            return false; // Not in alias.FIELD format
        }
        String alias = parts[0];
        String field = parts[1].toUpperCase(); // Structural fields are typically case-insensitive or stored uppercase

        // Define known structural fields
        Set<String> knownStructuralFields = Set.of("TITLE", "TIMESTAMP", "DOCUMENT_ID", "SENTENCE_ID", "BEGIN", "END");
        if (!knownStructuralFields.contains(field)) {
            return false;
        }

        // Check if the alias matches the main query's alias or any subquery's alias
        String mainAlias = mainAliasOpt.orElse("$main");
        if (alias.equals(mainAlias)) {
            return true;
        }

        return subqueries.stream().anyMatch(sq -> sq.alias().equals(alias));
    }
} 