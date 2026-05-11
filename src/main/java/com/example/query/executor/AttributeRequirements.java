package com.example.query.executor;

import java.util.HashSet;
import java.util.Set;

import com.example.core.PostingList;

/**
 * Encapsulates the attribute requirements for a query execution.
 * Used to determine which attributes need to be deserialized from indexes
 * to optimize memory usage and performance.
 *
 * <p>
 * In the new Roaring64-backed format, the only decision is whether
 * occurrence-level detail (char offsets) is needed.
 * </p>
 */
public class AttributeRequirements {
    // Legacy fields — will be removed in Phase 4 when executors are rewritten
    public boolean needsDocumentId = true;
    public boolean needsSentenceId = false;
    public boolean needsPositions = false;
    public boolean needsSynonymIds = false;
    public boolean needsConceptualRowIds = false;

    // New field for PostingList-based format
    private boolean needsOccurrences = false;

    /**
     * Creates AttributeRequirements with default values.
     */
    public AttributeRequirements() {
    }

    // --- New API ---

    /** Returns true if occurrence-level detail (char offsets) is needed. */
    public boolean needsOccurrences() {
        return needsOccurrences;
    }

    /** Sets whether occurrence-level detail is needed. */
    public void setNeedsOccurrences(boolean needsOccurrences) {
        this.needsOccurrences = needsOccurrences;
    }

    /**
     * Converts to the appropriate {@link PostingList.DeserializeMode}.
     * Returns FULL if occurrences are needed, CELLS_ONLY otherwise.
     */
    public PostingList.DeserializeMode toDeserializeMode() {
        return needsOccurrences ? PostingList.DeserializeMode.FULL : PostingList.DeserializeMode.CELLS_ONLY;
    }

    /**
     * Gets the set of required attribute names for selective deserialization.
     *
     * @return Set of attribute names that need to be deserialized
     */
    public Set<String> getRequiredSoAAttributes() {
        Set<String> required = new HashSet<>();
        if (needsDocumentId)
            required.add("documentIds");
        if (needsSentenceId)
            required.add("sentenceIds");
        if (needsPositions) {
            required.add("beginChars");
            required.add("endChars");
        }
        if (needsSynonymIds)
            required.add("synonymIds");
        if (needsConceptualRowIds)
            required.add("conceptualRowIds");
        return required;
    }

    /**
     * Merges another AttributeRequirements into this one, taking the union of all
     * requirements.
     *
     * @param other The other requirements to merge
     */
    public void merge(AttributeRequirements other) {
        this.needsDocumentId = this.needsDocumentId || other.needsDocumentId;
        this.needsSentenceId = this.needsSentenceId || other.needsSentenceId;
        this.needsPositions = this.needsPositions || other.needsPositions;
        this.needsSynonymIds = this.needsSynonymIds || other.needsSynonymIds;
        this.needsConceptualRowIds = this.needsConceptualRowIds || other.needsConceptualRowIds;
        this.needsOccurrences = this.needsOccurrences || other.needsOccurrences;
    }

    @Override
    public String toString() {
        return "AttributeRequirements{" +
                "docId=" + needsDocumentId +
                ", sentId=" + needsSentenceId +
                ", positions=" + needsPositions +
                ", synonymIds=" + needsSynonymIds +
                ", conceptualRowIds=" + needsConceptualRowIds +
                ", occurrences=" + needsOccurrences +
                ", deserializeMode=" + toDeserializeMode() +
                ", required=" + getRequiredSoAAttributes() +
                '}';
    }
}
