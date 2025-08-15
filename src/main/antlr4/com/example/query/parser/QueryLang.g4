grammar QueryLang;

/*
 * This grammar supports variable binding using the BIND keyword pattern.
 * Example: NER(PERSON) BIND person
 * The BIND keyword followed by a variable name binds the result of a condition to a variable.
 * Variables are plain identifiers.
 * Variables can be used in SELECT clause and subsequent WHERE conditions.
 *
 * Variable binding flow:
 * 1. Variables are produced by conditions using the BIND var syntax
 * 2. Variables can be consumed by other conditions that reference them
 * 3. Variables can be used in the SELECT clause to display results
 * 4. Type safety is enforced through the VariableRegistry
 *
 * This grammar also supports subqueries and joins:
 * - Subqueries are defined using parentheses around a full query
 * - Joins are specified using the JOIN keyword
 * - Temporal join conditions use predicates like CONTAINS, CONTAINED_BY, INTERSECT
 */

// Lexer Rules (Tokens) ==========================================================

// Keywords (Order matters for some lexers, define longer matches first if prefixes overlap)
SELECT: 'SELECT';
FROM: 'FROM';
WHERE: 'WHERE';
ALIAS: 'ALIAS';
BIND: 'BIND';
SNIPPET: 'SNIPPET';
WINDOW: 'WINDOW';
NER: 'NER';
POS: 'POS';
DEPENDS: 'DEPENDS';
DATE: 'DATE'; // Used for DATE() condition and NER(DATE) type
PROXIMITY: 'PROXIMITY';
GRANULARITY: 'GRANULARITY';
DOCUMENT: 'DOCUMENT';
SENTENCE: 'SENTENCE';
TITLE: 'TITLE';
TIMESTAMP: 'TIMESTAMP';
CONTAINS: 'CONTAINS';
CONTAINED_BY: 'CONTAINED_BY';
INTERSECT: 'INTERSECT';
BEFORE: 'BEFORE';
AFTER: 'AFTER';
RADIUS: 'RADIUS';
AND: 'AND';
OR: 'OR';
NOT: 'NOT';
ORDER: 'ORDER';
BY: 'BY';
ASC: 'ASC';
DESC: 'DESC';
LIMIT: 'LIMIT';
COUNT: 'COUNT';
UNIQUE: 'UNIQUE';
DOCUMENTS: 'DOCUMENTS';
JOIN: 'JOIN';
ON: 'ON';
INNER: 'INNER';
LEFT: 'LEFT';
RIGHT: 'RIGHT';
BEGIN: 'BEGIN';
END: 'END';
GROUP: 'GROUP';

// NER Entity Type Keywords (Must match VALID_NER_TYPES in validator, case-insensitive)
PERSON: 'PERSON';
LOCATION: 'LOCATION';
ORGANIZATION: 'ORGANIZATION';
// DATE token (defined above) is used for NER(DATE)
TIME: 'TIME';
DURATION: 'DURATION';
MONEY: 'MONEY';
NUMBER: 'NUMBER';
ORDINAL: 'ORDINAL';
PERCENT: 'PERCENT';
SET: 'SET';

// Structure and Symbol Tokens
LPAREN: '(';
RPAREN: ')';
COMMA: ',';
EQUALS: '='; // Often used for assignments/options
EQ: '==';    // Often used for comparison
LBRACKET: '[';
RBRACKET: ']';
WILDCARD: '*';
LT: '<';
GT: '>';
LE: '<=';
GE: '>=';
DOT: '.'; // Added for qualified identifiers

// Time Units
YEAR: 'y';
DAY: 'd';

// Date Literals can be in formats: YYYY, YYYY-MM, YYYY-MM-DD
DATE_LITERAL: [0-9][0-9][0-9][0-9] ('-' [0-9][0-9]? ('-' [0-9][0-9]?)?)?;

