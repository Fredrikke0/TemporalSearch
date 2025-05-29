package com.example.index;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.pig.impl.util.MultiMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import no.ntnu.sandbox.Nash;

@DisplayName("Nash Predicate Granularity Tests (Observation Only)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class) // Run tests in defined order for clearer logs
class NashPredicateTest {
    private static final Logger logger = LoggerFactory.getLogger(NashPredicateTest.class);

    private static List<String> indexedIntervals; // Renamed for clarity
    private static MultiMap<String, Integer> invertedIndex; // Prefix -> List<Original Interval Index>

    @BeforeAll
    static void setUpIndex() throws UnsupportedEncodingException, IOException {
        // Define intervals testing month/day sensitivity
        indexedIntervals = List.of(
            "[2000-08-20 , 2000-08-20]", // 0: Early Point
            "[2005-02-10 , 2005-02-10]", // 1: Mid Point
            "[2005-01-01 , 2005-12-31]", // 2: Year Range containing Index 1
            "[2010-04-05 , 2010-04-05]", // 3: Late Point
            "[2004-12-31 , 2004-12-31]", // 4: Point just before Range 2
            "[2006-01-01 , 2006-01-01]"  // 5: Point just after Range 2

        );

        // Build the inverted index using Nash.invert (mimics current indexing)
        invertedIndex = Nash.invert(indexedIntervals);
        logger.info("--- Test Inverted Index built (Granularity Test) ---");
        logger.info("Indexed Intervals:");
        for (int i = 0; i < indexedIntervals.size(); i++) {
            logger.info("  Index {}: {}", i, indexedIntervals.get(i));
        }
        logger.info("Total Prefixes in Index Map: {}", invertedIndex.size());
        logger.info("------------------------------------------------");
    }

    // Helper method to simulate prefix lookup and log results
    private void runQueryForAllPredicates(String queryLabel, String queryInterval) {
        logger.info("\n--- Testing Query Scenario: {} ({}) ---", queryLabel, queryInterval);
        boolean foundAnyMatch = false;
        for (Nash.RangePredicate predicate : Nash.RangePredicate.values()) {
            String[] queryPrefixes = Nash.generateTimeHash(queryInterval, predicate);
            Set<Integer> matchedIndices = new HashSet<>();
            int prefixesChecked = 0;
            int prefixesFound = 0;

            for (String prefix : queryPrefixes) {
                prefixesChecked++;
                if (invertedIndex.containsKey(prefix)) {
                    prefixesFound++;
                    matchedIndices.addAll(invertedIndex.get(prefix));
                }
            }
            logger.info("  Predicate: {} | Prefixes: (Generated:{} Checked:{} Found:{}) | Matched Indices: {}",
                               String.format("%-12s", predicate),
                               queryPrefixes.length,
                               prefixesChecked,
                               prefixesFound,
                               (matchedIndices.isEmpty() ? "[]" : matchedIndices.stream().sorted().map(Object::toString).collect(Collectors.joining(", ", "[", "]"))));
            if (!matchedIndices.isEmpty()) {
                foundAnyMatch = true;
            }
        }
         logger.info("-------------------------------------------------------------");

    }

    // --- Test Cases Start Here ---

    @Test @Order(1)
    @DisplayName("Query Before All")
    void testQueryBefore() {
        runQueryForAllPredicates("Before All", "[1999-06-01 , 1999-06-01]");
    }

    @Test @Order(2)
    @DisplayName("Query Identical to Index 0")
    void testQueryIdentical() {
        runQueryForAllPredicates("Identical (Index 0)", "[2000-08-20 , 2000-08-20]");
    }

    @Test @Order(3)
    @DisplayName("Query Within Index 2 Range (Diff Month)")
    void testQueryWithinRange() {
        runQueryForAllPredicates("Within Range (Index 2, Diff Month)", "[2005-09-15 , 2005-09-15]");
    }

    @Test @Order(4)
    @DisplayName("Query After All")
    void testQueryAfter() {
        runQueryForAllPredicates("After All", "[2011-01-01 , 2011-01-01]");
    }

    @Test @Order(5)
    @DisplayName("Query Overlapping Point Index 1")
    void testQueryOverlapPoint() {
        runQueryForAllPredicates("Overlap Point (Index 1)", "[2005-02-08 , 2005-02-12]");
    }

    @Test @Order(6)
    @DisplayName("Query Overlapping Start of Index 2 (Cross Year)")
    void testQueryOverlapRangeStart() {
        runQueryForAllPredicates("Overlap Range Start (Index 2, Cross Year)", "[2004-12-25 , 2005-01-05]");
    }

    @Test @Order(7)
    @DisplayName("Query Overlapping End of Index 2 (Cross Year)")
    void testQueryOverlapRangeEnd() {
        runQueryForAllPredicates("Overlap Range End (Index 2, Cross Year)", "[2005-12-25 , 2006-01-05]");
    }

    @Test @Order(8)
    @DisplayName("Query Contains Index 2 (and 4, 5)")
    void testQueryContainsRange() {
        runQueryForAllPredicates("Contains Range (Index 2, 4, 5)", "[2004-11-01 , 2006-02-01]");
    }

    @Test @Order(9)
    @DisplayName("Query Year 2005 (Exact Match to Index 2, Should find Index 1)")
    void testQueryYear2005() {
        runQueryForAllPredicates("Year 2005 (Matches Index 2)", "[2005-01-01 , 2005-12-31]");
    }

}