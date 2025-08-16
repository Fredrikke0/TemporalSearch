package com.example.query.parser;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.antlr.v4.runtime.tree.ParseTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import com.example.query.model.*;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Dependency;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.StitchedCondition;
import com.example.query.model.condition.Temporal;
import com.example.query.parser.util.ValidationUtils;
import com.example.query.parser.util.VariableQualifier;

/**
 * Visitor implementation that builds a Query model from the parse tree.
 * Handles conversion from parse tree nodes to model objects.
 * Uses VariableRegistry to manage variable scopes and types.
 */
public class QueryModelBuilder extends QueryLangBaseVisitor<Object> {
    private static final Logger logger = LoggerFactory.getLogger(QueryModelBuilder.class);
    public static final String DEFAULT_MAIN_ALIAS = "$main"; // Default alias for main query without ALIAS

    // Variable registry for tracking variables - qualified names will be used internally
    private final VariableRegistry variableRegistry = new VariableRegistry();

    /**
     * Creates a new QueryModelBuilder.
     */
    public QueryModelBuilder() {
        // No parameters needed
    }

    @Override
    public Query visitQuery(QueryLangParser.QueryContext ctx) {
        String source = null;
        Optional<String> explicitMainAlias = Optional.empty(); // Explicit alias given by user

        // Get the FROM source identifier
        if (ctx.identifier() != null && !ctx.identifier().isEmpty()) {
            source = ctx.identifier(0).getText();
            // Check if an explicit alias is provided for the main source using ALIAS
            if (ctx.ALIAS() != null && ctx.alias != null) {
                explicitMainAlias = Optional.of(ctx.alias.getText());
            }
        }

        // Determine the effective alias for this scope
        String effectiveMainAlias = explicitMainAlias.orElse(DEFAULT_MAIN_ALIAS);
        String currentLeftSourceAlias = effectiveMainAlias; // Initialize for the first join

        List<Condition> conditions = new ArrayList<>();
        List<String> orderColumns = new ArrayList<>();
        Optional<Integer> limit = Optional.empty();
        Query.Granularity granularity = Query.Granularity.DOCUMENT;
        Optional<Integer> granularitySize = Optional.empty();
        List<SelectColumn> selectColumns = new ArrayList<>();
        List<JoinStep> joinSteps = new ArrayList<>(); // Changed from subqueries and joinCondition
        List<String> groupByColumns = new ArrayList<>();

        // Process join clauses first to determine if qualification is needed early
        if (ctx.joinClause() != null && !ctx.joinClause().isEmpty()) {
            for (QueryLangParser.JoinClauseContext joinCtx : ctx.joinClause()) {
                 // Pass qualification requirement to visitJoinClause
                 // Qualification is required if there's an explicit main alias OR if there are joins
                boolean qualificationRequiredForJoin = explicitMainAlias.isPresent() || !ctx.joinClause().isEmpty(); // this flag is for join condition columns
                Object[] joinResult = visitJoinClause(joinCtx, qualificationRequiredForJoin); // Pass flag
                SubquerySpec subquerySpec = (SubquerySpec) joinResult[0];
                JoinCondition specificJoinCondition = (JoinCondition) joinResult[1];

                JoinStep joinStep = new JoinStep(
                    subquerySpec.alias(),         // rightSourceAlias
                    subquerySpec.subquery(),      // subquery Query object
                    specificJoinCondition,        // onCondition for this step
                    specificJoinCondition.type(), // JoinType for this step (obtained from JoinCondition)
                    currentLeftSourceAlias        // leftSourceAlias (main query or previous step's rightSourceAlias)
                );
                joinSteps.add(joinStep);

                // Update leftSourceAlias for the next iteration
                currentLeftSourceAlias = subquerySpec.alias();

                // Register subquery's produced variables as available in main query scope
                VariableRegistry subqueryRegistry = subquerySpec.subquery().variableRegistry();
                for (String varName : subqueryRegistry.getAllVariableNames()) {
                    if (subqueryRegistry.isProduced(varName)) {
                        // Get the type from the subquery registry
                        VariableType varType = subqueryRegistry.getInferredType(varName);
                        // Register it as a producer in the main registry
                        // The varName here is already qualified with the subquery's alias (e.g., q1.date)
                        variableRegistry.registerProducer(varName, varType, "SUBQUERY_" + subquerySpec.alias());
                        logger.debug("Registered subquery variable '{}' as producer in main registry with type {}", varName, varType);
                    }
                }
            }
        }

        // Determine if qualification is required in SELECT, ORDER BY, GROUP BY
        // Qualification is needed if there's an explicit main alias or if there are any join steps.
        boolean qualificationRequired = VariableQualifier.isQualificationRequired(explicitMainAlias, !joinSteps.isEmpty(), !joinSteps.isEmpty());

        // Extract select columns, passing qualification requirement
        if (ctx.selectClause() != null && ctx.selectClause().selectList() != null) {
            // Pass qualification requirement to visitSelectList
            selectColumns = visitSelectList(ctx.selectClause().selectList(), qualificationRequired);
        }

        if (ctx.whereClause() != null) {
             // Pass the effective alias for this scope to resolve implicit variables
            conditions.addAll(visitConditionList(ctx.whereClause().conditionList(), effectiveMainAlias));
        }

        if (ctx.orderByClause() != null) {
             // Pass qualification requirement to visitOrderByClause
            orderColumns.addAll(visitOrderByClause(ctx.orderByClause(), qualificationRequired));
        }

        if (ctx.groupByClause() != null) {
            groupByColumns.addAll(visitGroupByClause(ctx.groupByClause(), effectiveMainAlias, qualificationRequired));
        }

        if (ctx.limitClause() != null) {
            limit = Optional.of(Integer.parseInt(ctx.limitClause().count.getText()));
        }

        if (ctx.granularityClause() != null) {
            if (ctx.granularityClause().DOCUMENT() != null) {
                granularity = Query.Granularity.DOCUMENT;
            } else {
                granularity = Query.Granularity.SENTENCE;
                if (ctx.granularityClause().size != null) {
                    granularitySize = Optional.of(Integer.parseInt(ctx.granularityClause().size.getText()));
                }
            }
        }

        // Validate variable registry - this automatically happens during variable registration now
        Set<String> validationErrors = variableRegistry.validate();
        if (!validationErrors.isEmpty()) {
            throw new IllegalStateException("Variable binding errors: " + String.join(", ", validationErrors));
        }

        // The Query object stores the user-provided alias, or empty if none.
        // Internal logic uses effectiveMainAlias ($main or explicit).
        return new Query(source, conditions, orderColumns, limit, granularity, granularitySize, selectColumns, variableRegistry, joinSteps, explicitMainAlias, groupByColumns);
    }

    // Overload visitSelectList to accept qualification requirement
    public List<SelectColumn> visitSelectList(QueryLangParser.SelectListContext ctx, boolean qualificationRequired) {
        List<SelectColumn> columns = new ArrayList<>();
        for (QueryLangParser.SelectColumnContext colCtx : ctx.selectColumn()) {
             // Pass qualification requirement down to individual column visitors
            columns.add((SelectColumn) visitSelectColumn(colCtx, qualificationRequired));
        }
        return columns;
    }

    // Helper method to dispatch select column visits with qualification context
    // Note: We now visit qualifiedIdentifier directly, so no specific visitQualifiedColumn needed here.
    private Object visitSelectColumn(QueryLangParser.SelectColumnContext ctx, boolean qualificationRequired) {
        // PRIORITIZE specific structural types FIRST using instanceof
        if (ctx instanceof QueryLangParser.UnqualifiedTitleColumnContext utcc) {
            return visitUnqualifiedTitleColumn(utcc);
        } else if (ctx instanceof QueryLangParser.UnqualifiedTimestampColumnContext utsc) {
            return visitUnqualifiedTimestampColumn(utsc);
        } else if (ctx instanceof QueryLangParser.UnqualifiedDocumentIdColumnContext udicc) {
            return visitUnqualifiedDocumentIdColumn(udicc);
        } else if (ctx instanceof QueryLangParser.UnqualifiedSentenceIdColumnContext usicc) {
            return visitUnqualifiedSentenceIdColumn(usicc);
        }
        // Then handle other specific types
        else if (ctx instanceof QueryLangParser.QualifiedIdentifierColumnContext qicc) {
            Object resolvedColumn = visitQualifiedIdentifier(qicc.qualifiedIdentifier());
            if (resolvedColumn instanceof VariableColumn vc) {
                // This VariableColumn was created by visitQualifiedIdentifier for a qualified name (e.g., alias.field)
                // Since it's being used as a select column, register it as a consumer.
                variableRegistry.registerConsumer(vc.getColumnName(), VariableType.ANY, "SELECT_QUALIFIED_VARIABLE");
                logger.debug("Registered SELECT qualified variable '{}' as consumer (from QualifiedIdentifierColumnContext).", vc.getColumnName());
            }
            return resolvedColumn; // This will be either the (now registered) VariableColumn or a StructuralColumn
        } else if (ctx instanceof QueryLangParser.SnippetColumnContext scc) {
            return visitSnippetColumn(scc, qualificationRequired);
        } else if (ctx instanceof QueryLangParser.StructColumnContext scc) {
            return visitStructColumn(scc);
        } else if (ctx instanceof QueryLangParser.CountColumnContext ccc) {
            return visitCountColumn(ccc, qualificationRequired);
        }
        // THEN handle the generic VariableColumn as a fallback
        else if (ctx instanceof QueryLangParser.VariableColumnContext vcc) {
            logger.trace("Dispatching to visitVariableColumn for: {}", vcc.getText());
            return visitVariableColumn(vcc, qualificationRequired); // This method now handles its own registration
        }
        // If none of the above match
        else {
            throw new IllegalStateException("Unknown SelectColumnContext type: " + ctx.getClass().getName());
        }
    }

    @Override
    public Object visitQualifiedIdentifier(QueryLangParser.QualifiedIdentifierContext ctx) {
        // Access children by position: child 0 is alias (identifier), child 2 is field
        String alias = ctx.getChild(0).getText();
        String fieldName = ctx.getChild(2).getText();
        String fullQualifiedName = alias + "." + fieldName;

        // Check if the field name is a known structural field
        if (Set.of("TITLE", "TIMESTAMP", "DOCUMENT_ID", "SENTENCE_ID", "BEGIN", "END").contains(fieldName.toUpperCase())) {
            logger.trace("Identified StructuralColumn from qualifiedIdentifier: {}", fullQualifiedName);
            return new com.example.query.model.StructuralColumn(alias, fieldName);
        } else {
            // Otherwise, assume it's a variable reference.
            // This method just identifies and creates the VariableColumn.
            // Registration as a consumer happens in the calling context if it's for SELECT.
            logger.trace("Identified VariableColumn from qualifiedIdentifier: {}. Registration will be handled by SELECT-specific visitors.", fullQualifiedName);
            return new VariableColumn(fullQualifiedName);
        }
    }

