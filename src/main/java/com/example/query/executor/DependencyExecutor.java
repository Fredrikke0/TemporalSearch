package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.core.IndexAccessException;
import com.example.query.model.Query;
import com.example.query.model.condition.Dependency;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import org.iq80.leveldb.DBIterator;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executor for DEPENDENCY conditions.
 */
public final class DependencyExecutor implements ConditionExecutor<Dependency> {
    private static final Logger logger = LoggerFactory.getLogger(DependencyExecutor.class);
    private static final String DEPENDENCY_INDEX_NAME = "dependency";

    // No state needed, can be stateless
    public DependencyExecutor() {}

    @Override
    // Use interface in signature
    public QueryResult execute(Dependency condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName)
        throws QueryExecutionException {

        logger.debug("Executing DEPENDENCY condition: {} (corpus: {})", condition, corpusName);

        // Use interface type
        IndexAccessInterface dependencyIndex = indexes.get(DEPENDENCY_INDEX_NAME);
        if (dependencyIndex == null) {
            throw new QueryExecutionException(
                "Dependency index ('" + DEPENDENCY_INDEX_NAME + "') not found.",
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX);
        }
        
        List<MatchDetail> details = new ArrayList<>();
        String governor = condition.governor();
        String dependent = condition.dependent();
        String relation = condition.relation();
        boolean isVariable = condition.isVariable();
        String variableName = condition.variableName();
        String conditionId = String.valueOf(condition.hashCode());

        // Always use specific search if all parts are provided literals
        // The 'isVariable' flag now only affects MatchDetail tagging.
        // TODO: Consider logic for when governor/dependent are variable references (e.g., DEPENDS(?g, 'rel', 'dep'))
        if (governor != null && !governor.startsWith("?") && 
            dependent != null && !dependent.startsWith("?") && 
            relation != null) {
            details.addAll(executeSpecificSearch(dependencyIndex, governor, dependent, relation, isVariable, variableName, conditionId));
        } 
        // Placeholder for potential future logic if governor/dependent are variables to consume
        // else if (isVariableReference(governor) || isVariableReference(dependent)) {
        //     logger.warn("Variable consumption in DEPENDENCY not yet implemented: {}", condition);
        // } 
        else {
            logger.warn("Unsupported or incomplete DEPENDENCY condition: {}. Governor, relation, and dependent must be specified literals for now.", condition);
            // Consider throwing an exception for unsupported cases
        }

        return new QueryResult(granularity, granularitySize, details);
    }

    private List<MatchDetail> executeSpecificSearch(
            IndexAccessInterface index,
            String governor,
            String dependent,
            String relation,
            boolean isVariable,
            String variableName,
            String conditionId)
        throws QueryExecutionException {
        
        try {
            // Normalize terms
            String normalizedGovernor = governor.toLowerCase();
            String normalizedDependent = dependent.toLowerCase();
            String normalizedRelation = relation.toLowerCase();
            
            // Create the search key in format "governor<DELIM>relation<DELIM>dependent"
            // Use constant from interface
            String searchKey = normalizedGovernor + IndexAccessInterface.DELIMITER + 
                               normalizedRelation + IndexAccessInterface.DELIMITER + 
                               normalizedDependent;
            byte[] keyBytes = searchKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            Optional<PositionListSoA> positionsOptSoA = index.get(keyBytes);
            
            if (positionsOptSoA.isPresent()) {
                PositionListSoA positionListSoA = positionsOptSoA.get();
                String value = String.join(":", normalizedGovernor, normalizedRelation, normalizedDependent);
                List<MatchDetail> resultDetails = new ArrayList<>();
                for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                    Position pos = positionListSoA.getPositionAt(i);
                    resultDetails.add(new MatchDetail(value, ValueType.DEPENDENCY, pos, isVariable ? variableName : null));
                }
                return resultDetails;
            } else {
                return Collections.emptyList();
            }
        } catch (IndexAccessException e) {
             throw new QueryExecutionException("Error accessing dependency index for specific search", e, "DEPENDENCY", QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }
    }

