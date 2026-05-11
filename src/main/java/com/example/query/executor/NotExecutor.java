package com.example.query.executor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.rocksdb.RocksIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Not;

/**
 * Executes a NOT condition: returns all cells in the universe that are
 * <em>not</em> matched by the sub-condition.
 *
 * <p>
 * The universe is taken from {@code allowedCells} when present, or
 * approximated by iterating every posting list in the unigram index.
 */
public final class NotExecutor implements ConditionExecutor<Not> {
    private static final Logger logger = LoggerFactory.getLogger(NotExecutor.class);
    private static final String UNIGRAM_INDEX_NAME = "unigram";

    private final ConditionExecutorFactory factory;

    public NotExecutor(ConditionExecutorFactory factory) {
        this.factory = factory;
        logger.info("NotExecutor initialized.");
    }

    @Override
    public CellResult execute(Not condition, Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException {

        logger.debug(">>> Executing NotExecutor (granularity={}, allowedCellsPresent={})",
                granularity, allowedCells.isPresent());

        // 1. Build the universe
        Roaring64NavigableMap universe;
        if (allowedCells.isPresent()) {
            universe = allowedCells.get().clone();
            logger.debug("Using allowedCells as universe: {} cells", universe.getLongCardinality());
        } else {
            universe = buildUniverseFromUnigramIndex(indexes);
            logger.debug("Built universe from unigram index: {} cells", universe.getLongCardinality());
        }

        if (universe.isEmpty() && allowedCells.isPresent()) {
            logger.warn("Universe (allowedCells) for NOT is empty; returning empty result.");
            return CellResult.empty(granularity);
        }

        // 2. Execute the sub-condition
        Condition subCondition = condition.condition();
        ConditionExecutor<Condition> subExecutor = factory.getExecutor(subCondition);

        CellResult subResult = subExecutor.execute(subCondition, indexes, granularity,
                granularitySize, corpusName, requirements, Optional.empty());
        logger.debug("Sub-condition returned {} cells", subResult.cellCount());

        // 3. Complement: universe minus sub-result cells
        Roaring64NavigableMap complement = universe.clone();
        complement.andNot(subResult.cells());
        logger.debug("NOT complement: {} cells (universe={}, sub={})",
                complement.getLongCardinality(), universe.getLongCardinality(),
                subResult.cellCount());

        return CellResult.of(complement, granularity);
    }

    /**
     * Iterates every entry in the unigram index and unions all cell bitmaps
     * into a single {@link Roaring64NavigableMap}.
     */
    private Roaring64NavigableMap buildUniverseFromUnigramIndex(
            Map<String, IndexAccessInterface> indexes) throws QueryExecutionException {

        IndexAccessInterface unigramIndex = indexes.get(UNIGRAM_INDEX_NAME);
        if (unigramIndex == null) {
            throw new QueryExecutionException(
                    "Required index '" + UNIGRAM_INDEX_NAME + "' is missing for NOT operation.",
                    "N/A",
                    QueryExecutionException.ErrorType.MISSING_INDEX);
        }

        Roaring64NavigableMap universe = new Roaring64NavigableMap();

        try (RocksIterator it = unigramIndex.iterateFromFirst()) {
            while (it.isValid()) {
                byte[] keyBytes = it.key();
                byte[] valueBytes = it.value();
                if (valueBytes != null && valueBytes.length > 0) {
                    try {
                        PostingList pl = PostingList.deserialize(valueBytes,
                                PostingList.DeserializeMode.CELLS_ONLY);
                        universe.or(pl.cells());
                    } catch (IOException e) {
                        logger.warn("Failed to deserialize PostingList for key '{}' in '{}': {}",
                                new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8),
                                UNIGRAM_INDEX_NAME, e.getMessage());
                    } catch (Exception e) {
                        logger.warn("Error processing entry for key '{}' in '{}': {}",
                                new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8),
                                UNIGRAM_INDEX_NAME, e.getMessage());
                    }
                }
                it.next();
            }
        } catch (Exception e) {
            logger.error("Failed to iterate through '{}' index: {}", UNIGRAM_INDEX_NAME, e.getMessage(), e);
            throw new QueryExecutionException(
                    "Error accessing index '" + UNIGRAM_INDEX_NAME + "' for NOT operation.",
                    e,
                    "N/A",
                    QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }

        if (universe.isEmpty()) {
            logger.error("Universe for NOT operation is empty. Check if '{}' index exists and is populated.",
                    UNIGRAM_INDEX_NAME);
            throw new QueryExecutionException(
                    "Could not determine the set of all possible matches (universe is empty). "
                            + "Check if '" + UNIGRAM_INDEX_NAME + "' index exists and is populated.",
                    "N/A",
                    QueryExecutionException.ErrorType.MISSING_INDEX);
        }

        return universe;
    }
}
