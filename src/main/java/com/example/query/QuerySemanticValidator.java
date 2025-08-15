package com.example.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.binding.VariableRegistry;
import com.example.query.model.CountColumn;
import com.example.query.model.JoinCondition;
import com.example.query.model.JoinStep;
import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.SnippetColumn;
import com.example.query.model.StructuralColumn;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.VariableColumn;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Temporal;
import com.example.query.parser.QueryModelBuilder;

/**
 * Validates the semantic correctness of a query using the new VariableRegistry system.
 * This validator relies entirely on the VariableRegistry for validation rather than
 * keeping its own state of variable bindings.
 */
public class QuerySemanticValidator {
    private static final Logger logger = LoggerFactory.getLogger(QuerySemanticValidator.class);
    private static final int MAX_CONDITION_DEPTH = 10; // Max nesting depth for conditions
    private static final int MAX_JOIN_DEPTH = 5;       // Max number of joins
    private static final int MAX_SNIPPET_WINDOW_SIZE = 150; // Max characters for snippet window

    // Maximum proximity window for temporal joins
    private static final int MAX_TEMPORAL_PROXIMITY_WINDOW = 365;

    // Global date bounds mirroring Nash.java for validation
    private static final LocalDate GLOBAL_LOWER_BOUND = LocalDate.parse("1925-01-01");
    private static final LocalDate GLOBAL_UPPER_BOUND = LocalDate.parse("2025-12-31");

    // Define the set of valid NER entity types (uppercase)
    private static final Set<String> VALID_NER_TYPES = Set.of(
        "PERSON", "ORGANIZATION", "LOCATION", "DATE", "TIME",
        "DURATION", "MONEY", "NUMBER", "ORDINAL", "PERCENT", "SET"
    );

    // Helper method to validate individual terms for disallowed wildcard usage
    private void validateTerm(String term, String conditionType, String fieldName) throws QueryParseException {
        if (term.startsWith("*") && term.length() > 1) {
            throw new QueryParseException(String.format(
                "Invalid wildcard usage in %s condition for field '%s': Term '%s' starts with '*' but is not solely '*'. " +
                "Searches starting with a wildcard (e.g., \"*term\") are not supported, except for the standalone wildcard \"*\".",
                conditionType, fieldName, term
            ));
        }
    }

    /**
     * Validates a query for semantic correctness.
     *
     * @param query The query to validate
     * @param externalAliasContext The alias under which this query is known, if it's a subquery.
     * @throws QueryParseException if the query has semantic errors
     */
    public void validate(Query query, Optional<String> externalAliasContext) throws QueryParseException {

        VariableRegistry registry = query.variableRegistry();
        if (registry == null) {
            throw new QueryParseException("Query does not have a variable registry");
        }

        validateConditionConstraints(query.conditions());
        validateNerTypes(query.conditions());
        validateTemporalBounds(query.conditions());
        validateVariableDependencies(registry);
        validateSelectColumns(query, registry, externalAliasContext);

        query.limit().ifPresent(limit -> {
            try {
                validateLimit(limit);
            } catch (QueryParseException e) {
                throw new RuntimeException(e);
            }
        });

        validateSnippetWindowSizes(query);

        if (query.hasSubqueries()) {
            validateSubqueries(query);
            validateJoinConditions(query);
        }

        if (!query.groupByColumns().isEmpty()) {
            validateGroupByClause(query, externalAliasContext);
        }

        if (!query.orderBy().isEmpty()) {
            validateOrderByClause(query);
        }

        logger.debug("Semantic validation completed successfully");
    }

    private void validateNerTypes(List<Condition> conditions) throws QueryParseException {
        for (Condition condition : conditions) {
            if (condition instanceof Ner nerCondition) {
                String entityType = nerCondition.entityType();
                String comparisonType = entityType.toUpperCase();
                if (!VALID_NER_TYPES.contains(comparisonType)) {
                    String validTypesString = String.join(", ", VALID_NER_TYPES);
                    throw new QueryParseException(String.format(
                        "Invalid NER entity type '%s' used in condition %s. Valid types are: %s",
                        entityType, nerCondition.toString(), validTypesString
                    ));
                }
            }
        }
    }

