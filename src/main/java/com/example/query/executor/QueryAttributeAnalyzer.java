package com.example.query.executor;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.model.Query;
import com.example.query.model.SelectedColumn;
import com.example.query.model.SelectedSnippet;
import com.example.query.model.SelectedStructural;
import com.example.query.model.StructuralField;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.Temporal;

/**
 * Analyzes queries to determine which attributes are required for execution.
 * In the new Roaring64-backed format, the primary decision is whether
 * occurrence-level detail (char offsets) is needed.
 */
public class QueryAttributeAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(QueryAttributeAnalyzer.class);

    /**
     * Analyzes a query to determine which attributes are required.
     *
     * @param query The query to analyze
     * @return AttributeRequirements specifying which attributes are needed
     */
    public static AttributeRequirements analyze(Query query) {
        return analyze(query, null);
    }

    /**
     * Analyzes a query to determine which attributes are required, considering
     * parent requirements.
     *
     * @param query              The query to analyze
     * @param parentRequirements Requirements from the parent query (may be null for
     *                           root queries)
     * @return AttributeRequirements specifying which attributes are needed
     */
    public static AttributeRequirements analyze(Query query, AttributeRequirements parentRequirements) {
        AttributeRequirements requirements = new AttributeRequirements();

        logger.trace("Analyzing query for attribute requirements: {}", query.toString());

        // Analyze SELECT clause
        analyzeSelectColumns(query.selectColumns(), requirements);

        // Analyze granularity requirements
        analyzeGranularity(query.granularity(), requirements);

        // Analyze conditions to determine attribute requirements
        analyzeConditions(query.conditions(), requirements);

        // Analyze subqueries recursively from JoinSteps, propagating parent
        // requirements
        for (com.example.query.model.JoinStep step : query.joinSteps()) {
            // Create combined requirements for subquery analysis
            AttributeRequirements subqueryParentRequirements = new AttributeRequirements();
            subqueryParentRequirements.merge(requirements); // Current query requirements
            if (parentRequirements != null) {
                subqueryParentRequirements.merge(parentRequirements); // Parent query requirements
            }

            AttributeRequirements subqueryRequirements = analyze(step.subquery(), subqueryParentRequirements);
            requirements.merge(subqueryRequirements);
        }

        // Inherit critical requirements from parent if present
        if (parentRequirements != null) {
            // If parent needs sentence IDs (e.g., for GRANULARITY SENTENCE), subqueries
            // must provide them
            if (parentRequirements.needsSentenceId) {
                logger.trace("Inheriting sentence ID requirement from parent query");
                requirements.needsSentenceId = true;
            }
            // Propagate occurrence requirement from parent
            if (parentRequirements.needsOccurrences()) {
                logger.trace("Inheriting occurrence requirement from parent query");
                requirements.setNeedsOccurrences(true);
            }
        }

        logger.trace("Query analysis complete. Requirements: {}", requirements);
        return requirements;
    }

    /**
     * Analyzes SELECT columns to determine attribute requirements.
     */
    private static void analyzeSelectColumns(List<SelectedColumn> selectColumns, AttributeRequirements requirements) {
        for (SelectedColumn column : selectColumns) {
            if (column instanceof SelectedSnippet) {
                logger.trace("Found SNIPPET column, requiring occurrences and positions");
                requirements.setNeedsOccurrences(true);
                requirements.needsPositions = true;
            } else if (column instanceof SelectedStructural ss) {
                StructuralField field = ss.field();
                if (field == StructuralField.SENTENCE_ID) {
                    logger.trace("Found SENTENCE_ID column, requiring sentence IDs");
                    requirements.needsSentenceId = true;
                } else if (field == StructuralField.BEGIN || field == StructuralField.END) {
                    logger.trace("Found position column ({}), requiring occurrences and positions", field);
                    requirements.setNeedsOccurrences(true);
                    requirements.needsPositions = true;
                }
            }
            // Note: Variable requirements depend on the conditions that produce them
            // This is handled in analyzeConditions()
        }
    }

    /**
     * Analyzes granularity settings to determine attribute requirements.
     */
    private static void analyzeGranularity(Query.Granularity granularity, AttributeRequirements requirements) {
        if (granularity == Query.Granularity.SENTENCE) {
            logger.trace("Sentence granularity detected, requiring sentence IDs");
            requirements.needsSentenceId = true;
        }
    }

    /**
     * Analyzes conditions to determine attribute requirements.
     */
    private static void analyzeConditions(List<Condition> conditions, AttributeRequirements requirements) {
        for (Condition condition : conditions) {
            analyzeCondition(condition, requirements);
        }
    }

    /**
     * Analyzes a single condition to determine attribute requirements.
     */
    private static void analyzeCondition(Condition condition, AttributeRequirements requirements) {
        if (condition instanceof Ner ner && (ner.isVariable() || ner.target() != null)) {
            logger.debug("[QueryAttributeAnalyzer.analyzeCondition] Analyzing NER condition: {}", ner);
            boolean isNerVariableOrHasTarget = ner.isVariable() || ner.target() != null;
            logger.debug(
                    "[QueryAttributeAnalyzer.analyzeCondition] NER check: ner.isVariable() -> {}, ner.target() -> '{}', (ner.isVariable() || ner.target() != null) -> {}",
                    ner.isVariable(), ner.target(), isNerVariableOrHasTarget);
            if (isNerVariableOrHasTarget) {
                logger.trace("Found NER condition with variable or target, requiring occurrences");
                requirements.setNeedsOccurrences(true);
                requirements.needsSynonymIds = true;
                requirements.needsConceptualRowIds = true;
            } else {
                logger.debug(
                        "[QueryAttributeAnalyzer.analyzeCondition] NER condition '{}' does NOT meet criteria for needing occurrences (isVariable: {}, target: '{}')",
                        ner, ner.isVariable(), ner.target());
            }
            logger.debug("[QueryAttributeAnalyzer.analyzeCondition] After NER check, requirements.needsOccurrences: {}",
                    requirements.needsOccurrences());
        } else if (condition instanceof Pos pos && (pos.isVariable() || pos.term() != null)) {
            logger.trace("Found POS condition with variable or target, requiring occurrences");
            requirements.setNeedsOccurrences(true);
            requirements.needsConceptualRowIds = true;
        } else if (condition instanceof Temporal) {
            logger.trace("Found Temporal condition, requiring occurrences");
            requirements.setNeedsOccurrences(true);
        } else if (condition instanceof Logical logical) {
            // Recursively analyze logical conditions
            analyzeConditions(logical.conditions(), requirements);
        }
    }
}
