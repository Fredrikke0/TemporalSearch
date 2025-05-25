package com.example.performance;

import com.example.core.Position;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.tablesaw.api.*;
import tech.tablesaw.selection.Selection;
import tech.tablesaw.aggregate.AggregateFunctions;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Disabled;

/**
 * Performance tests comparing Set<MatchDetail> and Tablesaw.
 * This test creates large datasets and performs operations simulating query processing.
 */
@Disabled("Disabled for regular builds - run manually if needed") // Disable test
public class TablesawQueryPerformanceTest {
    private static final Logger logger = LoggerFactory.getLogger(TablesawQueryPerformanceTest.class);
    
    // Constants for test data generation
    private static final int NUM_DOCS = 10_000;
    private static final int MAX_SENTENCES_PER_DOC = 50;
    private static final int NUM_CONDITION_IDS = 5; // Simulate different conditions producing matches
    private static final LocalDate START_DATE = LocalDate.of(2020, 1, 1);
    private static final String[] SOURCES = {"wikipedia", "news", "academic", "social"}; // Example sources
    
    @BeforeAll
    public static void setup() {
        // Setup if needed, e.g., warming up JVM
        logger.info("Setting up performance test environment...");
    }
    
    @ParameterizedTest
    @ValueSource(ints = {10_000, 100_000, 1_000_000}) // Test different sizes
    @DisplayName("Compare performance: Set<MatchDetail> vs Tablesaw")
    public void comparePerformance(int rowCount) {
        logger.info("===== Running performance test with {} target matches =====", rowCount);
        
        // Generate test data for MatchDetail
        logger.info("Generating MatchDetail data...");
        Set<MatchDetail> matchSetMD = generateMatchDetailSet(rowCount);
        
        // Convert MatchDetail set to Tablesaw Table
        logger.info("Converting MatchDetail set to Tablesaw Table...");
        Table table = convertMatchDetailToTablesaw(matchSetMD);
        
        logger.info("Generated {} MD matches, and Tablesaw table with {} rows",
                matchSetMD.size(), table.rowCount());
        
        // --- Benchmark typical operations ---
        
        // Filter by document ID
        runBenchmark("Filter by documentId",
            () -> filterByDocumentIdMD(matchSetMD, NUM_DOCS / 2),
            () -> filterByDocumentIdTablesaw(table, NUM_DOCS / 2));
        
        // Filter by value type
        runBenchmark("Filter operation (ValueType)",
            () -> filterByValueTypeMD(matchSetMD, ValueType.ENTITY),
            () -> filterByValueTypeTablesaw(table, "ENTITY"));
        
        // Intersection operation (Simulating AND)
        runBenchmark("Join/Intersection operation",
            () -> intersectSetsMD(matchSetMD),
            () -> intersectSetsTablesaw(table));
        
        // Group by document
        runBenchmark("Group by document",
            () -> groupByDocumentMD(matchSetMD),
            () -> groupByDocumentTablesaw(table));
        
        logger.info("===== Performance test with {} matches complete =====", rowCount);
    }
    
    @Test
    @DisplayName("Test memory usage: Set<MatchDetail> vs Tablesaw")
    public void compareMemoryUsage() {
        int size = 1_000_000; // Use a fixed large size for memory test
        logger.info("===== Comparing memory usage for approximately {} matches =====", size);
        
        // Force garbage collection before starting measurements
        System.gc();
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // 1. Measure Set<MatchDetail>
        logger.info("Generating and measuring Set<MatchDetail>...");
        Set<MatchDetail> matchSetMD = generateMatchDetailSet(size);
        System.gc();
        long memAfterMD = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long setMDMemory = memAfterMD - memBefore;
        int actualMDSize = matchSetMD.size(); // Capture actual size
        
        // 2. Measure Tablesaw Table (derived from MatchDetail)
        logger.info("Converting MatchDetail set to Tablesaw and measuring...");
        Table table = convertMatchDetailToTablesaw(matchSetMD);
        matchSetMD = null; // Release MatchDetail set memory
        System.gc();
        long memAfterTablesaw = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long tablesawMemory = memAfterTablesaw - memBefore; // Measure against initial state
        table = null; // Release table memory
        System.gc();
        
        logger.info("--- Memory Usage Comparison (Approx {} Matches) ---", size);
        logger.info("Set<MatchDetail>      ({} items): {} MB", actualMDSize, setMDMemory / (1024 * 1024));
        logger.info("Tablesaw Table        ({} rows) : {} MB", actualMDSize, tablesawMemory / (1024 * 1024));
        logger.info("===================================================");
    }
    
