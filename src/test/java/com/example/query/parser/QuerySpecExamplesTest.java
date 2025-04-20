package com.example.query.parser;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    @DisplayName("Basic query examples from spec")
    void testBasicQueryExamples() {
        // Simple query with CONTAINS
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE CONTAINS(\"artificial intelligence\") AS ?doc");
        
        // Query with variable binding in NER
        assertSpecExampleValid("SELECT ?person FROM corpus WHERE NER(\"PERSON\") AS ?person");
    }

    @Test
    @DisplayName("Snippet examples from spec")
    void testSnippetExamples() {
        // Basic snippet usage
        assertSpecExampleValid("SELECT ?person, SNIPPET(?person) FROM corpus WHERE NER(\"PERSON\") AS ?person");
        
        // Snippet with custom window size
        assertSpecExampleValid("SELECT ?person, SNIPPET(?person, WINDOW=10) FROM corpus WHERE NER(\"PERSON\") AS ?person");
    }

    @Test
    @DisplayName("Title examples from spec")
    void testTitleExamples() {
        assertSpecExampleValid("SELECT TITLE FROM corpus WHERE CONTAINS(\"climate change\")");
    }

    @Test
    @DisplayName("Count expression examples from spec")
    void testCountExpressionExamples() {
        // Count all matches
        assertSpecExampleValid("SELECT COUNT(*) FROM corpus WHERE CONTAINS(\"neural network\")");
        
        // Count unique values of a variable
        assertSpecExampleValid("SELECT COUNT(UNIQUE ?person) FROM corpus WHERE NER(\"PERSON\") AS ?person");
        
        // Count documents
        assertSpecExampleValid("SELECT COUNT(DOCUMENTS) FROM corpus WHERE CONTAINS(\"machine learning\")");
    }

    @Test
    @DisplayName("Date comparison examples from spec")
    void testDateComparisonExamples() {
        // Greater than comparison
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE DATE(> 1990) AS ?doc");
        
        // Less than comparison
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE DATE(< 2000) AS ?doc");
        
        // Equals comparison
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE DATE(== 1995) AS ?doc");
        
        // Greater than or equal comparison
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE DATE(>= 1995) AS ?doc");
        
        // Less than or equal comparison
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE DATE(<= 2005) AS ?doc");
    }

    @Test
    @DisplayName("Complex date operations examples from spec")
    void testComplexDateOperationsExamples() {
        // Contains date range - now using DATE_LITERAL instead of INTEGER_LITERAL
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE DATE(CONTAINS [1990-01-01, 2000-01-01]) AS ?doc");
        
        // Contained by date
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE DATE(CONTAINED_BY 2000) AS ?doc");
        
        // Intersect with date range
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE DATE(INTERSECT [1990-01-01, 2000-01-01]) AS ?doc");
        
        // Proximity date with radius (formerly NEAR)
        assertSpecExampleValid("SELECT ?doc FROM corpus WHERE DATE(PROXIMITY 2000 RADIUS 5y) AS ?doc");
    }

    @Test
    @DisplayName("Granularity examples from spec")
    void testGranularityExamples() {
        // Document granularity
        assertSpecExampleValid("SELECT ?entity FROM corpus WHERE NER(\"ORGANIZATION\") AS ?entity GRANULARITY DOCUMENT");
        
        // Sentence granularity
        assertSpecExampleValid("SELECT ?entity FROM corpus WHERE NER(\"ORGANIZATION\") AS ?entity GRANULARITY SENTENCE");
        
        // Sentence granularity with context size
        assertSpecExampleValid("SELECT ?entity FROM corpus WHERE NER(\"ORGANIZATION\") AS ?entity GRANULARITY SENTENCE 3");
    }

    @Test
    @DisplayName("Order by examples from spec")
    void testOrderByExamples() {
        // Simple order by
        assertSpecExampleValid("SELECT ?company FROM corpus WHERE NER(\"ORGANIZATION\") AS ?company ORDER BY ?company");
        
        // Order by with direction
        assertSpecExampleValid("SELECT ?company FROM corpus WHERE NER(\"ORGANIZATION\") AS ?company ORDER BY ?company ASC");
        assertSpecExampleValid("SELECT ?company FROM corpus WHERE NER(\"ORGANIZATION\") AS ?company ORDER BY ?company DESC");
        
        // Multiple order by fields
        assertSpecExampleValid("SELECT ?company, ?date FROM corpus WHERE NER(\"ORGANIZATION\") AS ?company AND DATE(> 2000) AS ?date ORDER BY ?company ASC, ?date DESC");
    }

    @Test
    @DisplayName("Limit examples from spec")
    void testLimitExamples() {
        assertSpecExampleValid("SELECT ?entity FROM corpus WHERE NER(\"ORGANIZATION\") AS ?entity LIMIT 10");
    }

    @Test
    @DisplayName("Combined features examples from spec")
    void testCombinedFeaturesExamples() {
        // Example with multiple features
        assertSpecExampleValid(
            "SELECT ?person, SNIPPET(?person, WINDOW=5) " +
            "FROM corpus " +
            "WHERE NER(\"PERSON\") AS ?person AND DATE(> 2000) AS ?date " +
            "GRANULARITY SENTENCE 3 " +
            "ORDER BY ?person DESC " +
            "LIMIT 5"
        );
        
        // Complex query with all features
        assertSpecExampleValid(
            "SELECT ?person, ?org, SNIPPET(?person), COUNT(UNIQUE ?org) " +
            "FROM corpus " +
            "WHERE NER(\"PERSON\") AS ?person AND NER(\"ORGANIZATION\") AS ?org AND CONTAINS(\"founded\") " +
            "GRANULARITY SENTENCE 2 " +
            "ORDER BY ?person ASC, ?org DESC " +
            "LIMIT 20"
        );
    }

    @Test
    @DisplayName("Basic variable binding examples should be valid")
    void basicVariableBindingExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT ?person FROM corpus WHERE NER(\"PERSON\") AS ?person");
    }

    @Test
    @DisplayName("Snippet examples should be valid")
    void snippetExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT ?person, SNIPPET(?person) FROM corpus WHERE NER(\"PERSON\") AS ?person");
        assertSpecExampleValid("SELECT ?person, SNIPPET(?person, WINDOW=10) FROM corpus WHERE NER(\"PERSON\") AS ?person");
    }

    @Test
    @DisplayName("Aggregation examples should be valid")
    void aggregationExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT COUNT(UNIQUE ?person) FROM corpus WHERE NER(\"PERSON\") AS ?person");
    }

    @Test
    @DisplayName("Granularity examples should be valid")
    void granularityExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT ?entity FROM corpus WHERE NER(\"ORGANIZATION\") AS ?entity GRANULARITY DOCUMENT");
        assertSpecExampleValid("SELECT ?entity FROM corpus WHERE NER(\"ORGANIZATION\") AS ?entity GRANULARITY SENTENCE");
        assertSpecExampleValid("SELECT ?entity FROM corpus WHERE NER(\"ORGANIZATION\") AS ?entity GRANULARITY SENTENCE 3");
    }

    @Test
    @DisplayName("Order by examples should be valid")
    void orderByExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT ?company FROM corpus WHERE NER(\"ORGANIZATION\") AS ?company ORDER BY ?company");
        assertSpecExampleValid("SELECT ?company FROM corpus WHERE NER(\"ORGANIZATION\") AS ?company ORDER BY ?company ASC");
        assertSpecExampleValid("SELECT ?company FROM corpus WHERE NER(\"ORGANIZATION\") AS ?company ORDER BY ?company DESC");
        assertSpecExampleValid("SELECT ?company, ?date FROM corpus WHERE NER(\"ORGANIZATION\") AS ?company AND DATE(> 2000) AS ?date ORDER BY ?company ASC, ?date DESC");
    }

    @Test
    @DisplayName("Limit examples should be valid")
    void limitExamplesShouldBeValid() {
        assertSpecExampleValid("SELECT ?entity FROM corpus WHERE NER(\"ORGANIZATION\") AS ?entity LIMIT 10");
    }

    @Test
    @DisplayName("Complex query examples should be valid")
    void complexQueryExamplesShouldBeValid() {
        assertSpecExampleValid(
            "SELECT ?person, ?org FROM corpus " +
            "WHERE NER(\"PERSON\") AS ?person AND NER(\"ORGANIZATION\") AS ?org AND CONTAINS(\"founded\") " +
            "ORDER BY ?person"
        );
    }
} 