    private void validateTemporalBounds(List<Condition> conditions) throws QueryParseException {
        for (Condition condition : conditions) {
            if (condition instanceof Temporal temporalCondition) {
                Optional<LocalDateTime> startDateOpt = temporalCondition.startDate();
                Optional<LocalDateTime> endDateOpt = temporalCondition.endDate();

                if (startDateOpt.isPresent()) {
                    LocalDate startDate = startDateOpt.get().toLocalDate();
                    if (startDate.isBefore(GLOBAL_LOWER_BOUND) || startDate.isAfter(GLOBAL_UPPER_BOUND)) {
                        throw new QueryParseException(String.format(
                            "Start date %s in temporal condition is outside the allowed global range (%s to %s).",
                            startDate, GLOBAL_LOWER_BOUND, GLOBAL_UPPER_BOUND
                        ));
                    }
                }

                if (endDateOpt.isPresent()) {
                    LocalDate endDate = endDateOpt.get().toLocalDate();
                    if (endDate.isBefore(GLOBAL_LOWER_BOUND) || endDate.isAfter(GLOBAL_UPPER_BOUND)) {
                        throw new QueryParseException(String.format(
                            "End date %s in temporal condition is outside the allowed global range (%s to %s).",
                            endDate, GLOBAL_LOWER_BOUND, GLOBAL_UPPER_BOUND
                        ));
                    }
                }

                if (startDateOpt.isPresent() && endDateOpt.isPresent()) {
                    LocalDate startDate = startDateOpt.get().toLocalDate();
                    LocalDate endDate = endDateOpt.get().toLocalDate();
                    if (startDate.isAfter(endDate)) {
                        throw new QueryParseException(String.format(
                            "Start date %s cannot be after end date %s in temporal condition.",
                            startDate, endDate
                        ));
                    }
                }
            }
            // Recursively validate for logical conditions (AND, OR)
            else if (condition instanceof com.example.query.model.condition.Logical logicalCondition) {
                // Iterate over all conditions within the logical condition
                for (Condition subCondition : logicalCondition.conditions()) {
                    validateTemporalBounds(List.of(subCondition));
                }
            }
            // Recursively validate for NOT conditions
            else if (condition instanceof com.example.query.model.condition.Not notCondition) {
                validateTemporalBounds(List.of(notCondition.condition()));
            }
        }
    }

    private void validateConditionConstraints(List<Condition> conditions) throws QueryParseException {
        for (Condition condition : conditions) {
            if (condition instanceof Contains containsCondition) {
                List<String> terms = containsCondition.terms();
                if (terms.size() > 3) {
                    throw new QueryParseException(String.format(
                        "CONTAINS condition supports at most 3 terms, but got %d terms: %s",
                        terms.size(), String.join(", ", terms)
                    ));
                }
                if (terms.isEmpty()) {
                    throw new QueryParseException("CONTAINS condition must have at least one term");
                }
                boolean hasStandaloneWildcard = terms.stream().anyMatch(t -> "*".equals(t));
                if (hasStandaloneWildcard && terms.size() > 1) {
                    throw new QueryParseException(String.format(
                        "Invalid wildcard usage in CONTAINS: If '*' is used as a term, it must be the only term. Found: %s", terms
                    ));
                }
                for (int i = 0; i < terms.size(); i++) {
                    validateTerm(terms.get(i), "CONTAINS", "term[" + i + "]");
                }
            } else if (condition instanceof com.example.query.model.condition.Dependency dependencyCondition) {
                String gov = dependencyCondition.governor();
                String rel = dependencyCondition.relation();
                String dep = dependencyCondition.dependent();
                boolean govIsWildcard = "*".equals(gov);
                boolean relIsWildcard = "*".equals(rel);
                boolean depIsWildcard = "*".equals(dep);

                // Rule: Only the dependent may be the wildcard "*".
                if (govIsWildcard || relIsWildcard) {
                    throw new QueryParseException("Invalid DEPENDS: only the dependent part may use the wildcard '*'.");
                }

                // Validate terms for illegal wildcard usage (e.g., prefix '*term').
                validateTerm(gov, "DEPENDS", "governor");
                validateTerm(rel, "DEPENDS", "relation");
                if (!depIsWildcard) {
                    validateTerm(dep, "DEPENDS", "dependent");
                }
            }
        }
    }