    // --- Data Generation Methods ---
    
    /** Generate Set<MatchDetail> */
    private Set<MatchDetail> generateMatchDetailSet(int targetSize) {
        Set<MatchDetail> matches = new HashSet<>();
        Random random = new Random(123); // Fixed seed for reproducibility
        
        while (matches.size() < targetSize) {
            int docId = random.nextInt(NUM_DOCS);
            int sentId = random.nextInt(MAX_SENTENCES_PER_DOC);
            int begin = random.nextInt(1000);
            int end = begin + random.nextInt(10) + 1;
            Position pos = new Position(docId, sentId, begin, end);
            
            String conditionId = "C" + random.nextInt(NUM_CONDITION_IDS);
            String variableName = random.nextDouble() < 0.2 ? "?var" + random.nextInt(2) : null; // 20% chance of variable
            
            // Simulate different value types
            Object value;
            ValueType valueType;
            int typeSelector = random.nextInt(4);
            if (typeSelector == 0) {
                value = "term" + random.nextInt(1000);
                valueType = ValueType.TERM;
            } else if (typeSelector == 1) {
                value = "ENTITY" + random.nextInt(50);
                valueType = ValueType.ENTITY;
            } else if (typeSelector == 2) {
                value = START_DATE.plus(random.nextInt(365), ChronoUnit.DAYS);
                valueType = ValueType.DATE;
            } else {
                // No specific numeric type, use TERM for test data generation
                value = "numeric_term_" + (random.nextDouble() * 100);
                valueType = ValueType.TERM;
            }
            
            matches.add(new MatchDetail(value, valueType, pos, variableName));
        }
        return matches;
    }
    
    /** Convert Set<MatchDetail> to Tablesaw Table */
    private Table convertMatchDetailToTablesaw(Set<MatchDetail> matches) {
        // Define columns based on MatchDetail fields needed for benchmarks
        IntColumn docIdCol = IntColumn.create("document_id");
        IntColumn sentIdCol = IntColumn.create("sentence_id");
        IntColumn startCol = IntColumn.create("start_pos");
        IntColumn endCol = IntColumn.create("end_pos");
        StringColumn valueTypeCol = StringColumn.create("value_type");
        BooleanColumn hasVariableCol = BooleanColumn.create("has_variable");
        
        for (MatchDetail md : matches) {
            docIdCol.append(md.getDocumentId());
            sentIdCol.append(md.getSentenceId());
            startCol.append(md.getStartPosition());
            endCol.append(md.getEndPosition());
            valueTypeCol.append(md.valueType().name());
            hasVariableCol.append(md.isVariableBinding());
        }
        
        return Table.create("MatchDetailTable",
                docIdCol, sentIdCol, startCol, endCol,
                valueTypeCol, hasVariableCol);
    }
    
    // --- Benchmark Runner ---
    
