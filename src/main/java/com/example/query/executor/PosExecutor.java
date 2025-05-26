package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.executor.QueryResult;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executor for POS conditions.
 * Handles matching POS tags against indexed data.
 * 
 * POS Condition Logic:
 *   - Simple match: POS(tag) -> Finds sentences containing the specified tag.
 *   - Match with term: POS(tag, term) -> Finds sentences where 'term' has the specified tag.
 *   - Variable binding: POS(tag) AS var -> Binds var to the tag string itself.
 *   - Variable consumption: POS(tag, var) -> Filters sentences where 'var' (must be text) has the tag.
 * This executor reflects the basic index structure where keys are POS tags
 * and values are lists of all positions for that tag.
 * It supports:
 *   - Basic lookup: POS(tag) -> Returns matches with the tag as the value.
 *   - Variable binding: POS(tag) AS ?var -> Binds ?var to the tag string itself.
 * It does NOT support:
 *   - Term specification: POS(tag, 'term')
 * Returns QueryResult containing MatchDetail objects.
 */
public final class PosExecutor implements ConditionExecutor<Pos> {
    private static final Logger logger = LoggerFactory.getLogger(PosExecutor.class);
    
    private static final String POS_INDEX = "pos";
    
    /**
     * Creates a new POS executor.
     */
    public PosExecutor() {
        // No initialization required
    }

