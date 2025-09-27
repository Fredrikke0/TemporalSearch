package com.example.query.executor;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.presence.RBPresenceIndex;
import com.example.query.executor.presence.PresenceReducer;
import com.example.query.model.Query;
import com.example.query.model.condition.Logical;

/**
 * Executor for logical conditions (AND, OR).
 * Presence-only evaluation using roaring bitmaps.
 */
public final class LogicalExecutor implements ConditionExecutor<Logical> {
    private static final Logger logger = LoggerFactory.getLogger(LogicalExecutor.class);

    private final ConditionExecutorFactory executorFactory;
    private final String stitchStrategy;
    private final Query.Granularity queryGranularity;

    public LogicalExecutor(ConditionExecutorFactory executorFactory, String stitchStrategy, Query.Granularity queryGranularity) {
        this.executorFactory = executorFactory;
        this.stitchStrategy = stitchStrategy;
        this.queryGranularity = queryGranularity;
    }

    @Override
    public QueryResultSoA execute(Logical condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements,
                               Optional<FilteringContext> context)
        throws QueryExecutionException {
        return executeInternal(condition, indexes, granularity, granularitySize, corpusName, requirements, context);
    }

    private QueryResultSoA executeInternal(Logical condition, Map<String, IndexAccessInterface> indexes,
                                      Query.Granularity granularity,
                                      int granularitySize,
                                      String corpusName,
                                      AttributeRequirements requirements,
                                      Optional<FilteringContext> context)
        throws QueryExecutionException {

        logger.debug(">>> Executing LogicalExecutor (presence-only)");
        logger.debug("Executing logical condition: operator={}, subconditions={}, granularity={}, size={}, corpus={}, requirements={}, contextIsPresent={}",
                condition.operator(), condition.conditions().size(), granularity, granularitySize, corpusName, requirements, context.isPresent());

        // If any subcondition produces variables, skip presence-only reduction so value columns get populated
        boolean hasProducedVars = !condition.getProducedVariables().isEmpty();
        Optional<RBPresenceIndex> presenceOpt = Optional.empty();
        if (!hasProducedVars) {
            // Reduce entire logical tree to a single presence bitmap
            presenceOpt = PresenceReducer.tryReduceConditionToPresence(condition, indexes);
        } else {
            logger.debug("Skipping presence-only reduction because the logical subtree produces variables: {}", condition.getProducedVariables());
        }
        if (presenceOpt.isPresent()) {
            RBPresenceIndex presence = presenceOpt.get();
            QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
            int added = 0;
            org.roaringbitmap.longlong.LongIterator it = presence.getBitmap().getLongIterator();
            while (it.hasNext()) {
                long pair = it.next();
                int docId = (int)(pair >>> 16);
                int sentId = (int)(pair & 0xFFFFL);
                if (!passesContextFilter(context, requirements, granularity, docId, sentId)) continue;
                resultSoA.add(
                    null,
                    com.example.query.binding.ValueType.TERM,
                    null,
                    docId,
                    requirements.needsSentenceId && granularity == Query.Granularity.SENTENCE ? sentId : -1,
                    -1,
                    -1,
                    -1,
                    resultSoA.getNextConceptualRowId()
                );
                added++;
            }
            logger.debug("Presence-based logical evaluation added {} rows.", added);
            resultSoA.sort();
            return resultSoA;
        }

        // Fallback: execute subconditions and combine results in-memory with basic context propagation
        java.util.List<com.example.query.model.condition.Condition> subs = condition.conditions();
        if (subs == null || subs.isEmpty()) {
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        if (condition.operator() == Logical.LogicalOperator.OR) {
            java.util.List<QueryResultSoA> results = new java.util.ArrayList<>(subs.size());
            for (com.example.query.model.condition.Condition sub : subs) {
                ConditionExecutor<com.example.query.model.condition.Condition> exec = this.executorFactory.getExecutor(sub);
                results.add(exec.execute(sub, indexes, granularity, granularitySize, corpusName, requirements, context));
            }
            return combineOr(results, granularity, requirements);
        } else { // AND
            Optional<FilteringContext> current = context.isPresent() ? context : Optional.of(FilteringContext.unrestricted(granularity));
            ConditionExecutor<com.example.query.model.condition.Condition> exec0 = this.executorFactory.getExecutor(subs.get(0));
            QueryResultSoA acc = exec0.execute(subs.get(0), indexes, granularity, granularitySize, corpusName, requirements, current);
            if (acc.isEmpty()) return acc;
            current = Optional.of(current.get().intersect(acc));
            for (int i = 1; i < subs.size(); i++) {
                ConditionExecutor<com.example.query.model.condition.Condition> ex = this.executorFactory.getExecutor(subs.get(i));
                QueryResultSoA next = ex.execute(subs.get(i), indexes, granularity, granularitySize, corpusName, requirements, current);
                if (next.isEmpty()) return next;
                acc = combineAnd(java.util.List.of(acc, next), granularity, requirements);
                current = Optional.of(current.get().intersect(next));
                if (current.get().allowedDocumentIds().isPresent() && current.get().allowedDocumentIds().get().isEmpty()) {
            return new QueryResultSoA(granularity, granularitySize, requirements);
                }
            }
            return acc;
        }
    }

    private boolean passesContextFilter(Optional<FilteringContext> context, AttributeRequirements requirements, Query.Granularity granularity, int docId, int sentId) {
        if (context.isEmpty()) return true;
        FilteringContext fc = context.get();
        if (granularity == Query.Granularity.SENTENCE && requirements.needsSentenceId) {
            if (fc.allowedDocumentSentenceIds().isPresent()) {
                var map = fc.allowedDocumentSentenceIds().get();
                var set = map.get(docId);
                return set == null || set.contains(sentId);
            }
        } else if (fc.allowedDocumentIds().isPresent()) {
            return fc.allowedDocumentIds().get().contains(docId);
        }
                return true;
    }

    private QueryResultSoA combineOr(java.util.List<QueryResultSoA> inputs, Query.Granularity granularity, AttributeRequirements requirements) throws QueryExecutionException {
        AttributeRequirements req = new AttributeRequirements();
        req.merge(requirements);
        req.needsConceptualRowIds = true;
        QueryResultSoA out = new QueryResultSoA(granularity, inputs.get(0).getGranularitySize(), req);
        int base = 0;
        for (QueryResultSoA in : inputs) {
            for (int i = 0; i < in.size(); i++) {
                out.add(
                    in.getValueAt(i), in.getValueTypeAt(i), in.getVariableNameAt(i),
                    in.getDocumentIdAt(i), req.needsSentenceId && in.getRequirements().needsSentenceId ? in.getSentenceIdAt(i) : -1,
                    req.needsPositions && in.getRequirements().needsPositions ? in.getBeginCharAt(i) : -1,
                    req.needsPositions && in.getRequirements().needsPositions ? in.getEndCharAt(i) : -1,
                    req.needsSynonymIds && in.getRequirements().needsSynonymIds ? in.getSynonymIdAt(i) : -1,
                    (in.getRequirements().needsConceptualRowIds ? in.getConceptualRowIdAt(i) : 0) + base
                );
            }
            // Advance base by unique conceptual rows seen in this input
            java.util.Set<Integer> unique = new java.util.HashSet<>();
            if (in.getRequirements().needsConceptualRowIds) {
                for (int i = 0; i < in.size(); i++) unique.add(in.getConceptualRowIdAt(i));
            }
            base += in.getRequirements().needsConceptualRowIds ? unique.size() : 0;
        }
        out.sort();
        return out;
    }

    private QueryResultSoA combineAnd(java.util.List<QueryResultSoA> inputs, Query.Granularity granularity, AttributeRequirements requirements) throws QueryExecutionException {
        if (inputs.isEmpty()) return new QueryResultSoA(granularity, 0, requirements);
        QueryResultSoA left = inputs.get(0);
        for (int i = 1; i < inputs.size(); i++) {
            QueryResultSoA right = inputs.get(i);
            AttributeRequirements req = new AttributeRequirements();
            req.merge(left.getRequirements());
            req.merge(right.getRequirements());
            req.merge(requirements);
            req.needsConceptualRowIds = true;
            QueryResultSoA out = new QueryResultSoA(granularity, left.getGranularitySize(), req);
            java.util.Map<Integer, java.util.List<Integer>> leftByDoc = new java.util.HashMap<>();
            for (int li = 0; li < left.size(); li++) {
                leftByDoc.computeIfAbsent(left.getDocumentIdAt(li), k -> new java.util.ArrayList<>()).add(li);
            }
            for (int ri = 0; ri < right.size(); ri++) {
                int doc = right.getDocumentIdAt(ri);
                java.util.List<Integer> lidx = leftByDoc.get(doc);
                if (lidx == null) continue;
                Integer sentR = req.needsSentenceId && right.getRequirements().needsSentenceId ? right.getSentenceIdAt(ri) : null;
                for (int li : lidx) {
                    Integer sentL = req.needsSentenceId && left.getRequirements().needsSentenceId ? left.getSentenceIdAt(li) : null;
                    if (sentR != null && sentL != null && !sentR.equals(sentL)) continue;
                    int newConcept = out.getNextConceptualRowId();
                    out.add(left.getValueAt(li), left.getValueTypeAt(li), left.getVariableNameAt(li), doc,
                            req.needsSentenceId && left.getRequirements().needsSentenceId ? left.getSentenceIdAt(li) : -1,
                            req.needsPositions && left.getRequirements().needsPositions ? left.getBeginCharAt(li) : -1,
                            req.needsPositions && left.getRequirements().needsPositions ? left.getEndCharAt(li) : -1,
                            req.needsSynonymIds && left.getRequirements().needsSynonymIds ? left.getSynonymIdAt(li) : -1,
                            newConcept);
                    out.add(right.getValueAt(ri), right.getValueTypeAt(ri), right.getVariableNameAt(ri), doc,
                            req.needsSentenceId && right.getRequirements().needsSentenceId ? right.getSentenceIdAt(ri) : -1,
                            req.needsPositions && right.getRequirements().needsPositions ? right.getBeginCharAt(ri) : -1,
                            req.needsPositions && right.getRequirements().needsPositions ? right.getEndCharAt(ri) : -1,
                            req.needsSynonymIds && right.getRequirements().needsSynonymIds ? right.getSynonymIdAt(ri) : -1,
                            newConcept);
                }
            }
            left = out;
            if (left.isEmpty()) break;
        }
        left.sort();
        return left;
    }
}