package com.example.query.parser;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Tests to validate that all examples from the grammar specification document parse correctly.
 * These tests help ensure that the implementation matches the intended syntax.
 */
@DisplayName("Query Specification Examples Validation Tests")
public class QuerySpecExamplesTest {

    /**
     * Parses a query string using the ANTLR parser directly and returns the parse tree.
     */
    private ParseTree parseQuery(String queryString) {
        CharStream input = CharStreams.fromString(queryString);
        QueryLangLexer lexer = new QueryLangLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                throw new RuntimeException("Lexer error at " + line + ":" + charPositionInLine + " - " + msg);
            }
        });

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        QueryLangParser parser = new QueryLangParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                throw new RuntimeException("Parser error at " + line + ":" + charPositionInLine + " - " + msg);
            }
        });

        return parser.query();
    }

    /**
     * Helper method to assert that a query from the specification parses correctly.
     */
    private void assertSpecExampleValid(String queryString) {
        assertDoesNotThrow(() -> parseQuery(queryString),
                "Specification example should have valid syntax: " + queryString);
    }

    private final List<String> basicQueryExamples = List.of(
        "SELECT doc FROM corpus WHERE CONTAINS(\"artificial intelligence\") AS doc",
        "SELECT title FROM corpus WHERE CONTAINS(\"search term\")",
        "SELECT person FROM corpus WHERE NER(\"PERSON\") AS person",
        "SELECT doc_id FROM corpus WHERE NER(LOCATION)"
    );

    private final List<String> snippetExamples = List.of(
        "SELECT person, SNIPPET(person) FROM corpus WHERE NER(\"PERSON\") AS person",
        "SELECT title, SNIPPET(text) FROM corpus WHERE CONTAINS(\"highlight\") AS text",
        "SELECT person, SNIPPET(person, WINDOW=10) FROM corpus WHERE NER(\"PERSON\") AS person",
        "SELECT timestamp FROM corpus WHERE CONTAINS(\"event\") AS event", // SNIPPET needs a variable
        "SELECT SNIPPET(var1) FROM corpus WHERE CONTAINS(\"word\") AS var1"
    );

    private final List<String> countExpressionExamples = List.of(
        "SELECT COUNT(*) FROM corpus",
        "SELECT COUNT(DOCUMENTS) FROM corpus",
        "SELECT COUNT(UNIQUE person) FROM corpus WHERE NER(\"PERSON\") AS person",
        "SELECT person, COUNT(*) FROM corpus WHERE NER(\"PERSON\") AS person"
        // "SELECT COUNT(UNIQUE missing_var) FROM corpus" // Semantic error, handled by validator
    );
    
    private final List<String> dateComparisonExamples = List.of(
        "SELECT doc FROM corpus WHERE DATE(> 1990) AS doc",
        "SELECT timestamp FROM corpus WHERE DATE(< 2000)",
        "SELECT doc FROM corpus WHERE DATE(< 2000) AS doc",
        "SELECT title FROM corpus WHERE DATE(== 1995)",
        "SELECT doc FROM corpus WHERE DATE(== 1995) AS doc",
        "SELECT title FROM corpus WHERE DATE(>= 1995)",
        "SELECT doc FROM corpus WHERE DATE(>= 1995) AS doc",
        "SELECT title FROM corpus WHERE DATE(<= 2005)",
        "SELECT doc FROM corpus WHERE DATE(<= 2005) AS doc",
        "SELECT title FROM corpus WHERE DATE(= 2010)" // Allow = as alias for ==
    );
    
    private final List<String> complexDateOperationsExamples = List.of(
        "SELECT doc FROM corpus WHERE DATE(CONTAINS [1990-01-01, 2000-01-01]) AS doc",
        "SELECT timestamp FROM corpus WHERE DATE(CONTAINED_BY 2000)",
        "SELECT doc FROM corpus WHERE DATE(CONTAINED_BY 2000) AS doc",
        "SELECT timestamp FROM corpus WHERE DATE(INTERSECT [1990-01-01, 2000-01-01])",
        "SELECT doc FROM corpus WHERE DATE(INTERSECT [1990-01-01, 2000-01-01]) AS doc",
        "SELECT timestamp FROM corpus WHERE DATE(PROXIMITY 2000 RADIUS 5y)",
        "SELECT doc FROM corpus WHERE DATE(PROXIMITY 2000 RADIUS 5y) AS doc"
        // Add DATE literal examples
    );

    private final List<String> granularityExamples = List.of(
        "SELECT entity FROM corpus WHERE NER(\"ORGANIZATION\") AS entity GRANULARITY DOCUMENT",
        "SELECT title FROM corpus GRANULARITY SENTENCE",
        "SELECT entity FROM corpus WHERE NER(\"ORGANIZATION\") AS entity GRANULARITY SENTENCE",
        "SELECT entity FROM corpus WHERE NER(\"ORGANIZATION\") AS entity GRANULARITY SENTENCE 3"
    );
    
    private final List<String> orderByExamples = List.of(
        "SELECT company FROM corpus WHERE NER(\"ORGANIZATION\") AS company ORDER BY company",
        "SELECT title FROM corpus ORDER BY title ASC",
        "SELECT company FROM corpus WHERE NER(\"ORGANIZATION\") AS company ORDER BY company ASC",
        "SELECT company FROM corpus WHERE NER(\"ORGANIZATION\") AS company ORDER BY company DESC",
        "SELECT title, timestamp FROM corpus ORDER BY timestamp DESC, title ASC",
        "SELECT company, date FROM corpus WHERE NER(\"ORGANIZATION\") AS company AND DATE(> 2000) AS date ORDER BY company ASC, date DESC"
    );
    
    private final List<String> limitExamples = List.of(
        "SELECT entity FROM corpus WHERE NER(\"ORGANIZATION\") AS entity LIMIT 10",
        "SELECT title FROM corpus ORDER BY timestamp DESC LIMIT 5"
    );
    
    private final List<String> combinedFeaturesExamples = List.of(
        "SELECT person, SNIPPET(person, WINDOW=5) FROM corpus " +
            "WHERE NER(\"PERSON\") AS person AND DATE(> 2000) AS date " +
            "GRANULARITY SENTENCE 3 ORDER BY person DESC LIMIT 5",
        "SELECT COUNT(DOCUMENTS) FROM corpus WHERE NER(LOCATION) AND CONTAINS(\"city\") LIMIT 1"
    );
    
    // Separate tests for groups
    @Test
    void testBasicQueryExamples() { basicQueryExamples.forEach(this::assertSpecExampleValid); }
    @Test
    void testSnippetExamples() { snippetExamples.forEach(this::assertSpecExampleValid); }
    @Test
    void testCountExpressionExamples() { countExpressionExamples.forEach(this::assertSpecExampleValid); }
    @Test
    void testDateComparisonExamples() { dateComparisonExamples.forEach(this::assertSpecExampleValid); }
    @Test
    void testComplexDateOperationsExamples() { complexDateOperationsExamples.forEach(this::assertSpecExampleValid); }
    @Test
    void testGranularityExamples() { granularityExamples.forEach(this::assertSpecExampleValid); }
    @Test
    void testOrderByExamples() { orderByExamples.forEach(this::assertSpecExampleValid); }
    @Test
    void testLimitExamples() { limitExamples.forEach(this::assertSpecExampleValid); }
    @Test
    void testCombinedFeaturesExamples() { combinedFeaturesExamples.forEach(this::assertSpecExampleValid); }
    
    // Duplicate tests based on old names - consolidate if needed
    @Test
    void basicVariableBindingExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT person FROM corpus WHERE NER(\"PERSON\") AS person");
    }
    @Test
    void snippetExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT person, SNIPPET(person) FROM corpus WHERE NER(\"PERSON\") AS person");
        assertSpecExampleValid("SELECT person, SNIPPET(person, WINDOW=10) FROM corpus WHERE NER(\"PERSON\") AS person");
    }
    @Test
    void aggregationExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT COUNT(UNIQUE person) FROM corpus WHERE NER(\"PERSON\") AS person");
    }
    @Test
    void granularityExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT entity FROM corpus WHERE NER(\"ORGANIZATION\") AS entity GRANULARITY DOCUMENT");
        assertSpecExampleValid("SELECT entity FROM corpus WHERE NER(\"ORGANIZATION\") AS entity GRANULARITY SENTENCE");
        assertSpecExampleValid("SELECT entity FROM corpus WHERE NER(\"ORGANIZATION\") AS entity GRANULARITY SENTENCE 3");
    }
    @Test
    void orderByExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT company FROM corpus WHERE NER(\"ORGANIZATION\") AS company ORDER BY company");
        assertSpecExampleValid("SELECT company FROM corpus WHERE NER(\"ORGANIZATION\") AS company ORDER BY company ASC");
        assertSpecExampleValid("SELECT company FROM corpus WHERE NER(\"ORGANIZATION\") AS company ORDER BY company DESC");
        assertSpecExampleValid("SELECT company, date FROM corpus WHERE NER(\"ORGANIZATION\") AS company AND DATE(> 2000) AS date ORDER BY company ASC, date DESC");
    }
    @Test
    void limitExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT entity FROM corpus WHERE NER(\"ORGANIZATION\") AS entity LIMIT 10");
    }
    @Test
    void complexQueryExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT person, org FROM corpus " +
                                "WHERE NER(\"PERSON\") AS person AND NER(\"ORGANIZATION\") AS org AND CONTAINS(\"founded\") " +
                                "ORDER BY person");
    }
} 