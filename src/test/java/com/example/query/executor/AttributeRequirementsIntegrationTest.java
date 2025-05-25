package com.example.query.executor;

import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.SnippetColumn;
import com.example.query.model.StructuralColumn;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Logical;
import com.example.query.binding.VariableRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AttributeRequirements Integration Tests")
class AttributeRequirementsIntegrationTest {

    @Test
    @DisplayName("QueryAttributeAnalyzer should correctly analyze complex queries")
    void complexQueryAnalysis() {
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.term", com.example.query.binding.VariableType.TEXT_SPAN, "CONTAINS");
        registry.registerProducer("$main.person", com.example.query.binding.VariableType.ENTITY, "NER");
        
        // Create a complex query: CONTAINS('test') AND NER(PERSON) with SNIPPET and SENTENCE_ID columns
        Contains containsCondition = new Contains(List.of("test"), "$main.term", true);
        Ner nerCondition = new Ner("PERSON", null, "$main.person", true);
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(containsCondition, nerCondition));
        
        Query query = new Query(
            "test",
            List.of(andCondition),
            List.of(),
            Optional.empty(),
            Query.Granularity.SENTENCE,  // Requires sentence IDs
            Optional.empty(),
            List.of(
                new SnippetColumn("$main.term", 5),  // Requires positions
                new StructuralColumn("$main", "SENTENCE_ID")  // Requires sentence IDs
            ),
            registry,
            List.of(),
            Optional.empty(),
            Optional.empty(),
            List.of()
        );

        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);

        // Verify all expected requirements are detected
        assertTrue(requirements.needsDocumentId, "Document ID should always be required");
        assertTrue(requirements.needsSentenceId, "Sentence ID should be required for sentence granularity and SENTENCE_ID column");
        assertTrue(requirements.needsPositions, "Positions should be required for SNIPPET column");
        assertTrue(requirements.needsSynonymIds, "Synonym IDs should be required for NER variable");
        assertFalse(requirements.needsDateValues, "Date values should not be required for this query");

        var requiredAttributes = requirements.getRequiredSoAAttributes();
        assertTrue(requiredAttributes.contains("documentIds"), "Should require document IDs");
        assertTrue(requiredAttributes.contains("sentenceIds"), "Should require sentence IDs");
        assertTrue(requiredAttributes.contains("beginChars"), "Should require begin chars");
        assertTrue(requiredAttributes.contains("endChars"), "Should require end chars");
        assertTrue(requiredAttributes.contains("synonymIds"), "Should require synonym IDs");
        assertEquals(5, requiredAttributes.size(), "Should require exactly 5 SoA attributes");
    }

    @Test
    @DisplayName("AttributeRequirements merge should work correctly")
    void attributeRequirementsMerge() {
        AttributeRequirements req1 = new AttributeRequirements();
        req1.needsPositions = true;
        req1.needsSentenceId = true;

        AttributeRequirements req2 = new AttributeRequirements();
        req2.needsSynonymIds = true;
        req2.needsDateValues = true;

        req1.merge(req2);

        assertTrue(req1.needsDocumentId, "Document ID should remain true");
        assertTrue(req1.needsSentenceId, "Sentence ID should remain true");
        assertTrue(req1.needsPositions, "Positions should remain true");
        assertTrue(req1.needsSynonymIds, "Synonym IDs should be merged");
        assertTrue(req1.needsDateValues, "Date values should be merged");
    }
} 