    private void validateVariableDependencies(VariableRegistry registry) throws QueryParseException {
        Set<String> registryErrors = registry.validate();
        if (!registryErrors.isEmpty()) {
            throw new QueryParseException("Variable validation errors: " + String.join(", ", registryErrors));
        }
    }

    private void validateSelectColumns(Query query, VariableRegistry currentQueryRegistryContext, Optional<String> externalAliasContext) throws QueryParseException {
        if (query.selectColumns().isEmpty()) {
            throw new QueryParseException("Query must select at least one column");
        }

        Map<String, Query> subqueryAliasToQueryMap = query.joinSteps().stream()
                .collect(Collectors.toMap(JoinStep::rightSourceAlias, JoinStep::subquery));

        String currentQueryEffectiveAlias = externalAliasContext.orElseGet(() -> query.mainAlias().orElse(QueryModelBuilder.DEFAULT_MAIN_ALIAS));

        logger.debug("Subquery aliases for SELECT validation (current scope '{}'): {}", currentQueryEffectiveAlias, subqueryAliasToQueryMap.keySet());

        for (SelectColumn column : query.selectColumns()) {
            String fullColumnName;
            String contextType;

            if (column instanceof VariableColumn variableColumn) {
                fullColumnName = variableColumn.getColumnName();
                contextType = "SELECT (Variable)";
            } else if (column instanceof SnippetColumn snippetColumn) {
                fullColumnName = snippetColumn.getVariableName();
                contextType = "SNIPPET";
            } else if (column instanceof StructuralColumn || column instanceof CountColumn) {
                continue;
            } else {
                logger.warn("Skipping validation for unknown SelectColumn type: {}", column.getClass().getName());
                 continue;
            }

            logger.trace("Validating {} column: {}", contextType, fullColumnName);

            String aliasPart;
            String variablePart;
            VariableRegistry registryToValidateAgainst;
            String validationContextDescription;
            String registryKeyToLookup = fullColumnName;

            if (fullColumnName.contains(".")) {
                String[] parts = fullColumnName.split("\\.", 2);
                if (parts.length != 2) {
                    throw new QueryParseException(String.format("Invalid qualified format in %s: %s", contextType, fullColumnName));
                }
                aliasPart = parts[0];
                variablePart = parts[1];

                if (query.joinSteps().isEmpty()) {
                    if (!aliasPart.equals(currentQueryEffectiveAlias)) {
                         throw new QueryParseException(String.format("Alias mismatch for %s '%s'. Alias '%s' vs current scope '%s'.", contextType, fullColumnName, aliasPart, currentQueryEffectiveAlias));
                    }
                    registryToValidateAgainst = currentQueryRegistryContext;
                    validationContextDescription = String.format("current query (alias %s) for %s", currentQueryEffectiveAlias, contextType);
                } else {
                    if (aliasPart.equals(currentQueryEffectiveAlias)) {
                        registryToValidateAgainst = currentQueryRegistryContext;
                        validationContextDescription = String.format("main query (alias %s) for %s", currentQueryEffectiveAlias, contextType);
                    } else if (subqueryAliasToQueryMap.containsKey(aliasPart)) {
                        registryToValidateAgainst = subqueryAliasToQueryMap.get(aliasPart).variableRegistry();
                        validationContextDescription = String.format("joined subquery '%s' for %s", aliasPart, contextType);
                    } else {
                        throw new QueryParseException(String.format("Unknown alias '%s' in %s: %s. Main: '%s', Subqueries: %s", aliasPart, contextType, fullColumnName, currentQueryEffectiveAlias, subqueryAliasToQueryMap.keySet()));
                }
                }
                 registryKeyToLookup = fullColumnName; // Qualified name is the direct key
            } else { // Unqualified column name
                if (query.isQualificationRequired()) {
                    // This applies to VariableColumn and SnippetColumn's variable.
                    // StructuralColumn or CountColumn would have been 'continue'd earlier.
                    String exampleSubqueryAlias = query.joinSteps().isEmpty() ? "some_sub_alias_for_example" : query.joinSteps().get(0).rightSourceAlias();
                    throw new QueryParseException(String.format(
                        "Unqualified column '%s' in %s is ambiguous. Qualification with an alias (e.g., '%s.%s' or '%s.%s') is required when joins are present or a main alias is specified.",
                        fullColumnName, contextType, currentQueryEffectiveAlias, fullColumnName, exampleSubqueryAlias, fullColumnName
                    ));
                }
                // If qualification is NOT required (original logic for this block was similar):
                aliasPart = currentQueryEffectiveAlias; // Default to current context's alias
                variablePart = fullColumnName;
                registryToValidateAgainst = currentQueryRegistryContext;
                // Construct the fully qualified key for registry lookup, as unqualified names are stored
                // under the context's effective alias in the registry.
                registryKeyToLookup = aliasPart + "." + variablePart;
                validationContextDescription = String.format("current query (alias %s, unqualified var '%s') for %s", aliasPart, variablePart, contextType);
    }

            // At this point, registryKeyToLookup is the fully qualified name for registry interaction.
            // For VariableColumn and SnippetColumn, we proceed to validate against the registry.
            // StructuralColumn and CountColumn types would have been skipped by the 'continue' statement.
            validateVariableInRegistry(registryKeyToLookup, registryToValidateAgainst, validationContextDescription);
        }
    }

