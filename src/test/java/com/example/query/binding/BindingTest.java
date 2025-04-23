package com.example.query.binding;

import com.example.query.QueryParseException;
import com.example.query.QuerySemanticValidator;
import com.example.query.model.Query;
import com.example.query.parser.QueryLangLexer;
import com.example.query.parser.QueryLangParser;
import com.example.query.parser.QueryModelBuilder;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the variable binding system integration.
 * Tests focus on complete query parsing and validation.
 */
public class BindingTest {

    /**
     * Parse a query string into a Query object
     */
    private Query parseQuery(String queryStr) {
        try {
            QueryLangLexer lexer = new QueryLangLexer(CharStreams.fromString(queryStr));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            QueryLangParser parser = new QueryLangParser(tokens);
            ParseTree tree = parser.query();
            QueryModelBuilder builder = new QueryModelBuilder();
            return builder.buildQuery(tree);
        } catch (Exception e) {
            fail("Failed to parse query: " + e.getMessage());
            return null;
        }
    }

    @Test
    @DisplayName("Test basic variable production")
    public void testVariableProduction() {
        String queryStr = "SELECT person FROM documents WHERE NER(PERSON) AS person";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertTrue(registry.isProduced("person"), "person should be produced");
    }

    @Test
    @DisplayName("Test variable consumption")
    public void testVariableConsumption() {
        String queryStr = "SELECT org FROM documents " +
                        "WHERE NER(PERSON) AS person AND " +
                        "DEPENDS(person, \"works_at\", \"company\") AS rel AND " +
                        "NER(ORGANIZATION) AS org";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertTrue(registry.isProduced("person"), "person should be produced");
        assertTrue(registry.isProduced("rel"), "rel should be produced");
        assertTrue(registry.isProduced("org"), "org should be produced");
        assertFalse(registry.getConsumers("person").isEmpty(), "person should be consumed by DEPENDENCY condition");
    }

    @Test
    @DisplayName("Test variable type check: Temporal")
    public void testVariableTypeTemporal() {
        String queryStr = "SELECT date FROM documents " +
                        "WHERE DATE(> 2020) AS date";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertEquals(VariableType.TEMPORAL, registry.getInferredType("date"));
    }
    
    @Test
    @DisplayName("Test variable type check: Entity via OR")
    public void testVariableTypeEntityOr() {
        String queryStr = "SELECT entity FROM documents " +
                        "WHERE NER(PERSON) AS entity OR NER(ORGANIZATION) AS entity";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertEquals(VariableType.ENTITY, registry.getInferredType("entity"));
    }

    @Test
    @DisplayName("Test variable type check: Any (via CONTAINS)")
    public void testVariableTypeAny() {
        String queryStr = "SELECT text FROM documents " +
                        "WHERE CONTAINS(\"test\") AS text";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertEquals(VariableType.TEXT_SPAN, registry.getInferredType("text"));
    }
    
    @Test
    @DisplayName("Test complex query with multiple bindings and types")
    public void testComplexQuery() {
        String queryStr = "SELECT person, org, date FROM documents " +
                        "WHERE NER(PERSON) AS person AND " +
                        "NER(ORGANIZATION) AS org AND " +
                        "DEPENDS(person, \"founded\", org) AS foundedRel AND " +
                        "DATE(> 2018) AS date";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        
        // Check production
        assertTrue(registry.isProduced("person"));
        assertTrue(registry.isProduced("org"));
        assertTrue(registry.isProduced("foundedRel"));
        assertTrue(registry.isProduced("date"));
        
        // Check consumption
        assertFalse(registry.getConsumers("person").isEmpty(), "person should be consumed by DEPENDENCY");
        assertFalse(registry.getConsumers("org").isEmpty(), "org should be consumed by DEPENDENCY");
        
        // Check types
        assertEquals(VariableType.ENTITY, registry.getInferredType("person"));
        assertEquals(VariableType.ENTITY, registry.getInferredType("org"));
        assertEquals(VariableType.DEPENDENCY, registry.getInferredType("foundedRel"));
        assertEquals(VariableType.TEMPORAL, registry.getInferredType("date"));
    }
} 