    // Overload visitVariableColumn
    public Object visitVariableColumn(QueryLangParser.VariableColumnContext ctx, boolean qualificationRequired) {
        String variableName = ctx.variable().getText();
        logger.debug("Visiting VariableColumnContext for: {}, qualificationRequired: {}", variableName, qualificationRequired);

        // *** CORRECTION LOGIC for keywords parsed as variables ***
        String upperVarName = variableName.toUpperCase();
        if (Set.of("DOCUMENT_ID", "SENTENCE_ID", "TIMESTAMP", "TITLE").contains(upperVarName)) {
             logger.warn("Keyword '{}' was parsed as an unqualified variable. Correcting to StructuralColumn with default alias.", variableName);
             if (qualificationRequired) {
                 // This specific path (unqualified keyword mistaken as variable where qualification is required)
                 // is an edge case. Typically, qualified keywords would be handled by visitQualifiedIdentifier.
                 // If it reaches here, it means an unqualified keyword was used where a qualified one was expected.
                 throw new IllegalStateException(
                     String.format("Unqualified structural keyword '%s' used where qualification (e.g. 'alias.%s') is required.",
                                   variableName, variableName));
             }
             return new StructuralColumn(DEFAULT_MAIN_ALIAS, upperVarName);
        }
        // *** END CORRECTION LOGIC ***

        String qualifiedName;
        if (qualificationRequired) {
            // If qualification is required, the variableName *must* already be in alias.var form.
            // This typically happens if a QualifiedIdentifierColumnContext was routed here,
            // or if the grammar/parsing logic leads an already-qualified name to this visitor.
            // For robust handling, we check if it contains '.' as an indicator of qualification.
            if (!variableName.contains(".")) {
                // This case should ideally be caught by grammar or earlier validation if an unqualified variable
                // (e.g. "date") is used in a context that strictly requires a qualified one (e.g. "q1.date").
                // If it still reaches here, it's an error.
                throw new IllegalStateException(
                    String.format("Unqualified variable '%s' used in SELECT where a qualified name (e.g., 'alias.%s') is strictly required by context.",
                                  variableName, variableName)
                );
            }
            qualifiedName = variableName; // Assume variableName is already qualified
            logger.debug("Processing already qualified variable for SELECT: {}", qualifiedName);
        } else {
            // If qualification is not required, implicitly qualify with the default main alias.
            qualifiedName = DEFAULT_MAIN_ALIAS + "." + variableName;
            logger.debug("Implicitly qualifying variable for SELECT: {} -> {}", variableName, qualifiedName);
        }

        // Register this variable as a consumer because it's being used in a SELECT clause.
        variableRegistry.registerConsumer(qualifiedName, VariableType.ANY, "SELECT_VARIABLE");
        logger.debug("Registered SELECT variable '{}' as consumer.", qualifiedName);

        return new VariableColumn(qualifiedName);
    }

    // Overload visitSnippetColumn
    public Object visitSnippetColumn(QueryLangParser.SnippetColumnContext ctx, boolean qualificationRequired) {
        SnippetNode snippetNode = (SnippetNode) visitSnippetExpression(ctx.snippetExpression(), qualificationRequired);
        String qualifiedVariableName = snippetNode.variableName();
        int windowSize = snippetNode.windowSize();

        logger.debug("Processing SnippetColumn for variable: {}, window: {}, qualificationRequired: {}", qualifiedVariableName, windowSize, qualificationRequired);

        // Register the variable used in SNIPPET as a consumer.
        variableRegistry.registerConsumer(qualifiedVariableName, VariableType.TEXT_SPAN, "SELECT_SNIPPET");
        logger.debug("Registered SELECT SNIPPET variable '{}' as consumer.", qualifiedVariableName);

        return new SnippetColumn(qualifiedVariableName, windowSize);
    }

    @Override
    public Object visitStructColumn(QueryLangParser.StructColumnContext ctx) {
        String alias = ctx.qualifiedStructuralColumn().alias.getText();
        String field = ctx.qualifiedStructuralColumn().field.getText();
        // Use the new external StructuralColumn class
        return new com.example.query.model.StructuralColumn(alias, field);
    }

    // Handle standalone TITLE (implicitly $main.TITLE)
    public Object visitUnqualifiedTitleColumn(QueryLangParser.UnqualifiedTitleColumnContext ctx) {
        return new com.example.query.model.StructuralColumn(DEFAULT_MAIN_ALIAS, "TITLE");
    }

    // Handle standalone TIMESTAMP (implicitly $main.TIMESTAMP)
    public Object visitUnqualifiedTimestampColumn(QueryLangParser.UnqualifiedTimestampColumnContext ctx) {
        return new com.example.query.model.StructuralColumn(DEFAULT_MAIN_ALIAS, "TIMESTAMP");
    }

    // Handle standalone DOCUMENT_ID (implicitly $main.DOCUMENT_ID)
    public Object visitUnqualifiedDocumentIdColumn(QueryLangParser.UnqualifiedDocumentIdColumnContext ctx) {
        // Previously was creating VariableColumn incorrectly? Let's ensure it's Structural.
        logger.debug("Visiting UnqualifiedDocumentIdColumn. Creating StructuralColumn.");
        return new StructuralColumn(DEFAULT_MAIN_ALIAS, "DOCUMENT_ID");
    }

    // Handle standalone SENTENCE_ID (implicitly $main.SENTENCE_ID)
    public Object visitUnqualifiedSentenceIdColumn(QueryLangParser.UnqualifiedSentenceIdColumnContext ctx) {
        logger.debug("Visiting UnqualifiedSentenceIdColumn. Creating StructuralColumn.");
        return new StructuralColumn(DEFAULT_MAIN_ALIAS, "SENTENCE_ID");
    }

    // Overload visitCountColumn
    public Object visitCountColumn(QueryLangParser.CountColumnContext ctx, boolean qualificationRequired) {
        // Pass qualificationRequired down to the specific count expression visitor
        // Only CountUniqueExpression needs it
        return visitCountExpression(ctx.countExpression(), qualificationRequired);
    }

    // Helper method to dispatch count expression visits
    private Object visitCountExpression(QueryLangParser.CountExpressionContext ctx, boolean qualificationRequired) {
         if (ctx instanceof QueryLangParser.CountAllExpressionContext caec) {
            return visitCountAllExpression(caec);
         } else if (ctx instanceof QueryLangParser.CountTargetExpressionContext ctec) {
             return visitCountTargetExpression(ctec, qualificationRequired); // Pass flag
         } else if (ctx instanceof QueryLangParser.CountDocumentsExpressionContext cdec) {
            return visitCountDocumentsExpression(cdec);
         } else {
             throw new IllegalStateException("Unknown CountExpressionContext type: " + ctx.getClass().getName());
         }
    }

    // Overload visitSnippetExpression
    public Object visitSnippetExpression(QueryLangParser.SnippetExpressionContext ctx, boolean qualificationRequired) {
        String qualifiedTargetName;

        // Check if the target is a variable or a qualified identifier
        if (ctx.variable() != null) {
            String variableName = (String) visit(ctx.variable()); // This correctly returns a String
            if (qualificationRequired) {
                 throw new IllegalStateException(
                    String.format("Unqualified variable '%s' used in SNIPPET where qualification is required (due to ALIAS or JOIN). Use 'alias.%s'.",
                                  variableName, variableName)
                     // TODO: Add line/pos info
                 );
            }
            // If not required, implicitly qualify
            qualifiedTargetName = DEFAULT_MAIN_ALIAS + "." + variableName;
        } else if (ctx.qualifiedIdentifier() != null) {
            Object visitedNode = visit(ctx.qualifiedIdentifier()); // visit() can return VariableColumn or StructuralColumn
            if (visitedNode instanceof VariableColumn vc) {
                qualifiedTargetName = vc.getColumnName();
            } else if (visitedNode instanceof com.example.query.model.StructuralColumn sc) { // Ensure fully qualified name if it's StructuralColumn from model package
                qualifiedTargetName = sc.getAlias() + "." + sc.getFieldName();
            } else if (visitedNode instanceof String) { // Fallback for safety, though visitQualifiedIdentifier shouldn't return String directly
                qualifiedTargetName = (String) visitedNode;
                 logger.warn("visitQualifiedIdentifier returned a String for SNIPPET target. This is unexpected. Value: {}", qualifiedTargetName);
            }
            else {
                throw new IllegalStateException("Snippet expression target (qualifiedIdentifier) resolved to unexpected type: " + visitedNode.getClass().getName());
            }
        } else {
             throw new IllegalStateException("Snippet expression target must be a variable or qualified identifier.");
        }

        int windowSizeForNode = -1; // Default to -1, to let SnippetColumn use its default
        if (ctx.windowSize != null) { // ctx.windowSize is the INTEGER_LITERAL token from the new grammar
            try {
                windowSizeForNode = Integer.parseInt(ctx.windowSize.getText());
            } catch (NumberFormatException e) {
                logger.warn("Invalid integer format for snippet window size: '{}'. Defaulting to internal snippet window.", ctx.windowSize.getText());
                // windowSizeForNode remains -1, relying on SnippetColumn's default logic
            }
        }

        // Call the SnippetNode constructor (assuming it's an intermediate helper class/record)
        // This SnippetNode is then used by visitSnippetColumn to create the actual SnippetColumn model object.
        return new SnippetNode(qualifiedTargetName, windowSizeForNode);
    }


    @Override
    public Object visitCountAllExpression(QueryLangParser.CountAllExpressionContext ctx) {
        return CountColumn.countAll();
    }