    /** Run benchmark comparing two implementations */
    private void runBenchmark(String name, Runnable mdImpl, Runnable tablesawImpl) {
        logger.info("--- Benchmarking: {} ---", name);
        
        // Warm up (optional, can take time)
        logger.debug("Warming up...");
        for (int i = 0; i < 2; i++) {
            mdImpl.run();
            tablesawImpl.run();
        }
        logger.debug("Warm up complete.");
        
        // Time Set<MatchDetail> implementation
        System.gc(); // Suggest GC before timing
        long mdStart = System.nanoTime();
        mdImpl.run();
        long mdTime = System.nanoTime() - mdStart;
        
        // Time Tablesaw implementation
        System.gc();
        long tablesawStart = System.nanoTime();
        tablesawImpl.run();
        long tablesawTime = System.nanoTime() - tablesawStart;
        
        logger.info("{} -> Set<MD>: {} ms | Tablesaw: {} ms",
                name,
                TimeUnit.NANOSECONDS.toMillis(mdTime),
                TimeUnit.NANOSECONDS.toMillis(tablesawTime));
    }
    
    // --- Benchmark Operation Implementations ---
    
    // 1. Filter by document ID
    private Set<MatchDetail> filterByDocumentIdMD(Set<MatchDetail> matches, int docId) {
        return matches.stream()
                .filter(m -> m.getDocumentId() == docId)
                .collect(Collectors.toSet());
    }
    private Table filterByDocumentIdTablesaw(Table table, int docId) {
        // Check if column exists before filtering
        if (!table.columnNames().contains("document_id")) return Table.create(table.name() + "_filtered");
        return table.where(table.intColumn("document_id").isEqualTo(docId));
    }
    
    // 2. Filter by ValueType
    private Set<MatchDetail> filterByValueTypeMD(Set<MatchDetail> matches, ValueType type) {
        return matches.stream()
                .filter(m -> m.valueType() == type)
                .collect(Collectors.toSet());
    }
    private Table filterByValueTypeTablesaw(Table table, String typeName) {
        // Check if column exists
        if (!table.columnNames().contains("value_type")) return Table.create(table.name() + "_filtered");
        return table.where(table.stringColumn("value_type").isEqualTo(typeName));
    }
    
    // 3. Intersection operation (Simulating AND)
    private Set<MatchDetail> intersectSetsMD(Set<MatchDetail> matches) {
        Set<MatchDetail> subset1 = matches.stream()
                .filter(m -> m.getDocumentId() % 3 == 0) // Different condition
                .collect(Collectors.toSet());
        Set<MatchDetail> subset2 = matches.stream()
                .filter(m -> m.valueType() == ValueType.ENTITY || m.valueType() == ValueType.DATE)
                .collect(Collectors.toSet());
        Set<MatchDetail> result = new HashSet<>(subset1);
        result.retainAll(subset2);
        return result;
    }
    private Table intersectSetsTablesaw(Table table) {
        // Check if columns exist
        if (!table.columnNames().contains("document_id") || !table.columnNames().contains("value_type")) {
             return Table.create(table.name() + "_intersected");
        }
        // Use remainder() method for divisibility check
        Selection sel1 = table.intColumn("document_id").remainder(3).isEqualTo(0);
        Selection sel2 = table.stringColumn("value_type").isEqualTo("ENTITY")
                         .or(table.stringColumn("value_type").isEqualTo("DATE"));
        return table.where(sel1.and(sel2));
    }
    
    // 4. Group by document
    private Map<Integer, List<MatchDetail>> groupByDocumentMD(Set<MatchDetail> matches) {
        return matches.stream()
                .collect(Collectors.groupingBy(MatchDetail::getDocumentId));
    }
    private Table groupByDocumentTablesaw(Table table) {
         // Check if columns exist
         if (!table.columnNames().contains("document_id") || !table.columnNames().contains("value_type")) {
              return Table.create(table.name() + "_grouped");
         }
         // Group by document and count occurrences of different value types (example aggregation)
        return table.summarize("value_type", AggregateFunctions.count).by("document_id");
    }

    // --- New Test for Large Table Memory Usage ---

