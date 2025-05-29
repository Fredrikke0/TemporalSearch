package com.example.query.executor;

import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.SubquerySpec;
import com.example.query.executor.QueryResultSoA;
import com.example.query.executor.AttributeRequirements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.example.core.Position;

import static org.junit.jupiter.api.Assertions.*;

class SubqueryContextTest {

    private SubqueryContext context;
    private SubquerySpec subquery1;
    private SubquerySpec subquery2;
    private QueryResultSoA results1;
    private QueryResultSoA results2;

    @BeforeEach
    void setUp() {
        context = new SubqueryContext();

        Query baseQuery1 = new Query("source1");
        Query baseQuery2 = new Query("source2");
        subquery1 = new SubquerySpec(baseQuery1, "sq1");
        subquery2 = new SubquerySpec(baseQuery2, "sq2", Optional.of(List.of("col1", "col2")));

        AttributeRequirements requirements = AttributeRequirements.forPhase1Compatibility();

        results1 = new QueryResultSoA(Query.Granularity.SENTENCE, requirements);
        results1.add("source1", ValueType.TERM, null, 1, 1, 0, 7, -1, 0);
        results1.add("source1", ValueType.TERM, null, 2, 2, 0, 7, -1, 0);

        results2 = new QueryResultSoA(Query.Granularity.DOCUMENT, requirements);
        results2.add("source2", ValueType.TERM, null, 3, -1, 0, 7, -1, 0);
    }

    @Test
    void testAddAndGetQueryResult() {
        context.addQueryResult(subquery1, results1);

        assertEquals(results1, context.getQueryResult("sq1"));
        assertNull(context.getQueryResult("sq2"));

        context.addQueryResult(subquery2, results2);

        assertEquals(results1, context.getQueryResult("sq1"));
        assertEquals(results2, context.getQueryResult("sq2"));
    }

    @Test
    void testHasResults() {
        assertFalse(context.hasResults("sq1"));
        assertFalse(context.hasResults("sq2"));

        context.addQueryResult(subquery1, results1);
        assertTrue(context.hasResults("sq1"));
        assertFalse(context.hasResults("sq2"));

        context.addQueryResult(subquery2, results2);
        assertTrue(context.hasResults("sq1"));
        assertTrue(context.hasResults("sq2"));
    }

    @Test
    void testGetAliases() {
        assertTrue(context.getAliases().isEmpty());

        context.addQueryResult(subquery1, results1);
        context.addQueryResult(subquery2, results2);

        assertEquals(2, context.getAliases().size());
        assertTrue(context.getAliases().contains("sq1"));
        assertTrue(context.getAliases().contains("sq2"));
    }

    @Test
    void testNullParameters() {
        assertThrows(NullPointerException.class, () -> context.addQueryResult((SubquerySpec) null, results1));
        assertThrows(NullPointerException.class, () -> context.addQueryResult(subquery1, null));
    }
}