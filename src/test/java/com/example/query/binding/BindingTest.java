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
        String queryStr = "SELECT t1.person FROM documents ALIAS t1 WHERE NER(PERSON) BIND person";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertTrue(registry.isProduced("t1.person"), "t1.person should be produced");
    }

    @Test
    @DisplayName("Test variable consumption")
    public void testVariableConsumption() {
        String queryStr = "SELECT t1.org FROM documents ALIAS t1 " +
                        "WHERE NER(PERSON) BIND person AND " +
                        "DEPENDS(t1.person, \"works_at\", \"company\") BIND rel AND " +
                        "NER(ORGANIZATION) BIND org";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertTrue(registry.isProduced("t1.person"), "t1.person should be produced");
        assertTrue(registry.isProduced("t1.rel"), "t1.rel should be produced");
        assertTrue(registry.isProduced("t1.org"), "t1.org should be produced");
        assertFalse(registry.getConsumers("t1.person").isEmpty(), "t1.person should be consumed by DEPENDENCY condition");
    }

    @Test
    @DisplayName("Test variable type check: Temporal")
    public void testVariableTypeTemporal() {
        String queryStr = "SELECT t1.date FROM documents ALIAS t1 " +
                        "WHERE DATE(> 2020) BIND date";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertEquals(VariableType.TEMPORAL, registry.getInferredType("t1.date"));
    }
    
    @Test
    @DisplayName("Test variable type check: Entity via OR")
    public void testVariableTypeEntityOr() {
        String queryStr = "SELECT t1.entity FROM documents ALIAS t1 " +
                        "WHERE NER(PERSON) BIND entity OR NER(ORGANIZATION) BIND entity";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertEquals(VariableType.ENTITY, registry.getInferredType("t1.entity"));
    }

    @Test
    @DisplayName("Test variable type check: Any (via CONTAINS)")
    public void testVariableTypeAny() {
        String queryStr = "SELECT t1.text FROM documents ALIAS t1 " +
                        "WHERE CONTAINS(\"test\") BIND text";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        assertEquals(VariableType.TEXT_SPAN, registry.getInferredType("t1.text"));
    }
    
    @Test
    @DisplayName("Test complex query with multiple bindings and types")
    public void testComplexQuery() {
        String queryStr = "SELECT t1.person, t1.org, t1.date FROM documents ALIAS t1 " +
                        "WHERE NER(PERSON) BIND person AND " +
                        "NER(ORGANIZATION) BIND org AND " +
                        "DEPENDS(t1.person, founded, t1.org) BIND foundedRel AND " +
                        "DATE(> 2018) BIND date";
        Query query = assertDoesNotThrow(() -> parseQuery(queryStr));
        VariableRegistry registry = query.variableRegistry();
        
        // Check production
        assertTrue(registry.isProduced("t1.person"));
        assertTrue(registry.isProduced("t1.org"));
        assertTrue(registry.isProduced("t1.foundedRel"));
        assertTrue(registry.isProduced("t1.date"));
        
        // Check consumption
        assertFalse(registry.getConsumers("t1.person").isEmpty(), "t1.person should be consumed by DEPENDENCY");
        assertFalse(registry.getConsumers("t1.org").isEmpty(), "t1.org should be consumed by DEPENDENCY");
        
        // Check types
        assertEquals(VariableType.ENTITY, registry.getInferredType("t1.person"));
        assertEquals(VariableType.ENTITY, registry.getInferredType("t1.org"));
        assertEquals(VariableType.DEPENDENCY, registry.getInferredType("t1.foundedRel"));
        assertEquals(VariableType.TEMPORAL, registry.getInferredType("t1.date"));
    }
} 