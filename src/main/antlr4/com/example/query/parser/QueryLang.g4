grammar QueryLang;

/*
 * This grammar supports variable binding using the AS keyword pattern.
 * Example: NER(PERSON) AS ?person
 * The AS keyword followed by a variable name binds the result of a condition to a variable.
 * All variables must be prefixed with ? character.
 * Variables can be used in SELECT clause and subsequent WHERE conditions.
 * 
 * Variable binding flow:
 * 1. Variables are produced by conditions using the AS ?var syntax
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
AS: 'AS'; 
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

// NER Entity Type Keywords (Must match VALID_NER_TYPES in validator, case-insensitive)
PERSON: 'PERSON';
LOCATION: 'LOCATION';
ORGANIZATION: 'ORGANIZATION';
// DATE token (defined above) is used for NER(DATE)
TIME: 'TIME';
DURATION: 'DURATION';
MONEY: 'MONEY';
NUMBER: 'NUMBER'; // Reverted: Token for NER(NUMBER) type
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
QUESTION: '?';
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
    : '"' ( ~["] | '""' )* '"'   // Double-quoted strings
    | '\'' ( ~['] | '\'\'' )* '\'' // Single-quoted strings
    ;
INTEGER_LITERAL: [0-9]+; // Renamed: For numeric literals like 123

// Whitespace and Comments (Skipped)
WS: [ \t\r\n]+ -> skip;
COMMENT: '//' ~[\r\n]* -> skip;
BLOCK_COMMENT: '/*' .*? '*/' -> skip;

// Parser Rules =============================================================

query
    : SELECT selectList
      FROM identifier (AS alias=identifier)?
      joinClause*
      whereClause?
      granularityClause?
      orderByClause?
      limitClause?
      EOF
    ;

selectList
    : selectColumn (',' selectColumn)*
    ;

selectColumn
    : variable                                  # VariableColumn
    | qualifiedIdentifier                       # QualifiedColumn
    | snippetExpression                         # SnippetColumn
    | titleExpression                           # TitleColumn
    | timestampExpression                       # TimestampColumn
    | countExpression                           # CountColumn
    ;

snippetExpression
    : SNIPPET LPAREN variable (COMMA WINDOW EQUALS windowSize=INTEGER_LITERAL)? RPAREN
    ;

titleExpression
    : TITLE
    ;

timestampExpression
    : TIMESTAMP
    ;

countExpression
    : COUNT LPAREN WILDCARD RPAREN                   # CountAllExpression
    | COUNT LPAREN UNIQUE variable RPAREN            # CountUniqueExpression
    | COUNT LPAREN DOCUMENTS RPAREN                  # CountDocumentsExpression
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
    : DATE LPAREN comparisonOp year=INTEGER_LITERAL RPAREN (AS var=variable)?        # DateComparisonExpression
    | DATE LPAREN comparisonOp date=DATE_LITERAL RPAREN (AS var=variable)?           # DateLiteralComparisonExpression
    | DATE LPAREN dateOperator dateValue
      (RADIUS radius=INTEGER_LITERAL unit=timeUnit)? RPAREN (AS var=variable)?       # DateOperatorExpression
    ;

dateOperator
    : CONTAINS
    | CONTAINED_BY
    | INTERSECT
    | PROXIMITY
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
    : (qualifiedIdentifier | identifier | variable) (ASC | DESC)?
    ;

qualifiedIdentifier
    : (identifier | variable) DOT (identifier | variable | TITLE | TIMESTAMP) // Changed '.' to DOT, Added TITLE, Added TIMESTAMP
    ;

limitClause
    : LIMIT count=INTEGER_LITERAL
    ;

nerExpression
    : NER LPAREN type=entityType (COMMA termValue=term)? RPAREN (AS var=variable)?
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
    | WILDCARD   // Special case handled in validator
    | STRING     // Allow quoted string for unknown/future types? Validation needed.
    | IDENTIFIER // Allow unquoted identifier? Validation needed.
    ;

containsExpression
    : CONTAINS LPAREN terms+=STRING (COMMA terms+=STRING)* RPAREN (AS var=variable)?
    ;

dependsExpression
    : DEPENDS LPAREN gov=governor COMMA rel=relation COMMA dep=dependent RPAREN (AS var=variable)?
    ;

governor
    : variable
    | STRING
    | identifier
    ;

relation
    : STRING
    | identifier
    ;

dependent
    : variable
    | STRING
    | identifier
    ;

variable
    : QUESTION IDENTIFIER
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
      RPAREN
      AS alias=identifier
    ;

joinCondition
    : leftColumn=joinColumn temporalOp rightColumn=joinColumn
      (WINDOW window=INTEGER_LITERAL)?
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
    ;

posExpression
    : POS LPAREN tag=posTag (COMMA termValue=term)? RPAREN (AS var=variable)?
    ;

posTag
    : STRING
    | identifier
    ; 