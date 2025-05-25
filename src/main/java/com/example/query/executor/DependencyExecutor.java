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
        
        // For now, delegate to the existing method
        // TODO: Implement SoA optimization using requirements in Step 3
        return execute(condition, indexes, granularity, granularitySize, corpusName);
    }
} 