    @Test
    @DisplayName("Measure memory usage for a large simulated join result table")
    @org.junit.jupiter.api.Disabled("Disabled for regular builds - run manually with sufficient heap") // Disable test
    public void testLargeTableMemoryUsage() {
        int targetRowCount = 10_000_000; // 10 Million rows
        logger.info("===== Measuring memory usage for large Tablesaw Table ({} rows) =====", targetRowCount);

        // Force garbage collection before starting measurements
        System.gc();
        long memBefore = getUsedMemory();
        logger.info("Memory before table creation: {} MB", memBefore / (1024 * 1024));

        Table largeTable = null;
        long memAfterCreation = memBefore;
        long creationTime = 0;

        try {
            // Create and populate the table
            long start = System.nanoTime();
            largeTable = createLargeSimulatedJoinTable(targetRowCount);
            creationTime = System.nanoTime() - start;

            // Measure memory after creation
            System.gc();
            memAfterCreation = getUsedMemory();
            long tableMemory = memAfterCreation - memBefore;

            logger.info("--- Large Table Creation Stats ---");
            logger.info("Target Rows: {}", targetRowCount);
            logger.info("Actual Rows: {}", largeTable != null ? largeTable.rowCount() : 0);
            logger.info("Creation Time: {} ms", TimeUnit.NANOSECONDS.toMillis(creationTime));
            logger.info("Memory After Creation: {} MB", memAfterCreation / (1024 * 1024));
            logger.info("Estimated Table Memory Footprint: {} MB", tableMemory / (1024 * 1024));
            if (largeTable != null && largeTable.rowCount() > 0) {
                 logger.info("Approx Memory per Row: {} bytes", tableMemory / largeTable.rowCount());
            }
            logger.info("---------------------------------");

            // --- Perform Operations ---
            if (largeTable != null) {
                 logger.info("Performing operations on large table...");
                 long opStart, opTime;
                 long memBeforeOp, memAfterOp;

                 // 1. Filtering
                 memBeforeOp = getUsedMemory();
                 opStart = System.nanoTime();
                 Table filteredTable = largeTable.where(
                     largeTable.intColumn("left_document_id").isLessThan(targetRowCount / 1000)
                 );
                 opTime = System.nanoTime() - opStart;
                 System.gc();
                 memAfterOp = getUsedMemory();
                 logger.info("Filter Operation: Found {} rows, Time: {} ms, Mem Delta: {} MB",
                             filteredTable.rowCount(), TimeUnit.NANOSECONDS.toMillis(opTime), (memAfterOp - memBeforeOp) / (1024*1024));
                 filteredTable = null; // Release memory

                 // 2. Sorting (Sort a smaller subset to avoid excessive time/memory in test)
                 int sortLimit = Math.min(targetRowCount, 100_000); // Limit sorting size for test speed
                 Table tableToSort = largeTable.first(sortLimit);
                 memBeforeOp = getUsedMemory();
                 opStart = System.nanoTime();
                 Table sortedTable = tableToSort.sortOn("-left_date", "right_document_id");
                 opTime = System.nanoTime() - opStart;
                 System.gc();
                 memAfterOp = getUsedMemory();
                 logger.info("Sort Operation (on {} rows): Time: {} ms, Mem Delta: {} MB",
                              sortLimit, TimeUnit.NANOSECONDS.toMillis(opTime), (memAfterOp - memBeforeOp) / (1024*1024));
                 tableToSort = null;
                 sortedTable = null; // Release memory

                 // 3. Aggregation
                 memBeforeOp = getUsedMemory();
                 opStart = System.nanoTime();
                 Table aggregatedTable = largeTable.summarize("left_value", AggregateFunctions.countNonMissing)
                                                  .by("right_source");
                 opTime = System.nanoTime() - opStart;
                 System.gc();
                 memAfterOp = getUsedMemory();
                 logger.info("Aggregate Operation: Result {} groups, Time: {} ms, Mem Delta: {} MB",
                              aggregatedTable.rowCount(), TimeUnit.NANOSECONDS.toMillis(opTime), (memAfterOp - memBeforeOp) / (1024*1024));
                 aggregatedTable = null; // Release memory

                 logger.info("Operations complete.");
            }

        } catch (OutOfMemoryError oom) {
             logger.error("!!! OutOfMemoryError occurred during large table test !!!", oom);
             System.err.println("!!! OutOfMemoryError during test for " + targetRowCount + " rows! Check JVM heap size (-Xmx).");
             // Log memory state right before OOM if possible (tricky)
             logger.info("Memory state before OOM (approx): {} MB used.", getUsedMemory() / (1024*1024));
        } catch (Exception e) {
             logger.error("An unexpected error occurred during the large table test", e);
        } finally {
             // Attempt to release memory
             largeTable = null;
             System.gc();
             long memAfterCleanup = getUsedMemory();
             logger.info("Memory after cleanup: {} MB", memAfterCleanup / (1024 * 1024));
             logger.info("===== Large table memory test complete =====");
        }
    }