    public Object visitCountTargetExpression(QueryLangParser.CountTargetExpressionContext ctx, boolean qualificationRequired) {
        boolean isUnique = ctx.unique != null; // Check if UNIQUE keyword is present
        String targetName;

        // Determine if the target is a variable or qualifiedIdentifier
        if (ctx.variable() != null) {
            String varName = ctx.variable().getText();
            try {
                targetName = VariableQualifier.qualifyVariable(varName, qualificationRequired, Optional.of(DEFAULT_MAIN_ALIAS));
            } catch (IllegalArgumentException e) {
                String countType = isUnique ? "COUNT(UNIQUE " + varName + ")" : "COUNT(" + varName + ")";
                throw new IllegalArgumentException(
                    String.format("%s requires a qualified variable in this context. Use %s",
                                  countType,
                                  isUnique ? "COUNT(UNIQUE alias." + varName + ")" : "COUNT(alias." + varName + ")")
                );
            }
        } else if (ctx.qualifiedIdentifier() != null) {
            // Extract qualified name from qualifiedIdentifier
            String alias = ctx.qualifiedIdentifier().getChild(0).getText();
            String fieldName = ctx.qualifiedIdentifier().getChild(2).getText();
            targetName = alias + "." + fieldName;
        } else {
            throw new IllegalStateException("CountTargetExpression must have either variable or qualifiedIdentifier");
        }

        if (isUnique) {
            return CountColumn.countUnique(targetName);
        } else {
            // For non-unique COUNT, we can create a simple count of the target
            // This may need to be implemented in CountColumn class
            return CountColumn.countUnique(targetName);
        }
    }

    @Override
    public Object visitCountDocumentsExpression(QueryLangParser.CountDocumentsExpressionContext ctx) {
        return CountColumn.countDocuments();
    }

    // Overload visitConditionList to pass the current scope alias
    public List<Condition> visitConditionList(QueryLangParser.ConditionListContext ctx, String currentScopeAlias) {
        if (ctx.condition().size() == 1) {
            // If there's only one condition, visit it and return the result
            Object result = visitCondition(ctx.condition(0), currentScopeAlias);
            if (result instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<Condition> conditions = (List<Condition>) result;
                return conditions;
            } else if (result instanceof Condition) {
                return List.of((Condition) result);
            } else {
                 throw new IllegalStateException("Visiting single condition did not return Condition or List<Condition>");
            }
        }

        // Start with the first condition
        Object firstResult = visitCondition(ctx.condition(0), currentScopeAlias);
        Condition currentCondition = ValidationUtils.extractSingleCondition(firstResult, "first operand");

        // Process the logical operations
        for (int i = 0; i < ctx.logicalOp().size(); i++) {
            // Get the logical operator
            Logical.LogicalOperator operator = parseLogicalOperator(ctx.logicalOp(i).getText());

            // Get the right operand
            Object rightResult = visitCondition(ctx.condition(i + 1), currentScopeAlias);
            Condition rightCondition = ValidationUtils.extractSingleCondition(rightResult, "right operand");

            // Create a logical condition
            currentCondition = new Logical(operator, currentCondition, rightCondition);
        }

        return List.of(currentCondition);
    }



    // Helper to parse logical operator text
    private Logical.LogicalOperator parseLogicalOperator(String opText) {
         if (opText.equalsIgnoreCase("AND")) {
            return Logical.LogicalOperator.AND;
         } else if (opText.equalsIgnoreCase("OR")) {
            return Logical.LogicalOperator.OR;
         } else {
             throw new IllegalStateException("Unexpected logical operator: " + opText); // TODO: Add context
         }
    }

    // Overload visitCondition to pass alias
    public Object visitCondition(QueryLangParser.ConditionContext ctx, String currentScopeAlias) {
        return visit(ctx.getChild(0), currentScopeAlias); // Pass alias to child visit
    }

    // Overload visitNotCondition to pass alias
    public Object visitNotCondition(QueryLangParser.NotConditionContext ctx, String currentScopeAlias) {
        Object result = visitAtomicCondition(ctx.atomicCondition(), currentScopeAlias);
        Condition conditionToNegate = ValidationUtils.extractSingleCondition(result, "operand of NOT");
        return new Not(conditionToNegate);
    }

    // Overload visitAtomicCondition to pass alias
    public Object visitAtomicCondition(QueryLangParser.AtomicConditionContext ctx, String currentScopeAlias) {
        if (ctx.singleCondition() != null) {
            return visitSingleCondition(ctx.singleCondition(), currentScopeAlias);
        } else if (ctx.LPAREN() != null) {
             // Condition list inside parentheses inherits the alias
            return visitConditionList(ctx.conditionList(), currentScopeAlias);
        }
        throw new IllegalStateException("Unexpected atomic condition structure");
    }

    // Overload visitSingleCondition to pass alias
    public Object visitSingleCondition(QueryLangParser.SingleConditionContext ctx, String currentScopeAlias) {
        // Dispatch to the specific condition visitor (e.g., visitNerExpression), passing the alias
        if (ctx.nerExpression() != null) {
             return visitNerExpression(ctx.nerExpression(), currentScopeAlias);
        } else if (ctx.containsExpression() != null) {
             return visitContainsExpression(ctx.containsExpression(), currentScopeAlias);
        } else if (ctx.dateExpression() != null) {
            // Date expressions need the alias passed down
            return visit(ctx.dateExpression(), currentScopeAlias);
        } else if (ctx.dependsExpression() != null) {
             return visitDependsExpression(ctx.dependsExpression(), currentScopeAlias);
        } else if (ctx.posExpression() != null) {
             return visitPosExpression(ctx.posExpression(), currentScopeAlias);
        }
        // Fallback or error if no condition matched
        throw new IllegalStateException("Unhandled single condition type: " + ctx.getText());
    }

    // Need to overload the dispatcher 'visit' to accept the alias.
    // This requires modifying the base class or using a different approach.
    // Let's create helper methods for visiting specific types with context.

    private Object visit(ParseTree tree, String currentScopeAlias) {
         // Helper to dispatch visits for conditions needing the alias
         logger.trace("visit: tree: {}, currentScopeAlias: {}", tree, currentScopeAlias);
         if (tree instanceof QueryLangParser.NerExpressionContext c) return visitNerExpression(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.ContainsExpressionContext c) return visitContainsExpression(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.DateComparisonExpressionContext c) return visitDateComparisonExpression(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.DateLiteralComparisonExpressionContext c) return visitDateLiteralComparisonExpression(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.DateOperatorExpressionContext c) return visitDateOperatorExpression(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.DependsExpressionContext c) return visitDependsExpression(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.PosExpressionContext c) return visitPosExpression(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.ConditionListContext c) return visitConditionList(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.ConditionContext c) return visitCondition(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.AtomicConditionContext c) return visitAtomicCondition(c, currentScopeAlias);
         if (tree instanceof QueryLangParser.NotConditionContext c) return visitNotCondition(c, currentScopeAlias);
         // For other node types, call the original visit method
         return visit(tree);
    }

    @Override
    public Object visitIdentifier(QueryLangParser.IdentifierContext ctx) {
        return ctx.IDENTIFIER().getText();
    }

    public Object visitContainsExpression(QueryLangParser.ContainsExpressionContext ctx, String currentScopeAlias) {
        List<String> terms = new ArrayList<>();

        // If only one string literal is provided, split it by spaces
        if (ctx.terms.size() == 1) {
            String singleTerm = unquote(ctx.terms.get(0).getText());
            terms.addAll(List.of(singleTerm.split("\\s+"))); // Split by one or more spaces
        } else {
            // Otherwise, treat each literal as a separate term
            for (var termNode : ctx.terms) {
                terms.add(unquote(termNode.getText()));
            }
        }

        String qualifiedVariableName = null;
        boolean isVariable = false; // Flag to track if variable is bound
        if (ctx.BIND() != null && ctx.var != null) {
            String plainVarName = (String) visit(ctx.var);
            qualifiedVariableName = VariableQualifier.qualifyWithScope(plainVarName, currentScopeAlias);
            isVariable = true; // Set flag
            logger.debug("Registering producer: {} type: TEXT_SPAN for CONTAINS", qualifiedVariableName);
            variableRegistry.registerProducer(qualifiedVariableName, VariableType.TEXT_SPAN, "CONTAINS");
        }

        // Model updated to store qualified name
        return new Contains(terms, qualifiedVariableName, isVariable); // Pass qualified name
    }

    public Object visitNerExpression(QueryLangParser.NerExpressionContext ctx, String currentScopeAlias) {
        String type = (String) visitEntityType(ctx.type);
        String qualifiedVariableName = null;
        boolean isVariable = false; // Flag
        logger.debug("[QueryModelBuilder.visitNerExpression] Processing NER expression: {}. BIND present: {}, var present: {}", ctx.getText(), ctx.BIND() != null, ctx.var != null);

        if (ctx.BIND() != null && ctx.var != null) {
            String plainVarName = (String) visit(ctx.var);
            qualifiedVariableName = VariableQualifier.qualifyWithScope(plainVarName, currentScopeAlias);
            isVariable = true; // Set flag
            VariableType varType = determineNerVariableType(type);
            logger.debug("[QueryModelBuilder.visitNerExpression] BIND clause detected. plainVarName: '{}', qualifiedVariableName: '{}', isVariable set to: {}, varType: {}", plainVarName, qualifiedVariableName, isVariable, varType);
            variableRegistry.registerProducer(qualifiedVariableName, varType, "NER");
        } else {
            logger.debug("[QueryModelBuilder.visitNerExpression] No BIND clause detected or var is null. isVariable remains: {}", isVariable);
        }

        List<String> targets = new ArrayList<>();
        if (ctx.terms != null && !ctx.terms.isEmpty()) {
            for (var termNode : ctx.terms) {
                Object termResult = visitTerm(termNode, currentScopeAlias); // Pass alias
                String termValue = (String) termResult; // visitTerm now returns plain name if variable
                logger.debug("[QueryModelBuilder.visitNerExpression] termValue present: '{}'", termValue);
                targets.add(termValue);
                // Consumption registration happens within visitTerm
            }
        }

        Ner nerCondition = new Ner(type, targets, qualifiedVariableName, isVariable); // Pass qualified name
        logger.debug("[QueryModelBuilder.visitNerExpression] Constructed Ner condition: {}", nerCondition);
        return nerCondition;
    }

    // Helper method to determine variable type from NER entity type
    private VariableType determineNerVariableType(String nerType) {
        if (nerType == null) {
            return VariableType.ANY;
        }

        return switch (nerType.toUpperCase()) {
            case "PERSON", "ORGANIZATION", "LOCATION" -> VariableType.ENTITY;
            case "DATE", "TIME" -> VariableType.TEMPORAL;
            default -> VariableType.ENTITY;
        };
    }