    private void validateVariableInRegistry(String internalQualifiedName, VariableRegistry registry, String contextDescription) throws QueryParseException {
        if (!registry.isProduced(internalQualifiedName)) {
            if (!registry.getAllVariableNames().contains(internalQualifiedName)) {
                 throw new QueryParseException(String.format("Variable '%s' not found in scope (%s). Available: %s", internalQualifiedName, contextDescription, registry.getAllVariableNames()));
            } else {
                 throw new QueryParseException(String.format("Variable '%s' consumed in %s but never produced.", internalQualifiedName, contextDescription));
            }
        }
        logger.debug("Variable '{}' validated successfully in context: {}", internalQualifiedName, contextDescription);
    }

    private void validateSnippetWindowSizes(Query query) throws QueryParseException {
        for (SelectColumn column : query.selectColumns()) {
            if (column instanceof SnippetColumn snippetColumn) {
                int windowSize = snippetColumn.getWindowSize();
                if (windowSize > MAX_SNIPPET_WINDOW_SIZE) {
                    throw new QueryParseException(String.format(
                        "Snippet window size %d characters exceeds maximum allowed size of %d characters",
                        windowSize, MAX_SNIPPET_WINDOW_SIZE
                    ));
                }
            }
        }
    }

    private void validateLimit(int limit) throws QueryParseException {
        if (limit <= 0) {
            throw new QueryParseException("LIMIT value must be greater than 0");
        }
    }

    private void validateSubqueries(Query query) throws QueryParseException {
        if (query.joinSteps().size() > MAX_JOIN_DEPTH) {
            throw new QueryParseException(String.format("Exceeded max joins: %d", MAX_JOIN_DEPTH));
            }
        for (JoinStep step : query.joinSteps()) {
            validate(step.subquery(), Optional.of(step.rightSourceAlias()));
            if (step.rightSourceAlias().isEmpty()) {
                throw new QueryParseException("Subquery alias in JoinStep cannot be empty: " + step);
        }
    }
    }

    private void validateJoinConditions(Query query) throws QueryParseException {
        if (query.joinSteps().isEmpty()) return;

        Map<String, VariableRegistry> aliasToRegistryMap = query.joinSteps().stream()
            .collect(Collectors.toMap(JoinStep::rightSourceAlias, step -> step.subquery().variableRegistry()));
        String mainQueryEffectiveAlias = query.mainAlias().orElse(QueryModelBuilder.DEFAULT_MAIN_ALIAS);
        aliasToRegistryMap.put(mainQueryEffectiveAlias, query.variableRegistry());

        Set<String> allKnownAliasesForStructuralCheck = new HashSet<>(aliasToRegistryMap.keySet());

        for (JoinStep step : query.joinSteps()) {
            JoinCondition condition = step.onCondition();
            validateSingleJoinColumn(condition.leftColumn(), step.leftSourceAlias(), aliasToRegistryMap, "left", query.mainAlias(), query.joinSteps(), allKnownAliasesForStructuralCheck, step);
            validateSingleJoinColumn(condition.rightColumn(), step.rightSourceAlias(), aliasToRegistryMap, "right", query.mainAlias(), query.joinSteps(), allKnownAliasesForStructuralCheck, step);
            if (condition.operatorType() == JoinCondition.JoinOperatorType.TEMPORAL && condition.temporalPredicate().orElse(null) == TemporalPredicate.PROXIMITY) {
                condition.proximityWindow().ifPresent(window -> {
                    if (window <= 0) throw new RuntimeException(new QueryParseException("Proximity window > 0"));
                    if (window > MAX_TEMPORAL_PROXIMITY_WINDOW) throw new RuntimeException(new QueryParseException(String.format("Proximity window %d > max %d", window, MAX_TEMPORAL_PROXIMITY_WINDOW)));
                });
            }
        }
    }