    /** Helper to create a large table simulating join results */
    private Table createLargeSimulatedJoinTable(int rowCount) {
        // Define columns similar to TableResultService join output
        IntColumn leftDocIdCol = IntColumn.create("left_document_id", rowCount);
        IntColumn rightDocIdCol = IntColumn.create("right_document_id", rowCount);
        IntColumn leftSentIdCol = IntColumn.create("left_sentence_id", rowCount);
        IntColumn rightSentIdCol = IntColumn.create("right_sentence_id", rowCount);
        DateColumn leftDateCol = DateColumn.create("left_date", rowCount);
        DateColumn rightDateCol = DateColumn.create("right_date", rowCount);
        StringColumn leftValueCol = StringColumn.create("left_value", rowCount);
        StringColumn rightValueCol = StringColumn.create("right_value", rowCount);
        StringColumn leftSourceCol = StringColumn.create("left_source", rowCount); // Example additional column
        StringColumn rightSourceCol = StringColumn.create("right_source", rowCount); // Example additional column


        Random random = new Random(System.currentTimeMillis()); // Use current time for more variability

        logger.info("Generating {} rows for large table...", rowCount);
        for (int i = 0; i < rowCount; i++) {
            leftDocIdCol.append(random.nextInt(rowCount / 10)); // Simulate fewer unique left docs
            rightDocIdCol.append(random.nextInt(rowCount / 5)); // Simulate fewer unique right docs
            leftSentIdCol.append(random.nextInt(MAX_SENTENCES_PER_DOC));
            rightSentIdCol.append(random.nextInt(MAX_SENTENCES_PER_DOC));

            LocalDate leftDate = START_DATE.plusDays(random.nextInt(365 * 4));
            leftDateCol.append(leftDate);
            // Ensure rightDate is distinct and somewhat related for temporal joins simulation
            rightDateCol.append(leftDate.plusDays(random.nextInt(180) - 90)); // +/- 3 months

            leftValueCol.append("lval_" + random.nextInt(10000));
            rightValueCol.append("rval_" + random.nextInt(10000));

            leftSourceCol.append(SOURCES[random.nextInt(SOURCES.length)]);
            rightSourceCol.append(SOURCES[random.nextInt(SOURCES.length)]);

            if ((i + 1) % (rowCount / 10) == 0) { // Log progress every 10%
                logger.debug("...generated {} rows ({}%)", i + 1, (int)(((double)(i + 1) / rowCount) * 100));
            }
        }
        logger.info("Row generation complete.");

        return Table.create("LargeJoinSim",
                leftDocIdCol, rightDocIdCol, leftSentIdCol, rightSentIdCol,
                leftDateCol, rightDateCol, leftValueCol, rightValueCol,
                leftSourceCol, rightSourceCol);
    }

    /** Helper to get currently used memory */
    private long getUsedMemory() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    // --- New Test for Large List<JoinedMatch> Memory Usage ---

