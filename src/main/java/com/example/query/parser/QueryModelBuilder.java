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
import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;

import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Visitor implementation that builds a Query model from the parse tree.
 * Handles conversion from parse tree nodes to model objects.
 */
public class QueryModelBuilder extends QueryLangBaseVisitor<Object> {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_DATE;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    
    // Variable registry for tracking variables
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
        Optional<String> mainAlias = Optional.empty(); // Initialize mainAlias
        
        // Get the FROM source identifier
        if (ctx.identifier() != null && !ctx.identifier().isEmpty()) {
            source = ctx.identifier(0).getText();
            // Check if an alias is provided for the main source
            if (ctx.alias != null) {
                mainAlias = Optional.of(ctx.alias.getText());
            }
        }
        
        List<Condition> conditions = new ArrayList<>();
        List<String> orderColumns = new ArrayList<>();
        Optional<Integer> limit = Optional.empty();
        Query.Granularity granularity = Query.Granularity.DOCUMENT;
        Optional<Integer> granularitySize = Optional.empty();
        List<SelectColumn> selectColumns = new ArrayList<>();
        List<SubquerySpec> subqueries = new ArrayList<>();
        Optional<JoinCondition> joinCondition = Optional.empty();

        // Extract select columns
        if (ctx.selectList() != null) {
            selectColumns = visitSelectList(ctx.selectList());
        }

        // Process join clauses if present
        if (ctx.joinClause() != null && !ctx.joinClause().isEmpty()) {
            for (QueryLangParser.JoinClauseContext joinCtx : ctx.joinClause()) {
                Object[] joinResult = (Object[]) visit(joinCtx);
                SubquerySpec subquery = (SubquerySpec) joinResult[0];
                JoinCondition jc = (JoinCondition) joinResult[1];
                
                subqueries.add(subquery);
                // Use the last join condition
                joinCondition = Optional.of(jc);
            }
        }

        if (ctx.whereClause() != null) {
            conditions.addAll(visitConditionList(ctx.whereClause().conditionList()));
        }

        if (ctx.orderByClause() != null) {
            for (QueryLangParser.OrderSpecContext specCtx : ctx.orderByClause().orderSpec()) {
                orderColumns.add((String) visitOrderSpec(specCtx));
            }
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

        return new Query(source, conditions, orderColumns, limit, granularity, granularitySize, selectColumns, variableRegistry, subqueries, joinCondition, mainAlias);
    }

    @Override
    public List<SelectColumn> visitSelectList(QueryLangParser.SelectListContext ctx) {
        List<SelectColumn> columns = new ArrayList<>();
        for (QueryLangParser.SelectColumnContext colCtx : ctx.selectColumn()) {
            columns.add((SelectColumn) visit(colCtx));
        }
        return columns;
    }
    
    @Override
    public Object visitQualifiedColumn(QueryLangParser.QualifiedColumnContext ctx) {
        // Reverted: Visit the qualifiedIdentifier child to get the full name string
        String qualifiedName = (String) visit(ctx.qualifiedIdentifier());
        // Reverted: Use VariableColumn as a temporary placeholder to avoid compilation error.
        // This is NOT correct logic for handling qualified columns but prevents build failure.
        // The real logic will depend on the MatchDetail refactor.
        return new VariableColumn(qualifiedName); 
    }

    @Override
    public Object visitVariableColumn(QueryLangParser.VariableColumnContext ctx) {
        String variable = (String) visit(ctx.variable());
        return new VariableColumn(variable);
    }
    
    @Override
    public Object visitSnippetColumn(QueryLangParser.SnippetColumnContext ctx) {
        SnippetNode snippetNode = (SnippetNode) visit(ctx.snippetExpression());
        // Extract windowSize and variableName from the node
        int windowSize = snippetNode.windowSize(); 
        String variableName = snippetNode.variable(); // Get plain variable name
        // Use variableName and windowSize to create SnippetColumn
        return new SnippetColumn(variableName, windowSize);
    }
    
    @Override
    public Object visitTitleColumn(QueryLangParser.TitleColumnContext ctx) {
        return new TitleColumn();
    }
    
    @Override
    public Object visitTimestampColumn(QueryLangParser.TimestampColumnContext ctx) {
        return new TimestampColumn();
    }
    
    @Override
    public Object visitCountColumn(QueryLangParser.CountColumnContext ctx) {
        return visit(ctx.countExpression());
    }
    
