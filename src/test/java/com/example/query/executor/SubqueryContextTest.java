package com.example.query.executor;

import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.SubquerySpec;
import com.example.query.executor.QueryResult;
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
    private QueryResult results1;
    private QueryResult results2;

    @BeforeEach
    void setUp() {
        context = new SubqueryContext();
        
        Query baseQuery1 = new Query("source1");
        Query baseQuery2 = new Query("source2");
        subquery1 = new SubquerySpec(baseQuery1, "sq1");
        subquery2 = new SubquerySpec(baseQuery2, "sq2", Optional.of(List.of("col1", "col2")));
        
        results1 = new QueryResult(Query.Granularity.SENTENCE, List.of(
            createMatchDetail(1, 1, "source1"),
            createMatchDetail(2, 2, "source1")
        ));
        results2 = new QueryResult(Query.Granularity.DOCUMENT, List.of(
            createMatchDetail(3, "source2")
        ));
    }

    private MatchDetail createMatchDetail(int docId, int sentenceId, String value) {
        Position pos = new Position(docId, sentenceId, 0, value.length());
        return new MatchDetail(value, ValueType.TERM, pos, (String) null);
    }

    private MatchDetail createMatchDetail(int docId, String value) {
        Position pos = new Position(docId, -1, 0, value.length());
        return new MatchDetail(value, ValueType.TERM, pos, (String) null);
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