    @Override
    public Object visitEntityType(QueryLangParser.EntityTypeContext ctx) {
        // Handle NER type tokens
        if (ctx.PERSON() != null) return "PERSON";
        if (ctx.LOCATION() != null) return "LOCATION";
        if (ctx.ORGANIZATION() != null) return "ORGANIZATION";
        if (ctx.TIME() != null) return "TIME";
        if (ctx.DURATION() != null) return "DURATION";
        if (ctx.MONEY() != null) return "MONEY";
        if (ctx.NUMBER() != null) return "NUMBER";
        if (ctx.ORDINAL() != null) return "ORDINAL";
        if (ctx.PERCENT() != null) return "PERCENT";
        if (ctx.SET() != null) return "SET";

        throw new IllegalArgumentException("Invalid entity type: " + ctx.getText());
    }

    @Override
    public Object visitVariable(QueryLangParser.VariableContext ctx) {
        return ctx.IDENTIFIER().getText(); // Return the plain variable name
    }

    /**
     * Handles DATE comparison expressions from the grammar rule:
     * DATE(comparisonOp year=INTEGER_LITERAL) (BIND var=variable)?
     *
     * Examples: DATE(> 2020) BIND date, DATE(< 2018), DATE(>= 2000) BIND year
     *
     * This creates a temporal condition that compares against a specific year.
     * The year value is extracted from ctx.year and the comparison operator determines
     * how the comparison is performed (before, after, etc.).
     */
    public Object visitDateComparisonExpression(QueryLangParser.DateComparisonExpressionContext ctx, String currentScopeAlias) {
        String operator = ctx.comparisonOp().getText();
        int year = Integer.parseInt(ctx.year.getText()); // Extract the year from the grammar

        TemporalPredicate temporalType = mapComparisonOpToPredicate(operator);

        Optional<LocalDateTime> startDate = Optional.empty();
        Optional<LocalDateTime> endDate = Optional.empty();

        switch (temporalType) {
            case EQUAL:
                // DATE(= 2020) means the whole year 2020.
                startDate = Optional.of(LocalDateTime.of(year, 1, 1, 0, 0));
                endDate = Optional.of(LocalDateTime.of(year, 12, 31, 23, 59, 59));
                break;
            case AFTER:
                // DATE(> 2020) should mean after Dec 31, 2020.
                startDate = Optional.of(LocalDateTime.of(year, 12, 31, 23, 59, 59));
                break;
            case AFTER_EQUAL:
                // DATE(>= 2020) should mean on or after Jan 1, 2020.
                startDate = Optional.of(LocalDateTime.of(year, 1, 1, 0, 0));
                break;
            case BEFORE:
                // DATE(< 2020) means before Jan 1, 2020.
                startDate = Optional.of(LocalDateTime.of(year, 1, 1, 0, 0));
                break;
            case BEFORE_EQUAL:
                // DATE(<= 2020) means on or before Dec 31, 2020.
                // The executor's `evaluateTemporalCondition` uses the endDate for BEFORE_EQUAL.
                endDate = Optional.of(LocalDateTime.of(year, 12, 31, 23, 59, 59));
                break;
        }

        String qualifiedVariableName = null;
        if (ctx.BIND() != null && ctx.var != null) {
            // BIND clause present: register the variable as a producer
            String plainVarName = (String) visit(ctx.var);
            qualifiedVariableName = currentScopeAlias + "." + plainVarName;
            logger.debug("Registering producer: {} type: TEMPORAL for DATE comparison", qualifiedVariableName);
            variableRegistry.registerProducer(qualifiedVariableName, VariableType.TEMPORAL, "DATE_COMPARISON");
        } else {
            // No BIND clause: this is a filter condition without variable binding
            logger.debug("DATE comparison without BIND clause - filtering documents by year {}", year);
            // qualifiedVariableName remains null - indicates this is a filter, not a variable producer
        }

        // Create Temporal model with the year date and predicate
        return new Temporal(
            startDate, // The year as a LocalDateTime (start of year)
            endDate, // End date not used for simple year comparisons
            Optional.ofNullable(qualifiedVariableName), // Variable name if BIND clause present
            Optional.empty(), // Range not applicable for year comparisons
            temporalType // The comparison predicate (BEFORE, AFTER, etc.)
        );
    }

    public Object visitDateOperatorExpression(QueryLangParser.DateOperatorExpressionContext ctx, String currentScopeAlias) {
        String operator = ctx.dateOperator().getText();
        TemporalPredicate type = mapOperatorToTemporal(operator); // Corrected variable name: 'type' not 'dependencyType'

        // Directly visit the dateValue node instead of using visitChildren
        Object dateValue = visit(ctx.dateValue());

        LocalDateTime startDate;
        Optional<LocalDateTime> endDate = Optional.empty();

        if (dateValue instanceof Integer year) {
            startDate = LocalDateTime.of(year, 1, 1, 0, 0);
        } else if (dateValue instanceof LocalDateTime[] dateRange) {
            // Handle date range as array of LocalDateTime [start, end]
            startDate = dateRange[0];
            endDate = Optional.of(dateRange[1]);
        } else {
            // Assume it's a single date
            startDate = (LocalDateTime) dateValue;
        }

        Optional<TemporalRange> range = Optional.empty();
        if (ctx.radius != null && ctx.unit != null) {
            int radiusVal = Integer.parseInt(ctx.radius.getText());
            String unitVal = ctx.unit.getText();
            range = Optional.ofNullable(new TemporalRange(radiusVal + unitVal));
        }

        String qualifiedVariableName = null;
        if (ctx.BIND() != null && ctx.var != null) {
            String plainVarName = (String) visit(ctx.var);
            qualifiedVariableName = currentScopeAlias + "." + plainVarName; // Qualify
            logger.debug("Registering producer: {} type: TEMPORAL for TEMPORAL", qualifiedVariableName);
            variableRegistry.registerProducer(qualifiedVariableName, VariableType.TEMPORAL, "TEMPORAL");
        }

        // Model updated to store qualified name (as Optional)
        // Wrap startDate in Optional.of() to match the updated Temporal constructor
        return new Temporal(Optional.of(startDate), endDate, Optional.ofNullable(qualifiedVariableName), range, type);
    }

    @Override
    public Object visitDateRange(QueryLangParser.DateRangeContext ctx) {
        int startYear = Integer.parseInt(ctx.start.getText());
        int endYear = Integer.parseInt(ctx.end.getText());
        LocalDateTime startDate = LocalDateTime.of(startYear, 1, 1, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(endYear, 12, 31, 23, 59, 59);
        LocalDateTime[] result = new LocalDateTime[] { startDate, endDate };
        return result;
    }

    @Override
    public Object visitSingleYear(QueryLangParser.SingleYearContext ctx) {
        int year = Integer.parseInt(ctx.single.getText());
        LocalDateTime result = LocalDateTime.of(year, 1, 1, 0, 0);
        return result;
    }



    /**
     * Maps a date operator string from the query language to the unified TemporalPredicate enum.
     * Used for both date expressions in the WHERE clause and join conditions.
     *
     * @param operator The operator string from the query
     * @return The corresponding TemporalPredicate value
     * @throws IllegalStateException if the operator is invalid
     */
    private TemporalPredicate mapOperatorToTemporal(String operator) {
        return switch (operator.toUpperCase()) {
            case "CONTAINS" -> TemporalPredicate.CONTAINS;
            case "CONTAINED_BY" -> TemporalPredicate.CONTAINED_BY;
            case "INTERSECT" -> TemporalPredicate.INTERSECT;
            case "PROXIMITY" -> TemporalPredicate.PROXIMITY;
            case "BEFORE" -> TemporalPredicate.BEFORE;
            case "AFTER" -> TemporalPredicate.AFTER;
            default -> throw new IllegalArgumentException("Invalid temporal operator: " + operator);
        };
    }

    public Object visitDependsExpression(QueryLangParser.DependsExpressionContext ctx, String currentScopeAlias) {
        String governor;
        boolean governorIsVariable = false;
        // Visit governor, passing alias for potential variable consumption registration
        Object govResult = visitGovernor(ctx.gov, currentScopeAlias);
        if (govResult instanceof VariableReference govVarRef) {
            governor = govVarRef.plainName(); // Use plain name for the Dependency model
            governorIsVariable = true;
            // Consumption registered within visitGovernor
        } else {
            governor = (String) govResult;
        }

        // Debugging visitRelation call
        logger.debug("Visiting relation: {}", ctx.rel != null ? ctx.rel.getText() : "null context");
        if (ctx.rel == null) {
            logger.error("RelationContext (ctx.rel) is null before calling visitRelation.");
            // Optionally print more context if possible, e.g., ctx.getText()
            logger.error("Parent dependsExpression context: {}", ctx.getText());
        }

        String relation = (String) visitRelation(ctx.rel); // Revert back to direct call

        String dependent;
        boolean dependentIsVariable = false;
         // Visit dependent, passing alias
        Object depResult = visitDependent(ctx.dep, currentScopeAlias);
         if (depResult instanceof VariableReference depVarRef) {
            dependent = depVarRef.plainName(); // Use plain name for the Dependency model
            dependentIsVariable = true;
             // Consumption registered within visitDependent
        } else {
            dependent = (String) depResult;
        }

        String qualifiedVariableName = null;
        boolean isVariable = false; // Flag for BIND
        if (ctx.BIND() != null && ctx.var != null) {
            String plainVarName = (String) visit(ctx.var);
            qualifiedVariableName = currentScopeAlias + "." + plainVarName; // Qualify BIND variable
            isVariable = true; // Set flag
            logger.debug("Registering producer: {} type: DEPENDENCY for DEPENDENCY", qualifiedVariableName);
            variableRegistry.registerProducer(qualifiedVariableName, VariableType.DEPENDENCY, "DEPENDENCY");
        }

        // Model updated to store qualified BIND variable name
        // Governor and Dependent strings remain plain if they were variables
        return new Dependency(governor, relation, dependent, qualifiedVariableName, isVariable);
    }

    // Overload visitGovernor to accept alias and return VariableReference if it's a variable
    public Object visitGovernor(QueryLangParser.GovernorContext ctx, String currentScopeAlias) {
        if (ctx.qualifiedIdentifier() != null) {
            // Visit the qualifiedIdentifier, which returns StructuralColumn or VariableColumn
            Object qualifiedResult = visitQualifiedIdentifier(ctx.qualifiedIdentifier());

            if (qualifiedResult instanceof VariableColumn vc) {
                String qualifiedName = vc.getColumnName(); // Get qualified name from VariableColumn
                logger.debug("Registering consumer: {} type: ANY for DEPENDENCY Governor", qualifiedName);
                variableRegistry.registerConsumer(qualifiedName, VariableType.ANY, "DEPENDENCY Governor");
                // Return VariableReference containing both plain and qualified name if possible
                // We only have qualified name here, maybe refine VariableReference or lookup?
                // For now, use qualified name for both fields in VariableReference.
                return new VariableReference(qualifiedName, qualifiedName); // Pass qualified name
            } else if (qualifiedResult instanceof com.example.query.model.StructuralColumn sc) {
                throw new IllegalStateException("Structural column ('" + sc.getColumnName() + "') cannot be used as governor in DEPENDS clause.");
            } else {
                throw new IllegalStateException("Unexpected result type from visitQualifiedIdentifier for governor: " + qualifiedResult.getClass().getName());
            }
        } else if (ctx.variable() != null) {
            String plainVarName = (String) visit(ctx.variable());
            String qualifiedName = currentScopeAlias + "." + plainVarName;
            logger.debug("Registering consumer: {} type: ANY for DEPENDENCY Governor", qualifiedName);
            variableRegistry.registerConsumer(qualifiedName, VariableType.ANY, "DEPENDENCY Governor");
            return new VariableReference(plainVarName, qualifiedName); // Return wrapper
        } else if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        } else if (ctx.identifier() != null) {
            return visitIdentifier(ctx.identifier());
        }
        throw new IllegalStateException("visitGovernor called on unexpected context type. Context: " + ctx.getText());
    }