    @Override
    public Object visitSnippetExpression(QueryLangParser.SnippetExpressionContext ctx) {
        String variable = (String) visit(ctx.variable());
        int windowSize = SnippetNode.DEFAULT_WINDOW_SIZE;
        
        if (ctx.windowSize != null) {
            windowSize = Integer.parseInt(ctx.windowSize.getText());
        }
        
        return new SnippetNode(variable, windowSize);
    }
    
    
    @Override
    public Object visitCountAllExpression(QueryLangParser.CountAllExpressionContext ctx) {
        return CountColumn.countAll();
    }
    
    @Override
    public Object visitCountUniqueExpression(QueryLangParser.CountUniqueExpressionContext ctx) {
        String variable = (String) visit(ctx.variable());
        return CountColumn.countUnique(variable);
    }
    
    @Override
    public Object visitCountDocumentsExpression(QueryLangParser.CountDocumentsExpressionContext ctx) {
        return CountColumn.countDocuments();
    }

    @Override
    public List<Condition> visitConditionList(QueryLangParser.ConditionListContext ctx) {
        if (ctx.condition().size() == 1) {
            // If there's only one condition, return it without creating a logical condition
            Object result = visit(ctx.condition(0));
            if (result instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<Condition> conditions = (List<Condition>) result;
                return conditions;
            } else if (result instanceof Condition) {
                return List.of((Condition) result);
            }
        }
        
        // Start with the first condition
        Object firstResult = visit(ctx.condition(0));
        Condition currentCondition;
        
        if (firstResult instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Condition> firstConditions = (List<Condition>) firstResult;
            if (firstConditions.size() == 1) {
                currentCondition = firstConditions.get(0);
            } else {
                throw new IllegalStateException("Unexpected multiple conditions in first result");
            }
        } else {
            currentCondition = (Condition) firstResult;
        }
        
        // Process the logical operations
        for (int i = 0; i < ctx.logicalOp().size(); i++) {
            // Get the logical operator
            String opText = ctx.logicalOp(i).getText();
            Logical.LogicalOperator operator;
            if (opText.equalsIgnoreCase("AND")) {
                operator = Logical.LogicalOperator.AND;
            } else if (opText.equalsIgnoreCase("OR")) {
                operator = Logical.LogicalOperator.OR;
            } else {
                throw new IllegalStateException("Unexpected logical operator: " + opText);
            }
            
            // Get the right operand
            Object rightResult = visit(ctx.condition(i + 1));
            Condition rightCondition;
            
            if (rightResult instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<Condition> rightConditions = (List<Condition>) rightResult;
                if (rightConditions.size() == 1) {
                    rightCondition = rightConditions.get(0);
                } else {
                    throw new IllegalStateException("Unexpected multiple conditions in right result");
                }
            } else {
                rightCondition = (Condition) rightResult;
            }
            
            // Create a logical condition
            currentCondition = new Logical(operator, currentCondition, rightCondition);
        }
        
        return List.of(currentCondition);
    }
    
    @Override
    public Object visitCondition(QueryLangParser.ConditionContext ctx) {
        return visit(ctx.getChild(0));
    }
    
    @Override
    public Object visitNotCondition(QueryLangParser.NotConditionContext ctx) {
        Object result = visit(ctx.atomicCondition());
        if (result instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Condition> conditions = (List<Condition>) result;
            if (conditions.size() == 1) {
                return new Not(conditions.get(0));
            }
            throw new IllegalStateException("Unexpected multiple conditions in NOT result");
        }
        return new Not((Condition) result);
    }
    
    @Override
    public Object visitAtomicCondition(QueryLangParser.AtomicConditionContext ctx) {
        if (ctx.singleCondition() != null) {
            return visit(ctx.singleCondition());
        } else if (ctx.LPAREN() != null) {
            return visit(ctx.conditionList());
        }
        throw new IllegalStateException("Unexpected atomic condition structure");
    }

    @Override
    public Object visitSingleCondition(QueryLangParser.SingleConditionContext ctx) {
        // The nesting logic is now handled in visitAtomicCondition
        return super.visitSingleCondition(ctx);
    }

    @Override
    public Object visitIdentifier(QueryLangParser.IdentifierContext ctx) {
        return ctx.IDENTIFIER().getText();
    }

