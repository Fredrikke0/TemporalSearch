package com.example.query.executor;

import com.example.query.binding.JoinedMatch;
import com.example.query.binding.MatchDetail;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;

/**
 * Optimizes join operations using SoA (Structure of Arrays) patterns.
 * Instead of iterating through individual MatchDetail objects, this class
 * extracts bulk arrays and performs operations on them for better performance.
 */
public class SoAJoinOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(SoAJoinOptimizer.class);

    /**
     * Performs an optimized hash join by selecting the best algorithm based on join keys.
     * 
     * @param leftDetails List of MatchDetail from the left side
     * @param rightDetails List of MatchDetail from the right side  
     * @param leftKey The key to extract from left side
     * @param rightKey The key to extract from right side
     * @param requirements Attribute requirements for optimization hints
     * @return List of JoinedMatch representing the join result
     */
    public static List<JoinedMatch> performOptimizedHashJoin(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails,
            String leftKey, String rightKey, AttributeRequirements requirements) {
        
        logger.debug("Performing optimized join on keys: {} = {} (left size: {}, right size: {})",
                    leftKey, rightKey, leftDetails.size(), rightDetails.size());

        // Choose specialized algorithm based on join keys
        if ("date".equals(leftKey) && "date".equals(rightKey)) {
            return performDateJoin(leftDetails, rightDetails, requirements);
        } else if ("document_id".equals(leftKey) && "document_id".equals(rightKey)) {
            return performDocumentIdJoin(leftDetails, rightDetails, requirements);
        } else if ("sentence_id".equals(leftKey) && "sentence_id".equals(rightKey)) {
            return performSentenceIdJoin(leftDetails, rightDetails, requirements);
        } else {
            return performGenericJoin(leftDetails, rightDetails, leftKey, rightKey, requirements);
        }
    }

    /**
     * Optimized join for date values using bulk array operations.
     * This method extracts date values in bulk and creates index mappings
     * for efficient joining.
     */
    private static List<JoinedMatch> performDateJoin(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails,
            AttributeRequirements requirements) {
        
        logger.debug("Performing optimized DATE join");
        
        // Extract date values and indices in bulk
        Map<LocalDate, IntArrayList> leftDateToIndices = new HashMap<>();
        Map<LocalDate, IntArrayList> rightDateToIndices = new HashMap<>();

        // Build left-side index
        for (int i = 0; i < leftDetails.size(); i++) {
            MatchDetail detail = leftDetails.get(i);
            if (detail.value() instanceof LocalDate date) {
                leftDateToIndices.computeIfAbsent(date, k -> new IntArrayList()).add(i);
            }
        }

        // Build right-side index  
        for (int i = 0; i < rightDetails.size(); i++) {
            MatchDetail detail = rightDetails.get(i);
            if (detail.value() instanceof LocalDate date) {
                rightDateToIndices.computeIfAbsent(date, k -> new IntArrayList()).add(i);
            }
        }

        // Perform join using bulk operations
        List<JoinedMatch> results = new ArrayList<>();
        
        // Iterate through the smaller index for efficiency
        Map<LocalDate, IntArrayList> smallerIndex = leftDateToIndices.size() <= rightDateToIndices.size() 
            ? leftDateToIndices : rightDateToIndices;
        Map<LocalDate, IntArrayList> largerIndex = smallerIndex == leftDateToIndices 
            ? rightDateToIndices : leftDateToIndices;
        boolean leftIsSmaller = smallerIndex == leftDateToIndices;

        for (Map.Entry<LocalDate, IntArrayList> entry : smallerIndex.entrySet()) {
            LocalDate date = entry.getKey();
            IntArrayList rightIndices = largerIndex.get(date);
            
            if (rightIndices != null) {
                IntArrayList leftIndices = leftIsSmaller ? entry.getValue() : rightIndices;
                IntArrayList actualRightIndices = leftIsSmaller ? rightIndices : entry.getValue();
                
                // Create cross product using bulk array access
                for (int leftIdx : leftIndices) {
                    for (int rightIdx : actualRightIndices) {
                        results.add(new JoinedMatch(
                            leftDetails.get(leftIdx),
                            rightDetails.get(rightIdx)
                        ));
                    }
                }
            }
        }

        logger.debug("DATE join completed: {} pairs created from {} left dates, {} right dates",
                    results.size(), leftDateToIndices.size(), rightDateToIndices.size());
        return results;
    }

    /**
     * Optimized join for document IDs using direct array access.
     * Since document IDs are stored directly in MatchDetail, this can use
     * very efficient integer-based indexing.
     */
    private static List<JoinedMatch> performDocumentIdJoin(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails,
            AttributeRequirements requirements) {
        
        logger.debug("Performing optimized DOCUMENT_ID join");

        // Use int-specialized maps for better performance
        Object2IntOpenHashMap<Integer> leftDocToFirstIndex = new Object2IntOpenHashMap<>();
        Map<Integer, IntArrayList> leftDocToIndices = new HashMap<>();
        Map<Integer, IntArrayList> rightDocToIndices = new HashMap<>();

        // Extract document IDs in bulk from left side
        for (int i = 0; i < leftDetails.size(); i++) {
            MatchDetail detail = leftDetails.get(i);
            int docId = detail.getDocumentId();
            leftDocToIndices.computeIfAbsent(docId, k -> new IntArrayList()).add(i);
        }

        // Extract document IDs in bulk from right side
        for (int i = 0; i < rightDetails.size(); i++) {
            MatchDetail detail = rightDetails.get(i);
            int docId = detail.getDocumentId();
            rightDocToIndices.computeIfAbsent(docId, k -> new IntArrayList()).add(i);
        }

        // Perform join
        List<JoinedMatch> results = new ArrayList<>();
        
        // Use the smaller map as the driver
        Map<Integer, IntArrayList> smallerMap = leftDocToIndices.size() <= rightDocToIndices.size()
            ? leftDocToIndices : rightDocToIndices;
        Map<Integer, IntArrayList> largerMap = smallerMap == leftDocToIndices
            ? rightDocToIndices : leftDocToIndices;
        boolean leftIsSmaller = smallerMap == leftDocToIndices;

        for (Map.Entry<Integer, IntArrayList> entry : smallerMap.entrySet()) {
            Integer docId = entry.getKey();
            IntArrayList otherIndices = largerMap.get(docId);
            
            if (otherIndices != null) {
                IntArrayList leftIndices = leftIsSmaller ? entry.getValue() : otherIndices;
                IntArrayList rightIndices = leftIsSmaller ? otherIndices : entry.getValue();
                
                // Create cross product
                for (int leftIdx : leftIndices) {
                    for (int rightIdx : rightIndices) {
                        // Avoid joining a detail with itself
                        if (leftIdx != rightIdx || leftDetails != rightDetails) {
                            results.add(new JoinedMatch(
                                leftDetails.get(leftIdx),
                                rightDetails.get(rightIdx)
                            ));
                        }
                    }
                }
            }
        }

        logger.debug("DOCUMENT_ID join completed: {} pairs created from {} left docs, {} right docs",
                    results.size(), leftDocToIndices.size(), rightDocToIndices.size());
        return results;
    }

    /**
     * Optimized join for sentence IDs using direct array access.
     */
    private static List<JoinedMatch> performSentenceIdJoin(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails,
            AttributeRequirements requirements) {
        
        logger.debug("Performing optimized SENTENCE_ID join");

        Map<Integer, IntArrayList> leftSentToIndices = new HashMap<>();
        Map<Integer, IntArrayList> rightSentToIndices = new HashMap<>();

        // Extract sentence IDs in bulk from left side
        for (int i = 0; i < leftDetails.size(); i++) {
            MatchDetail detail = leftDetails.get(i);
            int sentId = detail.getSentenceId();
            if (sentId >= 0) { // Valid sentence ID
                leftSentToIndices.computeIfAbsent(sentId, k -> new IntArrayList()).add(i);
            }
        }

        // Extract sentence IDs in bulk from right side
        for (int i = 0; i < rightDetails.size(); i++) {
            MatchDetail detail = rightDetails.get(i);
            int sentId = detail.getSentenceId();
            if (sentId >= 0) { // Valid sentence ID
                rightSentToIndices.computeIfAbsent(sentId, k -> new IntArrayList()).add(i);
            }
        }

        // Perform join
        List<JoinedMatch> results = new ArrayList<>();
        
        // Use the smaller map as the driver
        Map<Integer, IntArrayList> smallerMap = leftSentToIndices.size() <= rightSentToIndices.size()
            ? leftSentToIndices : rightSentToIndices;
        Map<Integer, IntArrayList> largerMap = smallerMap == leftSentToIndices
            ? rightSentToIndices : leftSentToIndices;
        boolean leftIsSmaller = smallerMap == leftSentToIndices;

        for (Map.Entry<Integer, IntArrayList> entry : smallerMap.entrySet()) {
            Integer sentId = entry.getKey();
            IntArrayList otherIndices = largerMap.get(sentId);
            
            if (otherIndices != null) {
                IntArrayList leftIndices = leftIsSmaller ? entry.getValue() : otherIndices;
                IntArrayList rightIndices = leftIsSmaller ? otherIndices : entry.getValue();
                
                // Create cross product
                for (int leftIdx : leftIndices) {
                    for (int rightIdx : rightIndices) {
                        // Avoid joining a detail with itself
                        if (leftIdx != rightIdx || leftDetails != rightDetails) {
                            results.add(new JoinedMatch(
                                leftDetails.get(leftIdx),
                                rightDetails.get(rightIdx)
                            ));
                        }
                    }
                }
            }
        }

        logger.debug("SENTENCE_ID join completed: {} pairs created from {} left sentences, {} right sentences",
                    results.size(), leftSentToIndices.size(), rightSentToIndices.size());
        return results;
    }

    /**
     * Generic optimized join for arbitrary keys using bulk value extraction.
     * Still more efficient than the original implementation due to reduced 
     * method calls and better memory access patterns.
     */
    private static List<JoinedMatch> performGenericJoin(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails,
            String leftKey, String rightKey, AttributeRequirements requirements) {
        
        logger.debug("Performing optimized GENERIC join on keys: {} = {}", leftKey, rightKey);

        Map<Object, IntArrayList> leftValueToIndices = new HashMap<>();
        Map<Object, IntArrayList> rightValueToIndices = new HashMap<>();

        // Extract values in bulk from left side
        for (int i = 0; i < leftDetails.size(); i++) {
            MatchDetail detail = leftDetails.get(i);
            Optional<Object> valueOpt = JoinHandler.extractValueForKey(detail, leftKey);
            if (valueOpt.isPresent()) {
                Object value = valueOpt.get();
                leftValueToIndices.computeIfAbsent(value, k -> new IntArrayList()).add(i);
            }
        }

        // Extract values in bulk from right side
        for (int i = 0; i < rightDetails.size(); i++) {
            MatchDetail detail = rightDetails.get(i);
            Optional<Object> valueOpt = JoinHandler.extractValueForKey(detail, rightKey);
            if (valueOpt.isPresent()) {
                Object value = valueOpt.get();
                rightValueToIndices.computeIfAbsent(value, k -> new IntArrayList()).add(i);
            }
        }

        // Perform join
        List<JoinedMatch> results = new ArrayList<>();
        
        // Use the smaller map as the driver
        Map<Object, IntArrayList> smallerMap = leftValueToIndices.size() <= rightValueToIndices.size()
            ? leftValueToIndices : rightValueToIndices;
        Map<Object, IntArrayList> largerMap = smallerMap == leftValueToIndices
            ? rightValueToIndices : leftValueToIndices;
        boolean leftIsSmaller = smallerMap == leftValueToIndices;

        for (Map.Entry<Object, IntArrayList> entry : smallerMap.entrySet()) {
            Object value = entry.getKey();
            IntArrayList otherIndices = largerMap.get(value);
            
            if (otherIndices != null) {
                IntArrayList leftIndices = leftIsSmaller ? entry.getValue() : otherIndices;
                IntArrayList rightIndices = leftIsSmaller ? otherIndices : entry.getValue();
                
                // Create cross product
                for (int leftIdx : leftIndices) {
                    for (int rightIdx : rightIndices) {
                        // Avoid joining a detail with itself
                        if (leftIdx != rightIdx || leftDetails != rightDetails) {
                            results.add(new JoinedMatch(
                                leftDetails.get(leftIdx),
                                rightDetails.get(rightIdx)
                            ));
                        }
                    }
                }
            }
        }

        logger.debug("GENERIC join completed: {} pairs created from {} left values, {} right values",
                    results.size(), leftValueToIndices.size(), rightValueToIndices.size());
        return results;
    }

    /**
     * Optimized temporal sort-merge join using bulk array extraction.
     * This method processes temporal data more efficiently by working with
     * sorted arrays instead of individual object comparisons.
     * 
     * @param leftDetails List of MatchDetail from the left side
     * @param rightDetails List of MatchDetail from the right side
     * @param leftKey The key to extract temporal value from left side
     * @param rightKey The key to extract temporal value from right side
     * @param predicate The temporal predicate (BEFORE or AFTER)
     * @param requirements Attribute requirements for optimization hints
     * @return List of JoinedMatch representing the temporal join result
     */
    public static List<JoinedMatch> performOptimizedTemporalJoin(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails,
            String leftKey, String rightKey, String predicate,
            AttributeRequirements requirements) {
        
        logger.debug("Performing optimized TEMPORAL join with predicate: {}", predicate);

        // Extract temporal values and indices in bulk
        List<TemporalIndexPair> leftTemporal = extractTemporalPairs(leftDetails, leftKey);
        List<TemporalIndexPair> rightTemporal = extractTemporalPairs(rightDetails, rightKey);

        if (leftTemporal.isEmpty() || rightTemporal.isEmpty()) {
            logger.debug("One or both temporal lists are empty after filtering. Returning empty result.");
            return new ArrayList<>();
        }

        // Sort both lists by date for merge algorithm
        leftTemporal.sort(Comparator.comparing(TemporalIndexPair::date));
        rightTemporal.sort(Comparator.comparing(TemporalIndexPair::date));

        List<JoinedMatch> results = new ArrayList<>();

        if ("BEFORE".equals(predicate)) { // Left Date < Right Date
            int j = 0;
            for (TemporalIndexPair left : leftTemporal) {
                // Advance j until right date is strictly > left date
                while (j < rightTemporal.size() && 
                       rightTemporal.get(j).date().compareTo(left.date()) <= 0) {
                    j++;
                }
                // All remaining right elements satisfy the condition
                for (int k = j; k < rightTemporal.size(); k++) {
                    TemporalIndexPair right = rightTemporal.get(k);
                    results.add(new JoinedMatch(
                        leftDetails.get(left.index()),
                        rightDetails.get(right.index())
                    ));
                }
            }
        } else if ("AFTER".equals(predicate)) { // Left Date > Right Date
            int i = 0;
            for (TemporalIndexPair right : rightTemporal) {
                // Advance i until left date is strictly > right date
                while (i < leftTemporal.size() && 
                       leftTemporal.get(i).date().compareTo(right.date()) <= 0) {
                    i++;
                }
                // All remaining left elements satisfy the condition
                for (int k = i; k < leftTemporal.size(); k++) {
                    TemporalIndexPair left = leftTemporal.get(k);
                    results.add(new JoinedMatch(
                        leftDetails.get(left.index()),
                        rightDetails.get(right.index())
                    ));
                }
            }
        }

        logger.debug("TEMPORAL join completed: {} pairs created", results.size());
        return results;
    }

    /**
     * Helper record to associate a temporal value with its index in the original list.
     */
    private record TemporalIndexPair(LocalDate date, int index) {}

    /**
     * Extracts temporal values and their indices in bulk.
     */
    private static List<TemporalIndexPair> extractTemporalPairs(
            List<MatchDetail> details, String key) {
        
        List<TemporalIndexPair> pairs = new ArrayList<>();
        for (int i = 0; i < details.size(); i++) {
            MatchDetail detail = details.get(i);
            Optional<Object> valueOpt = JoinHandler.extractValueForKey(detail, key);
            if (valueOpt.isPresent() && valueOpt.get() instanceof LocalDate date) {
                pairs.add(new TemporalIndexPair(date, i));
            }
        }
        return pairs;
    }
} 