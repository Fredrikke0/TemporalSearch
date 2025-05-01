package com.example.query.parser;

import com.example.query.model.*;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Dependency;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Logical.LogicalOperator;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.Temporal;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.TemporalRange;
import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;

import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Visitor implementation that builds a Query model from the parse tree.
 * Handles conversion from parse tree nodes to model objects.
 * Uses VariableRegistry to manage variable scopes and types.
 */
public class QueryModelBuilder extends QueryLangBaseVisitor<Object> {
    private static final Logger logger = LoggerFactory.getLogger(QueryModelBuilder.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_DATE;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final String DEFAULT_MAIN_ALIAS = "$main"; // Default alias for main query without ALIAS
    
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
            if (ctx.ALIAS() != null && ctx.alias != null) { // Changed AS() to ALIAS()
                explicitMainAlias = Optional.of(ctx.alias.getText());
            }
        }
        
        // Determine the effective alias for this scope
        String effectiveMainAlias = explicitMainAlias.orElse(DEFAULT_MAIN_ALIAS);
        
        List<Condition> conditions = new ArrayList<>();
        List<String> orderColumns = new ArrayList<>();
        Optional<Integer> limit = Optional.empty();
        Query.Granularity granularity = Query.Granularity.DOCUMENT;
        Optional<Integer> granularitySize = Optional.empty();
        List<SelectColumn> selectColumns = new ArrayList<>();
        List<SubquerySpec> subqueries = new ArrayList<>();
        Optional<JoinCondition> joinCondition = Optional.empty();

        // Process join clauses first to determine if qualification is needed early
        if (ctx.joinClause() != null && !ctx.joinClause().isEmpty()) {
            for (QueryLangParser.JoinClauseContext joinCtx : ctx.joinClause()) {
                 // Pass qualification requirement to visitJoinClause
                 // Qualification is required if there's an explicit main alias OR if there are joins
                boolean qualificationRequired = explicitMainAlias.isPresent() || !ctx.joinClause().isEmpty();
                Object[] joinResult = (Object[]) visitJoinClause(joinCtx, qualificationRequired); // Pass flag
                SubquerySpec subquery = (SubquerySpec) joinResult[0];
                JoinCondition jc = (JoinCondition) joinResult[1];
                
                subqueries.add(subquery);
                joinCondition = Optional.of(jc); // Use the last join condition
            }
        }

        // Determine if qualification is required in SELECT, ORDER BY
        boolean qualificationRequired = explicitMainAlias.isPresent() || !subqueries.isEmpty();

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
        