    @Override
    public Object visitContainsExpression(QueryLangParser.ContainsExpressionContext ctx) {
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
        
        String variableName = null;
        boolean isVariable = false;
        
        if (ctx.var != null) {
            variableName = (String) visit(ctx.var);
            isVariable = true;
            // Register variable in registry with TEXT_SPAN type
            variableRegistry.registerProducer(variableName, VariableType.TEXT_SPAN, "CONTAINS");
        }
        
        return new Contains(terms, variableName, isVariable);
    }

    @Override
    public Object visitNerExpression(QueryLangParser.NerExpressionContext ctx) {
        String type = (String) visitEntityType(ctx.type);
        String variableName = null;
        boolean isVariable = false;
        
        if (ctx.var != null) {
            variableName = (String) visit(ctx.var);
            isVariable = true;
            // Register variable in registry with appropriate type based on NER entity type
            VariableType varType = determineNerVariableType(type);
            variableRegistry.registerProducer(variableName, varType, "NER");
        }
        
        String termValue = null;
        if (ctx.termValue != null) {
             Object termResult = visitTerm(ctx.termValue);
             termValue = (String) termResult;
             // Check if the original context was a variable and register consumer if so
             if (ctx.termValue.variable() != null) {
                  // Term is a variable, register consumption using its plain name
                  variableRegistry.registerConsumer(termValue, VariableType.ANY, "NER Term");
             }
        }
        
        return new Ner(type, termValue, variableName, isVariable);
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
        // Return just the identifier text, without '?'
        return ctx.IDENTIFIER().getText();
    }

    @Override
    public Object visitDateComparisonExpression(QueryLangParser.DateComparisonExpressionContext ctx) {
        String operator = ctx.comparisonOp().getText();
        int year = Integer.parseInt(ctx.year.getText());
        
        TemporalPredicate predicate = TemporalPredicate.INTERSECT; // Use INTERSECT for all comparisons
        LocalDateTime queryStart;
        Optional<LocalDateTime> queryEnd = Optional.empty();

        // Define the interval based on the comparison operator
        switch (operator.toUpperCase()) {
            case ">": // Greater than year (e.g., > 2000 means 2001 onwards)
                queryStart = LocalDateTime.of(year + 1, 1, 1, 0, 0);
                queryEnd = Optional.of(LocalDateTime.MAX); // Use MAX for unbounded upper end
                break;
            case "<": // Less than year (e.g., < 2000 means up to end of 1999)
                queryStart = LocalDateTime.MIN; 
                queryEnd = Optional.of(LocalDateTime.of(year - 1, 12, 31, 23, 59, 59));
                break;
            case ">=": // Greater than or equal to year (e.g., >= 2000 means 2000 onwards)
                queryStart = LocalDateTime.of(year, 1, 1, 0, 0);
                queryEnd = Optional.of(LocalDateTime.MAX); // Use MAX for unbounded upper end
                break;
            case "<=": // Less than or equal to year (e.g., <= 2000 means up to end of 2000)
                queryStart = LocalDateTime.MIN;
                queryEnd = Optional.of(LocalDateTime.of(year, 12, 31, 23, 59, 59));
                break;
            case "=":
            case "==": // Equal to year (e.g., == 2000 means the full year 2000)
                queryStart = LocalDateTime.of(year, 1, 1, 0, 0);
                queryEnd = Optional.of(LocalDateTime.of(year, 12, 31, 23, 59, 59));
                break;
            default:
                throw new IllegalStateException("Invalid comparison operator: " + operator);
        }
        
        String variableName = null;
        if (ctx.var != null) {
            variableName = (String) visit(ctx.var);
            // Register variable in registry with TEMPORAL type
            variableRegistry.registerProducer(variableName, VariableType.TEMPORAL, "TEMPORAL");
        }
        
        // Create the Temporal condition using the INTERSECT predicate and the defined interval
        return new Temporal(queryStart, queryEnd, Optional.ofNullable(variableName), Optional.empty(), predicate);
    }
    