    @Test
    @DisplayName("Measure memory usage for a large List<JoinedMatch>")
    @org.junit.jupiter.api.Disabled("Disabled for regular builds - run manually with sufficient heap") // Disable test
    public void testLargeJoinedMatchListMemoryUsage() {
        int targetSize = 10_000_000; // 10 Million objects
        logger.info("===== Measuring memory usage for large List<JoinedMatch> ({} objects) =====", targetSize);

        System.gc();
        long memBefore = getUsedMemory();
        logger.info("Memory before List creation: {} MB", memBefore / (1024 * 1024));

        List<com.example.query.binding.JoinedMatch> joinedMatchesList = null;
        long memAfterCreation = memBefore;
        long creationTime = 0;

        try {
            long start = System.nanoTime();
            joinedMatchesList = createLargeJoinedMatchList(targetSize);
            creationTime = System.nanoTime() - start;

            System.gc();
            memAfterCreation = getUsedMemory();
            long listMemory = memAfterCreation - memBefore;

            logger.info("--- Large List<JoinedMatch> Creation Stats ---");
            logger.info("Target Objects: {}", targetSize);
            logger.info("Actual Objects: {}", joinedMatchesList != null ? joinedMatchesList.size() : 0);
            logger.info("Creation Time: {} ms", TimeUnit.NANOSECONDS.toMillis(creationTime));
            logger.info("Memory After Creation: {} MB", memAfterCreation / (1024 * 1024));
            logger.info("Estimated List Memory Footprint: {} MB", listMemory / (1024 * 1024));
             if (joinedMatchesList != null && !joinedMatchesList.isEmpty()) {
                 logger.info("Approx Memory per JoinedMatch object: {} bytes", listMemory / joinedMatchesList.size());
             }
            logger.info("-------------------------------------------");

        } catch (OutOfMemoryError oom) {
            logger.error("!!! OutOfMemoryError occurred during large List<JoinedMatch> test !!!", oom);
            System.err.println("!!! OutOfMemoryError during test for " + targetSize + " objects! Check JVM heap size (-Xmx).");
            logger.info("Memory state before OOM (approx): {} MB used.", getUsedMemory() / (1024*1024));
        } catch (Exception e) {
            logger.error("An unexpected error occurred during the large list test", e);
        } finally {
            joinedMatchesList = null;
            System.gc();
            long memAfterCleanup = getUsedMemory();
            logger.info("Memory after cleanup: {} MB", memAfterCleanup / (1024 * 1024));
            logger.info("===== Large List<JoinedMatch> memory test complete =====");
        }
    }

    /** Helper to create a large list of JoinedMatch objects */
    private List<com.example.query.binding.JoinedMatch> createLargeJoinedMatchList(int size) {
        List<com.example.query.binding.JoinedMatch> joinedMatches = new ArrayList<>(size / 2); // Approximate
        Random random = new Random(System.nanoTime()); // More varied seed

        for (int i = 0; i < size / 2; i++) {
            MatchDetail left = createRandomMatchDetail(random, "left");
            MatchDetail right = createRandomMatchDetail(random, "right");
            joinedMatches.add(new com.example.query.binding.JoinedMatch(left, right));
        }
        return joinedMatches;
    }

    /** Helper to create a single random MatchDetail */
    private MatchDetail createRandomMatchDetail(Random random, String prefix) {
        int docId = random.nextInt(NUM_DOCS);
        int sentId = random.nextInt(MAX_SENTENCES_PER_DOC);
        int begin = random.nextInt(1000);
        int end = begin + random.nextInt(20) + 1; // Slightly longer entities possible
        Position pos = new Position(docId, sentId, begin, end);

        String variableName = random.nextDouble() < 0.1 ? "?" + prefix + "Var" + random.nextInt(3) : null;

        Object value;
        ValueType valueType;
        int typeSelector = random.nextInt(3); // TERM, ENTITY, DATE
        if (typeSelector == 0) {
            value = prefix + "_term" + random.nextInt(50000);
            valueType = ValueType.TERM;
        } else if (typeSelector == 1) {
            value = prefix + "_ENTITY" + random.nextInt(1000);
            valueType = ValueType.ENTITY;
        } else {
            value = START_DATE.plus(random.nextInt(365 * 2), ChronoUnit.DAYS);
            valueType = ValueType.DATE;
        }

        return new MatchDetail(value, valueType, pos, variableName);
    }
} 