    private void validateSingleJoinColumn(String qualifiedColumnName, String expectedAliasContext, Map<String, VariableRegistry> aliasToRegistryMap, String side, Optional<String> mainQueryAliasForStructuralCheck, List<JoinStep> allJoinStepsForStructuralCheck, Set<String> allKnownAliasesForStructuralCheck, JoinStep currentStep) throws QueryParseException {
        if (qualifiedColumnName == null || !qualifiedColumnName.contains(".")) {
            throw new QueryParseException(String.format("%s join column '%s' must be qualified (alias.var). Current step: %s", side, qualifiedColumnName, currentStep));
                }
        String[] parts = qualifiedColumnName.split("\\.", 2);
        if (parts.length != 2) { throw new QueryParseException(String.format("Invalid qualified format for %s join column: %s", side, qualifiedColumnName)); }
        String aliasPart = parts[0];

        if (!aliasPart.equals(expectedAliasContext)) {
            throw new QueryParseException(String.format("Alias mismatch for %s join column '%s'. Column alias '%s' != expected context '%s'. Step: %s", side, qualifiedColumnName, aliasPart, expectedAliasContext, currentStep));
        }
        VariableRegistry targetRegistry = aliasToRegistryMap.get(aliasPart);
        if (targetRegistry == null) { throw new QueryParseException(String.format("No registry for alias '%s' (%s join column '%s'). Step: %s", aliasPart, side, qualifiedColumnName, currentStep)); }

        boolean isStructural = isStructuralColumn(qualifiedColumnName, mainQueryAliasForStructuralCheck, allJoinStepsForStructuralCheck, allKnownAliasesForStructuralCheck);
        boolean isProducedInTarget = targetRegistry.isProduced(qualifiedColumnName);

        if (!isStructural && !isProducedInTarget) {
             if (!targetRegistry.getAllVariableNames().contains(qualifiedColumnName)) {
                 throw new QueryParseException(String.format("%s join column '%s' not in scope (alias '%s'). Available: %s. Step: %s", side, qualifiedColumnName, aliasPart, targetRegistry.getAllVariableNames(), currentStep));
                } else {
                 throw new QueryParseException(String.format("%s join column '%s' (alias '%s') consumed but not produced. Step: %s", side, qualifiedColumnName, aliasPart, currentStep));
            }
        }
        logger.debug("Successfully validated {} join column: {} for step: {}", side, qualifiedColumnName, currentStep);
            }

