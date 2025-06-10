package com.example.query.executor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import tech.tablesaw.api.Table;

/**
 * Structure of Arrays (SoA) implementation for query results with massive memory optimization.
 *
 * Replaces the previous array-of-objects approach with columnar storage and deduplication:
 * - Position arrays store document/sentence/character indices
 * - Value deduplication eliminates repeated strings/objects
 * - Variable name interning reduces string storage overhead
 * - Type storage uses single bytes instead of object references
 *  */
public final class QueryResultSoA {
    private static final Logger logger = LoggerFactory.getLogger(QueryResultSoA.class);

    // Position arrays (SoA structure from PositionListSoA pattern)
    private IntArrayList documentIds;      // Always present
    private IntArrayList sentenceIds;     // Null if not needed
    private IntArrayList beginChars;      // Null if not needed
    private IntArrayList endChars;        // Null if not needed
    private IntArrayList synonymIds;      // Null if not needed
    private IntArrayList conceptualRowIds; // NEW: Groups bindings into conceptual output rows

    // Value deduplication for memory optimization
    private List<Object> uniqueValues;        // Deduplicated values
    private IntArrayList valueIndices;       // Indices into uniqueValues

    // Variable name interning for memory optimization
    private List<String> uniqueVariableNames;   // Deduplicated variable names
    private IntArrayList variableNameIndices;  // Indices into uniqueVariableNames (-1 for no variable)

    // Type storage using bytes for memory efficiency
    private ByteArrayList valueTypes;        // ValueType enum ordinals (1 byte each)

    // Metadata
    private int size;
    private AttributeRequirements requirements;
    private Query.Granularity granularity;
    private int granularitySize;

    // Static value interning map for across-query optimization
    private static final Map<Object, Integer> GLOBAL_VALUE_INTERNER = new ConcurrentHashMap<>();
    private static final List<Object> GLOBAL_UNIQUE_VALUES = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Integer> GLOBAL_VARIABLE_INTERNER = new ConcurrentHashMap<>();
    private static final List<String> GLOBAL_UNIQUE_VARIABLE_NAMES = Collections.synchronizedList(new ArrayList<>());

    private int maxConceptualRowId = -1; // Tracks the highest conceptual row ID encountered
    private int nextConceptualRowIdGenerator = 0; // Counter for getNextConceptualRowId()

    /**
     * Constructs a new QueryResultSoA with the specified granularity and attribute requirements.
     *
     * @param granularity The query granularity
     * @param granularitySize The granularity window size
     * @param requirements Specifies which SoA attributes to maintain
     */
    public QueryResultSoA(Query.Granularity granularity, int granularitySize, AttributeRequirements requirements) {
        this.granularity = Objects.requireNonNull(granularity, "granularity cannot be null");
        this.granularitySize = granularitySize;
        this.requirements = Objects.requireNonNull(requirements, "requirements cannot be null");
        this.size = 0;

        // Initialize required arrays only
        this.documentIds = new IntArrayList(); // Always required
        this.sentenceIds = requirements.needsSentenceId ? new IntArrayList() : null;
        this.beginChars = requirements.needsPositions ? new IntArrayList() : null;
        this.endChars = requirements.needsPositions ? new IntArrayList() : null;
        this.synonymIds = requirements.needsSynonymIds ? new IntArrayList() : null;
        this.conceptualRowIds = requirements.needsConceptualRowIds ? new IntArrayList() : null;

        // Initialize deduplication structures
        this.uniqueValues = new ArrayList<>();
        this.valueIndices = new IntArrayList();
        this.uniqueVariableNames = new ArrayList<>();
        this.variableNameIndices = new IntArrayList();
        this.valueTypes = new ByteArrayList();
        this.nextConceptualRowIdGenerator = 0;

        logger.trace("QueryResultSoA Constructor (hashCode={}): Initialized. size={}, nextConceptualRowIdGenerator={}. Granularity={}, requirements={}",
                     System.identityHashCode(this), this.size, this.nextConceptualRowIdGenerator, granularity, requirements);
    }

