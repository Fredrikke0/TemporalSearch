package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.PostingList;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.SubquerySpec;

class SubqueryContextTest {

    private SubqueryContext context;
    private SubquerySpec subquery1;
    private SubquerySpec subquery2;
    private CellResult results1;
    private CellResult results2;

    @BeforeEach
    void setUp() {
        context = new SubqueryContext();

        Query baseQuery1 = new Query("source1");
        Query baseQuery2 = new Query("source2");
        subquery1 = new SubquerySpec(baseQuery1, "sq1");
        subquery2 = new SubquerySpec(baseQuery2, "sq2", Optional.of(List.of("col1", "col2")));

        Roaring64NavigableMap cells1 = new Roaring64NavigableMap();
        cells1.add(PostingList.packCellKey(1, 1));
        cells1.add(PostingList.packCellKey(2, 2));
        Bindings bindings1 = Bindings.builder()
                .add("source1", ValueType.TERM, null)
                .add("source1", ValueType.TERM, null)
                .build();
        results1 = CellResult.of(cells1, bindings1, Query.Granularity.SENTENCE);

        Roaring64NavigableMap cells2 = new Roaring64NavigableMap();
        cells2.add(PostingList.packCellKey(3, 0));
        Bindings bindings2 = Bindings.builder()
                .add("source2", ValueType.TERM, null)
                .build();
        results2 = CellResult.of(cells2, bindings2, Query.Granularity.DOCUMENT);
    }

    @Test
    void testAddAndGetQueryResult() {
        context.addQueryResult(subquery1.alias(), results1);

        assertEquals(results1, context.getQueryResult("sq1"));
        assertNull(context.getQueryResult("sq2"));

        context.addQueryResult(subquery2.alias(), results2);

        assertEquals(results1, context.getQueryResult("sq1"));
        assertEquals(results2, context.getQueryResult("sq2"));
    }

    @Test
    void testHasResults() {
        assertFalse(context.hasResults("sq1"));
        assertFalse(context.hasResults("sq2"));

        context.addQueryResult(subquery1.alias(), results1);
        assertTrue(context.hasResults("sq1"));
        assertFalse(context.hasResults("sq2"));

        context.addQueryResult(subquery2.alias(), results2);
        assertTrue(context.hasResults("sq1"));
        assertTrue(context.hasResults("sq2"));
    }

    @Test
    void testGetAliases() {
        assertTrue(context.getAliases().isEmpty());

        context.addQueryResult(subquery1.alias(), results1);
        context.addQueryResult(subquery2.alias(), results2);

        assertEquals(2, context.getAliases().size());
        assertTrue(context.getAliases().contains("sq1"));
        assertTrue(context.getAliases().contains("sq2"));
    }

    @Test
    void testNullParameters() {
        assertThrows(NullPointerException.class, () -> context.addQueryResult((String) null, results1));
        assertThrows(NullPointerException.class, () -> context.addQueryResult(subquery1.alias(), null));
    }
}