// Basic Data Type Tokens
IDENTIFIER: [a-zA-Z_][a-zA-Z0-9_]*;
STRING
    : '"' ( ~["] | '""' )+ '"'   // Non-empty double-quoted strings
    | '\'' ( ~['] | '\'\'' )+ '\'' // Non-empty single-quoted strings
    ;
INTEGER_LITERAL: [0-9]+; // For numeric literals like 123

// Whitespace and Comments (Skipped)
WS: [ \t\r\n]+ -> skip;
COMMENT: '//' ~[\r\n]* -> skip;
BLOCK_COMMENT: '/*' .*? '*/' -> skip;

// Parser Rules =============================================================

query
    : selectClause
      FROM identifier (ALIAS alias=identifier)?
      joinClause*
      whereClause?
      groupByClause?
      granularityClause?
      orderByClause?
      limitClause?
      EOF
    ;

selectClause
    : SELECT selectList
    ;

selectList
    : selectColumn (',' selectColumn)*
    ;

selectColumn
    // Prioritize specific structural keywords before generic variable/identifier rules
    : TITLE                               # UnqualifiedTitleColumn
    | TIMESTAMP                           # UnqualifiedTimestampColumn
    | DOCUMENT_ID                         # UnqualifiedDocumentIdColumn
    | SENTENCE_ID                         # UnqualifiedSentenceIdColumn
    | qualifiedStructuralColumn           # StructColumn             // alias.TITLE, alias.TIMESTAMP, etc.
    | countExpression                     # CountColumn              // COUNT(*), COUNT(UNIQUE var), etc.
    | snippetExpression                   # SnippetColumn            // SNIPPET(var), SNIPPET(alias.var)
    | qualifiedIdentifier                 # QualifiedIdentifierColumn // alias.myVar (handled by visitor as Var or Struct)
    | variable                            # VariableColumn           // myVar (implicitly $main.myVar)
    ;

snippetExpression
    : SNIPPET LPAREN (variable | qualifiedIdentifier) (COMMA windowSize=INTEGER_LITERAL)? RPAREN
    ;

qualifiedStructuralColumn
    : alias=IDENTIFIER DOT field=(TITLE | TIMESTAMP | DOCUMENT_ID | SENTENCE_ID | BEGIN | END)
    ;

countExpression
    : COUNT LPAREN WILDCARD RPAREN                                               # CountAllExpression
    | COUNT LPAREN DOCUMENTS RPAREN                                              # CountDocumentsExpression
    | COUNT LPAREN (unique=UNIQUE)? (variable | qualifiedIdentifier) RPAREN      # CountTargetExpression
    ;

whereClause
    : WHERE conditionList
    ;

conditionList
    : condition (logicalOp condition)*
    ;

condition
    : notCondition
    | atomicCondition
    ;

notCondition
    : NOT atomicCondition
    ;

atomicCondition
    : singleCondition
    | LPAREN conditionList RPAREN
    ;

logicalOp
    : AND
    | OR
    ;

singleCondition
    : nerExpression
    | containsExpression
    | dateExpression
    | dependsExpression
    | posExpression
    ;

dateExpression
    : DATE LPAREN comparisonOp year=INTEGER_LITERAL RPAREN (BIND var=variable)?        # DateComparisonExpression
    | DATE LPAREN comparisonOp date=DATE_LITERAL RPAREN (BIND var=variable)?           # DateLiteralComparisonExpression
    | DATE LPAREN dateOperator dateValue
      (RADIUS radius=INTEGER_LITERAL unit=timeUnit)? RPAREN (BIND var=variable)?       # DateOperatorExpression
    ;

dateOperator
    : CONTAINS
    | CONTAINED_BY
    | INTERSECT
    | PROXIMITY
    | BEFORE
    | AFTER
    ;

dateValue
    : LBRACKET start=INTEGER_LITERAL COMMA end=INTEGER_LITERAL RBRACKET  # DateRange
    | LBRACKET start=DATE_LITERAL COMMA end=DATE_LITERAL RBRACKET        # DateLiteralRange
    | start=DATE_LITERAL (COMMA end=DATE_LITERAL)?                       # DateSingleOrPair
    | single=INTEGER_LITERAL                                             # SingleYear
    ;

timeUnit
    : YEAR
    | DAY
    ;

granularityClause
    : GRANULARITY (DOCUMENT | SENTENCE size=INTEGER_LITERAL?)
    ;

orderByClause
    : ORDER BY orderSpec (COMMA orderSpec)*
    ;

// Order specification rule
// The visitor implementation converts this to strings with "-" prefix for DESC order
// Example: "column_name" for ASC, "-column_name" for DESC
orderSpec
    : (qualifiedIdentifier | identifier | variable | countExpression) (ASC | DESC)?
    ;

qualifiedIdentifier
    : alias=identifier DOT (identifier | variable | TITLE | TIMESTAMP | DOCUMENT_ID | SENTENCE_ID)
    ;

limitClause
    : LIMIT count=INTEGER_LITERAL
    ;

nerExpression
    : NER LPAREN type=entityType (COMMA terms+=term (COMMA terms+=term)*)? RPAREN (BIND var=variable)?
    ;

entityType // Should align with VALID_NER_TYPES in QuerySemanticValidator (case-insensitive)
    : PERSON
    | LOCATION
    | ORGANIZATION
    | DATE       // Use existing DATE token
    | TIME
    | DURATION
    | MONEY
    | NUMBER     // Use reverted NUMBER token
    | ORDINAL
    | PERCENT
    | SET
    ;

containsExpression
    : CONTAINS LPAREN terms+=STRING (COMMA terms+=STRING)* RPAREN (BIND var=variable)?
    ;

dependsExpression
    : DEPENDS LPAREN gov=governor COMMA rel=relation COMMA dep=dependent RPAREN (BIND var=variable)?
    ;

governor
    : qualifiedIdentifier
    | variable
    | STRING
    | identifier
    ;

relation
    : STRING
    | identifier
    ;

dependent
    : qualifiedIdentifier
    | variable
    | STRING
    | identifier
    | WILDCARD
    ;

variable
    : IDENTIFIER
    ;

identifier
    : IDENTIFIER
    ;

comparisonOp
    : LT
    | GT
    | LE
    | GE
    | EQ
    | EQUALS
    ;

term
    : STRING
    | variable
    | identifier
    ;

// Subquery and join syntax
joinClause
    : joinType? JOIN subquery ON joinCondition
    ;

joinType
    : INNER
    | LEFT
    | RIGHT
    ;

subquery
    : LPAREN
      SELECT selectList
      FROM identifier
      whereClause?
      groupByClause?
      granularityClause?
      orderByClause?
      limitClause?
      RPAREN
      ALIAS alias=identifier
    ;

joinCondition
    : leftColumn=joinColumn op=temporalOp rightColumn=joinColumn
      (WINDOW window=INTEGER_LITERAL)?                 # TemporalJoinCondition
    | leftColumn=joinColumn op=(EQ | EQUALS) rightColumn=joinColumn # EqualityJoinCondition
    ;

joinColumn
    : qualifiedIdentifier
    | variable
    ;

temporalOp
    : CONTAINS
    | CONTAINED_BY
    | INTERSECT
    | PROXIMITY
    | BEFORE
    | AFTER
    ;

posExpression
    : POS LPAREN tag=posTag (COMMA termValue=term)? RPAREN (BIND var=variable)?
    ;

posTag
    : STRING
    | identifier
    ;

// Define DOCUMENT_ID and SENTENCE_ID if they are not already keywords
// Assuming they are needed for qualifiedStructuralColumn but not elsewhere as keywords yet
DOCUMENT_ID: 'DOCUMENT_ID';
SENTENCE_ID: 'SENTENCE_ID';


groupByClause
    : GROUP BY groupByItemList
    ;

groupByItemList
    : groupByItem (COMMA groupByItem)*
    ;

groupByItem
    : qualifiedIdentifier  // e.g., alias.myVar, alias.DOCUMENT_ID
    | variable             // e.g., myVar (implicitly $main.myVar)
    | identifier           // Potentially for simple, unqualified column names if design evolves
    ;