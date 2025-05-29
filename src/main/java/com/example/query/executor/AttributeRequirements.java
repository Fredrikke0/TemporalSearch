package com.example.query.executor;

import java.util.HashSet;
import java.util.Set;

/**
 * Encapsulates the attribute requirements for a query execution.
 * Used to determine which SoA attributes need to be deserialized from indexes
 * to optimize memory usage and performance.
 */
public class AttributeRequirements {
    public boolean needsDocumentId = true;     // Always needed for result grouping
    public boolean needsSentenceId = false;   // Only for sentence granularity
    public boolean needsPositions = false;    // Only for SNIPPET() expressions
    public boolean needsSynonymIds = false;   // Only for stitch conditions
    public boolean needsDateValues = false;   // Only for temporal operations
    public boolean needsConceptualRowIds = false; // For grouping bindings into conceptual rows

    /**
     * Creates AttributeRequirements with default values.
     * Document ID is always required for result grouping.
     */
    public AttributeRequirements() {
        // Default values set in field declarations
    }

    /**
     * Creates AttributeRequirements optimized for Phase 1 compatibility.
     * Enables all attributes to ensure backward compatibility with existing systems.
     *
     * @return AttributeRequirements with full compatibility enabled
     */
    public static AttributeRequirements forPhase1Compatibility() {
        AttributeRequirements requirements = new AttributeRequirements();
        requirements.needsDocumentId = true;
        requirements.needsSentenceId = true;  // Enable for maximum compatibility
        requirements.needsPositions = true;   // Enable for maximum compatibility
        requirements.needsSynonymIds = true;  // Enable for maximum compatibility
        requirements.needsDateValues = true;  // Enable for temporal joins
        requirements.needsConceptualRowIds = true; // Enable for new join/logical op logic
        return requirements;
    }

    /**
     * Creates AttributeRequirements for join operations.
     * Join operations typically need access to most attributes.
     *
     * @return AttributeRequirements optimized for joins
     */
    public static AttributeRequirements forJoinOperations() {
        AttributeRequirements requirements = new AttributeRequirements();
        requirements.needsDocumentId = true;
        requirements.needsSentenceId = true;  // Joins may need sentence-level granularity
        requirements.needsPositions = true;   // Joins may need position access
        requirements.needsSynonymIds = false; // Usually not needed for joins
        requirements.needsDateValues = true;  // Temporal joins need date values
        requirements.needsConceptualRowIds = true; // Joins will use conceptual rows
        return requirements;
    }

    /**
     * Gets the set of required SoA attribute names for selective deserialization.
     *
     * @return Set of attribute names that need to be deserialized
     */
    public Set<String> getRequiredSoAAttributes() {
        Set<String> required = new HashSet<>();
        if (needsDocumentId) required.add("documentIds");
        if (needsSentenceId) required.add("sentenceIds");
        if (needsPositions) {
            required.add("beginChars");
            required.add("endChars");
        }
        if (needsSynonymIds) required.add("synonymIds");
        if (needsConceptualRowIds) required.add("conceptualRowIds");
        return required;
    }

    /**
     * Checks if position offsets (beginChars, endChars) are required.
     *
     * @return true if position offsets are needed
     */
    public boolean needsPositionOffsets() {
        return needsPositions;
    }

    /**
     * Merges another AttributeRequirements into this one, taking the union of all requirements.
     *
     * @param other The other requirements to merge
     */
    public void merge(AttributeRequirements other) {
        this.needsDocumentId = this.needsDocumentId || other.needsDocumentId;
        this.needsSentenceId = this.needsSentenceId || other.needsSentenceId;
        this.needsPositions = this.needsPositions || other.needsPositions;
        this.needsSynonymIds = this.needsSynonymIds || other.needsSynonymIds;
        this.needsDateValues = this.needsDateValues || other.needsDateValues;
        this.needsConceptualRowIds = this.needsConceptualRowIds || other.needsConceptualRowIds;
    }

    @Override
    public String toString() {
        return "AttributeRequirements{" +
               "docId=" + needsDocumentId +
               ", sentId=" + needsSentenceId +
               ", positions=" + needsPositions +
               ", synonymIds=" + needsSynonymIds +
               ", dateValues=" + needsDateValues +
               ", conceptualRowIds=" + needsConceptualRowIds +
               ", required=" + getRequiredSoAAttributes() +
               '}';
    }
}