        // Updated Query constructor call - pass explicitMainAlias, not effectiveMainAlias
        // The Query object stores the user-provided alias, or empty if none.
        // Internal logic uses effectiveMainAlias ($main or explicit).
        return new Query(source, conditions, orderColumns, limit, granularity, granularitySize, selectColumns, variableRegistry, subqueries, joinCondition, explicitMainAlias);
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
        if (ctx instanceof QueryLangParser.VariableColumnContext vcc) {
            return visitVariableColumn(vcc, qualificationRequired);
        } else if (ctx.getChild(0) instanceof QueryLangParser.QualifiedIdentifierContext qic) { 
            // If the child is a QualifiedIdentifierContext, visit it
            return visitQualifiedIdentifier(qic); 
        } else if (ctx instanceof QueryLangParser.SnippetColumnContext scc) { 
            return visitSnippetColumn(scc, qualificationRequired);
        } else if (ctx instanceof QueryLangParser.StructColumnContext scc) {
            return visitStructColumn(scc);
        } else if (ctx instanceof QueryLangParser.UnqualifiedTitleColumnContext utcc) {
            return visitUnqualifiedTitleColumn(utcc);
        } else if (ctx instanceof QueryLangParser.UnqualifiedTimestampColumnContext utsc) {
            return visitUnqualifiedTimestampColumn(utsc);
        } else if (ctx instanceof QueryLangParser.CountColumnContext ccc) {
            return visitCountColumn(ccc, qualificationRequired); // Pass flag for COUNT(UNIQUE var)
        } else {
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
            // Otherwise, assume it's a variable reference
            logger.trace("Identified VariableColumn from qualifiedIdentifier: {}", fullQualifiedName);
            return new VariableColumn(fullQualifiedName);
        }
    }

    // Overload visitVariableColumn
    public Object visitVariableColumn(QueryLangParser.VariableColumnContext ctx, boolean qualificationRequired) {
        String variableName = (String) visit(ctx.variable()); // Gets plain name
        if (qualificationRequired) {
            throw new IllegalStateException(
                String.format("Unqualified variable '%s' used in SELECT where qualification is required (due to ALIAS or JOIN). Use 'alias.%s'.",
                              variableName, variableName)
                // TODO: Consider adding line/pos info to exception message
            );
        }
        // If qualification is not required, implicitly qualify with default alias
        String qualifiedName = DEFAULT_MAIN_ALIAS + "." + variableName;
        return new VariableColumn(qualifiedName);
    }
    
    // Overload visitSnippetColumn
    public Object visitSnippetColumn(QueryLangParser.SnippetColumnContext ctx, boolean qualificationRequired) {
         // Visit the expression first to get the SnippetNode (which contains the qualified name)
        SnippetNode snippetNode = (SnippetNode) visitSnippetExpression(ctx.snippetExpression(), qualificationRequired);
        
        // Extract windowSize and qualified variableName from the node
        int windowSize = snippetNode.windowSize(); 
        String qualifiedVariableName = snippetNode.variableName(); // Use variableName() getter
        
        // Use qualifiedVariableName and windowSize to create SnippetColumn
        // Assuming SnippetColumn constructor takes qualified name and window size
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
         } else if (ctx instanceof QueryLangParser.CountUniqueExpressionContext cuec) {
             return visitCountUniqueExpression(cuec, qualificationRequired); // Pass flag
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
            String variableName = (String) visit(ctx.variable());
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
            qualifiedTargetName = (String) visit(ctx.qualifiedIdentifier());
        } else {
             throw new IllegalStateException("Snippet expression target must be a variable or qualified identifier.");
        }

        int windowSize = SnippetNode.DEFAULT_WINDOW_SIZE;
        if (ctx.windowSize != null) { // Grammar rule name for window size is 'windowSize'
            windowSize = Integer.parseInt(ctx.windowSize.getText());
        }
        
        // Call the simplified SnippetNode constructor
        return new SnippetNode(qualifiedTargetName, windowSize);
    }
    
    
    @Override
    public Object visitCountAllExpression(QueryLangParser.CountAllExpressionContext ctx) {
        return CountColumn.countAll();
    }
    
    public Object visitCountUniqueExpression(QueryLangParser.CountUniqueExpressionContext ctx, boolean qualificationRequired) {
        // COUNT(UNIQUE var) always refers to the main query scope
        String variableName = (String) visit(ctx.variable());
        // Check if qualification is required *in the query context* (even though COUNT(UNIQUE) uses unqualified grammar)
        if (qualificationRequired) {
             // We could throw an error here, or implicitly qualify.
             // Let's implicitly qualify, assuming COUNT(UNIQUE var) always refers to the main scope's variable.
             // Validation should catch if 'var' doesn't exist in the main scope later.
             logger.warn("Unqualified variable '{}' used in COUNT(UNIQUE ...) when query requires qualification. Assuming reference to main scope.", variableName);
        }
        // Always qualify with default main alias for internal representation
        String qualifiedName = DEFAULT_MAIN_ALIAS + "." + variableName;
        // CountColumn needs the qualified name for validation consistency
        return CountColumn.countUnique(qualifiedName);
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
        Condition currentCondition = extractSingleCondition(firstResult, "first operand");
        
        // Process the logical operations
        for (int i = 0; i < ctx.logicalOp().size(); i++) {
            // Get the logical operator
            Logical.LogicalOperator operator = parseLogicalOperator(ctx.logicalOp(i).getText());
            
            // Get the right operand
            Object rightResult = visitCondition(ctx.condition(i + 1), currentScopeAlias);
            Condition rightCondition = extractSingleCondition(rightResult, "right operand");
            
            // Create a logical condition
            currentCondition = new Logical(operator, currentCondition, rightCondition);
        }
        
        return List.of(currentCondition);
    }
    
    // Helper to extract a single condition from the result of visiting a condition node
    private Condition extractSingleCondition(Object visitResult, String operandDescription) {
         if (visitResult instanceof Condition) {
            return (Condition) visitResult;
         } else if (visitResult instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Condition> conditions = (List<Condition>) visitResult;
            if (conditions.size() == 1) {
                return conditions.get(0);
            } else {
                 throw new IllegalStateException(String.format("Logical operator %s unexpectedly resolved to multiple conditions.", operandDescription)); // TODO: Add context
            }
         } else {
             throw new IllegalStateException(String.format("Logical operator %s resolved to unexpected type: %s", operandDescription, visitResult != null ? visitResult.getClass().getName() : "null")); // TODO: Add context
         }
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
        Condition conditionToNegate = extractSingleCondition(result, "operand of NOT");
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
            qualifiedVariableName = currentScopeAlias + "." + plainVarName; // Qualify
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
        if (ctx.BIND() != null && ctx.var != null) {
            String plainVarName = (String) visit(ctx.var);
            qualifiedVariableName = currentScopeAlias + "." + plainVarName; // Qualify
            isVariable = true; // Set flag
            VariableType varType = determineNerVariableType(type);
            logger.debug("Registering producer: {} type: {} for NER", qualifiedVariableName, varType);
            variableRegistry.registerProducer(qualifiedVariableName, varType, "NER");
        }
        
        String termValue = null;
        if (ctx.termValue != null) {
             Object termResult = visitTerm(ctx.termValue, currentScopeAlias); // Pass alias
             termValue = (String) termResult; // visitTerm now returns plain name if variable
             // Consumption registration happens within visitTerm
        }
        
        // Model updated to store qualified name
        return new Ner(type, termValue, qualifiedVariableName, isVariable); // Pass qualified name
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
        if (ctx.WILDCARD() != null) {
            return "*";
        }
        if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        }
        if (ctx.IDENTIFIER() != null) {
            return ctx.IDENTIFIER().getText();
        }
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
        
        throw new IllegalStateException("Invalid entity type: " + ctx.getText());
    }

    @Override
    public Object visitVariable(QueryLangParser.VariableContext ctx) {
        return ctx.IDENTIFIER().getText(); // Return the plain variable name
    }

    public Object visitDateComparisonExpression(QueryLangParser.DateComparisonExpressionContext ctx, String currentScopeAlias) {
        String operator = ctx.comparisonOp().getText();
        // Year parsing is no longer needed here as comparison logic is changing.
        // int year = Integer.parseInt(ctx.year.getText());

        // Determine the correct TemporalPredicate based on the operator
        TemporalPredicate temporalType = mapComparisonOpToPredicate(operator);

        // The date value comes from the variable being compared against,
        // so startDate and endDate are not set here in the model.
        // The variable name itself is stored.

        String qualifiedVariableName = null;
        if (ctx.BIND() != null && ctx.var != null) {
            String plainVarName = (String) visit(ctx.var);
            qualifiedVariableName = currentScopeAlias + "." + plainVarName; // Qualify
            logger.debug("Registering producer: {} type: TEMPORAL for TEMPORAL comparison", qualifiedVariableName);
            // Registering as TEMPORAL, executor needs to handle variable resolution
            variableRegistry.registerProducer(qualifiedVariableName, VariableType.TEMPORAL, "TEMPORAL");
        } else {
            // TODO: Handle the case where the comparison target itself might be a variable - Needs grammar change?
            // For now, assume the comparison is against a property of the current alias scope, e.g., q1.date < 2024
            // If we need to compare q1.date < q2.another_date, the grammar needs adjustment.
            // Assuming implicit target is 'date' field of currentScopeAlias if BIND is absent.
            // This part might need refinement depending on exact semantics desired.
             logger.warn("DATE comparison without explicit BIND clause. Assuming comparison against implicit 'date' field.");
             // qualifiedVariableName = currentScopeAlias + ".date"; // Implicit target - Reconsider if this is correct.
             // For now, let's require BIND for clarity.
             throw new UnsupportedOperationException("DATE comparison requires an explicit BIND clause specifying the date variable.");
        }

        // Create Temporal model with the specific predicate and variable name
        return new Temporal(
            Optional.empty(), // Start date comes from variable resolution later
            Optional.empty(), // End date is not used for simple comparison predicates
            Optional.ofNullable(qualifiedVariableName), // The variable being compared
            Optional.empty(), // Range not applicable here
            temporalType // Use the specific predicate (BEFORE, AFTER, etc.)
        );
    }

    public Object visitDateOperatorExpression(QueryLangParser.DateOperatorExpressionContext ctx, String currentScopeAlias) {
        String operator = ctx.dateOperator().getText();
        TemporalPredicate type = mapOperatorToTemporal(operator); // Corrected variable name: 'type' not 'dependencyType'
        
        System.out.println("DEBUG: DateOperatorExpression with operator: " + operator);
        System.out.println("DEBUG: DateValue context: " + ctx.dateValue().getText());
        
        // Directly visit the dateValue node instead of using visitChildren
        Object dateValue = visit(ctx.dateValue());
        System.out.println("DEBUG: Date value type: " + (dateValue != null ? dateValue.getClass().getName() : "null"));
        System.out.println("DEBUG: Date value: " + dateValue);
        
        LocalDateTime startDate;
        Optional<LocalDateTime> endDate = Optional.empty();
        
        if (dateValue instanceof Integer year) {
            System.out.println("DEBUG: Handling as Integer year: " + year);
            startDate = LocalDateTime.of(year, 1, 1, 0, 0);
        } else if (dateValue instanceof LocalDateTime[] dateRange) {
            System.out.println("DEBUG: Handling as LocalDateTime[] with length: " + dateRange.length);
            // Handle date range as array of LocalDateTime [start, end]
            startDate = dateRange[0];
            endDate = Optional.of(dateRange[1]);
        } else {
            System.out.println("DEBUG: Handling as single date");
            // Assume it's a single date
            startDate = (LocalDateTime) dateValue;
        }
        
        Optional<TemporalRange> range = Optional.empty();
        if (ctx.radius != null && ctx.unit != null) {
            int radius = Integer.parseInt(ctx.radius.getText());
            String unit = ctx.unit.getText();
            range = Optional.of(new TemporalRange(radius + unit));
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
        System.out.println("DEBUG: visitDateRange with text: " + ctx.getText());
        int startYear = Integer.parseInt(ctx.start.getText());
        int endYear = Integer.parseInt(ctx.end.getText());
        System.out.println("DEBUG: DateRange with startYear=" + startYear + ", endYear=" + endYear);
        LocalDateTime startDate = LocalDateTime.of(startYear, 1, 1, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(endYear, 12, 31, 23, 59, 59);
        LocalDateTime[] result = new LocalDateTime[] { startDate, endDate };
        System.out.println("DEBUG: Returning LocalDateTime[] with values: " + result[0] + ", " + result[1]);
        return result;
    }
    
    @Override
    public Object visitSingleYear(QueryLangParser.SingleYearContext ctx) {
        System.out.println("DEBUG: visitSingleYear with text: " + ctx.getText());
        int year = Integer.parseInt(ctx.single.getText());
        System.out.println("DEBUG: SingleYear with year=" + year);
        LocalDateTime result = LocalDateTime.of(year, 1, 1, 0, 0);
        System.out.println("DEBUG: Returning LocalDateTime: " + result);
        return result;
    }

    private Temporal.ComparisonType mapComparisonOp(String operator) {
        return switch (operator) {
            case "<" -> Temporal.ComparisonType.LT;
            case ">" -> Temporal.ComparisonType.GT;
            case "<=" -> Temporal.ComparisonType.LE;
            case ">=" -> Temporal.ComparisonType.GE;
            case "==" -> Temporal.ComparisonType.EQ;
            default -> throw new IllegalStateException("Invalid comparison operator: " + operator);
        };
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
            default -> throw new IllegalStateException("Invalid temporal operator: " + operator);
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
        String qualifiedFieldName; // Holds the final name, e.g., $main.var, alias.var, alias.TITLE
        
        // Determine the base field name (plain or qualified)
        if (ctx.variable() != null) {
            String variableName = (String) visit(ctx.variable());
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
            // Only treat TITLE, TIMESTAMP, DOCUMENT_ID, SENTENCE_ID as structural fields
            String id = ctx.identifier().getText();
            if (Set.of("TITLE", "TIMESTAMP", "DOCUMENT_ID", "SENTENCE_ID").contains(id.toUpperCase())) {
                if (qualificationRequired) {
                    throw new IllegalStateException(
                        String.format("Unqualified identifier '%s' used in ORDER BY where qualification is required. Use 'alias.%s' or select a bound variable.",
                                       id, id));
                }
                qualifiedFieldName = DEFAULT_MAIN_ALIAS + "." + id.toUpperCase();
            } else {
                // Treat as variable binding (e.g., $main.date)
                qualifiedFieldName = DEFAULT_MAIN_ALIAS + "." + id;
            }
        }
        else {
            throw new IllegalStateException("OrderSpec must have identifier, variable or qualifiedIdentifier");
        }
        
        // Return the qualified field name, prefixed with "-" if DESC
        if (ctx.DESC() != null) {
            return "-" + qualifiedFieldName;
        }
        return qualifiedFieldName;
    }

    // Overload visitOrderByClause
    public List<String> visitOrderByClause(QueryLangParser.OrderByClauseContext ctx, boolean qualificationRequired) {
        List<String> orderColumns = new ArrayList<>();
         for (QueryLangParser.OrderSpecContext specCtx : ctx.orderSpec()) {
            orderColumns.add(visitOrderSpec(specCtx, qualificationRequired)); // Pass flag
         }
         return orderColumns;
    }

    private LocalDateTime parseDateTime(String text) {
        text = unquote(text);
        try {
            // Try parsing as date-time first
            return LocalDateTime.parse(text, DATE_TIME_FORMATTER);
        } catch (Exception e) {
            // If that fails, try parsing as date and convert to start of day
            return LocalDateTime.of(LocalDate.parse(text, DATE_FORMATTER), java.time.LocalTime.MIN);
        }
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
            logger.debug("Registering producer: {} type: POS_TAG for POS", qualifiedVariableName);
            variableRegistry.registerProducer(qualifiedVariableName, VariableType.POS_TAG, "POS");
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
        boolean subqueryQualificationRequired = false; // Assume false for now

        // Visit the subquery's select list
        List<SelectColumn> selectColumns = subqueryBuilder.visitSelectList(ctx.selectList(), subqueryQualificationRequired);
        
        List<Condition> conditions = new ArrayList<>();
        if (ctx.whereClause() != null) {
            // Visit the subquery's WHERE clause using the SUBQUERY'S alias
            conditions.addAll(subqueryBuilder.visitConditionList(ctx.whereClause().conditionList(), subqueryAlias));
        }

        // Re-qualify all variables from $main. to subqueryAlias.
        subqueryBuilder.variableRegistry.requalifyVariables("$main.", subqueryAlias + ".");

        // Also update select columns to use the new qualified names
        List<SelectColumn> requalifiedSelectColumns = selectColumns.stream()
            .map(col -> {
                if (col instanceof VariableColumn varCol) {
                    String oldName = varCol.getColumnName();
                    String plainName = oldName.contains(".") ? oldName.substring(oldName.indexOf(".") + 1) : oldName;
                    // Construct the target qualified name using the subquery's alias
                    String targetQualifiedName = subqueryAlias + "." + plainName;
                    // Check if this variable is actually produced in the requalified registry
                    // If not produced, validation will catch it later. We construct the expected name here.
                    return new VariableColumn(targetQualifiedName);
                    
                } else if (col instanceof SnippetColumn snipCol) {
                    String oldName = snipCol.getVariableName();
                     String plainName = oldName.contains(".") ? oldName.substring(oldName.indexOf(".") + 1) : oldName;
                    // Construct the target qualified name using the subquery's alias
                    String targetQualifiedName = subqueryAlias + "." + plainName;
                     // Check if this variable is actually produced in the requalified registry
                     // If not produced, validation will catch it later. We construct the expected name here.
                     return new SnippetColumn(targetQualifiedName, snipCol.getWindowSize());

                }
                // Handle other column types like TitleColumn, CountColumn etc. if necessary
                return col; // Return non-variable/snippet columns unchanged
            })
            .collect(java.util.stream.Collectors.toList());
        
        // Create the subquery Query object
        Query subquery = new Query(
            source,
            conditions,
            List.of(), // No ORDER BY within subquery definition
            Optional.empty(), // No LIMIT within subquery definition
            Query.Granularity.DOCUMENT, // Default granularity for subquery context? Or inherit? Let's assume default.
            Optional.empty(), // Default granularity size
            requalifiedSelectColumns,
            subqueryBuilder.variableRegistry, // Use the isolated registry (now requalified)
            List.of(), // No nested subqueries within this subquery's definition
            Optional.empty(), // No join condition within this subquery's definition
            Optional.empty() // Subquery's internal Query object doesn't have a main alias itself
        );
        
        // Return the SubquerySpec containing the Query object and its external alias
        return new SubquerySpec(subquery, subqueryAlias);
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
        LocalDateTime literalDateTime = parseDateLiteral(dateText);

        // Determine the correct TemporalPredicate based on the operator
        TemporalPredicate temporalType = mapComparisonOpToPredicate(operator);

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
        return new Temporal(
            Optional.of(literalDateTime), // The literal date being compared against
            Optional.empty(), // End date is not needed for simple comparison predicates
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

    // Helper method to map comparison operator string to TemporalPredicate
    private TemporalPredicate mapComparisonOpToPredicate(String operator) {
        return switch (operator) {
            case "<" -> TemporalPredicate.BEFORE;
            case ">" -> TemporalPredicate.AFTER;
            case "=" -> TemporalPredicate.EQUAL; // Assuming '=' means exact match for the date granularity
            case "==" -> TemporalPredicate.EQUAL;
            case "<=" -> TemporalPredicate.BEFORE_EQUAL;
            case ">=" -> TemporalPredicate.AFTER_EQUAL;
            default -> throw new IllegalArgumentException("Unsupported comparison operator for DATE: " + operator);
        };
    }
} 