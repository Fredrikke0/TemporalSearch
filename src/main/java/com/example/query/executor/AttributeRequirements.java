package com.example.query.executor;

import java.util.HashSet;
import java.util.Set;

/**
 * Encapsulates the attribute requirements for a query execution.
 * Used to determine which SoA attributes need to be deserialized from indexes
 * to optimize memory usage and performance.
 */
public class AttributeRequirements {
    public boolean needsDocumentId = true;
    public boolean needsSentenceId = false;
    public boolean needsPositions = false;
    public boolean needsSynonymIds = false;
    public boolean needsConceptualRowIds = false;

    /**
     * Creates AttributeRequirements with default values.
     * Document ID is always required for result grouping.
     */
    public AttributeRequirements() {
        // Default values set in field declarations
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
     * Merges another AttributeRequirements into this one, taking the union of all requirements.
     *
     * @param other The other requirements to merge
     */
    public void merge(AttributeRequirements other) {
        this.needsDocumentId = this.needsDocumentId || other.needsDocumentId;
        this.needsSentenceId = this.needsSentenceId || other.needsSentenceId;
        this.needsPositions = this.needsPositions || other.needsPositions;
        this.needsSynonymIds = this.needsSynonymIds || other.needsSynonymIds;
        this.needsConceptualRowIds = this.needsConceptualRowIds || other.needsConceptualRowIds;
    }

    @Override
    public String toString() {
        return "AttributeRequirements{" +
               "docId=" + needsDocumentId +
               ", sentId=" + needsSentenceId +
               ", positions=" + needsPositions +
               ", synonymIds=" + needsSynonymIds +
               ", conceptualRowIds=" + needsConceptualRowIds +
               ", required=" + getRequiredSoAAttributes() +
               '}';
    }
}