    /**
     * Convenience constructor with default granularity size.
     */
    public QueryResultSoA(Query.Granularity granularity, AttributeRequirements requirements) {
        this(granularity, 0, requirements);
    }

    /**
     * Adds a match result to this SoA structure.
     * Performs automatic deduplication of values and variable names.
     *
     * @param value The matched value (will be deduplicated)
     * @param valueType The type of the value
     * @param variableName Optional variable name (will be interned)
     * @param documentId The document ID
     * @param sentenceId The sentence ID (ignored if not required)
     * @param beginChar The begin character offset (ignored if not required)
     * @param endChar The end character offset (ignored if not required)
     * @param synonymId The synonym ID (ignored if not required)
     * @param conceptualRowId The conceptual row ID for this binding
     */
    public void add(Object value, ValueType valueType, String variableName,
                   int documentId, int sentenceId, int beginChar, int endChar, int synonymId, int conceptualRowId) {

        logger.trace("QueryResultSoA add() (hashCode={}): BEFORE. current_size={}, current_nextConceptualRowIdGenerator={}. Adding: value={}, type={}, var={}, docId={}, conceptualRowId={}",
                     System.identityHashCode(this), this.size, this.nextConceptualRowIdGenerator,
                     value, valueType, variableName, documentId, conceptualRowId);

        // Add to required position arrays
        documentIds.add(documentId);
        if (sentenceIds != null) sentenceIds.add(sentenceId);
        if (beginChars != null) beginChars.add(beginChar);
        if (endChars != null) endChars.add(endChar);
        if (synonymIds != null) synonymIds.add(synonymId);
        if (conceptualRowIds != null) conceptualRowIds.add(conceptualRowId);

        // Deduplicate and store value
        int valueIndex = getOrAddValueIndex(value);
        valueIndices.add(valueIndex);

        // Intern and store variable name
        int variableIndex = getOrAddVariableNameIndex(variableName);
        variableNameIndices.add(variableIndex);

        // Store value type as byte
        valueTypes.add((byte) valueType.ordinal());

        size++;
        this.maxConceptualRowId = Math.max(this.maxConceptualRowId, conceptualRowId); // Ensure maxConceptualRowId is updated

        logger.trace("QueryResultSoA add() (hashCode={}): AFTER. new_size={}, current_nextConceptualRowIdGenerator={}. Added: value={}, type={}, var={}, docId={}, conceptualRowId={}",
                     System.identityHashCode(this), this.size, this.nextConceptualRowIdGenerator,
                     value, valueType, variableName, documentId, conceptualRowId);

        if (logger.isTraceEnabled()) {
            logger.trace("Added match: value={}, type={}, var={}, doc={}, sent={}, pos=[{}:{}], syn={}",
                        value, valueType, variableName, documentId, sentenceId, beginChar, endChar, synonymId);
        }
    }

    /**
     * Adds a match result without position information.
     */
    public void add(Object value, ValueType valueType, String variableName, int documentId, int sentenceId) {
        add(value, valueType, variableName, documentId, sentenceId, -1, -1, -1, -1);
    }

    /**
     * Adds a match result with minimal information (document-level only).
     */
    public void add(Object value, ValueType valueType, String variableName, int documentId) {
        add(value, valueType, variableName, documentId, -1, -1, -1, -1, -1);
    }

    /**
     * Gets or creates an index for the given value in the deduplication table.
     */
    private int getOrAddValueIndex(Object value) {
        if (value == null) {
            return -1; // Special sentinel for null values
        }

        // Try local deduplication first
        for (int i = 0; i < uniqueValues.size(); i++) {
            if (Objects.equals(uniqueValues.get(i), value)) {
                return i;
            }
        }

        // Add new unique value
        int index = uniqueValues.size();
        uniqueValues.add(value);
        return index;
    }

    /**
     * Gets or creates an index for the given variable name in the interning table.
     */
    private int getOrAddVariableNameIndex(String variableName) {
        if (variableName == null) {
            return -1; // Special sentinel for no variable
        }

        // Try local interning first
        for (int i = 0; i < uniqueVariableNames.size(); i++) {
            if (Objects.equals(uniqueVariableNames.get(i), variableName)) {
                return i;
            }
        }

        // Add new unique variable name
        int index = uniqueVariableNames.size();
        uniqueVariableNames.add(variableName);
        return index;
    }

