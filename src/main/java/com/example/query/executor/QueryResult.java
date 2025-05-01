package com.example.query.executor;

import com.example.query.binding.MatchDetail;
import com.example.query.model.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Holds the results of a query execution, centered around a list of MatchDetail objects.
 * Provides various ways to access and group the details, often using lazy initialization
 * for performance.
 */
public class QueryResult {

    private static final Logger logger = LoggerFactory.getLogger(QueryResult.class);

    private final Query.Granularity granularity;
    private final int granularitySize;
    private final List<MatchDetail> allDetails;

    // Lazily initialized maps for efficient access
    private Map<String, List<Object>> variableBindings = null;
    private Map<LocalDate, List<MatchDetail>> detailsByMatchedDate = null;
    private Map<Integer, List<MatchDetail>> detailsByDocId = null;
    private Map<Integer, Map<Integer, List<MatchDetail>>> detailsBySentence = null; // docId -> sentenceId -> details

    /**
     * Constructs a QueryResult.
     *
     * @param granularity The granularity of the query (DOCUMENT or SENTENCE).
     * @param granularitySize The window size used for sentence granularity (e.g., 0 for exact, 1 for +/- 1 sentence).
     * @param allDetails  The list of MatchDetail objects representing the raw results.
     */
    public QueryResult(Query.Granularity granularity, int granularitySize, List<MatchDetail> allDetails) {
        this.granularity = Objects.requireNonNull(granularity, "granularity cannot be null");
        this.granularitySize = granularitySize;
        // Store an immutable copy
        this.allDetails = allDetails != null ? List.copyOf(allDetails) : List.of();
    }

    /**
     * Convenience constructor assuming default granularity size (0).
     *
     * @param granularity The granularity of the query (DOCUMENT or SENTENCE).
     * @param allDetails  The list of MatchDetail objects representing the raw results.
     */
    public QueryResult(Query.Granularity granularity, List<MatchDetail> allDetails) {
        this(granularity, 0, allDetails); // Default size to 0
    }

    /**
     * Gets the granularity of the query result.
     *
     * @return The granularity.
     */
    public Query.Granularity getGranularity() {
        return granularity;
    }

    /**
     * Gets the granularity window size used for this result.
     * 
     * @return The granularity size.
     */
    public int getGranularitySize() {
        return granularitySize;
    }

    /**
     * Gets the complete list of all MatchDetail objects.
     *
     * @return An unmodifiable list of all details.
     */
    public List<MatchDetail> getAllDetails() {
        return allDetails; // Already immutable
    }

    /**
     * Gets all bound values for a specific variable name.
     * Ensures the variable name starts with '?'.
     *
     * @param varName The variable name (with or without leading '?').
     * @return A list of bound values, or an empty list if the variable is not found or has no bindings.
     */
    public List<Object> getVariableBindings(String varName) {
        if (variableBindings == null) {
            initializeVariableBindings();
        }
        String normalizedVarName = varName.startsWith("?") ? varName : "?" + varName;
        return variableBindings.getOrDefault(normalizedVarName, Collections.emptyList());
    }

    private synchronized void initializeVariableBindings() {
        if (variableBindings != null) return; // Double-check locking idiom

        Map<String, List<Object>> tempBindings = new HashMap<>();
        for (MatchDetail detail : allDetails) {
            if (detail.variableName().isPresent() && detail.value() != null) {
                String varName = detail.variableName().get();
                String normalizedVarName = varName.startsWith("?") ? varName : "?" + varName;
                tempBindings.computeIfAbsent(normalizedVarName, k -> new ArrayList<>()).add(detail.value());
            }
        }
        // Make lists unmodifiable before assigning
        this.variableBindings = tempBindings.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    /**
     * Gets MatchDetails grouped by the matched date (using MatchDetail::getMatchedDate).
     * Filters out details where getMatchedDate() returns null.
     *
     * @return An unmodifiable map where keys are LocalDates and values are unmodifiable lists of MatchDetails.
     */
    public Map<LocalDate, List<MatchDetail>> getDetailsByMatchedDate() {
        if (detailsByMatchedDate == null) {
            initializeDetailsByMatchedDate();
        }
        return detailsByMatchedDate;
    }

    private synchronized void initializeDetailsByMatchedDate() {
        if (detailsByMatchedDate != null) return;

        Map<LocalDate, List<MatchDetail>> tempMap = allDetails.stream()
                .filter(detail -> detail.getMatchedDate() != null)
                .collect(Collectors.groupingBy(
                        MatchDetail::getMatchedDate, // Use the specific getter
                        Collectors.toList() // Collect into mutable lists first
                ));
        
        // Make value lists immutable
        this.detailsByMatchedDate = tempMap.entrySet().stream()
                 .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    /**
     * Gets MatchDetails grouped by document ID.
     *
     * @return An unmodifiable map where keys are document IDs and values are unmodifiable lists of MatchDetails.
     */
    public Map<Integer, List<MatchDetail>> getDetailsByDocId() {
        if (detailsByDocId == null) {
            initializeDetailsByDocId();
        }
        return detailsByDocId;
    }

    private synchronized void initializeDetailsByDocId() {
        if (detailsByDocId != null) return;

        Map<Integer, List<MatchDetail>> tempMap = allDetails.stream()
                .collect(Collectors.groupingBy(
                        MatchDetail::getDocumentId,
                        Collectors.toList()
                ));
        
        this.detailsByDocId = tempMap.entrySet().stream()
                 .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    /**
     * Gets MatchDetails grouped first by document ID, then by sentence ID.
     * Sentence ID -1 indicates document-level grouping within a document.
     *
     * @return An unmodifiable nested map (docId -> sentenceId -> list of MatchDetails).
     */
    public Map<Integer, Map<Integer, List<MatchDetail>>> getDetailsBySentence() {
        if (detailsBySentence == null) {
            initializeDetailsBySentence();
        }
        return detailsBySentence;
    }

    private synchronized void initializeDetailsBySentence() {
        if (detailsBySentence != null) return;

        Map<Integer, Map<Integer, List<MatchDetail>>> tempMap = allDetails.stream()
                .collect(Collectors.groupingBy(
                        MatchDetail::getDocumentId,
                        Collectors.groupingBy(
                                MatchDetail::getSentenceId,
                                Collectors.toList()
                        )
                ));
                
        // Make inner lists and maps unmodifiable
        this.detailsBySentence = tempMap.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, 
                docEntry -> docEntry.getValue().entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, // sentenceId
                        sentEntry -> List.copyOf(sentEntry.getValue()) // unmodifiable list of details
                    ))
            ));
    }

    @Override
    public String toString() {
        return "QueryResult{" +
                "granularity=" + granularity +
                ", granularitySize=" + granularitySize +
                ", detailCount=" + allDetails.size() +
                // Optionally add more info, like number of unique docs/sentences
                '}';
    }
} 