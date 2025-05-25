package com.example.query.executor;

import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.SnippetColumn;
import com.example.query.model.StructuralColumn;
import com.example.query.model.JoinCondition;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.Logical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Analyzes queries to determine which SoA attributes are required for execution.
 * This enables selective deserialization to optimize memory usage and performance.
 */
public class QueryAttributeAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(QueryAttributeAnalyzer.class);

    /**
     * Analyzes a query to determine which SoA attributes are required.
     * 
     * @param query The query to analyze
     * @return AttributeRequirements specifying which attributes are needed
     */
    public static AttributeRequirements analyze(Query query) {
        logger.debug("Analyzing query for attribute requirements: {}", query);
        
        AttributeRequirements requirements = new AttributeRequirements();

        // Analyze SELECT clause
        analyzeSelectColumns(query.selectColumns(), requirements);

        // Analyze granularity requirements
        analyzeGranularity(query.granularity(), requirements);

        // Analyze conditions for stitch requirements
        analyzeConditions(query.conditions(), requirements);

        // Analyze join conditions
        if (query.joinCondition().isPresent()) {
            analyzeJoinCondition(query.joinCondition().get(), requirements);
        }

        // Analyze subqueries recursively
        for (var subquery : query.subqueries()) {
            AttributeRequirements subRequirements = analyze(subquery.subquery());
            requirements.merge(subRequirements);
        }

        logger.debug("Query analysis complete. Requirements: {}", requirements);
        return requirements;
    }

    /**
     * Analyzes SELECT columns to determine attribute requirements.
     */
    private static void analyzeSelectColumns(List<SelectColumn> selectColumns, AttributeRequirements requirements) {
        for (SelectColumn column : selectColumns) {
            if (column instanceof SnippetColumn) {
                logger.debug("Found SNIPPET column, requiring position offsets");
                requirements.needsPositions = true;
            } else if (column instanceof StructuralColumn structCol) {
                String fieldName = structCol.getFieldName();
                if ("SENTENCE_ID".equals(fieldName)) {
                    logger.debug("Found SENTENCE_ID column, requiring sentence IDs");
                    requirements.needsSentenceId = true;
                } else if ("BEGIN".equals(fieldName) || "END".equals(fieldName)) {
                    logger.debug("Found position column ({}), requiring position offsets", fieldName);
                    requirements.needsPositions = true;
                }
            }
            // Note: VariableColumn requirements depend on the conditions that produce them
            // This is handled in analyzeConditions()
        }
    }

    /**
     * Analyzes granularity settings to determine attribute requirements.
     */
    private static void analyzeGranularity(Query.Granularity granularity, AttributeRequirements requirements) {
        if (granularity == Query.Granularity.SENTENCE) {
            logger.debug("Sentence granularity detected, requiring sentence IDs");
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
        if (condition instanceof Ner ner && ner.isVariable()) {
            logger.debug("Found NER variable condition, requiring synonym IDs for stitch operations");
            requirements.needsSynonymIds = true;
        } else if (condition instanceof Pos pos && pos.isVariable()) {
            logger.debug("Found POS variable condition, requiring synonym IDs for stitch operations");
            requirements.needsSynonymIds = true;
        } else if (condition instanceof Logical logical) {
            // Recursively analyze logical conditions
            analyzeConditions(logical.conditions(), requirements);
        }
        // Note: Temporal conditions are handled in analyzeJoinCondition if they involve joins
    }

    /**
     * Analyzes join conditions to determine attribute requirements.
     */
    private static void analyzeJoinCondition(JoinCondition joinCondition, AttributeRequirements requirements) {
        if (joinCondition.operatorType() == JoinCondition.JoinOperatorType.TEMPORAL) {
            logger.debug("Found temporal join, requiring date values");
            requirements.needsDateValues = true;
        }
        // Additional join analysis can be added here as needed
    }

    /**
     * Detects if a query has stitch-eligible patterns.
     * This is a placeholder for future optimization where queries like
     * CONTAINS('term') AND NER(TYPE, 'term') can be routed to stitch indexes.
     * 
     * @param conditions The conditions to analyze
     * @return true if stitch-eligible patterns are detected
     */
    public static boolean hasStitchEligiblePatterns(List<Condition> conditions) {
        // TODO: Implement stitch pattern detection
        // Look for patterns like: CONTAINS('term') AND NER(TYPE, 'term')
        // or: CONTAINS('term') AND POS(TAG, 'term')
        // These can be optimized using stitch indexes
        return false; // Placeholder for future implementation
    }
} 