    @Override
    public Object visitDateOperatorExpression(QueryLangParser.DateOperatorExpressionContext ctx) {
        String operator = ctx.dateOperator().getText();
        TemporalPredicate type = mapOperatorToTemporal(operator);
        
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
        
        String variableName = null;
        if (ctx.var != null) {
            variableName = (String) visit(ctx.var);
            // Register variable in registry with TEMPORAL type
            variableRegistry.registerProducer(variableName, VariableType.TEMPORAL, "TEMPORAL");
        }
        
        // Create the temporal condition with the correct constructor
        System.out.println("DEBUG: Creating Temporal with startDate=" + startDate + ", endDate=" + endDate + ", type=" + type);
        return new Temporal(startDate, endDate, variableName != null ? Optional.of(variableName) : Optional.empty(), range, type);
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
        return switch (operator) {
            case "CONTAINS" -> TemporalPredicate.CONTAINS;
            case "CONTAINED_BY" -> TemporalPredicate.CONTAINED_BY;
            case "INTERSECT" -> TemporalPredicate.INTERSECT;
            case "PROXIMITY" -> TemporalPredicate.PROXIMITY;
            default -> throw new IllegalStateException("Invalid temporal operator: " + operator);
        };
    }

    @Override
    public Object visitDependsExpression(QueryLangParser.DependsExpressionContext ctx) {
        String governor;
        boolean governorIsVariable = false;
        if (ctx.gov.variable() != null) {
            governor = (String) visit(ctx.gov.variable());
            governorIsVariable = true;
        } else {
            // Handle STRING or identifier
            governor = (String) visit(ctx.gov);
        }

        String relation = (String) visitRelation(ctx.rel);

        String dependent;
        boolean dependentIsVariable = false;
        if (ctx.dep.variable() != null) {
            dependent = (String) visit(ctx.dep.variable());
            dependentIsVariable = true;
        } else {
             // Handle STRING or identifier
            dependent = (String) visit(ctx.dep);
        }
        
        String variableName = null;
        boolean isVariable = false;
        
        if (ctx.var != null) {
            variableName = (String) visit(ctx.var);
            isVariable = true;
            // Register variable in registry with DEPENDENCY type
            variableRegistry.registerProducer(variableName, VariableType.DEPENDENCY, "DEPENDENCY");
        }
        
        // Register consumed variables directly
        if (governorIsVariable) {
            variableRegistry.registerConsumer(governor, VariableType.ANY, "DEPENDENCY Governor");
        }
        
        if (dependentIsVariable) {
            variableRegistry.registerConsumer(dependent, VariableType.ANY, "DEPENDENCY Dependent");
        }
        
        return new Dependency(governor, relation, dependent, variableName, isVariable);
    }