    private void validateGroupByClause(Query query, Optional<String> externalAliasContext) throws QueryParseException {
        logger.debug("Validating GROUP BY clause: {} within external context: {}", query.groupByColumns(), externalAliasContext);
        String currentQueryEffectiveAlias = externalAliasContext.orElseGet(() -> query.mainAlias().orElse(QueryModelBuilder.DEFAULT_MAIN_ALIAS));

        VariableRegistry currentQueryRegistry = query.variableRegistry(); // Registry of the query (main or sub) being validated
        Map<String, VariableRegistry> subqueryAliasToRegistryMap = query.joinSteps().stream()
            .collect(Collectors.toMap(JoinStep::rightSourceAlias, step -> step.subquery().variableRegistry()));

        List<String> availableVarsForErrorMessage;

        for (String groupByColumnOriginalName : query.groupByColumns()) {
            String columnToCheckInRegistry;
            // String aliasOfColumnInGroupBy = null; // Not strictly needed with this logic structure
            VariableRegistry registryToUse;
            String validationContextDescription;

            if (groupByColumnOriginalName.contains(".")) { // Qualified item
                String[] parts = groupByColumnOriginalName.split("\\.", 2);
                if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                     throw new QueryParseException(String.format("Invalid qualified format in GROUP BY item '%s'. Expected alias.fieldname.", groupByColumnOriginalName));
                }
                String aliasFromItem = parts[0];
                columnToCheckInRegistry = groupByColumnOriginalName; // Use the full qualified name for registry lookup

                if (aliasFromItem.equals(currentQueryEffectiveAlias)) {
                    registryToUse = currentQueryRegistry;
                    validationContextDescription = String.format("current query (alias '%s')", currentQueryEffectiveAlias);
                } else if (subqueryAliasToRegistryMap.containsKey(aliasFromItem)) {
                    registryToUse = subqueryAliasToRegistryMap.get(aliasFromItem);
                    validationContextDescription = String.format("subquery '%s'", aliasFromItem);
                } else {
                    // The alias is not the current query's effective alias, nor is it a known joined subquery's alias.
                    throw new QueryParseException(String.format(
                        "Unknown alias '%s' in GROUP BY item '%s'. Current scope (effective alias): '%s'. Known subquery aliases: %s.",
                        aliasFromItem, groupByColumnOriginalName, currentQueryEffectiveAlias, subqueryAliasToRegistryMap.keySet()
                    ));
                }
            } else { // Unqualified item
                // Unqualified names in GROUP BY must belong to the current query's effective alias.
                // The variable in the registry is stored qualified.
                columnToCheckInRegistry = currentQueryEffectiveAlias + "." + groupByColumnOriginalName;
                registryToUse = currentQueryRegistry;
                validationContextDescription = String.format("current query (alias '%s', unqualified item '%s')", currentQueryEffectiveAlias, groupByColumnOriginalName);
            }

            // Validate that the determined variable is produced in the selected registry
            if (!registryToUse.isProduced(columnToCheckInRegistry)) {
                availableVarsForErrorMessage = new ArrayList<>(registryToUse.getAllVariableNames());
                Collections.sort(availableVarsForErrorMessage); // For consistent error messages
                throw new QueryParseException(String.format(
                    "Variable '%s' for GROUP BY ('%s') not found or not produced in scope (%s). Available variables in this scope: %s",
                    columnToCheckInRegistry,      // The fully qualified name we attempted to find (e.g., q1.p or $main.p)
                    groupByColumnOriginalName,    // The original name as it appeared in the GROUP BY clause (e.g. p or q1.p)
                    validationContextDescription, // e.g. "current query (alias 'q1')" or "subquery 'q2'"
                    availableVarsForErrorMessage   // All variables available in the target registry
                ));
            }
        }
        // Note: The logic for checking SELECT column compatibility with GROUP BY (aggregates vs. grouped columns)
        // has been removed from this method to keep it focused on validating GROUP BY items themselves.
        // That is a separate validation concern (aggregate consistency).
        logger.debug("GROUP BY clause for effective alias '{}' validated successfully.", currentQueryEffectiveAlias);
    }

    private void validateOrderByClause(Query query) throws QueryParseException {
        VariableRegistry currentQueryRegistry = query.variableRegistry();
        Set<String> groupByKeySet = new HashSet<>(query.groupByColumns());
        String currentQueryEffectiveAlias = query.mainAlias().orElse(QueryModelBuilder.DEFAULT_MAIN_ALIAS);
        Map<String, Query> subqueryAliasToQueryMap = query.joinSteps().stream()
            .collect(Collectors.toMap(JoinStep::rightSourceAlias, JoinStep::subquery));
        Set<String> allKnownAliasesInScope = new HashSet<>(subqueryAliasToQueryMap.keySet());
        allKnownAliasesInScope.add(currentQueryEffectiveAlias);

        for (String orderSpecifier : query.orderBy()) {
            String rawColumnName = orderSpecifier.startsWith("-") ? orderSpecifier.substring(1) : orderSpecifier;

            // Check if this is a COUNT expression (original format like "COUNT(*)" or "COUNT(q2.president)")
            if (rawColumnName.startsWith("COUNT(")) {
                // Validate that this COUNT expression exists in the SELECT clause
                boolean foundMatchingCountColumn = false;
                for (SelectColumn selectColumn : query.selectColumns()) {
                    if (selectColumn instanceof CountColumn countColumn) {
                        // Check if this CountColumn matches the ORDER BY expression
                        String countColumnRepresentation = countColumn.toString();
                        if (rawColumnName.equals(countColumnRepresentation)) {
                            foundMatchingCountColumn = true;
                            break;
                        }
                    }
                }

                if (!foundMatchingCountColumn) {
                    throw new QueryParseException(String.format(
                        "ORDER BY COUNT expression '%s' does not match any COUNT column in the SELECT clause.", rawColumnName
                    ));
                }

                boolean hasNonAggregateInSelect = query.selectColumns().stream()
                    .anyMatch(sc -> !(sc instanceof CountColumn));

                if (hasNonAggregateInSelect && query.groupByColumns().isEmpty()) {
                    throw new QueryParseException(String.format(
                        "Cannot ORDER BY aggregate function '%s' when non-aggregate columns are present in the SELECT list and no GROUP BY clause is specified.", rawColumnName
                    ));
                }
                continue;
            }

            // Check if this is a generated COUNT column name (like "count_unique_q2_president")
            boolean isCountColumnName = false;
            for (SelectColumn selectColumn : query.selectColumns()) {
                if (selectColumn instanceof CountColumn countColumn) {
                    if (rawColumnName.equals(countColumn.getColumnName())) {
                        isCountColumnName = true;

                        boolean hasNonAggregateInSelect = query.selectColumns().stream()
                            .anyMatch(sc -> !(sc instanceof CountColumn));

                        if (hasNonAggregateInSelect && query.groupByColumns().isEmpty()) {
                            throw new QueryParseException(String.format(
                                "Cannot ORDER BY aggregate function '%s' when non-aggregate columns are present in the SELECT list and no GROUP BY clause is specified.", rawColumnName
                            ));
                        }
                        break;
                    }
                }
            }

            if (isCountColumnName) {
                continue; // Skip further validation for count column names
            }

            boolean isStructCol = isStructuralColumn(rawColumnName, query.mainAlias(), query.joinSteps(), allKnownAliasesInScope);
            VariableRegistry registryForVarCheck;
            String aliasFromColumn = rawColumnName.contains(".") ? rawColumnName.split("\\.", 2)[0] : currentQueryEffectiveAlias;

            if (query.joinSteps().isEmpty() || aliasFromColumn.equals(currentQueryEffectiveAlias)) {
                 registryForVarCheck = currentQueryRegistry;
            } else if (subqueryAliasToQueryMap.containsKey(aliasFromColumn)) {
                 registryForVarCheck = subqueryAliasToQueryMap.get(aliasFromColumn).variableRegistry();
            } else {
                 if (!isStructCol) { throw new QueryParseException(String.format("ORDER BY '%s' uses unknown alias '%s'.", rawColumnName, aliasFromColumn)); }
                 registryForVarCheck = null;
            }
            boolean isKnownVar = registryForVarCheck != null && registryForVarCheck.isProduced(rawColumnName);
            if (!isStructCol && !isKnownVar) { throw new QueryParseException(String.format("ORDER BY '%s' not recognized in scope.", rawColumnName)); }
            if (!query.groupByColumns().isEmpty() && !groupByKeySet.contains(rawColumnName)) { throw new QueryParseException(String.format("ORDER BY '%s' must be in GROUP BY if present.", rawColumnName)); }
            }
    }

    private boolean isStructuralColumn(String qualifiedName, Optional<String> mainAliasOpt, List<JoinStep> joinSteps, Set<String> allKnownAliases) {
        if (qualifiedName == null) return false;
        String[] parts = qualifiedName.split("\\.", 2);
        if (parts.length != 2) return false;
        String aliasFromColumn = parts[0];
        String field = parts[1].toUpperCase();
        Set<String> knownStructuralFields = Set.of("TITLE", "TIMESTAMP", "DOCUMENT_ID", "SENTENCE_ID", "BEGIN", "END");
        if (!knownStructuralFields.contains(field)) return false;
        if (!allKnownAliases.contains(aliasFromColumn)) {
            logger.warn("Structural column '{}' has alias '{}' not in known aliases: {}", qualifiedName, aliasFromColumn, allKnownAliases);
            return false;
        }
                return true;
    }
}