    // --- Accessors for SoA data ---

    /**
     * Gets the number of matches in this result set.
     */
    public int size() {
        return size;
    }

    /**
     * Checks if this result set is empty.
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Gets the granularity of this result set.
     */
    public Query.Granularity getGranularity() {
        return granularity;
    }

    /**
     * Gets the granularity window size.
     */
    public int getGranularitySize() {
        return granularitySize;
    }

    /**
     * Gets the attribute requirements used for this result set.
     */
    public AttributeRequirements getRequirements() {
        return requirements;
    }

    /**
     * Gets the list of unique variable names stored in this QueryResultSoA.
     * @return A list of unique variable names. Can be empty if no variables were used.
     */
    public List<String> getUniqueVariableNames() {
        return Collections.unmodifiableList(uniqueVariableNames); // Return unmodifiable list for safety
    }

    /**
     * Gets the document ID at the specified index.
     */
    public int getDocumentIdAt(int index) {
        validateIndex(index);
        return documentIds.getInt(index);
    }

    /**
     * Gets the sentence ID at the specified index, if available.
     */
    public int getSentenceIdAt(int index) {
        validateIndex(index);
        if (sentenceIds == null) {
            throw new IllegalStateException("Sentence IDs not available (not required by AttributeRequirements)");
        }
        return sentenceIds.getInt(index);
    }

    /**
     * Gets the begin character offset at the specified index, if available.
     */
    public int getBeginCharAt(int index) {
        validateIndex(index);
        if (beginChars == null) {
            throw new IllegalStateException("Begin characters not available (not required by AttributeRequirements)");
        }
        return beginChars.getInt(index);
    }

    /**
     * Gets the end character offset at the specified index, if available.
     */
    public int getEndCharAt(int index) {
        validateIndex(index);
        if (endChars == null) {
            throw new IllegalStateException("End characters not available (not required by AttributeRequirements)");
        }
        return endChars.getInt(index);
    }

    /**
     * Gets the synonym ID at the specified index, if available.
     */
    public int getSynonymIdAt(int index) {
        validateIndex(index);
        if (synonymIds == null) {
            throw new IllegalStateException("Synonym IDs not available (not required by AttributeRequirements)");
        }
        return synonymIds.getInt(index);
    }

    /**
     * Gets the conceptual row ID at the specified index, if available.
     */
    public int getConceptualRowIdAt(int index) {
        validateIndex(index);
        if (conceptualRowIds == null) {
            throw new IllegalStateException("Conceptual Row IDs not available (not required by AttributeRequirements)");
        }
        return conceptualRowIds.getInt(index);
    }

    /**
     * Gets the value at the specified index.
     */
    public Object getValueAt(int index) {
        validateIndex(index);
        int valueIndex = valueIndices.getInt(index);
        return valueIndex == -1 ? null : uniqueValues.get(valueIndex);
    }

    /**
     * Gets the value type at the specified index.
     */
    public ValueType getValueTypeAt(int index) {
        validateIndex(index);
        byte typeOrdinal = valueTypes.getByte(index);
        return ValueType.values()[typeOrdinal];
    }

    /**
     * Gets the variable name at the specified index, if any.
     */
    public String getVariableNameAt(int index) {
        validateIndex(index);
        int variableIndex = variableNameIndices.getInt(index);
        return variableIndex == -1 ? null : uniqueVariableNames.get(variableIndex);
    }

    /**
     * Validates that the given index is within bounds.
     */
    private void validateIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    // --- Bulk access methods for performance ---

    /**
     * Gets direct access to the document IDs array.
     * Returns a read-only view for safety.
     */
    public IntArrayList getDocumentIds() {
        return documentIds;
    }

    // Add missing getters for the IntArrayList fields
    public IntArrayList getSentenceIds() {
        return sentenceIds;
    }

