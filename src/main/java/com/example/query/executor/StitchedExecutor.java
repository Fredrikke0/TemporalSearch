package com.example.query.executor;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.RocksIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.index.KeySchema;
import com.example.index.util.SynonymManager;
import com.example.query.model.Query;
import com.example.query.model.TemporalBounds;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.StitchedCondition;
import com.example.query.model.condition.Temporal;

/**
 * Executor for stitched (fused CONTAINS + annotation) conditions.
 * Leverages pre-built stitch indexes that co-locate n-gram and annotation
 * data using the new {@code KeySchema}/{@code CellResult} interface.
 *
 * <p>
 * Stitch keys follow the format {@code ngramKey\0type\0<4-byte synId>}
 * (see {@link KeySchema#encodeStitchKey(String, String, int)}). For general
 * annotation lookups (no specific annotation value), a prefix scan over
 * {@code ngramKey\0type\0} is performed. For specific annotation values, the
 * value is resolved to a synonym ID and an exact key is used.
 *
 * <p>
 * Temporal stitch keys use {@code "DATE"} as the type and the date encoded
 * as a {@code YYYYMMDD} integer as the synonym ID.
 */
public final class StitchedExecutor implements ConditionExecutor<StitchedCondition> {
    private static final Logger logger = LoggerFactory.getLogger(StitchedExecutor.class);
    private static final char DELIMITER_CHAR = IndexAccessInterface.DELIMITER;

    private final SynonymManager synonymManager;

    public StitchedExecutor(SynonymManager synonymManager) {
        if (synonymManager == null) {
            throw new IllegalArgumentException("SynonymManager cannot be null for StitchedExecutor");
        }
        this.synonymManager = synonymManager;
    }

