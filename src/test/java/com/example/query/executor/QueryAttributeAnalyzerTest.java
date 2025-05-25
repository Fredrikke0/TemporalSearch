package com.example.query.executor;

import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.SnippetColumn;
import com.example.query.model.StructuralColumn;
import com.example.query.model.VariableColumn;
import com.example.query.model.CountColumn;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Contains;
import com.example.query.binding.VariableRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Query Attribute Analyzer Tests")
class QueryAttributeAnalyzerTest {

    @Test
    @DisplayName("Document granularity query should not require sentence IDs")
    void documentGranularityDoesNotRequireSentenceIds() {
        Query query = new Query(
            "test",
            List.of(),
            List.of(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            List.of(CountColumn.countAll()),
            new VariableRegistry(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            List.of()
        );

        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);

        assertTrue(requirements.needsDocumentId, "Document ID should always be required");
        assertFalse(requirements.needsSentenceId, "Sentence ID should not be required for document granularity");
        assertFalse(requirements.needsPositions, "Positions should not be required for COUNT query");
        assertFalse(requirements.needsSynonymIds, "Synonym IDs should not be required for simple query");
    }

    @Test
    @DisplayName("Sentence granularity query should require sentence IDs")
    void sentenceGranularityRequiresSentenceIds() {
        Query query = new Query(
            "test",
            List.of(),
            List.of(),
            Optional.empty(),
            Query.Granularity.SENTENCE,
            Optional.empty(),
            List.of(CountColumn.countAll()),
            new VariableRegistry(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            List.of()
        );

        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);

        assertTrue(requirements.needsDocumentId, "Document ID should always be required");
        assertTrue(requirements.needsSentenceId, "Sentence ID should be required for sentence granularity");
        assertFalse(requirements.needsPositions, "Positions should not be required for COUNT query");
    }

    @Test
    @DisplayName("SNIPPET column should require position offsets")
    void snippetColumnRequiresPositions() {
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.term", com.example.query.binding.VariableType.TEXT_SPAN, "CONTAINS");
        
        Query query = new Query(
            "test",
            List.of(new Contains(List.of("test"), "$main.term", true)),
            List.of(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            List.of(new SnippetColumn("$main.term", 5)),
            registry,
            List.of(),
            Optional.empty(),
            Optional.empty(),
            List.of()
        );

        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);

        assertTrue(requirements.needsDocumentId, "Document ID should always be required");
        assertTrue(requirements.needsPositions, "Positions should be required for SNIPPET column");
        assertFalse(requirements.needsSynonymIds, "Synonym IDs should not be required for simple CONTAINS");
    }

    @Test
    @DisplayName("SENTENCE_ID structural column should require sentence IDs")
    void sentenceIdColumnRequiresSentenceIds() {
        Query query = new Query(
            "test",
            List.of(),
            List.of(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            List.of(new StructuralColumn("$main", "SENTENCE_ID")),
            new VariableRegistry(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            List.of()
        );

        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);

        assertTrue(requirements.needsDocumentId, "Document ID should always be required");
        assertTrue(requirements.needsSentenceId, "Sentence ID should be required for SENTENCE_ID column");
        assertFalse(requirements.needsPositions, "Positions should not be required for SENTENCE_ID column");
    }

    @Test
    @DisplayName("NER variable condition should require synonym IDs")
    void nerVariableRequiresSynonymIds() {
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.person", com.example.query.binding.VariableType.ENTITY, "NER");
        
        Query query = new Query(
            "test",
            List.of(new Ner("PERSON", null, "$main.person", true)),
            List.of(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            List.of(new VariableColumn("$main.person")),
            registry,
            List.of(),
            Optional.empty(),
            Optional.empty(),
            List.of()
        );

        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);

        assertTrue(requirements.needsDocumentId, "Document ID should always be required");
        assertTrue(requirements.needsSynonymIds, "Synonym IDs should be required for NER variable");
        assertFalse(requirements.needsPositions, "Positions should not be required for simple variable");
    }

    @Test
    @DisplayName("Required SoA attributes should be correctly identified")
    void requiredSoAAttributesCorrectlyIdentified() {
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.term", com.example.query.binding.VariableType.TEXT_SPAN, "CONTAINS");
        
        Query query = new Query(
            "test",
            List.of(new Contains(List.of("test"), "$main.term", true)),
            List.of(),
            Optional.empty(),
            Query.Granularity.SENTENCE,
            Optional.empty(),
            List.of(new SnippetColumn("$main.term", 5)),
            registry,
            List.of(),
            Optional.empty(),
            Optional.empty(),
            List.of()
        );

        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);
        var requiredAttributes = requirements.getRequiredSoAAttributes();

        assertTrue(requiredAttributes.contains("documentIds"), "Should require document IDs");
        assertTrue(requiredAttributes.contains("sentenceIds"), "Should require sentence IDs for sentence granularity");
        assertTrue(requiredAttributes.contains("beginChars"), "Should require begin chars for SNIPPET");
        assertTrue(requiredAttributes.contains("endChars"), "Should require end chars for SNIPPET");
        assertFalse(requiredAttributes.contains("synonymIds"), "Should not require synonym IDs for simple CONTAINS");
    }
} 