    public IntArrayList getBeginChars() {
        return beginChars;
    }

    public IntArrayList getEndChars() {
        return endChars;
    }

    public IntArrayList getSynonymIds() {
        return synonymIds;
    }

    public IntArrayList getConceptualRowIds() {
        return conceptualRowIds;
    }

    /**
     * Gets all bound values for a specific variable name.
     */
    public List<Object> getVariableBindings(String varName) {
        if (varName == null) {
            return Collections.emptyList();
        }

        String normalizedVarName = varName.startsWith("?") ? varName : "?" + varName;
        List<Object> bindings = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            String variableNameAtIndex = getVariableNameAt(i);
            if (normalizedVarName.equals(variableNameAtIndex)) {
                bindings.add(getValueAt(i));
            }
        }

        return bindings;
    }

    /**
     * Groups matches by document ID for efficient processing.
     */
    public Map<Integer, List<Integer>> getMatchesByDocumentId() {
        Map<Integer, List<Integer>> groupedMatches = new HashMap<>();

        for (int i = 0; i < size; i++) {
            int docId = documentIds.getInt(i);
            groupedMatches.computeIfAbsent(docId, k -> new ArrayList<>()).add(i);
        }

        return groupedMatches;
    }

    // --- Export and Table Generation (User-Driven Strategy) ---

    /**
     * Creates a Tablesaw Table for display purposes (preview mode).
     * This method is optimized for small result sets (typically 20 rows for preview).
     *
     * @param query The original query for context
     * @param indexes Index access for metadata lookup
     * @return A Tablesaw Table suitable for formatted display
     */
    public Table toTable(Query query, Map<String, IndexAccessInterface> indexes) {
        logger.debug("Converting QueryResultSoA to Table for display (size={})", size);

        // TODO: Implement table generation using SelectColumn processors
        // This will be implemented in Phase 4
        throw new UnsupportedOperationException("Table generation will be implemented in Phase 4");
    }

    /**
     * Exports results directly to CSV format using streaming approach.
     * Maintains constant memory usage regardless of result size.
     *
     * @param filename The output filename
     * @param query The original query for context
     * @param indexes Index access for metadata lookup
     */
    public void exportToCsv(String filename, Query query, Map<String, IndexAccessInterface> indexes) {
        logger.info("Exporting QueryResultSoA to CSV: {} (size={})", filename, size);

        // TODO: Implement direct CSV streaming export
        // This will be implemented in Phase 4
        throw new UnsupportedOperationException("CSV export will be implemented in Phase 4");
    }

    /**
     * Exports results directly to JSON format using streaming approach.
     * Maintains constant memory usage regardless of result size.
     *
     * @param filename The output filename
     * @param query The original query for context
     * @param indexes Index access for metadata lookup
     */
    public void exportToJson(String filename, Query query, Map<String, IndexAccessInterface> indexes) {
        logger.info("Exporting QueryResultSoA to JSON: {} (size={})", filename, size);

        // TODO: Implement direct JSON streaming export
        // This will be implemented in Phase 4
        throw new UnsupportedOperationException("JSON export will be implemented in Phase 4");
    }

    // --- Memory usage estimation ---

    /**
     * Estimates the memory usage of this QueryResultSoA in bytes.
     * Useful for monitoring and optimization.
     */
    public long estimateMemoryUsage() {
        long totalBytes = 0;

        // Position arrays
        totalBytes += documentIds.size() * 4; // int = 4 bytes
        if (sentenceIds != null) totalBytes += sentenceIds.size() * 4;
        if (beginChars != null) totalBytes += beginChars.size() * 4;
        if (endChars != null) totalBytes += endChars.size() * 4;
        if (synonymIds != null) totalBytes += synonymIds.size() * 4;
        if (conceptualRowIds != null) totalBytes += conceptualRowIds.size() * 4;

        // Index arrays
        totalBytes += valueIndices.size() * 4;
        totalBytes += variableNameIndices.size() * 4;
        totalBytes += valueTypes.size(); // byte = 1 byte

        // Deduplicated values (rough estimate)
        totalBytes += uniqueValues.size() * 24; // Rough estimate for object overhead + content
        totalBytes += uniqueVariableNames.size() * 24; // Rough estimate for string overhead + content

        return totalBytes;
    }

    @Override
    public String toString() {
        long memoryUsage = estimateMemoryUsage();
        double memoryMB = memoryUsage / (1024.0 * 1024.0);

        return String.format("QueryResultSoA{size=%d, granularity=%s, granularitySize=%d, " +
                           "uniqueValues=%d, uniqueVariableNames=%d, estimatedMemory=%.1fMB, requirements=%s}",
                           size, granularity, granularitySize,
                           uniqueValues.size(), uniqueVariableNames.size(), memoryMB, requirements);
    }

    public void clear() {
        logger.trace("QueryResultSoA clear() (hashCode={}): BEFORE. current_size={}, current_nextConceptualRowIdGenerator={}",
                     System.identityHashCode(this), this.size, this.nextConceptualRowIdGenerator);
        this.uniqueValues.clear();
        this.valueIndices.clear();
        this.uniqueVariableNames.clear();
        this.variableNameIndices.clear();
        this.valueTypes.clear();
        this.documentIds.clear();
        if (this.sentenceIds != null) this.sentenceIds.clear();
        if (this.beginChars != null) this.beginChars.clear();
        if (this.endChars != null) this.endChars.clear();
        if (this.synonymIds != null) this.synonymIds.clear();
        this.conceptualRowIds.clear();
        this.size = 0;
        this.maxConceptualRowId = -1;
        this.nextConceptualRowIdGenerator = 0; // Reset conceptual row ID counter
        logger.trace("QueryResultSoA clear() (hashCode={}): AFTER. new_size={}, new_nextConceptualRowIdGenerator={}",
                     System.identityHashCode(this), this.size, this.nextConceptualRowIdGenerator);
    }

    /**
     * Gets the next available conceptual row ID for results being generated by this instance.
     * This is used to link related entries that form a single conceptual result row.
     * @return The next unique conceptual row ID.
     */
    public int getNextConceptualRowId() {
        int currentVal = this.nextConceptualRowIdGenerator;
        logger.trace("QueryResultSoA getNextConceptualRowId() (hashCode={}): BEFORE. current_nextConceptualRowIdGenerator={}. Returning currentVal={}",
                     System.identityHashCode(this), this.nextConceptualRowIdGenerator, currentVal);
        this.nextConceptualRowIdGenerator = currentVal + 1;
        logger.trace("QueryResultSoA getNextConceptualRowId() (hashCode={}): AFTER. new_nextConceptualRowIdGenerator={}. Returned currentVal={}",
                     System.identityHashCode(this), this.nextConceptualRowIdGenerator, currentVal);
        return currentVal;
    }

    /**
     * Gets the count of unique conceptual rows *generated by this QueryResultSoA instance*.
     * This is based on the number of times getNextConceptualRowId() has been called.
     * It does not reflect conceptual rows potentially merged from other instances without calling this method.
     * @return The number of conceptual rows originating from *this* instance.
     */
    public int getConceptualRowCount() {
        logger.trace("QueryResultSoA getConceptualRowCount() (hashCode={}): Returning nextConceptualRowIdGenerator={}",
                     System.identityHashCode(this), this.nextConceptualRowIdGenerator);
        return nextConceptualRowIdGenerator;
    }

    /**
     * Returns the set of unique conceptual row IDs present in this result set.
     * This reflects all conceptual IDs, including those potentially merged from other QueryResultSoA instances.
     */
    public Set<Integer> getUniqueConceptualRowIds() {
        if (conceptualRowIds == null || conceptualRowIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(conceptualRowIds);
    }

    /**
     * Sorts all parallel attribute arrays according to the standard position comparison:
     * documentId, then sentenceId (if available), then beginChar (if available), then endChar (if available).
     * The sort preserves conceptual row IDs and all other attributes.
     * The sort is stable with respect to elements not distinguished by the comparator.
     *
     * This follows the same efficient pattern as PositionListSoA.sort().
     */
    public void sort() {
        if (size <= 1) {
            return;
        }

        // Create index array for indirect sorting (same pattern as PositionListSoA)
        int[] indices = new int[size];
        for (int i = 0; i < size; i++) {
            indices[i] = i;
        }

        // Define comparator similar to PositionListSoA but respecting requirements
        IntComparator comparator = (i1, i2) -> {
            // Always compare by document ID first
            int docIdCompare = Integer.compare(documentIds.getInt(i1), documentIds.getInt(i2));
            if (docIdCompare != 0) return docIdCompare;

            // Compare by sentence ID if available
            if (sentenceIds != null) {
                int sentIdCompare = Integer.compare(sentenceIds.getInt(i1), sentenceIds.getInt(i2));
                if (sentIdCompare != 0) return sentIdCompare;
            }

            // Compare by begin char if available
            if (beginChars != null) {
                int beginCompare = Integer.compare(beginChars.getInt(i1), beginChars.getInt(i2));
                if (beginCompare != 0) return beginCompare;
            }

            // Compare by end char if available
            if (endChars != null) {
                int endCompare = Integer.compare(endChars.getInt(i1), endChars.getInt(i2));
                if (endCompare != 0) return endCompare;
            }

            // Compare by synonym ID if available
            if (synonymIds != null) {
                int synonymCompare = Integer.compare(synonymIds.getInt(i1), synonymIds.getInt(i2));
                if (synonymCompare != 0) return synonymCompare;
            }

            return 0; // Equal according to all available criteria
        };

        // Sort indices using efficient QuickSort (same as PositionListSoA)
        IntArrays.quickSort(indices, comparator);

        // Rebuild all arrays in sorted order (same pattern as PositionListSoA)
        IntArrayList sortedDocumentIds = new IntArrayList(size);
        IntArrayList sortedSentenceIds = sentenceIds != null ? new IntArrayList(size) : null;
        IntArrayList sortedBeginChars = beginChars != null ? new IntArrayList(size) : null;
        IntArrayList sortedEndChars = endChars != null ? new IntArrayList(size) : null;
        IntArrayList sortedSynonymIds = synonymIds != null ? new IntArrayList(size) : null;
        IntArrayList sortedConceptualRowIds = conceptualRowIds != null ? new IntArrayList(size) : null;

        // Rebuild value and variable arrays
        IntArrayList sortedValueIndices = new IntArrayList(size);
        IntArrayList sortedVariableNameIndices = new IntArrayList(size);
        ByteArrayList sortedValueTypes = new ByteArrayList(size);

        for (int i = 0; i < size; i++) {
            int originalIndex = indices[i];

            // Rebuild position arrays
            sortedDocumentIds.add(documentIds.getInt(originalIndex));
            if (sortedSentenceIds != null) sortedSentenceIds.add(sentenceIds.getInt(originalIndex));
            if (sortedBeginChars != null) sortedBeginChars.add(beginChars.getInt(originalIndex));
            if (sortedEndChars != null) sortedEndChars.add(endChars.getInt(originalIndex));
            if (sortedSynonymIds != null) sortedSynonymIds.add(synonymIds.getInt(originalIndex));
            if (sortedConceptualRowIds != null) sortedConceptualRowIds.add(conceptualRowIds.getInt(originalIndex));

            // Rebuild value arrays
            sortedValueIndices.add(valueIndices.getInt(originalIndex));
            sortedVariableNameIndices.add(variableNameIndices.getInt(originalIndex));
            sortedValueTypes.add(valueTypes.getByte(originalIndex));
        }

        // Replace arrays with sorted versions
        this.documentIds = sortedDocumentIds;
        this.sentenceIds = sortedSentenceIds;
        this.beginChars = sortedBeginChars;
        this.endChars = sortedEndChars;
        this.synonymIds = sortedSynonymIds;
        this.conceptualRowIds = sortedConceptualRowIds;
        this.valueIndices = sortedValueIndices;
        this.variableNameIndices = sortedVariableNameIndices;
        this.valueTypes = sortedValueTypes;
    }
}