    private List<MatchDetail> executeVariableSearch(
            IndexAccessInterface index,
            String relation, // Relation is required for variable search prefix
            boolean isVariable,
            String variableName,
            String conditionId)
        throws QueryExecutionException {
        
        List<MatchDetail> details = new ArrayList<>();
        if (relation == null || relation.isEmpty()) {
             logger.warn("Relation is required for variable search in DEPENDENCY condition");
             return details;
        }
        
        String prefix = relation.toLowerCase() + IndexAccessInterface.DELIMITER;
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        logger.debug("Executing variable search with prefix: {}", prefix);
        
        try (DBIterator iterator = index.iterator()) {
            iterator.seek(prefixBytes);

            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
                String key = new String(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8);

                // Check if the key still starts with the prefix
                if (!key.startsWith(prefix)) {
                    break; // Moved past relevant keys
                }

                // Extract governor, relation, dependent from key
                String[] parts = key.split(String.valueOf(IndexAccessInterface.DELIMITER));
                if (parts.length == 3) {
                    String gov = parts[0];
                    String rel = parts[1]; // Should match input relation (case-insensitively)
                    String dep = parts[2];
                    
                    // Deserialize PositionListSoA
                    PositionListSoA positionListSoA = PositionListSoA.deserializeFromCompositeBlob(entry.getValue());
                    String value = String.join("/", gov, rel, dep); 
                    
                    for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                        // Use SoA-native access instead of reconstructing Position objects
                        details.add(new MatchDetail(
                            value, 
                            ValueType.DEPENDENCY, 
                            isVariable ? variableName : null,
                            positionListSoA.getDocIdAt(i),
                            positionListSoA.getSentenceIdAt(i),
                            positionListSoA.getBeginCharAt(i),
                            positionListSoA.getEndCharAt(i),
                            positionListSoA.getSynonymIdAt(i)
                        ));
                    }
                } else {
                     logger.warn("Skipping invalid key format in dependency index: {}", key);
                }
            }
        } catch (Exception e) { // Catch IndexAccessException, IOException, RuntimeException from deserialize
            throw new QueryExecutionException("Error during variable search in dependency index", e, "DEPENDENCY", QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }
        
        return details;
    }

    @Override
    public QueryResult execute(Dependency condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {
        
        logger.debug("Executing DEPENDENCY condition with AttributeRequirements: {}", requirements.getRequiredSoAAttributes());
        
        // Validate required indexes
        if (!indexes.containsKey(DEPENDENCY_INDEX_NAME)) {
            throw new QueryExecutionException(
                "Missing required dependency index",
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }
        
        IndexAccessInterface index = indexes.get(DEPENDENCY_INDEX_NAME);
        if (index == null) {
            throw new QueryExecutionException(
                "Required index not found: " + DEPENDENCY_INDEX_NAME,
                condition.toString(),
                QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR
            );
        }
        
        List<MatchDetail> details = new ArrayList<>();
        
        try {
            if (condition.isVariable()) {
                // Variable binding mode - extract dependency relations
                details = executeVariableSearchOptimized(condition, index, requirements);
            } else {
                // Search mode - find specific dependency relation
                details = executeSpecificSearchOptimized(condition, index, requirements);
            }
            
            logger.debug("DEPENDENCY condition with selective deserialization produced {} MatchDetail objects", details.size());
            return new QueryResult(granularity, granularitySize, details);
            
        } catch (Exception e) {
            if (e instanceof QueryExecutionException qee) {
                throw qee;
            }
            throw new QueryExecutionException(
                "Error executing DEPENDENCY condition: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }
    }
    
    /**
     * Executes variable search for dependency relations using selective deserialization.
     */
    private List<MatchDetail> executeVariableSearchOptimized(Dependency condition, IndexAccessInterface index, AttributeRequirements requirements)
        throws Exception {
        
        List<MatchDetail> details = new ArrayList<>();
        String variableName = condition.variableName();
        
        logger.debug("Executing variable search for dependency relations (selective deserialization)");
        
        try (DBIterator iterator = index.iterator()) {
            iterator.seekToFirst();
            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
                String key = new String(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8);
                
                // Parse dependency relation from key (format: "governor:dependent:relation")
                String[] parts = key.split(":");
                if (parts.length == 3) {
                    String relation = parts[2]; // The dependency relation type
                    
                    // Use selective deserialization
                    byte[] rawBlob = entry.getValue();
                    try {
                        int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
                        
                        if (numPositions == 0) {
                            continue;
                        }
                        
                        // Selective deserialization based on requirements
                        IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
                        
                        IntArrayList sentIds = requirements.needsSentenceId ? 
                            PositionListSoA.decompressSentenceIds(rawBlob) : null;
                        
                        IntArrayList beginChars = requirements.needsPositions ? 
                            PositionListSoA.decompressBeginChars(rawBlob) : null;
                        
                        IntArrayList endChars = requirements.needsPositions ? 
                            PositionListSoA.decompressEndChars(rawBlob) : null;
                        
                        IntArrayList synonymIds = requirements.needsSynonymIds ? 
                            PositionListSoA.decompressSynonymIds(rawBlob) : null;
                        
                        // Create MatchDetail objects directly from SoA arrays
                        for (int i = 0; i < numPositions; i++) {
                            details.add(new MatchDetail(
                                relation,
                                ValueType.DEPENDENCY,
                                variableName,
                                docIds.getInt(i),
                                sentIds != null ? sentIds.getInt(i) : -1,
                                beginChars != null ? beginChars.getInt(i) : -1,
                                endChars != null ? endChars.getInt(i) : -1,
                                synonymIds != null ? synonymIds.getInt(i) : -1
                            ));
                        }
                    } catch (Exception e) {
                        logger.warn("Error during selective deserialization for dependency key '{}', falling back to full deserialization: {}", 
                                   key, e.getMessage());
                        // Fall back to full deserialization for this entry
                        PositionListSoA positionListSoA = PositionListSoA.deserializeFromCompositeBlob(rawBlob);
                        for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                            details.add(new MatchDetail(
                                relation,
                                ValueType.DEPENDENCY,
                                variableName,
                                positionListSoA.getDocIdAt(i),
                                positionListSoA.getSentenceIdAt(i),
                                positionListSoA.getBeginCharAt(i),
                                positionListSoA.getEndCharAt(i),
                                positionListSoA.getSynonymIdAt(i)
                            ));
                        }
                    }
                } else {
                    logger.warn("Skipping invalid key format in dependency index: {}", key);
                }
            }
        }
        
        logger.debug("Variable search found {} dependency relations using selective deserialization", details.size());
        return details;
    }
    
    /**
     * Executes specific search for dependency relations using selective deserialization.
     */
    private List<MatchDetail> executeSpecificSearchOptimized(Dependency condition, IndexAccessInterface index, AttributeRequirements requirements)
        throws Exception {
        
        List<MatchDetail> details = new ArrayList<>();
        String searchKey = condition.governor() + ":" + condition.dependent() + ":" + condition.relation();
        
        logger.debug("Searching for specific dependency relation: {} (selective deserialization)", searchKey);
        
        byte[] keyBytes = searchKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Optional<byte[]> rawBlob = index.getRaw(keyBytes);
        
        if (rawBlob.isPresent()) {
            try {
                int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob.get());
                logger.debug("Found {} positions for dependency relation '{}' (using selective deserialization)", numPositions, searchKey);
                
                if (numPositions == 0) {
                    return details;
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
                
                // Create MatchDetail objects directly from SoA arrays
                for (int i = 0; i < numPositions; i++) {
                    details.add(new MatchDetail(
                        condition.relation(),
                        ValueType.DEPENDENCY,
                        (String) null,
                        docIds.getInt(i),
                        sentIds != null ? sentIds.getInt(i) : -1,
                        beginChars != null ? beginChars.getInt(i) : -1,
                        endChars != null ? endChars.getInt(i) : -1,
                        synonymIds != null ? synonymIds.getInt(i) : -1
                    ));
                }
                
                logger.debug("Selective deserialization for dependency '{}': docIds={}, sentIds={}, positions={}, synonymIds={}", 
                           searchKey, true, sentIds != null, beginChars != null, synonymIds != null);
                
            } catch (Exception e) {
                logger.warn("Error during selective deserialization for dependency '{}', falling back to full deserialization: {}", 
                           searchKey, e.getMessage());
                // Fall back to the existing method if selective deserialization fails
                return executeSpecificSearch(index, condition.governor(), condition.dependent(), condition.relation(), false, null, String.valueOf(condition.hashCode()));
            }
        } else {
            logger.debug("No positions found for dependency relation: '{}'", searchKey);
        }
        
        return details;
    }
} 