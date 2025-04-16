package com.example.performance;

import com.example.core.Position;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.model.DocSentenceMatch;

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

/**
 * Performance tests comparing Set<DocSentenceMatch>, Set<MatchDetail>, and Tablesaw.
 * This test creates large datasets and performs operations simulating query processing.
 */
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
    @DisplayName("Compare performance: Set<DSM> vs Set<MD> vs Tablesaw")
    public void comparePerformance(int rowCount) {
        logger.info("===== Running performance test with {} target matches =====", rowCount);
        
        // Generate test data for both Set types
        logger.info("Generating DocSentenceMatch data...");
        Set<DocSentenceMatch> matchSetDSM = generateDocSentenceMatchSet(rowCount);
        logger.info("Generating MatchDetail data...");
        Set<MatchDetail> matchSetMD = generateMatchDetailSet(rowCount);
        
        // Convert MatchDetail set to Tablesaw Table (assuming MD is the structure to convert)
        logger.info("Converting MatchDetail set to Tablesaw Table...");
        Table table = convertMatchDetailToTablesaw(matchSetMD);
        
        logger.info("Generated {} DSM matches, {} MD matches, and Tablesaw table with {} rows",
                matchSetDSM.size(), matchSetMD.size(), table.rowCount());
        
        // --- Benchmark typical operations ---
        
        // Filter by document ID
        runBenchmark("Filter by documentId",
            () -> filterByDocumentIdDSM(matchSetDSM, NUM_DOCS / 2),
            () -> filterByDocumentIdMD(matchSetMD, NUM_DOCS / 2),
            () -> filterByDocumentIdTablesaw(table, NUM_DOCS / 2));
        
        // Filter by source (If source is applicable to MatchDetail, otherwise adapt)
        // Assuming source is implicitly available via context or document lookup elsewhere
        // For test purposes, let's filter on something MatchDetail has, e.g., valueType
        runBenchmark("Filter operation (ValueType)",
            () -> filterBySourceDSM(matchSetDSM, SOURCES[0]), // DSM can filter by source
            () -> filterByValueTypeMD(matchSetMD, ValueType.ENTITY), // MD filters by ValueType
            () -> filterByValueTypeTablesaw(table, "ENTITY")); // Table filters by ValueType string
        
        // Intersection operation (Simulating AND)
        runBenchmark("Join/Intersection operation",
            () -> intersectSetsDSM(matchSetDSM),
            () -> intersectSetsMD(matchSetMD),
            () -> intersectSetsTablesaw(table)); // Simulating intersection in Tablesaw
        
        // Group by document
        runBenchmark("Group by document",
            () -> groupByDocumentDSM(matchSetDSM),
            () -> groupByDocumentMD(matchSetMD),
            () -> groupByDocumentTablesaw(table));
        
        logger.info("===== Performance test with {} matches complete =====", rowCount);
    }
    
    @Test
    @DisplayName("Test memory usage: Set<DSM> vs Set<MD> vs Tablesaw")
    public void compareMemoryUsage() {
        int size = 1_000_000; // Use a fixed large size for memory test
        logger.info("===== Comparing memory usage for approximately {} matches =====", size);
        
        // Force garbage collection before starting measurements
        System.gc();
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // 1. Measure Set<DocSentenceMatch>
        logger.info("Generating and measuring Set<DocSentenceMatch>...");
        Set<DocSentenceMatch> matchSetDSM = generateDocSentenceMatchSet(size);
        System.gc();
        long memAfterDSM = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long setDSMMemory = memAfterDSM - memBefore;
        int actualDSMSize = matchSetDSM.size(); // Capture actual size
        matchSetDSM = null; // Release memory
        System.gc(); // GC again
        
        // 2. Measure Set<MatchDetail>
        long memBeforeMD = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        logger.info("Generating and measuring Set<MatchDetail>...");
        Set<MatchDetail> matchSetMD = generateMatchDetailSet(size);
        System.gc();
        long memAfterMD = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long setMDMemory = memAfterMD - memBeforeMD;
        int actualMDSize = matchSetMD.size(); // Capture actual size
        
        // 3. Measure Tablesaw Table (derived from MatchDetail)
        logger.info("Converting MatchDetail set to Tablesaw and measuring...");
        Table table = convertMatchDetailToTablesaw(matchSetMD);
        matchSetMD = null; // Release MatchDetail set memory
        System.gc();
        long memAfterTablesaw = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long tablesawMemory = memAfterTablesaw - memBeforeMD; // Measure against state before table creation
        table = null; // Release table memory
        System.gc();
        
        logger.info("--- Memory Usage Comparison (Approx {} Matches) ---", size);
        logger.info("Set<DocSentenceMatch> ({} items): {} MB", actualDSMSize, setDSMMemory / (1024 * 1024));
        logger.info("Set<MatchDetail>      ({} items): {} MB", actualMDSize, setMDMemory / (1024 * 1024));
        logger.info("Tablesaw Table        ({} rows) : {} MB", actualMDSize, tablesawMemory / (1024 * 1024));
        logger.info("===================================================");
    }
    
    // --- Data Generation Methods ---
    
    /** Generate Set<DocSentenceMatch> */
    private Set<DocSentenceMatch> generateDocSentenceMatchSet(int targetSize) {
        Set<DocSentenceMatch> matches = new HashSet<>();
        Random random = new Random(42); // Fixed seed
        
        while (matches.size() < targetSize) {
            int docId = random.nextInt(NUM_DOCS);
            String source = SOURCES[random.nextInt(SOURCES.length)];
            boolean isSentenceLevel = random.nextBoolean();
            int sentId = isSentenceLevel ? random.nextInt(MAX_SENTENCES_PER_DOC) : -1;
            
            DocSentenceMatch match = isSentenceLevel
                ? new DocSentenceMatch(docId, sentId, source)
                : new DocSentenceMatch(docId, source);
            
            // Add some simulated position data if needed for benchmarks, simplified here
            matches.add(match);
        }
        return matches;
    }
    
    /** Generate Set<MatchDetail> */
    private Set<MatchDetail> generateMatchDetailSet(int targetSize) {
        Set<MatchDetail> matches = new HashSet<>();
        Random random = new Random(123); // Different seed
        
        while (matches.size() < targetSize) {
            int docId = random.nextInt(NUM_DOCS);
            int sentId = random.nextInt(MAX_SENTENCES_PER_DOC);
            int begin = random.nextInt(1000);
            int end = begin + random.nextInt(10) + 1;
            LocalDate docDate = START_DATE.plus(random.nextInt(365 * 3), ChronoUnit.DAYS);
            Position pos = new Position(docId, sentId, begin, end, docDate);
            
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
            
            matches.add(new MatchDetail(value, valueType, pos, conditionId, variableName));
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
        DateColumn docDateCol = DateColumn.create("doc_date");
        StringColumn valueTypeCol = StringColumn.create("value_type");
        StringColumn conditionIdCol = StringColumn.create("condition_id");
        BooleanColumn hasVariableCol = BooleanColumn.create("has_variable");
        
        for (MatchDetail md : matches) {
            docIdCol.append(md.getDocumentId());
            sentIdCol.append(md.getSentenceId());
            startCol.append(md.getStartPosition());
            endCol.append(md.getEndPosition());
            docDateCol.append(md.getDocumentDate()); // Assuming Position has getTimestamp returning LocalDate
            valueTypeCol.append(md.valueType().name());
            conditionIdCol.append(md.conditionId());
            hasVariableCol.append(md.isVariableBinding());
        }
        
        return Table.create("MatchDetailTable",
                docIdCol, sentIdCol, startCol, endCol, docDateCol,
                valueTypeCol, conditionIdCol, hasVariableCol);
    }
    
    // --- Benchmark Runner ---
    
    /** Run benchmark comparing three implementations */
    private void runBenchmark(String name, Runnable dsmImpl, Runnable mdImpl, Runnable tablesawImpl) {
        logger.info("--- Benchmarking: {} ---", name);
        
        // Warm up (optional, can take time)
        logger.debug("Warming up...");
        for (int i = 0; i < 2; i++) {
            dsmImpl.run();
            mdImpl.run();
            tablesawImpl.run();
        }
        logger.debug("Warm up complete.");
        
        // Time Set<DocSentenceMatch> implementation
        System.gc(); // Suggest GC before timing
        long dsmStart = System.nanoTime();
        dsmImpl.run();
        long dsmTime = System.nanoTime() - dsmStart;
        
        // Time Set<MatchDetail> implementation
        System.gc();
        long mdStart = System.nanoTime();
        mdImpl.run();
        long mdTime = System.nanoTime() - mdStart;
        
        // Time Tablesaw implementation
        System.gc();
        long tablesawStart = System.nanoTime();
        tablesawImpl.run();
        long tablesawTime = System.nanoTime() - tablesawStart;
        
        logger.info("{} -> Set<DSM>: {} ms | Set<MD>: {} ms | Tablesaw: {} ms",
                name,
                TimeUnit.NANOSECONDS.toMillis(dsmTime),
                TimeUnit.NANOSECONDS.toMillis(mdTime),
                TimeUnit.NANOSECONDS.toMillis(tablesawTime));
    }
    
    // --- Benchmark Operation Implementations ---
    
    // 1. Filter by document ID
    private Set<DocSentenceMatch> filterByDocumentIdDSM(Set<DocSentenceMatch> matches, int docId) {
        return matches.stream()
                .filter(m -> m.documentId() == docId)
                .collect(Collectors.toSet());
    }
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
    
    // 2. Filter by Source (DSM) / ValueType (MD/Table)
    private Set<DocSentenceMatch> filterBySourceDSM(Set<DocSentenceMatch> matches, String source) {
        return matches.stream()
                .filter(m -> source.equals(m.getSource()))
                .collect(Collectors.toSet());
    }
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
    private Set<DocSentenceMatch> intersectSetsDSM(Set<DocSentenceMatch> matches) {
        Set<DocSentenceMatch> subset1 = matches.stream()
                .filter(m -> m.documentId() % 3 == 0) // Different condition
                .collect(Collectors.toSet());
        Set<DocSentenceMatch> subset2 = matches.stream()
                .filter(m -> SOURCES[0].equals(m.getSource()) || SOURCES[1].equals(m.getSource()))
                .collect(Collectors.toSet());
        Set<DocSentenceMatch> result = new HashSet<>(subset1);
        result.retainAll(subset2);
        return result;
    }
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
    private Map<Integer, List<DocSentenceMatch>> groupByDocumentDSM(Set<DocSentenceMatch> matches) {
        return matches.stream()
                .collect(Collectors.groupingBy(DocSentenceMatch::documentId));
    }
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
} 