    @Override
    public CellResult execute(
            StitchedCondition stitchedCondition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String sourceName,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells) throws QueryExecutionException {

        logger.debug(">>> Executing StitchedExecutor");
        Contains containsCondition = stitchedCondition.containsCondition();
        Condition annotationCondition = stitchedCondition.annotationCondition();

        List<String> terms = containsCondition.terms();
        if (terms == null || terms.isEmpty() || terms.size() > 3) {
            logger.warn("StitchedExecutor requires 1 to 3 terms for CONTAINS. Found: {}. Returning empty result.",
                    terms != null ? terms.size() : "null");
            return CellResult.empty(granularity);
        }

        // --- Build n-gram key ---
        int ngramLevel = terms.size();
        String ngramTerm;
        String ngramPrefix;

        if (ngramLevel == 1) {
            ngramTerm = terms.get(0).toLowerCase();
            ngramPrefix = "unigram";
        } else if (ngramLevel == 2) {
            ngramTerm = terms.get(0).toLowerCase() + DELIMITER_CHAR + terms.get(1).toLowerCase();
            ngramPrefix = "bigram";
        } else { // ngramLevel == 3
            ngramTerm = terms.get(0).toLowerCase() + DELIMITER_CHAR
                    + terms.get(1).toLowerCase() + DELIMITER_CHAR
                    + terms.get(2).toLowerCase();
            ngramPrefix = "trigram";
        }

        // --- Determine stitch index group and annotation type ---
        String stitchIndexGroupIdentifier;
        String annotationTypeForKey;
        Set<Integer> targetAnnotationSynIds = new HashSet<>();
        boolean hasSpecificAnnotationValue = false;

        Temporal temporalConditionDetails = null;

        if (annotationCondition instanceof Ner nerCond) {
            String nerEntityType = nerCond.entityType().toUpperCase();
            if ("DATE".equals(nerEntityType)) {
                logger.warn("Stitch optimization for NER type DATE is handled by Temporal stitch. Returning empty.");
                return CellResult.empty(granularity);
            }
            stitchIndexGroupIdentifier = "ner";
            annotationTypeForKey = nerEntityType;

            // Resolve specific NER targets to synonym IDs
            if (!nerCond.targets().isEmpty()) {
                hasSpecificAnnotationValue = true;
                resolveNerTargets(nerCond.targets(), targetAnnotationSynIds);
                if (targetAnnotationSynIds.isEmpty()) {
                    logger.warn("No valid synonym IDs for NER targets {} in StitchedExecutor. Returning empty.",
                            nerCond.targets());
                    return CellResult.empty(granularity);
                }
            }
        } else if (annotationCondition instanceof Pos posCond) {
            stitchIndexGroupIdentifier = "pos";
            annotationTypeForKey = posCond.posTag().toUpperCase();

            // Resolve specific POS term to synonym ID
            if (posCond.term() != null && !posCond.term().isBlank()) {
                hasSpecificAnnotationValue = true;
                resolvePosTerm(posCond.term(), targetAnnotationSynIds);
                if (targetAnnotationSynIds.isEmpty()) {
                    logger.warn("No valid synonym ID for POS term '{}' in StitchedExecutor. Returning empty.",
                            posCond.term());
                    return CellResult.empty(granularity);
                }
            }
        } else if (annotationCondition instanceof Temporal tempCond) {
            stitchIndexGroupIdentifier = "date";
            annotationTypeForKey = "DATE";
            temporalConditionDetails = tempCond;
        } else {
            logger.warn("Unsupported annotation condition type for stitch optimization: {}. Returning empty.",
                    annotationCondition.getType());
            return CellResult.empty(granularity);
        }

        String stitchIndexName = "stitch_" + ngramPrefix + "_" + stitchIndexGroupIdentifier;
        IndexAccessInterface stitchIndex = indexes.get(stitchIndexName);

        if (stitchIndex == null || !stitchIndex.isOpen()) {
            logger.warn("Stitch index '{}' not found or not open. Returning empty.", stitchIndexName);
            return CellResult.empty(granularity);
        }

        PostingList.DeserializeMode mode = requirements.toDeserializeMode();
        CellResult result;

        try {
            if (temporalConditionDetails != null) {
                // Temporal stitch: prefix scan, evaluate temporal condition per key
                result = executeTemporalStitchSearch(ngramTerm, temporalConditionDetails,
                        stitchIndex, granularity, mode);
            } else if (hasSpecificAnnotationValue) {
                // Specific NER/POS annotation: exact key lookups
                result = executeSpecificAnnotationStitchSearch(ngramTerm, annotationTypeForKey,
                        targetAnnotationSynIds, stitchIndex, granularity, mode);
            } else {
                // General NER/POS annotation (no specific value): prefix scan
                result = executeGeneralAnnotationStitchSearch(ngramTerm, annotationTypeForKey,
                        stitchIndex, granularity, mode);
            }

            // Apply allowedCells filtering if present
            if (allowedCells.isPresent() && !allowedCells.get().isEmpty()) {
                logger.debug("Applying allowedCells filter with {} cells",
                        allowedCells.get().getLongCardinality());
                result = result.and(CellResult.of(allowedCells.get(), granularity));
            }

        } catch (IndexAccessException e) {
            logger.error("Error accessing stitch index {}.", stitchIndexName, e);
            throw new QueryExecutionException("Error accessing stitch index " + stitchIndexName,
                    e, sourceName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (Exception e) {
            if (e instanceof QueryExecutionException qee) {
                throw qee;
            }
            logger.error("Unexpected exception during StitchedExecutor execution for index {}.",
                    stitchIndexName, e);
            String message = "Unexpected error during stitch execution for " + stitchIndexName;
            if (e.getClass().getName().contains("RocksDBException")) {
                message = "Database error during stitch execution for " + stitchIndexName;
            }
            throw new QueryExecutionException(message, e, sourceName,
                    QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        logger.debug("StitchedExecutor finished for type '{}', N-gram '{}', index '{}'. Result has {} cells.",
                stitchedCondition.stitchType(), ngramPrefix, stitchIndexName, result.cellCount());

        return result;
    }

    /**
     * Temporal stitch: prefix scan for {@code ngramKey\0DATE\0}, decode date
     * from each key, evaluate temporal condition, and OR matching cells.
     */
    private CellResult executeTemporalStitchSearch(String ngramKey, Temporal temporalCond,
            IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {
        byte[] prefix = KeySchema.encodeStitchPrefix(ngramKey, "DATE");
        logger.debug("Temporal stitch prefix scan for ngram '{}' (prefix hex: {})",
                ngramKey, bytesToHex(prefix));

        CellResult result = CellResult.empty(granularity);

        try (RocksIterator iter = index.seek(prefix)) {
            int keysExamined = 0;
            int matched = 0;
            while (iter.isValid()) {
                byte[] keyBytes = iter.key();
                if (!startsWith(keyBytes, prefix)) {
                    break;
                }
                keysExamined++;

                // Decode key to extract date (synId)
                int dateEncoded;
                try {
                    KeySchema.DecodedStitchKey decoded = KeySchema.decodeStitchKey(keyBytes);
                    dateEncoded = decoded.synId();
                } catch (IllegalArgumentException e) {
                    logger.warn("Failed to decode stitch key ({} bytes), skipping.", keyBytes.length);
                    iter.next();
                    continue;
                }

                LocalDate dateFromKey = dateFromEncodedInt(dateEncoded);
                if (dateFromKey == null) {
                    logger.warn("Could not parse date from encoded int {} for key of {} bytes. Skipping.",
                            dateEncoded, keyBytes.length);
                    iter.next();
                    continue;
                }

                // Check bounds
                if (dateFromKey.isBefore(TemporalBounds.LOWER) || dateFromKey.isAfter(TemporalBounds.UPPER)) {
                    iter.next();
                    continue;
                }

                // Evaluate temporal condition
                boolean matches = TemporalExecutor.evaluateTemporalCondition(
                        temporalCond.temporalType(),
                        dateFromKey.atStartOfDay(),
                        dateFromKey.atTime(LocalTime.MAX),
                        temporalCond.startDate().orElse(null),
                        temporalCond.endDate().orElse(null));

                if (matches) {
                    matched++;
                    Optional<PostingList> plOpt = index.getPostingList(keyBytes, mode);
                    if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
                        CellResult cr = toCellResult(plOpt.get(), granularity, mode);
                        result = result.or(cr);
                    }
                }
                iter.next();
            }
            logger.debug("Temporal stitch: examined {} keys, {} matched temporal condition, result has {} cells",
                    keysExamined, matched, result.cellCount());
        }

        return result;
    }

    /**
     * Specific NER/POS annotation stitch: look up exact keys
     * {@code ngramKey\0type\0<synId>} for each target synonym ID.
     */
    private CellResult executeSpecificAnnotationStitchSearch(String ngramKey, String type,
            Set<Integer> synIds,
            IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {
        logger.debug("Specific annotation stitch search: ngram='{}', type='{}', synIds={}",
                ngramKey, type, synIds);

        CellResult result = CellResult.empty(granularity);
        for (int synId : synIds) {
            byte[] key = KeySchema.encodeStitchKey(ngramKey, type, synId);
            Optional<PostingList> plOpt = index.getPostingList(key, mode);
            if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
                CellResult cr = toCellResult(plOpt.get(), granularity, mode);
                result = result.or(cr);
            }
        }

        logger.debug("Specific annotation stitch search: result has {} cells", result.cellCount());
        return result;
    }

    /**
     * General NER/POS annotation stitch (no specific annotation value):
     * prefix scan for {@code ngramKey\0type\0} and OR all posting lists.
     */
    private CellResult executeGeneralAnnotationStitchSearch(String ngramKey, String type,
            IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {
        byte[] prefix = KeySchema.encodeStitchPrefix(ngramKey, type);
        logger.debug("General annotation stitch prefix scan for ngram '{}', type '{}'",
                ngramKey, type);

        CellResult result = CellResult.empty(granularity);

        try (RocksIterator iter = index.seek(prefix)) {
            int keysExamined = 0;
            while (iter.isValid()) {
                byte[] keyBytes = iter.key();
                if (!startsWith(keyBytes, prefix)) {
                    break;
                }
                keysExamined++;

                Optional<PostingList> plOpt = index.getPostingList(keyBytes, mode);
                if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
                    CellResult cr = toCellResult(plOpt.get(), granularity, mode);
                    result = result.or(cr);
                }
                iter.next();
            }
            logger.debug("General annotation stitch: examined {} keys, result has {} cells",
                    keysExamined, result.cellCount());
        }

        return result;
    }

    // --- Target resolution helpers ---

    private void resolveNerTargets(List<String> targets, Set<Integer> outSynIds) {
        for (String target : targets) {
            if (target != null && !target.isBlank()) {
                try {
                    int synId = synonymManager.getId(target.toLowerCase());
                    outSynIds.add(synId);
                    logger.trace("Stitch NER target '{}' -> synId {}", target, synId);
                } catch (Exception e) {
                    logger.warn("Failed to get synonym ID for NER target '{}': {}", target, e.getMessage());
                }
            }
        }
    }

    private void resolvePosTerm(String term, Set<Integer> outSynIds) {
        try {
            int synId = synonymManager.getId(term.toLowerCase());
            outSynIds.add(synId);
            logger.trace("Stitch POS term '{}' -> synId {}", term, synId);
        } catch (Exception e) {
            logger.warn("Failed to get synonym ID for POS term '{}': {}", term, e.getMessage());
        }
    }

    // --- Conversion / utility helpers ---

    /**
     * Converts a PostingList to a CellResult, choosing the appropriate factory
     * based on deserialization mode.
     */
    private static CellResult toCellResult(PostingList pl, Query.Granularity granularity,
            PostingList.DeserializeMode mode) {
        if (mode == PostingList.DeserializeMode.FULL) {
            return CellResult.fromPostingListWithOccurrences(pl, granularity);
        }
        return CellResult.fromPostingList(pl, granularity);
    }

    /**
     * Converts a date encoded as {@code YYYYMMDD} integer (e.g. 20240115) to a
     * {@link LocalDate}. Returns {@code null} on invalid values.
     */
    static LocalDate dateFromEncodedInt(int encoded) {
        int year = encoded / 10000;
        int month = (encoded % 10000) / 100;
        int day = encoded % 100;
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Checks if {@code array} starts with {@code prefix}. */
    private static boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Simple hex dump for debug logging. */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