    @Override
    public QueryResult execute(Pos condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName)
        throws QueryExecutionException {
        
        logger.debug("Executing basic POS condition for tag {} at {} granularity with size {} (corpus: {})", 
                condition.posTag(), granularity, granularitySize, corpusName);
        
        // Validate that this condition is used correctly according to the basic index structure.
        // Term specification is not supported by this index.
        if (condition.term() != null) {
            throw new QueryExecutionException(
                "Term specification (e.g., POS(tag, 'term')) is not supported by the basic POS executor. " +
                "Specific term/tag lookup requires a different index structure (e.g., stitch index).",
                condition.toString(), QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }
        // Variable binding IS supported, but it binds to the tag itself.
        
        // Validate required indexes
        if (!indexes.containsKey(POS_INDEX)) {
            throw new QueryExecutionException(
                "Missing required POS index",
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }
        
        String posTag = condition.posTag();
        boolean isVariable = condition.isVariable();
        String variableName = condition.variableName(); // May be null if not isVariable
        
        // Normalize POS tag to lowercase (aligns with POSIndexGenerator)
        String normalizedPosTag = posTag.toLowerCase();
        
        logger.debug("POS condition details: tag='{}', isVariable={}, variableName='{}' (basic lookup/binding)",
                     normalizedPosTag, isVariable, variableName != null ? variableName : "(none)");
        
        // Get the POS index
        IndexAccessInterface index = indexes.get(POS_INDEX);
        
        if (index == null) {
            throw new QueryExecutionException(
                "Required index not found: " + POS_INDEX,
                condition.toString(),
                QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR
            );
        }
        
        List<MatchDetail> details = new ArrayList<>();
        String conditionId = String.valueOf(condition.hashCode());
        
        try {
            // Search key is just the normalized POS tag
            byte[] keyBytes = normalizedPosTag.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Optional<PositionListSoA> positionsOptSoA = index.get(keyBytes);

            if (positionsOptSoA.isPresent()) {
                PositionListSoA positionListSoA = positionsOptSoA.get();
                String valueString = posTag; 

                for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                    // Use SoA-native access instead of reconstructing Position objects
                    MatchDetail detail = new MatchDetail(
                        valueString,        
                        ValueType.POS_TERM,    
                        isVariable ? variableName : null,
                        positionListSoA.getDocIdAt(i),
                        positionListSoA.getSentenceIdAt(i),
                        positionListSoA.getBeginCharAt(i),
                        positionListSoA.getEndCharAt(i),
                        positionListSoA.getSynonymIdAt(i)
                    );
                    details.add(detail);
                }
                logger.debug("Found {} positions for POS tag '{}'", details.size(), normalizedPosTag);
            } else {
                 logger.debug("POS tag '{}' not found in index", normalizedPosTag);
                 // No details found, details list remains empty.
            }

            logger.debug("POS condition produced {} MatchDetail objects. Returning QueryResult.",
                        details.size());
            
            // Create QueryResult directly
            QueryResult finalResult = new QueryResult(granularity, granularitySize, details);

            logger.debug("POS execution complete with {} MatchDetail objects.", finalResult.getAllDetails().size());
            return finalResult;
        } catch (Exception e) {
            // Catch specific IndexAccessException or others if needed
            if (e instanceof QueryExecutionException qee) {
                throw qee;
            }
            throw new QueryExecutionException(
                "Error executing POS condition: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }
    }
    
    @Override
    public QueryResult execute(Pos condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {
        
        logger.debug("Executing POS condition with AttributeRequirements: {}", requirements.getRequiredSoAAttributes());
        
        // Validate that this condition is used correctly according to the basic index structure.
        if (condition.term() != null) {
            throw new QueryExecutionException(
                "Term specification (e.g., POS(tag, 'term')) is not supported by the basic POS executor. " +
                "Specific term/tag lookup requires a different index structure (e.g., stitch index).",
                condition.toString(), QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }
        
        // Validate required indexes
        if (!indexes.containsKey(POS_INDEX)) {
            throw new QueryExecutionException(
                "Missing required POS index",
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }
        
        String posTag = condition.posTag();
        boolean isVariable = condition.isVariable();
        String variableName = condition.variableName();
        
        // Normalize POS tag to lowercase (aligns with POSIndexGenerator)
        String normalizedPosTag = posTag.toLowerCase();
        
        logger.debug("POS condition details: tag='{}', isVariable={}, variableName='{}' (selective deserialization)",
                     normalizedPosTag, isVariable, variableName != null ? variableName : "(none)");
        
        // Get the POS index
        IndexAccessInterface index = indexes.get(POS_INDEX);
        
        List<MatchDetail> details = new ArrayList<>();
        
        try {
            // Use selective deserialization for better performance
            byte[] keyBytes = normalizedPosTag.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Optional<byte[]> rawBlob = index.getRaw(keyBytes);

            if (rawBlob.isPresent()) {
                try {
                    int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob.get());
                    logger.debug("Found {} positions for POS tag '{}' (using selective deserialization)", numPositions, normalizedPosTag);
                    
                    if (numPositions == 0) {
                        return new QueryResult(granularity, granularitySize, details);
                    }
                    
                    // Selective deserialization based on requirements
                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob.get());
                    
                    IntArrayList sentIds = requirements.needsSentenceId ? 
                        PositionListSoA.decompressSentenceIds(rawBlob.get()) : null;
                    
                    IntArrayList beginChars = requirements.needsPositions ? 
                        PositionListSoA.decompressBeginChars(rawBlob.get()) : null;
                    
                    IntArrayList endChars = requirements.needsPositions ? 
                        PositionListSoA.decompressEndChars(rawBlob.get()) : null;
                    
                    IntArrayList synonymIds = requirements.needsSynonymIds ? 
                        PositionListSoA.decompressSynonymIds(rawBlob.get()) : null;
                    
                    String valueString = posTag;
                    
                    // Create MatchDetail objects directly from SoA arrays
                    for (int i = 0; i < numPositions; i++) {
                        details.add(new MatchDetail(
                            valueString,        
                            ValueType.POS_TERM,    
                            isVariable ? variableName : null,
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                            synonymIds != null ? synonymIds.getInt(i) : -1
                        ));
                    }
                    
                    logger.debug("Selective deserialization for POS '{}': docIds={}, sentIds={}, positions={}, synonymIds={}", 
                               normalizedPosTag, true, sentIds != null, beginChars != null, synonymIds != null);
                    
                } catch (Exception e) {
                    logger.warn("Error during selective deserialization for POS tag '{}', falling back to full deserialization: {}", 
                               normalizedPosTag, e.getMessage());
                    // Fall back to the existing method if selective deserialization fails
                    return execute(condition, indexes, granularity, granularitySize, corpusName);
                }
            } else {
                logger.debug("POS tag '{}' not found in index", normalizedPosTag);
            }

            logger.debug("POS condition with selective deserialization produced {} MatchDetail objects", details.size());
            return new QueryResult(granularity, granularitySize, details);
            
        } catch (Exception e) {
            if (e instanceof QueryExecutionException qee) {
                throw qee;
            }
            throw new QueryExecutionException(
                "Error executing POS condition: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }
    }
} 