    @Override
    public Object visitGovernor(QueryLangParser.GovernorContext ctx) {
        if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        } else if (ctx.identifier() != null) {
            return visitIdentifier(ctx.identifier());
        }
        // Variable case is handled directly in visitDependsExpression
        throw new IllegalStateException("visitGovernor called on unexpected context type, expected STRING or identifier. Variable handled by caller.");
    }

    @Override
    public Object visitDependent(QueryLangParser.DependentContext ctx) {
        if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        } else if (ctx.identifier() != null) {
            return visitIdentifier(ctx.identifier());
        }
         // Variable case is handled directly in visitDependsExpression
        throw new IllegalStateException("visitDependent called on unexpected context type, expected STRING or identifier. Variable handled by caller.");
    }

    @Override
    public Object visitRelation(QueryLangParser.RelationContext ctx) {
        if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        }
        return visitIdentifier(ctx.identifier());
    }

    @Override
    public Object visitOrderSpec(QueryLangParser.OrderSpecContext ctx) {
        String field;
        
        // Extract the field name from either identifier or variable
        if (ctx.identifier() != null) {
            field = (String) visitIdentifier(ctx.identifier());
        } else if (ctx.variable() != null) {
            field = (String) visit(ctx.variable());
        } else if (ctx.qualifiedIdentifier() != null) {
            field = (String) visit(ctx.qualifiedIdentifier());
        }
        else {
            throw new IllegalStateException("OrderSpec must have identifier, variable or qualifiedIdentifier");
        }
        
        // For descending order, prefix with minus sign
        if (ctx.DESC() != null) {
            return "-" + field;
        }
        
        // For ascending order, just return the field name
        return field;
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

    @Override
    public Object visitPosExpression(QueryLangParser.PosExpressionContext ctx) {
        String posTag = (String) visitPosTag(ctx.tag);
        String termValue = null;
        
        if (ctx.termValue != null) {
             Object termResult = visitTerm(ctx.termValue);
             termValue = (String) termResult;
             // Check if the original context was a variable and register consumer if so
             if (ctx.termValue.variable() != null) {
                  // Term is a variable, register consumption using its plain name
                  variableRegistry.registerConsumer(termValue, VariableType.ANY, "POS Term");
             }
        }
        
        String variableName = null;
        boolean isVariable = false;
        
        if (ctx.var != null) {
            variableName = (String) visit(ctx.var);
            isVariable = true;
            // Register variable in registry with POS_TAG type
            variableRegistry.registerProducer(variableName, VariableType.POS_TAG, "POS");
        }
        
        return new Pos(posTag, termValue, variableName, isVariable);
    }

    @Override
    public Object visitPosTag(QueryLangParser.PosTagContext ctx) {
        if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        }
        return visitIdentifier(ctx.identifier());
    }

    @Override
    public Object visitTerm(QueryLangParser.TermContext ctx) {
        if (ctx.STRING() != null) {
            return unquote(ctx.STRING().getText());
        } else if (ctx.variable() != null) {
            return visit(ctx.variable());
        } else if (ctx.identifier() != null) {
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
        
        // Create a new QueryModelBuilder for the subquery to isolate the variable scope
        QueryModelBuilder subqueryBuilder = new QueryModelBuilder();
        
        List<SelectColumn> selectColumns = subqueryBuilder.visitSelectList(ctx.selectList());
        
        List<Condition> conditions = new ArrayList<>();
        if (ctx.whereClause() != null) {
            conditions.addAll(subqueryBuilder.visitConditionList(ctx.whereClause().conditionList()));
        }
        
        // Create a subquery with minimal settings since additional settings will be ignored
        Query subquery = new Query(
            source,
            conditions,
            List.of(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            selectColumns,
            subqueryBuilder.variableRegistry
        );
        
        // Get the alias (which is the second identifier, or identified by the alias label)
        String alias = ctx.alias.getText();
        
        // Return the SubquerySpec
        return new SubquerySpec(subquery, alias);
    }

    @Override
    public Object visitJoinClause(QueryLangParser.JoinClauseContext ctx) {
        // Get the subquery
        SubquerySpec subquery = (SubquerySpec) visit(ctx.subquery());
        
        // Get the join condition
        JoinCondition joinCondition = (JoinCondition) visit(ctx.joinCondition());
        
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
        joinCondition = new JoinCondition(
            joinCondition.leftColumn(),
            joinCondition.rightColumn(),
            joinType,
            joinCondition.temporalPredicate(),
            joinCondition.proximityWindow()
        );
        
        // Return both the subquery and the join condition as a pair
        return new Object[] { subquery, joinCondition };
    }

    @Override
    public Object visitJoinCondition(QueryLangParser.JoinConditionContext ctx) {
        // Get the left and right columns
        String leftColumn = (String) visit(ctx.leftColumn);
        String rightColumn = (String) visit(ctx.rightColumn);
        
        // Get the temporal operator
        TemporalPredicate temporalPredicate = mapOperatorToTemporal(ctx.temporalOp().getText());
        
        // Check if there's a window specification
        Optional<Integer> proximityWindow = Optional.empty();
        if (ctx.window != null) {
            proximityWindow = Optional.of(Integer.parseInt(ctx.window.getText()));
        }
        
        // Create and return the join condition
        return new JoinCondition(
            leftColumn,
            rightColumn,
            JoinCondition.JoinType.INNER, // default, will be updated in visitJoinClause
            temporalPredicate,
            proximityWindow
        );
    }

    @Override
    public Object visitQualifiedIdentifier(QueryLangParser.QualifiedIdentifierContext ctx) {
        // Reverted: Logic to parse alias.NAME, alias.VAR, alias.TITLE, alias.TIMESTAMP
        // and return a combined string.
        if (ctx == null || ctx.getChildCount() < 3) {
            throw new IllegalStateException("Invalid QualifiedIdentifierContext. Expected 'alias DOT column'.");
        }

        String alias = null;
        String rightPart = null;

        // 1. Determine the left part (alias)
        ParseTree leftChild = ctx.getChild(0);
        // Alias should come from an identifier rule (FROM alias=identifier, subquery alias=identifier)
        if (leftChild instanceof QueryLangParser.IdentifierContext identifierContext) {
            alias = identifierContext.getText();
        } else if (leftChild instanceof QueryLangParser.VariableContext variableContext) {
            // This case *shouldn't* happen if grammar is unambiguous and used correctly,
            // but handle it by using the text if it does.
            alias = variableContext.getText(); // Use text, likely a plain identifier now
            // Consider logging a warning here if this path is taken unexpectedly.
        } else {
             throw new IllegalStateException("QualifiedIdentifier couldn't determine alias (left part). Expected IdentifierContext or VariableContext, Found: " + leftChild.getClass().getSimpleName());
        }

        // 2. Determine the right part (column name/type)
        ParseTree rightChild = ctx.getChild(2); // Child after the DOT

        if (rightChild instanceof QueryLangParser.VariableContext variableContext) {
            rightPart = (String) visitVariable(variableContext); // visitVariable returns plain "varname"
        } else if (rightChild instanceof QueryLangParser.IdentifierContext idContext) {
            rightPart = idContext.getText();
         } else if (rightChild instanceof TerminalNode terminalNode) {
            int tokenType = terminalNode.getSymbol().getType();
            if (tokenType == QueryLangLexer.TITLE) {
                rightPart = "TITLE";
            } else if (tokenType == QueryLangLexer.TIMESTAMP) {
                rightPart = "TIMESTAMP";
            } else {
                 throw new IllegalStateException("QualifiedIdentifier encountered unexpected terminal node after dot: " + terminalNode.getText());
            }
        } else {
            throw new IllegalStateException("QualifiedIdentifier couldn't determine column part (right part). Found: " + rightChild.getClass().getSimpleName());
        }

        if (alias == null || rightPart == null) {
             throw new IllegalStateException("Failed to parse qualified identifier parts completely.");
        }

        // Return the combined string representation (e.g., "q1.person", "q1.TITLE")
        return alias + "." + rightPart; // Right part is now plain name if it was a variable
    }

    @Override
    public Object visitJoinColumn(QueryLangParser.JoinColumnContext ctx) {
        if (ctx.qualifiedIdentifier() != null) {
            // Returns "alias.col" or "alias.var" (plain var)
            return visit(ctx.qualifiedIdentifier());
        } else if (ctx.variable() != null) {
             // Returns plain variable name "var"
            return visit(ctx.variable());
        }
        throw new IllegalStateException("Invalid join column type");
    }

    @Override
    public Object visitDateLiteralComparisonExpression(QueryLangParser.DateLiteralComparisonExpressionContext ctx) {
        String operator = ctx.comparisonOp().getText();
        String dateText = ctx.date.getText();
        
        TemporalPredicate predicate = TemporalPredicate.INTERSECT; // Use INTERSECT for all comparisons
        LocalDateTime queryStart;
        Optional<LocalDateTime> queryEnd = Optional.empty();

        // Parse the date literal
        LocalDateTime parsedDate = parseDateLiteral(dateText);
        
        // Define the interval based on the comparison operator
        switch (operator.toUpperCase()) {
            case ">": // Greater than date
                if (isYearOnly(dateText)) {
                    // Year only: "2000" means after Dec 31, 2000
                    LocalDate yearEnd = LocalDate.of(Integer.parseInt(dateText), 12, 31);
                    queryStart = LocalDateTime.of(yearEnd, java.time.LocalTime.MAX).plusNanos(1);
                } else if (isYearMonth(dateText)) {
                    // Year-month: "2000-01" means after Jan 31, 2000
                    String[] parts = dateText.split("-");
                    int year = Integer.parseInt(parts[0]);
                    int month = Integer.parseInt(parts[1]);
                    LocalDate monthEnd = getLastDayOfMonth(year, month);
                    queryStart = LocalDateTime.of(monthEnd, java.time.LocalTime.MAX).plusNanos(1);
                } else {
                    // Full date: "2000-01-01" means after that specific day
                    queryStart = LocalDateTime.of(parsedDate.toLocalDate(), java.time.LocalTime.MAX).plusNanos(1);
                }
                queryEnd = Optional.of(LocalDateTime.MAX);
                break;
            case "<": // Less than date
                if (isYearOnly(dateText)) {
                    // Year only: "2000" means before Jan 1, 2000
                    queryStart = LocalDateTime.MIN;
                    queryEnd = Optional.of(LocalDateTime.of(Integer.parseInt(dateText), 1, 1, 0, 0));
                } else if (isYearMonth(dateText)) {
                    // Year-month: "2000-01" means before Jan 1, 2000
                    String[] parts = dateText.split("-");
                    int year = Integer.parseInt(parts[0]);
                    int month = Integer.parseInt(parts[1]);
                    queryStart = LocalDateTime.MIN;
                    queryEnd = Optional.of(LocalDateTime.of(year, month, 1, 0, 0));
                } else {
                    // Full date: "2000-01-01" means before that specific day
                    queryStart = LocalDateTime.MIN;
                    queryEnd = Optional.of(LocalDateTime.of(parsedDate.toLocalDate(), java.time.LocalTime.MIN));
                }
                break;
            case ">=": // Greater than or equal to date
                if (isYearOnly(dateText)) {
                    // Year only: "2000" means from Jan 1, 2000
                    queryStart = LocalDateTime.of(Integer.parseInt(dateText), 1, 1, 0, 0);
                } else if (isYearMonth(dateText)) {
                    // Year-month: "2000-01" means from Jan 1, 2000
                    String[] parts = dateText.split("-");
                    queryStart = LocalDateTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1, 0, 0);
                } else {
                    // Full date: "2000-01-01" means from that specific day
                    queryStart = LocalDateTime.of(parsedDate.toLocalDate(), java.time.LocalTime.MIN);
                }
                queryEnd = Optional.of(LocalDateTime.MAX);
                break;
            case "<=": // Less than or equal to date
                if (isYearOnly(dateText)) {
                    // Year only: "2000" means up to Dec 31, 2000
                    queryStart = LocalDateTime.MIN;
                    LocalDate yearEnd = LocalDate.of(Integer.parseInt(dateText), 12, 31);
                    queryEnd = Optional.of(LocalDateTime.of(yearEnd, java.time.LocalTime.MAX));
                } else if (isYearMonth(dateText)) {
                    // Year-month: "2000-01" means up to Jan 31, 2000
                    String[] parts = dateText.split("-");
                    int year = Integer.parseInt(parts[0]);
                    int month = Integer.parseInt(parts[1]);
                    queryStart = LocalDateTime.MIN;
                    LocalDate monthEnd = getLastDayOfMonth(year, month);
                    queryEnd = Optional.of(LocalDateTime.of(monthEnd, java.time.LocalTime.MAX));
                } else {
                    // Full date: "2000-01-01" means up to end of that specific day
                    queryStart = LocalDateTime.MIN;
                    queryEnd = Optional.of(LocalDateTime.of(parsedDate.toLocalDate(), java.time.LocalTime.MAX));
                }
                break;
            case "=":
            case "==": // Equal to date
                if (isYearOnly(dateText)) {
                    // Year only: "2000" means the full year 2000
                    queryStart = LocalDateTime.of(Integer.parseInt(dateText), 1, 1, 0, 0);
                    LocalDate yearEnd = LocalDate.of(Integer.parseInt(dateText), 12, 31);
                    queryEnd = Optional.of(LocalDateTime.of(yearEnd, java.time.LocalTime.MAX));
                } else if (isYearMonth(dateText)) {
                    // Year-month: "2000-01" means the full month
                    String[] parts = dateText.split("-");
                    int year = Integer.parseInt(parts[0]);
                    int month = Integer.parseInt(parts[1]);
                    queryStart = LocalDateTime.of(year, month, 1, 0, 0);
                    LocalDate monthEnd = getLastDayOfMonth(year, month);
                    queryEnd = Optional.of(LocalDateTime.of(monthEnd, java.time.LocalTime.MAX));
                } else {
                    // Full date: "2000-01-01" means the full day
                    queryStart = LocalDateTime.of(parsedDate.toLocalDate(), java.time.LocalTime.MIN);
                    queryEnd = Optional.of(LocalDateTime.of(parsedDate.toLocalDate(), java.time.LocalTime.MAX));
                }
                break;
            default:
                throw new IllegalStateException("Invalid comparison operator: " + operator);
        }
        
        String variableName = null;
        if (ctx.var != null) {
            variableName = (String) visit(ctx.var);
            // Register variable in registry with TEMPORAL type
            variableRegistry.registerProducer(variableName, VariableType.TEMPORAL, "TEMPORAL");
        }
        
        // Create the Temporal condition using the INTERSECT predicate and the defined interval
        return new Temporal(queryStart, queryEnd, Optional.ofNullable(variableName), Optional.empty(), predicate);
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
} 