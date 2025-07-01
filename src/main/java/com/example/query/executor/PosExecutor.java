package com.example.query.executor;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

/**
 * Executor for POS conditions.
 * Handles matching POS tags against indexed data.
 *
 * POS Condition Logic:
 *   - Simple match: POS(tag) -> Finds sentences containing the specified tag. Value stored is the tag itself (e.g. "NN") with ValueType.POS_TAG_TYPE.
 *   - Match with term: POS(tag, term) -> Finds sentences where 'term' has the specified tag. Value stored is 'term' (e.g. "apple") with ValueType.POS_TERM.
 *   - Variable binding: POS(tag) BIND var -> Binds var to the 'term' (e.g. "apple") that has the tag. ValueType.POS_TERM.
 */
public final class PosExecutor implements ConditionExecutor<Pos> {
    private static final Logger logger = LoggerFactory.getLogger(PosExecutor.class);
    private static final String ALL_POS_TAGS_WILDCARD = "*";
    private static final String POS_INDEX_NAME = "pos";
    private final SynonymManager synonymManager;

    /**
     * Creates a new POS executor.
     * @param synonymManager The synonym manager instance.
     */
    public PosExecutor(SynonymManager synonymManager) {
        this.synonymManager = synonymManager;
    }

    @Override
    public QueryResultSoA execute(Pos condition, Map<String, IndexAccessInterface> indexes, Query.Granularity granularity,
                                 int granularitySize, String corpusName, AttributeRequirements requirements,
                                 Optional<FilteringContext> context)
            throws QueryExecutionException {
        logger.debug(">>> Executing PosExecutor");
        logger.debug("Executing POS condition: {}, AttrReqs: {}, ContextIsPresent: {}",
                     condition, requirements, context.isPresent());

        IndexAccessInterface posIndex = indexes.get(POS_INDEX_NAME);
        if (posIndex == null) {
            throw new QueryExecutionException("POS index not found in provided indexes.", condition.toString(), QueryExecutionException.ErrorType.MISSING_INDEX);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);

        String tagFromQuery = condition.posTag().toUpperCase();
        String termFromQuery = condition.term();
        String variableName = condition.variableName();

        logger.debug("POS condition details: tag='{}', term='{}', isVariable={}, variableName='{}'",
                     tagFromQuery, termFromQuery, (variableName != null), variableName != null ? variableName : "(none)");

        if (ALL_POS_TAGS_WILDCARD.equals(tagFromQuery)) {
            throw new QueryExecutionException(
                "Wildcard POS tag (*) is not supported for direct execution. Use specific tags or variable binding for tags.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
            );
        }
        if (termFromQuery != null && termFromQuery.contains("*")) {
            throw new QueryExecutionException(
                "Wildcard in target term ('" + termFromQuery + "') for POS condition is not supported.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }

        try {
            if (termFromQuery != null) {
                logger.debug("POS path: Specific Term Search. Tag='{}', TermFromQuery='{}', VarName='{}'", tagFromQuery, termFromQuery, variableName);
                executeSpecificTermSearch(tagFromQuery, termFromQuery, variableName, posIndex, requirements, resultSoA, context);
            } else {
                logger.debug("POS path: Tag-Only or Variable Binding to Term. Tag='{}', VarName='{}'", tagFromQuery, variableName);
                executeTagOnlyOrVariableTermSearch(tagFromQuery, variableName, posIndex, requirements, resultSoA, context);
            }
        } catch (IOException e) {
            logger.error("IOException during POS condition execution: {}", e.getMessage(), e);
            throw new QueryExecutionException("Unexpected IO error executing POS condition: " + e.getMessage(),
                                            e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        } catch (IndexAccessException e) {
            logger.error("IndexAccessException during POS condition execution: {}", e.getMessage(), e);
            throw new QueryExecutionException("Unexpected index access error executing POS condition: " + e.getMessage(),
                                            e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (org.rocksdb.RocksDBException rde) {
            logger.error("RocksDBException during POS condition execution: {}", rde.getMessage(), rde);
            throw new QueryExecutionException("RocksDB error executing POS condition: " + rde.getMessage(),
                                            rde, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }

        logger.debug("POS condition execution produced {} conceptual rows, total SoA size: {}",
                     resultSoA.getConceptualRowCount(), resultSoA.size());

        // Sort by document ID to ensure merge join optimization works correctly
        resultSoA.sort();

        return resultSoA;
    }

    private void executeSpecificTermSearch(String tagFromQuery, String termFromQuery, String variableName, IndexAccessInterface index,
                                           AttributeRequirements requirements, QueryResultSoA resultSoA,
                                           Optional<FilteringContext> context)
            throws IOException, IndexAccessException, org.rocksdb.RocksDBException {

        if (!requirements.needsSynonymIds && variableName == null) {
            logger.warn("executeSpecificTermSearch called for Tag='{}', Term='{}' but AttributeRequirements.needsSynonymIds is false. Efficient filtering by synonym ID is not possible.",
                        tagFromQuery, termFromQuery);
        }

        String normalizedTargetTerm = termFromQuery.toLowerCase();
        int targetSynonymId = synonymManager.getId(normalizedTargetTerm);

        logger.debug("executeSpecificTermSearch: Tag='{}', TermValue='{}' (original), NormalizedTerm='{}', TargetSynonymID={}",
            tagFromQuery, termFromQuery, normalizedTargetTerm, targetSynonymId);

        Optional<PositionListSoA> positionsOptional = index.getMergedPositions(tagFromQuery, context);

        if (!positionsOptional.isPresent() || positionsOptional.get().isEmpty()) {
            logger.debug("executeSpecificTermSearch: No data found for POS tag '{}' after getMergedPositions (with context filtering)", tagFromQuery);
            return;
        }

        PositionListSoA positions = positionsOptional.get();

        if (positions.isEmpty()) {
            logger.debug("executeSpecificTermSearch: No positions for tag '{}' after context filtering (positions.isEmpty() check).", tagFromQuery);
            return;
        }

        int numPositionsTotal = positions.getNumPositions();
        if (numPositionsTotal == 0) return; // Should be caught by positions.isEmpty() but defensive check.

        int conceptualRowId = -1;
        int positionsAddedToSoa = 0;

        for (int i = 0; i < numPositionsTotal; i++) {
            if (positions.getSynonymIdAt(i) == targetSynonymId) {
                if (conceptualRowId == -1) {
                    conceptualRowId = resultSoA.getNextConceptualRowId();
                }
                resultSoA.add(
                    termFromQuery,
                    ValueType.POS_TERM,
                    variableName,
                    positions.getDocIdAt(i),
                    requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                    requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                    requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                    targetSynonymId,
                    conceptualRowId
                );
                positionsAddedToSoa++;
            }
        }
        logger.debug("executeSpecificTermSearch for Tag '{}', Term '{}' added {} positions to QueryResultSoA under conceptualRowId {}",
            tagFromQuery, termFromQuery, positionsAddedToSoa, conceptualRowId != -1 ? conceptualRowId : "(none)");
    }

    private void executeTagOnlyOrVariableTermSearch(String tagFromQuery, String variableName, IndexAccessInterface index,
                                                    AttributeRequirements requirements, QueryResultSoA resultSoA,
                                                    Optional<FilteringContext> context)
            throws IOException, IndexAccessException, org.rocksdb.RocksDBException, QueryExecutionException {

        Optional<PositionListSoA> positionsOptional = index.getMergedPositions(tagFromQuery, context);

        if (!positionsOptional.isPresent() || positionsOptional.get().isEmpty()) {
            logger.debug("executeTagOnlyOrVariableTermSearch: No data found for POS tag '{}' after getMergedPositions (with context filtering)", tagFromQuery);
            return;
        }

        PositionListSoA positions = positionsOptional.get();
        // The positions object is already filtered by the context.

        if (positions.isEmpty()) { // Defensive check
            logger.debug("executeTagOnlyOrVariableTermSearch: No positions for tag '{}' after context filtering (positions.isEmpty() check).", tagFromQuery);
            return;
        }

        int numPositions = positions.getNumPositions();
        logger.debug("executeTagOnlyOrVariableTermSearch: Positions found for '{}'. numPositions: {}", tagFromQuery, numPositions);

        if (numPositions == 0) return;

        if (variableName != null) {
            // Collect unique synonym IDs
            Set<Integer> uniqueSynonymIds = new HashSet<>();
            for (int i = 0; i < numPositions; i++) {
                uniqueSynonymIds.add(positions.getSynonymIdAt(i));
            }

            // Fetch terms in a batch
            Map<Integer, String> resolvedTermsCache = Collections.emptyMap(); // Default to empty
            if (!uniqueSynonymIds.isEmpty()) {
                try {
                    resolvedTermsCache = synonymManager.getTerms(uniqueSynonymIds);
                    logger.debug("executeTagOnlyOrVariableTermSearch: Batch fetched {} terms for {} unique synonym IDs for POS tag '{}'.",
                                 resolvedTermsCache.size(), uniqueSynonymIds.size(), tagFromQuery);
                } catch (org.rocksdb.RocksDBException e) {
                    logger.error("RocksDBException while batch fetching terms in POS variable binding for Tag '{}'", tagFromQuery, e);
                    // Propagate as a QueryExecutionException or a more specific custom exception if desired
                    throw new QueryExecutionException("Failed to batch fetch terms from SynonymManager for POS BIND",
                                                    e, "POS(" + tagFromQuery + ") BIND " + variableName,
                                                    QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
                }
            }

            Map<String, Integer> resolvedTermToConceptualRowId = new java.util.HashMap<>();
            // Map<Integer, String> resolvedSynonymIdToTermCache = new java.util.HashMap<>(); // REMOVED

            for (int i = 0; i < numPositions; i++) {
                int currentSynonymId = positions.getSynonymIdAt(i);
                String termToBind = resolvedTermsCache.get(currentSynonymId);

                if (termToBind == null) {
                    // This can happen if a synonym ID was in positions but not resolvable by getTerms (e.g. not in DB, cache issue)
                    logger.warn("executeTagOnlyOrVariableTermSearch: No term found in pre-fetched cache for synonymId {} (tag: {}). Skipping.",
                                currentSynonymId, tagFromQuery);
                    continue;
                }

                int conceptualRowId = resolvedTermToConceptualRowId.computeIfAbsent(termToBind, k -> resultSoA.getNextConceptualRowId());

                resultSoA.add(
                    termToBind,
                    ValueType.POS_TERM,
                    variableName,
                    positions.getDocIdAt(i),
                    requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                    requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                    requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                    currentSynonymId,
                    conceptualRowId
                );
            }
            logger.debug("executeTagOnlyOrVariableTermSearch for Tag '{}' BIND '{}' added {} conceptual rows and {} total bindings.",
                tagFromQuery, variableName, resolvedTermToConceptualRowId.size(), resultSoA.size());

        } else { // Tag-only search, no variable binding for the term itself
            int conceptualRowIdForTag = resultSoA.getNextConceptualRowId();
            int positionsAddedToSoa = 0;
            for (int i = 0; i < numPositions; i++) {
                resultSoA.add(
                    tagFromQuery, // Value is the tag itself
                    ValueType.POS_TAG_TYPE,
                    null, // No variable name for the tag value
                    positions.getDocIdAt(i),
                    requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                    requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                    requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                    -1, // No specific synonym ID is relevant when just matching the tag
                    conceptualRowIdForTag
                );
                positionsAddedToSoa++;
            }
            logger.debug("executeTagOnlyOrVariableTermSearch for Tag '{}' (no BIND) added {} positions to QueryResultSoA under conceptualRowId {}",
                tagFromQuery, positionsAddedToSoa, conceptualRowIdForTag);
        }
    }
}