    // Overload visitDependent to accept alias and return VariableReference
    public Object visitDependent(QueryLangParser.DependentContext ctx, String currentScopeAlias) {
        if (ctx.qualifiedIdentifier() != null) {
            // Visit the qualifiedIdentifier, which returns StructuralColumn or VariableColumn
            Object qualifiedResult = visitQualifiedIdentifier(ctx.qualifiedIdentifier());

            if (qualifiedResult instanceof VariableColumn vc) {
                String qualifiedName = vc.getColumnName(); // Get qualified name from VariableColumn
                logger.debug("Registering consumer: {} type: ANY for DEPENDENCY Dependent", qualifiedName);
                variableRegistry.registerConsumer(qualifiedName, VariableType.ANY, "DEPENDENCY Dependent");
                // For now, use qualified name for both fields in VariableReference.
                return new VariableReference(qualifiedName, qualifiedName); // Pass qualified name
            } else if (qualifiedResult instanceof com.example.query.model.StructuralColumn sc) {
                throw new IllegalStateException("Structural column ('" + sc.getColumnName() + "') cannot be used as dependent in DEPENDS clause.");
            } else {
                throw new IllegalStateException("Unexpected result type from visitQualifiedIdentifier for dependent: " + qualifiedResult.getClass().getName());
            }
        } else if (ctx.variable() != null) {
            String plainVarName = (String) visit(ctx.variable());
            String qualifiedName = currentScopeAlias + "." + plainVarName;
            logger.debug("Registering consumer: {} type: ANY for DEPENDENCY Dependent", qualifiedName);
            variableRegistry.registerConsumer(qualifiedName, VariableType.ANY, "DEPENDENCY Dependent");
            return new VariableReference(plainVarName, qualifiedName); // Return wrapper
        } else if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        } else if (ctx.identifier() != null) {
            return visitIdentifier(ctx.identifier());
        } else if (ctx.WILDCARD() != null) { // Added WILDCARD handling
            return ctx.WILDCARD().getText();
        }
        throw new IllegalStateException("visitDependent called on unexpected context type. Context: " + ctx.getText());
    }

    @Override
    public Object visitRelation(QueryLangParser.RelationContext ctx) {
        if (ctx == null) {
             throw new IllegalStateException("visitRelation called with null context");
        }
        if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        }
        if (ctx.identifier() != null) { // Check identifier explicitly
            return visitIdentifier(ctx.identifier());
        }
        // Handle potential case where relation might be defined differently (e.g., keywords)
        // For now, throw if neither STRING nor identifier is found
        throw new IllegalStateException("Relation context does not contain STRING or identifier: " + ctx.getText());
    }

    // Overload visitOrderSpec to accept qualification requirement
    public String visitOrderSpec(QueryLangParser.OrderSpecContext ctx, boolean qualificationRequired) {
        String qualifiedFieldName; // Holds the final name, e.g., $main.var, alias.var, alias.TITLE, COUNT(*)

        // Determine the base field name (plain or qualified)
        if (ctx.variable() != null) {
            String variableName = (String) visit(ctx.variable()); // visitVariable returns plain name
            if (qualificationRequired) {
                 throw new IllegalStateException(
                    String.format("Unqualified variable '%s' used in ORDER BY where qualification is required. Use 'alias.%s'.",
                                   variableName, variableName)
                 );
            }
            // If not required, implicitly qualify
            qualifiedFieldName = DEFAULT_MAIN_ALIAS + "." + variableName;
        } else if (ctx.qualifiedIdentifier() != null) {
            // Visit the qualifiedIdentifier, which returns StructuralColumn or VariableColumn
            Object qualifiedResult = visitQualifiedIdentifier(ctx.qualifiedIdentifier());
            if (qualifiedResult instanceof VariableColumn vc) {
                qualifiedFieldName = vc.getColumnName();
            } else if (qualifiedResult instanceof com.example.query.model.StructuralColumn sc) {
                qualifiedFieldName = sc.getColumnName();
            } else {
                throw new IllegalStateException("Unexpected result type from visitQualifiedIdentifier for ORDER BY column: " + qualifiedResult.getClass().getName());
            }
        } else if (ctx.identifier() != null) {
            // Only treat TITLE, TIMESTAMP, DOCUMENT_ID, SENTENCE_ID as structural fields if unqualified
            String id = ctx.identifier().getText();
            if (Set.of("TITLE", "TIMESTAMP", "DOCUMENT_ID", "SENTENCE_ID").contains(id.toUpperCase())) {
                if (qualificationRequired) {
                    throw new IllegalStateException(
                        String.format("Unqualified structural identifier '%s' used in ORDER BY where qualification is required. Use 'alias.%s' or select a bound variable.",
                                       id, id.toUpperCase())); // Use toUpperCase for consistency if it's a structural field
                }
                qualifiedFieldName = DEFAULT_MAIN_ALIAS + "." + id.toUpperCase();
            } else {
                // Treat as variable binding (e.g., date -> $main.date)
                 if (qualificationRequired) {
                    // This case implies an unbound identifier or a variable that should have been qualified.
                    // If 'id' is a variable, it should have been parsed as ctx.variable() or ctx.qualifiedIdentifier().
                    // If it's an identifier not matching known structural ones and qualification is required,
                    // it implies an attempt to use an unqualified variable or a misspelled/unknown field.
                     throw new IllegalStateException(
                        String.format("Unqualified identifier '%s' used in ORDER BY where qualification is required. Ensure it's a defined variable (e.g., 'alias.var') or a known structural field.",
                                       id));
                }
                qualifiedFieldName = DEFAULT_MAIN_ALIAS + "." + id;
            }
        } else if (ctx.countExpression() != null) {
            QueryLangParser.CountExpressionContext countCtx = ctx.countExpression();
            CountColumn countCol;
            if (countCtx instanceof QueryLangParser.CountAllExpressionContext cax) {
                countCol = (CountColumn) visitCountAllExpression(cax);
            } else if (countCtx instanceof QueryLangParser.CountTargetExpressionContext ctex) {
                // visitCountTargetExpression needs qualificationRequired for the variable inside
                countCol = (CountColumn) visitCountTargetExpression(ctex, qualificationRequired);
            } else if (countCtx instanceof QueryLangParser.CountDocumentsExpressionContext cdx) {
                countCol = (CountColumn) visitCountDocumentsExpression(cdx);
            } else {
                throw new IllegalStateException("Unknown CountExpressionContext type: " + countCtx.getClass().getName());
            }
            qualifiedFieldName = countCol.getColumnName(); // Assumes getColumnName() gives "COUNT(*)", "COUNT(UNIQUE alias.var)" etc.
        }
        else {
            throw new IllegalStateException("OrderSpec must have identifier, variable, qualifiedIdentifier, or countExpression. Found: " + ctx.getText());
        }

        // Return the qualified field name, with appropriate prefix for ordering
        if (ctx.DESC() != null) {
            return "-" + qualifiedFieldName;
        } else if (ctx.ASC() != null) {
            return qualifiedFieldName;
        } else {
            return "-" + qualifiedFieldName;
        }
    }

    // Overload visitOrderByClause
    public List<String> visitOrderByClause(QueryLangParser.OrderByClauseContext ctx, boolean qualificationRequired) {
        List<String> orderColumns = new ArrayList<>();
         for (QueryLangParser.OrderSpecContext specCtx : ctx.orderSpec()) {
            orderColumns.add(visitOrderSpec(specCtx, qualificationRequired)); // Pass flag
         }
         return orderColumns;
    }


    private String unquote(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        char firstChar = text.charAt(0);
        char lastChar = text.charAt(text.length() - 1);

        if ((firstChar == '"' && lastChar == '"') || (firstChar == '\'' && lastChar == '\'')) {
            // Additional logic to handle escaped quotes if necessary, e.g., replace "" with " or '' with '
            // For now, just removing the outer quotes
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    public Query buildQuery(ParseTree tree) {
        return (Query) visit(tree);
    }

    public Object visitPosExpression(QueryLangParser.PosExpressionContext ctx, String currentScopeAlias) {
        String posTag = (String) visitPosTag(ctx.tag);
        String termValue = null;
        if (ctx.termValue != null) {
             Object termResult = visitTerm(ctx.termValue, currentScopeAlias); // Pass alias
             termValue = (String) termResult; // visitTerm returns plain name if variable
             // Consumption registration happens within visitTerm
        }

        String qualifiedVariableName = null;
        boolean isVariable = false; // Flag for BIND
        if (ctx.BIND() != null && ctx.var != null) {
            String plainVarName = (String) visit(ctx.var);
            qualifiedVariableName = currentScopeAlias + "." + plainVarName; // Qualify BIND variable
            isVariable = true; // Set flag
            logger.debug("Registering producer: {} type: TEXT_SPAN for POS", qualifiedVariableName);
            variableRegistry.registerProducer(qualifiedVariableName, VariableType.TEXT_SPAN, "POS");
        }

        // Model updated to store qualified BIND variable name
        return new Pos(posTag, termValue, qualifiedVariableName, isVariable); // Pass qualified name
    }

    @Override
    public Object visitPosTag(QueryLangParser.PosTagContext ctx) {
        if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        }
        return visitIdentifier(ctx.identifier());
    }

    public Object visitTerm(QueryLangParser.TermContext ctx, String currentScopeAlias) {
        if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        } else if (ctx.variable() != null) {
            String plainVarName = (String) visit(ctx.variable());
            String qualifiedName = currentScopeAlias + "." + plainVarName;
            logger.debug("Registering consumer: {} type: ANY for Term Value", qualifiedName);
            variableRegistry.registerConsumer(qualifiedName, VariableType.ANY, "Term Value");
            // Return the plain variable name for the condition model
            return plainVarName;
        } else if (ctx.identifier() != null) {
             // Identifiers as terms are treated as literal strings? Or index lookups? Assume literal for now.
             return visitIdentifier(ctx.identifier());
        }
        throw new IllegalStateException("visitTerm called on unexpected context type");
    }

    public Query buildSubquery(ParseTree tree) {
        // Create a new QueryModelBuilder for the subquery to isolate variable bindings
        QueryModelBuilder subqueryBuilder = new QueryModelBuilder();
        return (Query) subqueryBuilder.visit(tree);
    }

    @Override
    public Object visitSubquery(QueryLangParser.SubqueryContext ctx) {
        // Get the source from the first identifier in the list
        String source = ctx.identifier(0).getText();

        // --- Create a NEW QueryModelBuilder for the subquery ---
        // This isolates the variable registry and scope for the subquery.
        QueryModelBuilder subqueryBuilder = new QueryModelBuilder();

        // Get the alias using ALIAS keyword - this is the alias for the subquery scope
        String subqueryAlias = ctx.alias.getText(); // Alias is mandatory based on grammar change

        logger.debug("Creating subquery with alias: {}", subqueryAlias);

        // Determine if qualification is required WITHIN the subquery
        // A subquery, as per the current grammar rule, cannot have its own JOIN clauses.
        // Therefore, internal qualification driven by joins is not applicable here.
        boolean subqueryInternalQualificationRequired = false;

        // Visit the subquery's select list
        List<SelectColumn> selectColumns = subqueryBuilder.visitSelectList(ctx.selectList(), subqueryInternalQualificationRequired);

        List<Condition> conditions = new ArrayList<>();
        if (ctx.whereClause() != null) {
            // Visit the subquery's WHERE clause using subquery's effective alias
            // For subquery internal conditions, the alias is its own alias.
            conditions.addAll(subqueryBuilder.visitConditionList(ctx.whereClause().conditionList(), subqueryAlias));
        }

        // Process additional clauses
        List<String> orderColumns = new ArrayList<>();
        if (ctx.orderByClause() != null) {
            orderColumns.addAll(subqueryBuilder.visitOrderByClause(ctx.orderByClause(), subqueryInternalQualificationRequired));
        }

        Optional<Integer> limit = Optional.empty();
        if (ctx.limitClause() != null) {
            limit = Optional.of(Integer.parseInt(ctx.limitClause().count.getText()));
        }

        Query.Granularity granularity = Query.Granularity.DOCUMENT;
        Optional<Integer> granularitySize = Optional.empty();
        if (ctx.granularityClause() != null) {
            if (ctx.granularityClause().DOCUMENT() != null) {
                granularity = Query.Granularity.DOCUMENT;
            } else {
                granularity = Query.Granularity.SENTENCE;
                if (ctx.granularityClause().size != null) {
                    granularitySize = Optional.of(Integer.parseInt(ctx.granularityClause().size.getText()));
                }
            }
        }

        List<String> groupByColumns = new ArrayList<>();
        if (ctx.groupByClause() != null) {
            groupByColumns.addAll(subqueryBuilder.visitGroupByClause(ctx.groupByClause(), subqueryAlias, subqueryInternalQualificationRequired));
        }

        // Important: Re-qualify variables bound within this subquery to use its *external* alias.
        // E.g., if subquery is `(SELECT NER(PERSON) BIND p FROM docs) ALIAS q1`,
        // internally `p` might be `$main.p` during subqueryBuilder's processing (if no ALIAS on docs).
        // It needs to become `q1.p` in the subqueryBuilder.variableRegistry that is part of the returned Query object.
        subqueryBuilder.variableRegistry.requalifyVariables(DEFAULT_MAIN_ALIAS + ".", subqueryAlias + ".");

        // Re-qualify conditions to update their variable names as well
        List<Condition> requalifiedConditions = conditions.stream()
            .map(condition -> requalifyCondition(condition, DEFAULT_MAIN_ALIAS + ".", subqueryAlias + "."))
            .collect(java.util.stream.Collectors.toList());

        // Re-qualify ORDER BY and GROUP BY columns
        List<String> requalifiedOrderColumns = orderColumns.stream()
            .map(col -> {
                String baseCol = col.startsWith("-") ? col.substring(1) : col;
                String requalifiedBase = requalifyColumnName(baseCol, DEFAULT_MAIN_ALIAS + ".", subqueryAlias + ".");
                return col.startsWith("-") ? "-" + requalifiedBase : requalifiedBase;
            })
            .collect(java.util.stream.Collectors.toList());

        List<String> requalifiedGroupByColumns = groupByColumns.stream()
            .map(col -> requalifyColumnName(col, DEFAULT_MAIN_ALIAS + ".", subqueryAlias + "."))
            .collect(java.util.stream.Collectors.toList());

        // Also update select columns to use the new qualified names
        List<SelectColumn> requalifiedSelectColumns = selectColumns.stream()
            .map(col -> {
                if (col instanceof VariableColumn varCol) {
                    String oldName = varCol.getColumnName(); // e.g., $main.p or an already qualified name if subquery had ALIAS
                    String plainName = VariableQualifier.extractPlainName(oldName);
                    String targetQualifiedName = subqueryAlias + "." + plainName; // e.g., q1.p
                    return new VariableColumn(targetQualifiedName);
                } else if (col instanceof SnippetColumn snipCol) {
                    String oldName = snipCol.getVariableName();
                    String plainName = VariableQualifier.extractPlainName(oldName);
                    String targetQualifiedName = subqueryAlias + "." + plainName;
                    return new SnippetColumn(targetQualifiedName, snipCol.getWindowSize());
                } else if (col instanceof StructuralColumn structCol) {
                    // If structural column refers to $main (e.g. $main.TITLE from subquery `(SELECT TITLE FROM docs) ALIAS q1`)
                    // it needs to be requalified to use the subquery's alias (e.g. q1.TITLE)
                    if (DEFAULT_MAIN_ALIAS.equals(structCol.getAlias())) {
                         return new StructuralColumn(subqueryAlias, structCol.getFieldName());
                    }
                    // If it's already qualified with some other alias (e.g. from a nested sub-subquery), leave it.
                }
                return col; // Return other column types (CountColumn, already correctly qualified StructuralColumn) unchanged
            })
            .collect(java.util.stream.Collectors.toList());

        // Ensure selected structural columns are marked as produced in the subquery's registry
        for (SelectColumn sc : requalifiedSelectColumns) {
            if (sc instanceof StructuralColumn structuralCol) {
                // The getColumnName() here will be the alias-qualified name like "q2.DOCUMENT_ID"
                String qualifiedStructuralName = structuralCol.getColumnName();
                // Check if it's already registered as a producer (e.g. if it was also bound via BIND)
                // Though structural columns typically aren't bound with BIND.
                if (!subqueryBuilder.variableRegistry.isProduced(qualifiedStructuralName)) {
                    subqueryBuilder.variableRegistry.registerProducer(
                        qualifiedStructuralName,
                        VariableType.ANY, // Structural columns don't have a specific BIND type here
                        "SUBQUERY_SELECT_STRUCTURAL"
                    );
                    logger.debug("Registered selected structural column '{}' as producer in subquery '{}' registry.",
                                 qualifiedStructuralName, subqueryAlias);
                }
            } else if (sc instanceof VariableColumn vc) {
                // Variables selected (e.g. q2.party) must have been produced by a BIND clause
                // within the subquery. The subqueryBuilder already handled their registration
                // as producers during its internal processing of BIND.
                // The requalifyVariables call on the registry handles their name changes (e.g. $main.party to q2.party).
                // We must ensure they are indeed marked as produced in the subquery registry.
                String qualifiedVariableName = vc.getColumnName();
                if (!subqueryBuilder.variableRegistry.isProduced(qualifiedVariableName)) {
                    // This case should ideally not happen if BIND clauses are correctly processed and registered.
                    // However, adding a safeguard or at least a warning.
                    logger.warn("Variable '{}' selected from subquery '{}' was not marked as produced. This might indicate an issue with BIND processing in the subquery.", qualifiedVariableName, subqueryAlias);
                    // Optionally, could register it here, but it implies a potential flaw elsewhere.
                    // For now, relying on BIND to have registered it.
                }
            }
        }

        // Create the subquery Query object
        Query subqueryAst = new Query(
            source,
            requalifiedConditions,
            requalifiedOrderColumns,
            limit,
            granularity,
            granularitySize,
            requalifiedSelectColumns,
            subqueryBuilder.variableRegistry, // Use the isolated registry (now requalified)
            List.of(), // Subqueries within a subquery are not part of this model change for JoinStep
            Optional.empty(), // Subquery itself has an EXPLICIT alias, not a mainAlias in the Query record sense
            requalifiedGroupByColumns
        );

        // Return the SubquerySpec containing the Query object and its external alias
        return new SubquerySpec(subqueryAst, subqueryAlias);
    }

    // Overload visitJoinClause to accept qualification requirement
    public Object[] visitJoinClause(QueryLangParser.JoinClauseContext ctx, boolean qualificationRequired) {
        // Visit the subquery first (its internal builder handles its scope)
        SubquerySpec subquery = (SubquerySpec) visit(ctx.subquery());

        // Visit the join condition, passing the flag
        JoinCondition joinCondition = visitJoinCondition(ctx.joinCondition(), qualificationRequired);

        // Get the join type - defaults to INNER if not specified
        JoinCondition.JoinType joinType = JoinCondition.JoinType.INNER;
        if (ctx.joinType() != null) {
            if (ctx.joinType().LEFT() != null) {
                joinType = JoinCondition.JoinType.LEFT;
            } else if (ctx.joinType().RIGHT() != null) {
                joinType = JoinCondition.JoinType.RIGHT;
            }
        }

        // Update the join condition with the correct join type
        Optional<Integer> proximityWindow = joinCondition.proximityWindow();
        if (joinCondition.operatorType() != JoinCondition.JoinOperatorType.TEMPORAL ||
            joinCondition.temporalPredicate().isEmpty() ||
            joinCondition.temporalPredicate().get() != TemporalPredicate.PROXIMITY) {
            proximityWindow = Optional.empty();
        }
        joinCondition = new JoinCondition(
            joinCondition.leftColumn(),       // Keep original
            joinCondition.rightColumn(),      // Keep original
            joinType,                         // Use the determined type (INNER/LEFT/RIGHT)
            joinCondition.operatorType(),     // Keep original
            joinCondition.temporalPredicate(), // Keep original
            proximityWindow                    // Only keep if valid
        );

        // Return both the subquery and the updated join condition
        return new Object[] { subquery, joinCondition };
    }

    // Overload visitJoinCondition to accept qualification requirement
    private JoinCondition visitJoinCondition(QueryLangParser.JoinConditionContext ctx, boolean qualificationRequired) {
        if (ctx instanceof QueryLangParser.TemporalJoinConditionContext tjcc) {
            return visitTemporalJoinCondition(tjcc, qualificationRequired);
        } else if (ctx instanceof QueryLangParser.EqualityJoinConditionContext ejcc) {
            return visitEqualityJoinCondition(ejcc, qualificationRequired);
        } else {
            throw new IllegalStateException("Unknown JoinConditionContext type: " + ctx.getClass().getName());
        }
    }

    // Visit Temporal Join Condition
    private JoinCondition visitTemporalJoinCondition(QueryLangParser.TemporalJoinConditionContext ctx, boolean qualificationRequired) {
        // Visit the left and right columns, passing the flag
        String leftColumn = visitJoinColumn(ctx.leftColumn, qualificationRequired);
        String rightColumn = visitJoinColumn(ctx.rightColumn, qualificationRequired);

        // Get the temporal operator
        TemporalPredicate temporalPredicate = mapOperatorToTemporal(ctx.op.getText()); // Use labeled op

        // Check if there's a window specification
        Optional<Integer> proximityWindow = Optional.empty();
        if (ctx.window != null) {
            proximityWindow = Optional.of(Integer.parseInt(ctx.window.getText()));
        }

        // Create and return the TEMPORAL join condition
        return new JoinCondition(
            leftColumn, // Now potentially qualified
            rightColumn, // Now potentially qualified
            JoinCondition.JoinType.INNER,       // Default type, updated later
            JoinCondition.JoinOperatorType.TEMPORAL, // Explicitly TEMPORAL
            Optional.of(temporalPredicate),     // The actual predicate
            proximityWindow                     // The proximity window
        );
    }

    // Visit Equality Join Condition
    private JoinCondition visitEqualityJoinCondition(QueryLangParser.EqualityJoinConditionContext ctx, boolean qualificationRequired) {
        // Visit the left and right columns, passing the flag
        String leftColumn = visitJoinColumn(ctx.leftColumn, qualificationRequired);
        String rightColumn = visitJoinColumn(ctx.rightColumn, qualificationRequired);

        // Create and return the EQUALITY join condition
        return JoinCondition.createEqualityJoin(leftColumn, rightColumn, JoinCondition.JoinType.INNER); // Default type
    }

    // Overload visitJoinColumn to accept qualification requirement
    public String visitJoinColumn(QueryLangParser.JoinColumnContext ctx, boolean qualificationRequired) {
        if (ctx.qualifiedIdentifier() != null) {
            // Visit the qualifiedIdentifier, which returns StructuralColumn or VariableColumn
            Object qualifiedResult = visitQualifiedIdentifier(ctx.qualifiedIdentifier());

            if (qualifiedResult instanceof VariableColumn vc) {
                // Return the qualified name (e.g., alias.variable) for the JoinCondition
                return vc.getColumnName();
            } else if (qualifiedResult instanceof com.example.query.model.StructuralColumn sc) {
                // Return the qualified name (e.g., alias.FIELD) for the JoinCondition
                return sc.getColumnName();
            } else {
                throw new IllegalStateException("Unexpected result type from visitQualifiedIdentifier for join column: " + qualifiedResult.getClass().getName());
            }
        } else if (ctx.variable() != null) {
            String variableName = (String) visit(ctx.variable());
             if (qualificationRequired) {
                 throw new IllegalStateException(
                    String.format("Unqualified variable '%s' used in JOIN ON where qualification is required. Use 'alias.%s'.",
                                  variableName, variableName)
                    // TODO: Add line/pos info
                 );
             }
             // If not required, implicitly qualify with main alias
            return DEFAULT_MAIN_ALIAS + "." + variableName;
        }
        throw new IllegalStateException("Invalid join column type");
    }

    public Object visitDateLiteralComparisonExpression(QueryLangParser.DateLiteralComparisonExpressionContext ctx, String currentScopeAlias) {
        String operator = ctx.comparisonOp().getText();
        String dateText = ctx.date.getText();

        // Parse the date literal which acts as the comparison point
        LocalDateTime parsedStartDate = parseDateLiteral(dateText); // This gives the start of the period

        // Determine the correct TemporalPredicate based on the operator
        TemporalPredicate temporalType = mapComparisonOpToPredicate(operator);

        Optional<LocalDateTime> effectiveEndDate = Optional.empty();
        if (temporalType == TemporalPredicate.EQUAL) {
            // For EQUAL, determine the end of the range based on original text precision
            if (isYearOnly(dateText)) {
                // Year only: "2000" means from Jan 1, 2000 00:00:00 to Dec 31, 2000 23:59:59
                LocalDate yearEnd = LocalDate.of(parsedStartDate.getYear(), 12, 31);
                effectiveEndDate = Optional.of(LocalDateTime.of(yearEnd, java.time.LocalTime.MAX));
            } else if (isYearMonth(dateText)) {
                // Year-month: "2000-01" means from Jan 1, 2000 00:00:00 to Jan 31, 2000 23:59:59
                LocalDate monthEnd = getLastDayOfMonth(parsedStartDate.getYear(), parsedStartDate.getMonthValue());
                effectiveEndDate = Optional.of(LocalDateTime.of(monthEnd, java.time.LocalTime.MAX));
            } else {
                // Full date: "2000-01-01" means the full day
                effectiveEndDate = Optional.of(LocalDateTime.of(parsedStartDate.toLocalDate(), java.time.LocalTime.MAX));
            }
        }

        String qualifiedVariableName = null;
        if (ctx.BIND() != null && ctx.var != null) {
            String plainVarName = (String) visit(ctx.var);
            qualifiedVariableName = currentScopeAlias + "." + plainVarName; // Qualify
            logger.debug("Registering producer: {} type: TEMPORAL for TEMPORAL literal comparison", qualifiedVariableName);
            variableRegistry.registerProducer(qualifiedVariableName, VariableType.TEMPORAL, "TEMPORAL");
        } else {
            // If BIND is not present, the condition applies to the documents matched by the current scope.
            // We still need a way to tell the executor *which* date field to compare.
            // Assuming an implicit 'date' field for the scope.
            // This might need to be made more explicit or configurable.
            logger.warn("DATE literal comparison without explicit BIND clause. Assuming comparison against implicit 'date' field of scope '{}'.", currentScopeAlias);
            qualifiedVariableName = currentScopeAlias + ".date"; // Implicit target field
             // No producer registration needed if it's an implicit field comparison
        }

        // Create Temporal model with the specific predicate and the literal date in startDate
        // For EQUAL, effectiveEndDate will now be populated. For others, it remains empty.
        return new Temporal(
            Optional.of(parsedStartDate),
            effectiveEndDate, // Pass the calculated endDate for EQUAL, or empty for others
            Optional.ofNullable(qualifiedVariableName), // The (potentially implicit) variable/field being compared
            Optional.empty(), // Range not applicable here
            temporalType // Use the specific predicate (BEFORE, AFTER, etc.)
        );
    }

    @Override
    public Object visitDateLiteralRange(QueryLangParser.DateLiteralRangeContext ctx) {
        LocalDateTime startDate = parseDateLiteral(ctx.start.getText());
        LocalDateTime endDate;

        if (ctx.end != null) {
            // If end date is provided, use it
            endDate = parseDateLiteral(ctx.end.getText());
            // Adjust end date to end of day/month/year
            String endDateText = ctx.end.getText();
            if (isYearOnly(endDateText)) {
                // Year only: "2000" means up to Dec 31, 2000 23:59:59
                LocalDate yearEnd = LocalDate.of(Integer.parseInt(endDateText), 12, 31);
                endDate = LocalDateTime.of(yearEnd, java.time.LocalTime.MAX);
            } else if (isYearMonth(endDateText)) {
                // Year-month: "2000-01" means up to Jan 31, 2000 23:59:59
                String[] parts = endDateText.split("-");
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                LocalDate monthEnd = getLastDayOfMonth(year, month);
                endDate = LocalDateTime.of(monthEnd, java.time.LocalTime.MAX);
            } else {
                // Full date: "2000-01-01" means up to 2000-01-01 23:59:59
                endDate = LocalDateTime.of(endDate.toLocalDate(), java.time.LocalTime.MAX);
            }
        } else {
            // If no end date, adjust start date based on its format
            String startDateText = ctx.start.getText();
            if (isYearOnly(startDateText)) {
                // Year only: "2000" means from Jan 1, 2000 00:00:00 to Dec 31, 2000 23:59:59
                LocalDate yearEnd = LocalDate.of(Integer.parseInt(startDateText), 12, 31);
                endDate = LocalDateTime.of(yearEnd, java.time.LocalTime.MAX);
            } else if (isYearMonth(startDateText)) {
                // Year-month: "2000-01" means from Jan 1, 2000 00:00:00 to Jan 31, 2000 23:59:59
                String[] parts = startDateText.split("-");
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                LocalDate monthEnd = getLastDayOfMonth(year, month);
                endDate = LocalDateTime.of(monthEnd, java.time.LocalTime.MAX);
            } else {
                // Full date: "2000-01-01" means the full day
                endDate = LocalDateTime.of(startDate.toLocalDate(), java.time.LocalTime.MAX);
            }
        }

        return new LocalDateTime[] { startDate, endDate };
    }

    @Override
    public Object visitDateSingleOrPair(QueryLangParser.DateSingleOrPairContext ctx) {
        String startDateText = ctx.start.getText();
        LocalDateTime startDateResult = parseDateLiteral(startDateText);

        if (ctx.end != null) {
            String endDateText = ctx.end.getText();
            LocalDateTime endDateResult = parseDateLiteral(endDateText);

            // Adjust endDateResult to the end of its period (day, month, year)
            // similar to logic in visitDateLiteralRange
            if (isYearOnly(endDateText)) {
                endDateResult = LocalDateTime.of(endDateResult.getYear(), 12, 31, 23, 59, 59);
            } else if (isYearMonth(endDateText)) {
                java.time.YearMonth ym = java.time.YearMonth.of(endDateResult.getYear(), endDateResult.getMonth());
                endDateResult = LocalDateTime.of(endDateResult.getYear(), endDateResult.getMonth(), ym.lengthOfMonth(), 23, 59, 59);
            } else { // Full date
                endDateResult = LocalDateTime.of(endDateResult.toLocalDate(), java.time.LocalTime.MAX);
            }
            return new LocalDateTime[] { startDateResult, endDateResult };
        } else {
            // If only start is present, return the single LocalDateTime.
            // This aligns with how visitSingleYear returns a LocalDateTime,
            // and how parseDateLiteral handles year-only strings.
            return startDateResult;
        }
    }

    // Helper methods for date literal handling

    /**
     * Parse a date literal in the format YYYY, YYYY-MM, or YYYY-MM-DD
     */
    private LocalDateTime parseDateLiteral(String dateLiteral) {
        if (isYearOnly(dateLiteral)) {
            // Year only: "2000"
            return LocalDateTime.of(Integer.parseInt(dateLiteral), 1, 1, 0, 0);
        } else if (isYearMonth(dateLiteral)) {
            // Year-month: "2000-01"
            String[] parts = dateLiteral.split("-");
            return LocalDateTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1, 0, 0);
        } else {
            // Full date: "2000-01-01"
            String[] parts = dateLiteral.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            return LocalDateTime.of(year, month, day, 0, 0);
        }
    }

    /**
     * Check if the date literal is year only format (YYYY)
     */
    private boolean isYearOnly(String dateLiteral) {
        return dateLiteral.matches("\\d{4}");
    }

    /**
     * Check if the date literal is year-month format (YYYY-MM)
     */
    private boolean isYearMonth(String dateLiteral) {
        return dateLiteral.matches("\\d{4}-\\d{1,2}");
    }

    /**
     * Get the last day of a given month in a given year
     */
    private LocalDate getLastDayOfMonth(int year, int month) {
        return LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
    }

    // Helper record to distinguish Variable references in ambiguous contexts (like DEPENDS)
    private record VariableReference(String plainName, String qualifiedName) {}

    /**
     * Helper method to requalify a single condition based on its type.
     * This updates variable names in conditions during subquery processing.
     */
    private static Condition requalifyCondition(Condition condition, String oldPrefix, String newPrefix) {
        return switch (condition) {
            case Temporal temporal -> temporal.requalifyVariable(oldPrefix, newPrefix);
            case Ner ner -> ner.requalifyVariable(oldPrefix, newPrefix);
            case Pos pos -> pos.requalifyVariable(oldPrefix, newPrefix);
            case Contains contains -> contains.requalifyVariable(oldPrefix, newPrefix);
            case Dependency dependency -> dependency.requalifyVariable(oldPrefix, newPrefix);
            case Logical logical -> logical.requalifyVariables(oldPrefix, newPrefix);
            case Not not -> not.requalifyVariables(oldPrefix, newPrefix);
            case StitchedCondition stitched -> stitched.requalifyVariables(oldPrefix, newPrefix);
            default -> throw new IllegalArgumentException("Unsupported condition type: " + condition.getClass().getSimpleName());
        };
    }

    /**
     * Helper method to requalify column names (used for ORDER BY and GROUP BY in subqueries)
     */
    private static String requalifyColumnName(String columnName, String oldPrefix, String newPrefix) {
        if (columnName.startsWith(oldPrefix)) {
            return columnName.replace(oldPrefix, newPrefix);
        }
        return columnName;
    }

    // Helper method to map comparison operator string to TemporalPredicate
    private TemporalPredicate mapComparisonOpToPredicate(String operator) {
        return switch (operator) {
            case "<" -> TemporalPredicate.BEFORE;
            case ">" -> TemporalPredicate.AFTER;
            case "=" -> TemporalPredicate.EQUAL;
            case "==" -> TemporalPredicate.EQUAL;
            case "<=" -> TemporalPredicate.BEFORE_EQUAL;
            case ">=" -> TemporalPredicate.AFTER_EQUAL;
            default -> throw new IllegalArgumentException("Unsupported comparison operator for DATE: " + operator);
        };
    }

    public List<String> visitGroupByClause(QueryLangParser.GroupByClauseContext ctx, String effectiveMainAlias, boolean qualificationRequired) {
        if (ctx.groupByItemList() != null) {
            return visitGroupByItemList(ctx.groupByItemList(), effectiveMainAlias, qualificationRequired);
        }
        return List.of();
    }

    public List<String> visitGroupByItemList(QueryLangParser.GroupByItemListContext ctx, String effectiveMainAlias, boolean qualificationRequired) {
        List<String> items = new ArrayList<>();
        for (QueryLangParser.GroupByItemContext itemCtx : ctx.groupByItem()) {
            items.add(visitGroupByItem(itemCtx, effectiveMainAlias, qualificationRequired));
        }
        return items;
    }

    public String visitGroupByItem(QueryLangParser.GroupByItemContext ctx, String effectiveMainAlias, boolean qualificationRequired) {
        String groupByColumnName;

        if (ctx.qualifiedIdentifier() != null) {
            Object colObj = visitQualifiedIdentifier(ctx.qualifiedIdentifier());
            if (colObj instanceof StructuralColumn sc) {
                groupByColumnName = sc.getColumnName();
                // Register this structural column as "produced" for validation purposes
                variableRegistry.registerProducer(groupByColumnName, VariableType.ANY, "GROUP_BY_STRUCTURAL_QUALIFIED");
                logger.debug("Registered qualified structural GROUP BY item '{}' as producer with type ANY.", groupByColumnName);
            } else if (colObj instanceof VariableColumn vc) {
                groupByColumnName = vc.getColumnName();
                // This variable (e.g., alias.var) should already be a producer from its BIND clause or subquery.
                // If it's not, the existing validation (which we are trying to satisfy) would catch it.
            } else {
                throw new IllegalStateException("Expected StructuralColumn or VariableColumn from visitQualifiedIdentifier in GROUP BY, got: " + (colObj != null ? colObj.getClass().getName() : "null"));
            }
        } else if (ctx.TITLE() != null || ctx.TIMESTAMP() != null || ctx.DOCUMENT_ID() != null || ctx.SENTENCE_ID() != null || ctx.BEGIN() != null || ctx.END() != null) {
            String tokenText = ctx.getText();
            String upperName = tokenText.toUpperCase();
            if (qualificationRequired) {
                throw new IllegalStateException(String.format(
                    "Unqualified structural column '%s' in GROUP BY; '%s' needs qualification as 'alias.%s'.",
                    tokenText, tokenText, upperName
                ));
            }
            groupByColumnName = effectiveMainAlias + "." + upperName;
            variableRegistry.registerProducer(groupByColumnName, VariableType.ANY, "GROUP_BY_STRUCTURAL_UNQUALIFIED_TOKEN");
            logger.debug("Registered unqualified structural GROUP BY item '{}' as producer with type ANY.", groupByColumnName);
        } else if (ctx.variable() != null) {
            String varName = ctx.variable().getText(); // This is a plain identifier by grammar
            if (qualificationRequired) {
                // If qualification is required, a plain variable here is an error.
                // It should have been expressed as alias.var and parsed as qualifiedIdentifier.
                throw new IllegalStateException(
                    String.format("Unqualified variable '%s' used in GROUP BY where qualification (e.g., 'alias.%s') is required.",
                                  varName, varName)
                );
            }
            // If qualification not required, implicitly qualify with effectiveMainAlias.
            groupByColumnName = effectiveMainAlias + "." + varName;
            // This variable (e.g., $main.myVar) must be a producer from a BIND.
            // The existing semantic validation for variables will check this.
        } else if (ctx.identifier() != null) {
            String identifierName = ctx.identifier().getText(); // Plain IDENTIFIER
            String upperIdName = identifierName.toUpperCase();
            final Set<String> structuralKeywords = Set.of("TITLE", "TIMESTAMP", "DOCUMENT_ID", "SENTENCE_ID", "BEGIN", "END");

            if (structuralKeywords.contains(upperIdName)) { // Is it a structural keyword?
                 if (qualificationRequired) {
                    // If it's a structural keyword used without an alias, and an alias is required.
                    throw new IllegalStateException(
                        String.format("Unqualified structural keyword '%s' used in GROUP BY where qualification (e.g., 'alias.%s') is required.",
                                      identifierName, identifierName)
                    );
                }
                // If qualification not required, implicitly qualify with effectiveMainAlias.
                groupByColumnName = effectiveMainAlias + "." + upperIdName;
                variableRegistry.registerProducer(groupByColumnName, VariableType.ANY, "GROUP_BY_STRUCTURAL_UNQUALIFIED");
                logger.debug("Registered unqualified structural GROUP BY item '{}' (from identifier) as producer with type ANY.", groupByColumnName);
            } else { // Not a structural keyword, so it's a variable name.
                if (qualificationRequired) {
                    // An identifier that's not a structural keyword, used without an alias, where an alias is required.
                    throw new IllegalStateException(
                        String.format("Unqualified variable '%s' (from identifier) used in GROUP BY where qualification (e.g., 'alias.%s') is required.",
                                      identifierName, identifierName)
                    );
                }
                // If qualification not required, implicitly qualify with effectiveMainAlias.
                groupByColumnName = effectiveMainAlias + "." + identifierName;
                // This variable (e.g., $main.myVar) must be a producer. Validation will check.
            }
        } else {
            throw new IllegalStateException("Unknown groupByItem type: " + ctx.getText());
        }
        return groupByColumnName;
    }
}