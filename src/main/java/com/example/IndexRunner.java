package com.example;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.rocksdb.Options;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationTypeSource;
import com.example.index.NgramType;
import com.example.index.RocksDBConfig;
import com.example.index.generators.BigramIndexGenerator;
import com.example.index.generators.DependencyIndexGenerator;
import com.example.index.generators.HypernymIndexGenerator;
import com.example.index.generators.NashIndexGenerator;
import com.example.index.generators.NerDateIndexGenerator;
import com.example.index.generators.NerIndexGenerator;
import com.example.index.generators.POSIndexGenerator;
import com.example.index.generators.TrigramIndexGenerator;
import com.example.index.generators.UnigramIndexGenerator;
import com.example.index.generators.stitch.NgramAnnotationStitchGenerator;
import com.example.index.generators.stitch.NgramAnnotationStitchGenerator.NgramInstance;
import com.example.index.generators.stitch.NgramAnnotationStitchGenerator.TermOccurrenceInSentence;
import com.example.index.util.ValueLookupManager;
import com.example.logging.IndexingMetrics;
import com.example.logging.ProgressTracker;
import com.google.common.base.Stopwatch;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

public class IndexRunner {
    private static final Logger logger = LoggerFactory.getLogger(IndexRunner.class);
    private static final String GLOBAL_VALUE_LOOKUP_DB_NAME = "global_values_lookup.db";

    public static void main(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("IndexRunner").build()
                .defaultHelp(true)
                .description("Create indexes from annotated database");
        parser.addArgument("-d", "--db").required(true).help("SQLite database file path");
        parser.addArgument("--index-dir").setDefault("indexes").help("Directory for storing indexes (default: 'indexes')");
        parser.addArgument("--stopwords").setDefault("stopwords.txt").help("Path to stopwords file (default: stopwords.txt)");
        parser.addArgument("--batch-size").setDefault(1000).type(Integer.class).help("Batch size for processing (default: 1000)");
        parser.addArgument("-t", "--type")
                .choices("all", "unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos", "hypernym", "nash", "stitches")
                .setDefault(List.of("all"))
                .nargs("+")
                .help("Type of index to generate (can specify multiple, space-separated)");
        parser.addArgument("--custom-temp-dir").dest("custom_temp_dir").type(String.class).required(false)
                .help("Path to a custom directory for temporary files during index generation.");
        parser.addArgument("--force").action(Arguments.storeTrue()).help("Force re-generation of indexes, overwriting existing ones.");
        try {
            Namespace ns = parser.parseArgs(args);
            runIndexing(
                ns.getString("db"),
                ns.getString("index_dir"),
                ns.getString("stopwords"),
                ns.getInt("batch_size"),
                ns.getList("type"),
                ns.getString("custom_temp_dir"),
                ns.getBoolean("force")
            );
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Error generating index: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    public static void runIndexing(String dbPath, String indexDir, String stopwordsPath,
            int batchSize, List<String> cliRequestedIndexTypes, String customTempDirStr, boolean force) throws Exception {

        Path customTempPath = (customTempDirStr != null && !customTempDirStr.isBlank()) ? Path.of(customTempDirStr) : null;
        if (customTempPath != null) {
            Files.createDirectories(customTempPath);
            logger.debug("Using custom temporary directory for ExternalSort: {}", customTempPath.toAbsolutePath());
        }

        Path dbFilePath = Path.of(dbPath);
        if (!Files.exists(dbFilePath)) {
            throw new FileNotFoundException("Database file not found: " + dbPath);
        }
        if (Files.size(dbFilePath) == 0) {
            throw new IOException("Database file is empty. Please run the annotation stage first.");
        }

        // Determine the effective set of index types to process
        Set<String> typesBeingBuilt = new LinkedHashSet<>();
        if (cliRequestedIndexTypes.contains("all")) {
            typesBeingBuilt.addAll(List.of(
                "unigram", "bigram", "trigram", "dependency", "hypernym",
                "ner_date", "pos", "ner", "nash", "stitches" // "stitches" added to "all"
            ));
        } else {
            typesBeingBuilt.addAll(cliRequestedIndexTypes);
        }

        // If "stitches" is to be processed (either from "all" or explicitly), ensure dependencies.
        if (typesBeingBuilt.contains("stitches")) {
            typesBeingBuilt.addAll(List.of(
                "unigram", "bigram", "trigram", "pos", "ner", "ner_date"
            ));
            typesBeingBuilt.add("stitches"); // Ensure it's there (harmless re-add)
        }

        // Convert all to lowercase for consistency
        Set<String> indexTypesToProcess = typesBeingBuilt.stream()
                                              .map(String::toLowerCase)
                                              .collect(Collectors.toCollection(LinkedHashSet::new));

        logger.debug("Effective index types to process by IndexRunner: {}", indexTypesToProcess);
        setupIndexDirectories(indexDir, new ArrayList<>(indexTypesToProcess), force);

        Stopwatch totalTime = Stopwatch.createStarted();
        IndexingMetrics metrics = new IndexingMetrics();
        ProgressTracker progress = new ProgressTracker();
        Path indexPath = Paths.get(indexDir);
        Files.createDirectories(indexPath);

        // Initialize Shared ValueLookupManager
        Path globalLookupDbPath = indexPath.resolve(GLOBAL_VALUE_LOOKUP_DB_NAME);
        ValueLookupManager sharedValueLookupManager = null;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try {
                // Initialize sharedValueLookupManager here, after connection is successful
                // and before any generators that need it are created.
                sharedValueLookupManager = new ValueLookupManager(globalLookupDbPath);
                logger.info("Shared ValueLookupManager initialized at: {}", globalLookupDbPath);

                for (String type : indexTypesToProcess) {
                    if (type.equals("stitches")) continue;

                    Path specificIndexDir = indexPath.resolve(type);
                    boolean generateThisIndex = true;

                    if (!force && Files.exists(specificIndexDir) && Files.isDirectory(specificIndexDir)) {
                        try (Stream<Path> stream = Files.list(specificIndexDir)) {
                            if (stream.findAny().isPresent()) {
                                logger.info("Index for type '{}' already exists at '{}' and --force is false. Skipping generation.", type, specificIndexDir.toAbsolutePath());
                                generateThisIndex = false;
                            } else {
                                logger.debug("Index directory for type '{}' exists but is empty: '{}'. Proceeding with generation.", type, specificIndexDir.toAbsolutePath());
                            }
                        }
                    } else if (force && Files.exists(specificIndexDir)) {
                        logger.info("--force is true for type '{}'. Regenerating index.", type);
                    }

                    if (!generateThisIndex) {
                        continue;
                    }

                    long documentCountForType = getDocumentCountForType(conn, type);
                    progress.startIndex(type, documentCountForType);

                    try (Options options = RocksDBConfig.createOptimizedOptions();
                         IndexAccessInterface indexAccess = new IndexAccess(specificIndexDir, type, options)) {

                        if (type.equals("unigram")) {
                            metrics.startBatch(batchSize, "unigram");
                            try (UnigramIndexGenerator gen = new UnigramIndexGenerator(
                                    indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath)) {
                                gen.generateIndex();
                                metrics.recordBatchSuccess((int) gen.getDocumentCountForIndex());
                            } catch (Exception e) {
                                metrics.recordBatchFailure();
                                logger.error("Error generating unigram index: {}", e.getMessage(), e);
                            } finally {
                                progress.completeIndex();
                            }
                        }
                        if (type.equals("bigram")) {
                            metrics.startBatch(batchSize, "bigram");
                            try (BigramIndexGenerator gen = new BigramIndexGenerator(
                                    indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath)) {
                                gen.generateIndex();
                                metrics.recordBatchSuccess((int) gen.getDocumentCountForIndex());
                            } catch (Exception e) {
                                metrics.recordBatchFailure();
                                logger.error("Error generating bigram index: {}", e.getMessage(), e);
                            } finally {
                                progress.completeIndex();
                            }
                        }

                        if (type.equals("trigram")) {
                            metrics.startBatch(batchSize, "trigram");
                            try (TrigramIndexGenerator gen = new TrigramIndexGenerator(
                                    indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath)) {
                                gen.generateIndex();
                                metrics.recordBatchSuccess((int) gen.getDocumentCountForIndex());
                            } catch (Exception e) {
                                metrics.recordBatchFailure();
                                logger.error("Error generating trigram index: {}", e.getMessage(), e);
                            } finally {
                                progress.completeIndex();
                            }
                        }

                        if (type.equals("dependency")) {
                            metrics.startBatch(batchSize, "dependency");
                            try (DependencyIndexGenerator gen = new DependencyIndexGenerator(
                                    indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath)) {
                                gen.generateIndex();
                                metrics.recordBatchSuccess((int) gen.getDocumentCountForIndex());
                            } catch (Exception e) {
                                metrics.recordBatchFailure();
                                logger.error("Error generating dependency index: {}", e.getMessage(), e);
                            } finally {
                                progress.completeIndex();
                            }
                        }

                        if (type.equals("ner_date")) {
                            metrics.startBatch(batchSize, "ner_date");
                            try (NerDateIndexGenerator gen = new NerDateIndexGenerator(
                                    indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath)) {
                                gen.generateIndex();
                                metrics.recordBatchSuccess((int) gen.getDocumentCountForIndex());
                            } catch (Exception e) {
                                metrics.recordBatchFailure();
                                logger.error("Error generating NER date index: {}", e.getMessage(), e);
                            } finally {
                                progress.completeIndex();
                            }
                        }

                        if (type.equals("ner")) {
                            metrics.startBatch(batchSize, "ner");
                            try (NerIndexGenerator gen = new NerIndexGenerator(
                                    indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                    sharedValueLookupManager)) {
                                gen.generateIndex();
                                metrics.recordBatchSuccess((int) gen.getDocumentCountForIndex());
                            } catch (Exception e) {
                                metrics.recordBatchFailure();
                                logger.error("Error generating NER index: {}", e.getMessage(), e);
                            } finally {
                                progress.completeIndex();
                            }
                        }

                        if (type.equals("pos")) {
                            metrics.startBatch(batchSize, "pos");
                            try (POSIndexGenerator gen = new POSIndexGenerator(
                                    indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                    sharedValueLookupManager)) {
                                gen.generateIndex();
                                metrics.recordBatchSuccess((int) gen.getDocumentCountForIndex());
                            } catch (Exception e) {
                                metrics.recordBatchFailure();
                                logger.error("Error generating POS index: {}", e.getMessage(), e);
                            } finally {
                                progress.completeIndex();
                            }
                        }

                        if (type.equals("hypernym")) {
                            metrics.startBatch(batchSize, "hypernym");
                            try (HypernymIndexGenerator gen = new HypernymIndexGenerator(
                                    indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath)) {
                                gen.generateIndex();
                                metrics.recordBatchSuccess((int) gen.getDocumentCountForIndex());
                            } catch (Exception e) {
                                metrics.recordBatchFailure();
                                logger.error("Error generating hypernym index: {}", e.getMessage(), e);
                            } finally {
                                progress.completeIndex();
                            }
                        }

                        if (type.equals("nash")) {
                            metrics.startBatch(batchSize, "nash");
                            try (NashIndexGenerator gen = new NashIndexGenerator(
                                    indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath)) {
                                gen.generateIndex();
                                metrics.recordBatchSuccess((int) gen.getDocumentCountForIndex());
                            } catch (Exception e) {
                                metrics.recordBatchFailure();
                                logger.error("Error generating nash index: {}", e.getMessage(), e);
                            } finally {
                                progress.completeIndex();
                            }
                        }
                    } catch (IndexAccessException e) {
                        logger.error("Failed to initialize IndexAccess for type {}: {}", type, e.getMessage(), e);
                        metrics.recordBatchFailure(); // Or a more specific metric
                        progress.completeIndex(); // Ensure progress is marked complete even on failure
                    }
                }

                if (indexTypesToProcess.contains("stitches")) {
                    logger.info("Starting N-gram Annotation Stitch generation process...");
                    Stopwatch nasSw = Stopwatch.createStarted();
                    Path tempBaseDir = indexPath.resolve("temp_stitch_gen");
                    Files.createDirectories(tempBaseDir);

                    EnumSet<NgramType> ngramTypesToProcessForStitch = EnumSet.allOf(NgramType.class);
                    EnumSet<AnnotationTypeSource> annotationTypesToProcessForStitch = EnumSet.allOf(AnnotationTypeSource.class);

                    try {
                        // Pre-declare a map to hold paths to annotation temp indexes for reuse across N-gram types
                        Map<AnnotationTypeSource, Path> tempAnnotationIndexPaths = new HashMap<>();
                        for (AnnotationTypeSource ats : annotationTypesToProcessForStitch) {
                            tempAnnotationIndexPaths.put(ats, tempBaseDir.resolve("temp_" + ats.getTypeIdentifier() + "_by_sentence"));
                        }

                        // Outer loop: NgramType
                        for (NgramType currentNgramType : ngramTypesToProcessForStitch) {
                            logger.debug("Starting processing for N-gram type: {}", currentNgramType);
                            Path sourceNgramIndexPath = indexPath.resolve(currentNgramType.name().toLowerCase());
                            Path tempNgramBySentenceOutputPath = tempBaseDir.resolve("temp_" + currentNgramType.name().toLowerCase() + "_by_sentence");
                            IndexAccess temporaryNgramBySentenceIA = null;

                            try {
                                // Check if the source N-gram index exists and is ready
                                if (!Files.exists(sourceNgramIndexPath) || !Files.isDirectory(sourceNgramIndexPath)) {
                                    logger.error("Source N-gram index directory not found for {}: {}. Ensure it was generated by the pipeline. Skipping stitch combinations for this N-gram type.", currentNgramType, sourceNgramIndexPath);
                                    continue; // Skip to the next N-gram type
                                }
                                try (Stream<Path> stream = Files.list(sourceNgramIndexPath)) {
                                    if (!stream.findAny().isPresent()) {
                                        logger.error("Source N-gram index directory is empty for {}: {}. Ensure it was generated by the pipeline. Skipping stitch combinations for this N-gram type.", currentNgramType, sourceNgramIndexPath);
                                        continue; // Skip to the next N-gram type
                                    }
                                }

                                boolean shouldGenerateTempNgramIndex = force || !Files.exists(tempNgramBySentenceOutputPath) || Files.list(tempNgramBySentenceOutputPath).findAny().isEmpty();
                                if (shouldGenerateTempNgramIndex) {
                                    logger.debug("Generating temporary N-gram by sentence index for {} at {}", currentNgramType, tempNgramBySentenceOutputPath);
                                    if (Files.exists(tempNgramBySentenceOutputPath)) {
                                        NgramAnnotationStitchGenerator.cleanupDirectory(tempNgramBySentenceOutputPath.toFile());
                                    }
                                    temporaryNgramBySentenceIA = generateTemporaryNgramBySentenceIndex(indexDir, currentNgramType, sourceNgramIndexPath, tempNgramBySentenceOutputPath, indexPath);
                                } else {
                                    logger.debug("Reusing existing temporary N-gram by sentence index for {} from {}", currentNgramType, tempNgramBySentenceOutputPath);
                                    temporaryNgramBySentenceIA = new IndexAccess(tempNgramBySentenceOutputPath, "temp_" + currentNgramType.name().toLowerCase() + "_by_sentence_reused", RocksDBConfig.createOptimizedOptions());
                                }

                                // Inner loop: AnnotationTypeSource
                                for (AnnotationTypeSource currentAnnotationType : annotationTypesToProcessForStitch) {
                                    logger.debug("Processing Annotation type: {} with N-gram type: {} for stitch generation", currentAnnotationType.getTypeIdentifier(), currentNgramType);

                                    Path sourceAnnotationIndexPath = indexPath.resolve(currentAnnotationType.getSourceIndexName());
                                    if (!Files.exists(sourceAnnotationIndexPath) || !Files.isDirectory(sourceAnnotationIndexPath) || Files.list(sourceAnnotationIndexPath).findAny().isEmpty()) {
                                        logger.error("Prerequisite annotation index '{}' missing or empty at {}. " +
                                                     "Skipping stitch for N-gram {} / Annotation {} pair.",
                                            currentAnnotationType.getSourceIndexName(), sourceAnnotationIndexPath, currentNgramType, currentAnnotationType.getTypeIdentifier());
                                        continue; // Skip to next annotation type for this N-gram
                                    }

                                    Path tempAnnotationBySentenceOutputPath = tempAnnotationIndexPaths.get(currentAnnotationType);
                                    IndexAccess temporaryAnnotationBySentenceIA = null;

                                    try {
                                        boolean shouldGenerateTempAnnotationIndex = force || !Files.exists(tempAnnotationBySentenceOutputPath) || Files.list(tempAnnotationBySentenceOutputPath).findAny().isEmpty();
                                        if (shouldGenerateTempAnnotationIndex) {
                                            logger.debug("Generating temporary Annotation-by-sentence index for {} at {}", currentAnnotationType.getTypeIdentifier(), tempAnnotationBySentenceOutputPath);
                                            if (Files.exists(tempAnnotationBySentenceOutputPath)) {
                                                NgramAnnotationStitchGenerator.cleanupDirectory(tempAnnotationBySentenceOutputPath.toFile()); // Clean if force implies regen
                                            }
                                            temporaryAnnotationBySentenceIA = generateTemporaryAnnotationBySentenceIndex(
                                                indexDir, currentAnnotationType, sourceAnnotationIndexPath, tempAnnotationBySentenceOutputPath, indexPath,
                                                sharedValueLookupManager
                                            );
                                        } else {
                                            logger.debug("Reusing existing temporary Annotation-by-sentence index for {} from {}", currentAnnotationType.getTypeIdentifier(), tempAnnotationBySentenceOutputPath);
                                            temporaryAnnotationBySentenceIA = new IndexAccess(tempAnnotationBySentenceOutputPath, "temp_" + currentAnnotationType.getTypeIdentifier() + "_by_sentence_reused", RocksDBConfig.createOptimizedOptions());
                                        }

                                        String finalStitchIndexName = "stitch_" + currentNgramType.name().toLowerCase() + "_" + currentAnnotationType.getTypeIdentifier();
                                        Path finalStitchOutputPath = indexPath.resolve(finalStitchIndexName);

                                        boolean generateThisStitchCombination = true;
                                        if (!force && Files.exists(finalStitchOutputPath) && Files.isDirectory(finalStitchOutputPath)) {
                                            try (Stream<Path> stream = Files.list(finalStitchOutputPath)) {
                                                if (stream.findAny().isPresent()) {
                                                    logger.info("Final stitch index '{}' already exists and --force is false. Skipping.", finalStitchOutputPath);
                                                    generateThisStitchCombination = false;
                                                }
                                            }
                                        }

                                        if (generateThisStitchCombination) {
                                            if (force && Files.exists(finalStitchOutputPath)) {
                                                logger.debug("--force is true, cleaning up existing final stitch directory: {}", finalStitchOutputPath);
                                                NgramAnnotationStitchGenerator.cleanupDirectory(finalStitchOutputPath.toFile());
                                            }
                                            ensureDirectoryExists(finalStitchOutputPath, false);

                                            logger.info("Generating stitch index: {}", finalStitchIndexName);
                                            progress.startIndex(finalStitchIndexName, getDocumentCountForType(conn, "stitch"));
                                            try (NgramAnnotationStitchGenerator generator = new NgramAnnotationStitchGenerator(indexDir, currentNgramType, currentAnnotationType, progress)) {
                                                generator.generateStitchIndex(temporaryNgramBySentenceIA, temporaryAnnotationBySentenceIA);
                                            } catch (Exception e) {
                                                logger.error("Error generating stitch index {} for N-gram {} and Annotation {}: {}",
                                                             finalStitchIndexName, currentNgramType, currentAnnotationType, e.getMessage(), e);
                                            } finally {
                                                progress.completeIndex();
                                            }
                                        }
                                    } catch (Exception e) {
                                        logger.error("Error processing Annotation type {} for N-gram {}: {}", currentAnnotationType.getTypeIdentifier(), currentNgramType, e.getMessage(), e);
                                    } finally {
                                        if (temporaryAnnotationBySentenceIA != null) {
                                            try {
                                                temporaryAnnotationBySentenceIA.close();
                                                logger.debug("Closed temporary Annotation-by-sentence index for {} (used with N-gram {}).", currentAnnotationType.getTypeIdentifier(), currentNgramType);
                                            } catch (Exception e) {
                                                logger.warn("Error closing temporary Annotation-by-sentence index for {}: {}", currentAnnotationType.getTypeIdentifier(), e.getMessage(), e);
                                            }
                                            // DO NOT delete tempAnnotationBySentenceOutputPath here; it's reused by other N-gram types.
                                        }
                                    }
                                } // End AnnotationTypeSource inner loop
                            } catch (Exception e) {
                                logger.error("Error during processing for N-gram type {}: {}", currentNgramType, e.getMessage(), e);
                            } finally {
                                if (temporaryNgramBySentenceIA != null) {
                                    try {
                                        temporaryNgramBySentenceIA.close();
                                        logger.debug("Closed temporary N-gram by sentence index for {}", currentNgramType);
                                    } catch (Exception e) {
                                        logger.warn("Error closing temporary N-gram index for {}: {}", currentNgramType, e.getMessage(), e);
                                    }
                                    // Now, delete the N-gram specific temporary directory as it has been processed by all annotation types.
                                    logger.debug("Cleaning up temporary N-gram by sentence directory for {}: {}", currentNgramType, tempNgramBySentenceOutputPath.toAbsolutePath());
                                    NgramAnnotationStitchGenerator.cleanupDirectory(tempNgramBySentenceOutputPath.toFile());
                                }
                            }
                        } // End NgramType outer loop
                    } finally {
                        // Final cleanup of the main temp_stitch_gen directory, which should mostly contain
                        // the temporary annotation-by-sentence indexes now, or be empty if no annotations processed.
                        if (Files.isDirectory(tempBaseDir)) {
                            logger.debug("Performing final cleanup of temporary stitch generation base directory: {}", tempBaseDir.toAbsolutePath());
                            NgramAnnotationStitchGenerator.cleanupDirectory(tempBaseDir.toFile());
                        }
                    }
                    logger.info("N-gram Annotation Stitch generation process finished in {} ms.", nasSw.elapsed(TimeUnit.MILLISECONDS));
                }

            } catch (Exception e) {
                logger.error("An error occurred during index generation: {}", e.getMessage(), e);
                throw e;
            } finally {
                metrics.logIndexingMetrics();
                // Close shared ValueLookupManager here
                if (sharedValueLookupManager != null) {
                    try {
                        sharedValueLookupManager.close();
                        logger.info("Shared ValueLookupManager closed successfully.");
                    } catch (Exception e) {
                        logger.error("Error closing shared ValueLookupManager: {}", e.getMessage(), e);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("SQL error during indexing: {}", e.getMessage(), e);
            throw e;
        }
        logger.info("Indexing process completed. Total time: {}", totalTime.elapsed(TimeUnit.MILLISECONDS));
    }

    private static long getDocumentCountForType(Connection conn, String indexType) {
        if ("unigram".equals(indexType) || "bigram".equals(indexType) || "trigram".equals(indexType)) {
            try (var stmt = conn.createStatement(); var rs = stmt.executeQuery("SELECT COUNT(DISTINCT document_id) FROM sentences")) {
                if (rs.next()) return rs.getLong(1);
            } catch (SQLException e) { logger.warn("Could not get doc count for {}", indexType); }
        }
        return 1000;
    }

    private static void setupIndexDirectories(String indexBaseDirStr, List<String> indexTypesToEnsure, boolean force) throws IOException {
        Path baseDir = Path.of(indexBaseDirStr);
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
            logger.debug("Created base index directory: {}", baseDir.toAbsolutePath());
        }

        for (String type : indexTypesToEnsure) {
            if ("stitches".equalsIgnoreCase(type)) {
                Path tempBaseStitchGenDir = baseDir.resolve("temp_stitch_gen");
                if(force && Files.exists(tempBaseStitchGenDir)){
                    logger.debug("--force: Cleaning up main temporary stitch generation directory: {}", tempBaseStitchGenDir);
                    NgramAnnotationStitchGenerator.cleanupDirectory(tempBaseStitchGenDir.toFile());
                }
                ensureDirectoryExists(tempBaseStitchGenDir, false);

                for (NgramType ngramType : NgramType.values()) {
                    Path tempNgramBySentencePath = tempBaseStitchGenDir.resolve("temp_" + ngramType.name().toLowerCase() + "_by_sentence");
                    if (force && Files.exists(tempNgramBySentencePath)) {
                        logger.debug("--force: Cleaning up temporary N-gram by sentence directory for {}: {}", ngramType, tempNgramBySentencePath);
                        NgramAnnotationStitchGenerator.cleanupDirectory(tempNgramBySentencePath.toFile());
                    }
                }

                for (AnnotationTypeSource annotationSrc : AnnotationTypeSource.values()) {
                    Path tempAnnotationBySentencePath = tempBaseStitchGenDir.resolve("temp_" + annotationSrc.getTypeIdentifier() + "_by_sentence");
                    if (force && Files.exists(tempAnnotationBySentencePath)) {
                        logger.debug("--force: Cleaning up temporary Annotation by sentence directory for {}: {}", annotationSrc.getTypeIdentifier(), tempAnnotationBySentencePath);
                        NgramAnnotationStitchGenerator.cleanupDirectory(tempAnnotationBySentencePath.toFile());
                    }

                    for (NgramType ngramType : NgramType.values()) { // Loop to cover all potential stitch output names
                        String finalStitchIndexName = "stitch_" + ngramType.name().toLowerCase() + "_" + annotationSrc.getTypeIdentifier();
                        Path finalStitchOutputPath = baseDir.resolve(finalStitchIndexName);
                        ensureDirectoryExists(finalStitchOutputPath, force); // This cleans if force is true
                    }
                }
            } else if (!"all".equalsIgnoreCase(type)) {
                ensureDirectoryExists(baseDir.resolve(type), force);
            }
        }
    }

    private static void ensureDirectoryExists(Path dirPath, boolean force) throws IOException {
        if (force && Files.exists(dirPath) && Files.isDirectory(dirPath)) {
            logger.debug("--force: Cleaning up existing directory: {}", dirPath.toAbsolutePath());
            NgramAnnotationStitchGenerator.cleanupDirectory(dirPath.toFile());
        }
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
            logger.debug("Created/ensured index directory: {}", dirPath.toAbsolutePath());
        } else {
            logger.trace("Directory already exists (or was just cleaned and recreated/left alone): {}", dirPath.toAbsolutePath());
        }
    }

    private static IndexAccess generateTemporaryNgramBySentenceIndex(
            String baseIndexDir,
            NgramType ngramType,
            Path sourceNgramIndexPath,
            Path tempNgramBySentenceOutputPath,
            Path mainIndexPath
    ) throws IOException, IndexAccessException {

        if(!Files.exists(sourceNgramIndexPath) || !Files.isDirectory(sourceNgramIndexPath)){
            throw new FileNotFoundException("Source N-gram index directory not found: " + sourceNgramIndexPath);
        }

        logger.info("Populating temporary N-gram-by-sentence index for '{}' from {} into {}.",
                ngramType, sourceNgramIndexPath, tempNgramBySentenceOutputPath);

        ensureDirectoryExists(tempNgramBySentenceOutputPath, true);
        Options options = RocksDBConfig.createOptimizedOptions();
        IndexAccess tempDb = null;
        try {
            tempDb = new IndexAccess(tempNgramBySentenceOutputPath, "temp_" + ngramType.name().toLowerCase() + "_by_sentence", options);
        } catch (IndexAccessException e) {
            options.close();
            throw e;
        }

        logger.info("Generating temporary index at {} for N-gram type {}...", tempNgramBySentenceOutputPath.toAbsolutePath(), ngramType.name());
        Stopwatch sw = Stopwatch.createStarted();

        Options sourceNgramOptions = RocksDBConfig.createOptimizedOptions();
        sourceNgramOptions.setCreateIfMissing(false);
        IndexAccessInterface sourceNgramIA = null;
        org.rocksdb.RocksIterator iterator = null;

        try {
            sourceNgramIA = new IndexAccess(sourceNgramIndexPath, ngramType.name().toLowerCase() + "_source_reader", sourceNgramOptions);
            iterator = sourceNgramIA.iterateFromFirst();
            Map<String, List<NgramInstance>> sentenceBatch = new HashMap<>();
            int batchCount = 0;
            final int SENTENCE_BATCH_SIZE = 5000;

            while (iterator.isValid()) {
                byte[] key = iterator.key();
                byte[] value = iterator.value();
                PositionListSoA posList = PositionListSoA.deserializeFromCompositeBlob(value);

                for (int i = 0; i < posList.getNumPositions(); i++) {
                    int docId = posList.getDocIdAt(i);
                    int sentenceId = posList.getSentenceIdAt(i);
                    int beginChar = posList.getBeginCharAt(i);
                    int endChar = posList.getEndCharAt(i);
                    String sentenceKey = docId + "_" + sentenceId;
                    sentenceBatch.computeIfAbsent(sentenceKey, k -> new ArrayList<>()).add(new NgramInstance(IndexAccess.asString(key), beginChar, endChar));
                }

                if (sentenceBatch.size() >= SENTENCE_BATCH_SIZE) {
                    writeSentenceBatchToTempNgramDB(sentenceBatch, tempDb, ngramType.name());
                    sentenceBatch.clear();
                    batchCount++;
                    if (batchCount % 10 == 0) {
                        //logger.debug("Processed {} sentence batches for temp {} index", batchCount, ngramType.name());
                    }
                }
                iterator.next();
            }
            if (!sentenceBatch.isEmpty()) {
                writeSentenceBatchToTempNgramDB(sentenceBatch, tempDb, ngramType.name());
            }

        } finally {
            if (iterator != null) {
                iterator.close();
            }
            if (sourceNgramIA != null) {
            sourceNgramIA.close();
            } else if (sourceNgramOptions != null) {
                sourceNgramOptions.close();
            }
        }

        logger.info("Finished generating temporary {} index in {} ms. Stored at: {}", ngramType.name(), sw.elapsed(TimeUnit.MILLISECONDS), tempNgramBySentenceOutputPath.toAbsolutePath());
        return tempDb;
    }

    private static void writeSentenceBatchToTempNgramDB(Map<String, List<NgramInstance>> sentenceBatch,
                                            IndexAccess tempDb, String ngramTypeForLog) throws IOException, IndexAccessException {
        try (org.rocksdb.WriteBatch batch = tempDb.createWriteBatch()) {
            for (Map.Entry<String, List<NgramInstance>> entry : sentenceBatch.entrySet()) {
                byte[] key = IndexAccess.bytes(entry.getKey());
                byte[] value = NgramInstance.serializeList(entry.getValue());
                try {
                batch.put(key, value);
                } catch (RocksDBException e) {
                    throw new IndexAccessException("Failed to put entry into batch for temp N-gram DB", tempDb.getIndexType(), IndexAccessException.ErrorType.WRITE_ERROR, e);
                }
            }
            tempDb.write(batch);
            logger.trace("Wrote batch of {} sentence entries for {} N-grams to {}", sentenceBatch.size(), ngramTypeForLog, tempDb.getIndexType());
        }
    }

    private static IndexAccess generateTemporaryAnnotationBySentenceIndex(
        String baseIndexDir,
        AnnotationTypeSource annotationTypeSource,
        Path sourceAnnotationIndexPath,
        Path tempAnnotationBySentenceOutputPath,
        Path mainIndexPath,
        ValueLookupManager valueLookupManager
    ) throws IOException, IndexAccessException, RocksDBException {

        if (!Files.exists(sourceAnnotationIndexPath) || !Files.isDirectory(sourceAnnotationIndexPath)) {
            throw new FileNotFoundException("Source Annotation index directory not found: " + sourceAnnotationIndexPath);
        }
        try (Stream<Path> stream = Files.list(sourceAnnotationIndexPath)) {
            if (!stream.findAny().isPresent()) {
                 throw new FileNotFoundException("Source Annotation index directory is empty: " + sourceAnnotationIndexPath);
            }
        }

        logger.info("Populating temporary Annotation-by-sentence index for '{}' from {} into {}.",
                annotationTypeSource.getTypeIdentifier(), sourceAnnotationIndexPath, tempAnnotationBySentenceOutputPath);

        ensureDirectoryExists(tempAnnotationBySentenceOutputPath, true);
        Options options = RocksDBConfig.createOptimizedOptions();
        IndexAccess tempDb = null;
        try {
            tempDb = new IndexAccess(tempAnnotationBySentenceOutputPath, "temp_" + annotationTypeSource.getTypeIdentifier() + "_by_sentence", options);
        } catch (IndexAccessException e) {
            options.close();
            throw e;
        }

        logger.info("Generating temporary index at {} for Annotation type {}...", tempAnnotationBySentenceOutputPath.toAbsolutePath(), annotationTypeSource.getTypeIdentifier());
        Stopwatch sw = Stopwatch.createStarted();

        Options sourceAnnotationOptions = RocksDBConfig.createOptimizedOptions();
        sourceAnnotationOptions.setCreateIfMissing(false);
        IndexAccessInterface sourceAnnotationIA = null;
        org.rocksdb.RocksIterator iterator = null;

        try {
            sourceAnnotationIA = new IndexAccess(sourceAnnotationIndexPath, annotationTypeSource.getSourceIndexName() + "_source_reader", sourceAnnotationOptions);
            iterator = sourceAnnotationIA.iterateFromFirst();
            Map<String, List<TermOccurrenceInSentence>> sentenceBatch = new HashMap<>();
            int batchCount = 0;
            final int SENTENCE_BATCH_SIZE = 5000;

            while (iterator.isValid()) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();

                logger.debug("Attempting to deserialize PositionListSoA for key '{}' (annotation type {}). Blob size: {} bytes.", IndexAccess.asString(keyBytes), annotationTypeSource.getTypeIdentifier(), (valueBytes != null ? valueBytes.length : "null"));

                try {
                    PositionListSoA posList = PositionListSoA.deserializeFromCompositeBlob(valueBytes);

                    String keyFromSourceIndex = IndexAccess.asString(keyBytes);

                    for (int i = 0; i < posList.getNumPositions(); i++) {
                        int docId = posList.getDocIdAt(i);
                        int sentenceId = posList.getSentenceIdAt(i);
                        int beginChar = posList.getBeginCharAt(i);
                        int endChar = posList.getEndCharAt(i);
                        String sentenceKey = docId + "_" + sentenceId;

                        String annotationTypeForTOS;
                        int specificValueIdForTOS;

                        if (annotationTypeSource == AnnotationTypeSource.NER || annotationTypeSource == AnnotationTypeSource.POS) {
                            annotationTypeForTOS = keyFromSourceIndex;
                            specificValueIdForTOS = posList.getSynonymIdAt(i);
                        } else if (annotationTypeSource == AnnotationTypeSource.NER_DATE) {
                            annotationTypeForTOS = annotationTypeSource.getTypeIdentifier();
                            String dateValue = keyFromSourceIndex;
                            specificValueIdForTOS = valueLookupManager.getId(dateValue);
                        } else {
                            logger.warn("Unhandled AnnotationTypeSource '{}' in generateTemporaryAnnotationBySentenceIndex. Attempting default ID lookup.", annotationTypeSource);
                            annotationTypeForTOS = annotationTypeSource.getTypeIdentifier();
                            String valueToLookup = keyFromSourceIndex;
                            specificValueIdForTOS = valueLookupManager.getId(valueToLookup);
                        }

                        sentenceBatch.computeIfAbsent(sentenceKey, k -> new ArrayList<>()).add(
                            new TermOccurrenceInSentence(annotationTypeForTOS, specificValueIdForTOS, beginChar, endChar)
                        );
                    }
                } catch (ArrayIndexOutOfBoundsException aioobe) {
                    logger.error("### AIOOBE Deserializing PositionListSoA for key '{}', annotationType '{}'. Blob size: {} bytes.",
                                IndexAccess.asString(keyBytes), annotationTypeSource.getTypeIdentifier(), (valueBytes != null ? valueBytes.length : "null"), aioobe);
                    // Now, let's try to manually inspect the problematic blob
                    if (valueBytes != null) {
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(valueBytes);
                             DataInputStream dis = new DataInputStream(bais)) {
                            logger.error("### Manual Blob Inspection for Key '{}':", IndexAccess.asString(keyBytes));
                            int numPositionsReported = dis.readInt();
                            logger.error("###   Reported numPositions in blob: {}", numPositionsReported);

                            // Basic sanity check on numPositions
                            if (numPositionsReported >= 0 && numPositionsReported < 10000000) { // Allow 0 for empty lists that somehow got here
                                String[] arrayNames = {"docIds", "sentenceIds", "beginChars", "endChars", "synonymIds"};
                                for (String arrayName : arrayNames) {
                                    if (numPositionsReported == 0 && dis.available() >=4) { // if numPos is 0, writer still writes a 0 for array size marker
                                        int arraySizeOrMarker = dis.readInt();
                                        logger.error("###   Marker for {} (when numPositions is 0): 0x{} ({})", arrayName, Integer.toHexString(arraySizeOrMarker), arraySizeOrMarker);
                                        if(arraySizeOrMarker != 0) {
                                            logger.error("###     Expected 0 marker for {} when numPositions is 0, but got {}. Halting inspection.", arrayName, arraySizeOrMarker);
                                            break;
                                        }
                                        // For numPositions == 0, after reading the '0' marker for the array, there's nothing else for this array.
                                        continue; // Move to the next array name
                                    } else if (numPositionsReported == 0){
                                         logger.error("###   numPositions is 0, but not enough bytes for array marker for {}.", arrayName);
                                         break;
                                    }

                                    // Proceed if numPositionsReported > 0
                                    if (dis.available() >= 4) {
                                        int arraySizeOrMarker = dis.readInt();
                                        logger.error("###   Marker for {}: 0x{} ({})", arrayName, Integer.toHexString(arraySizeOrMarker), arraySizeOrMarker);

                                        if (arraySizeOrMarker == PositionListSoA.RLE_ENCODED_MARKER) {
                                            if (dis.available() >=4) {
                                                int rleValue = dis.readInt();
                                                logger.error("###     RLE Value for {}: {}", arrayName, rleValue);
                                            } else {
                                                logger.error("###     RLE marker found for {} but not enough bytes for value. Remaining: {}", arrayName, dis.available());
                                                break;
                                            }
                                        } else if (arraySizeOrMarker > 0 && arraySizeOrMarker < valueBytes.length && arraySizeOrMarker % 4 == 0) { // Plausible size
                                            if (dis.available() >= arraySizeOrMarker) {
                                                dis.skipBytes(arraySizeOrMarker);
                                                logger.error("###     Successfully skipped {} bytes for {}", arraySizeOrMarker, arrayName);
                                            } else {
                                                logger.error("###     Declared size {} for {} exceeds remaining {} bytes in stream. Halting inspection.", arraySizeOrMarker, arrayName, dis.available());
                                                break;
                                            }
                                        } else if (arraySizeOrMarker == 0 && numPositionsReported > 0) { // Valid for an empty array payload if numExpected > 0
                                            logger.error("###     Marker for {} is 0 (empty payload).", arrayName);
                                            // Nothing to skip.
                                        }
                                        else {
                                           logger.error("###     Marker for {} (0x{}/ {}) is not RLE, 0 (for empty payload), or a plausible size. Halting inspection.", arrayName, Integer.toHexString(arraySizeOrMarker), arraySizeOrMarker);
                                           break;
                                        }
                                    } else {
                                        logger.error("###   Not enough bytes remaining to read marker for {}. Needed 4, available {}. Halting inspection.", arrayName, dis.available());
                                        break;
                                    }
                                }
                            } else { // numPositionsReported < 0 or too large
                                logger.error("###   Reported numPositions ({}) is negative or seems excessively large. Halting inspection.", numPositionsReported);
                            }
                        } catch (IOException ioe) {
                            logger.error("### IOException during manual blob inspection for key '{}': {}", IndexAccess.asString(keyBytes), ioe.getMessage(), ioe);
                        }
                    }
                    // Re-throw the original exception to halt the process as before
                    throw aioobe;
                } catch (Exception e) { // Catch other potential exceptions during deserialization
                    logger.error("### GENERIC EXCEPTION Deserializing PositionListSoA for key '{}', annotationType '{}'. Blob size: {} bytes. Message: {}",
                                IndexAccess.asString(keyBytes), annotationTypeSource.getTypeIdentifier(), (valueBytes != null ? valueBytes.length : "null"), e.getMessage(), e);
                    // Re-throw to halt
                    throw e;
                }

                // Rest of the original loop logic for batching
                if (sentenceBatch.size() >= SENTENCE_BATCH_SIZE) {
                    writeSentenceBatchToTempAnnotationDB(sentenceBatch, tempDb, annotationTypeSource.getTypeIdentifier());
                    sentenceBatch.clear();
                    batchCount++;
                    // if (batchCount % 10 == 0) { // logger.debug(...) }
                }
                iterator.next();
            } // End while (iterator.isValid())
            if (!sentenceBatch.isEmpty()) {
                writeSentenceBatchToTempAnnotationDB(sentenceBatch, tempDb, annotationTypeSource.getTypeIdentifier());
            }
        } finally {
            if (iterator != null) {
                iterator.close();
            }
            if (sourceAnnotationIA != null) {
                sourceAnnotationIA.close();
            } else if (sourceAnnotationOptions != null) {
                sourceAnnotationOptions.close();
            }
        }
        logger.info("Finished generating temporary {} index in {} ms. Stored at: {}", annotationTypeSource.getTypeIdentifier(), sw.elapsed(TimeUnit.MILLISECONDS), tempAnnotationBySentenceOutputPath.toAbsolutePath());
        return tempDb;
    }

    private static void writeSentenceBatchToTempAnnotationDB(Map<String, List<TermOccurrenceInSentence>> sentenceBatch,
                                                      IndexAccess tempDb, String annotationTypeForLog) throws IOException, IndexAccessException {
        try (org.rocksdb.WriteBatch batch = tempDb.createWriteBatch()) {
            for (Map.Entry<String, List<TermOccurrenceInSentence>> entry : sentenceBatch.entrySet()) {
                byte[] key = IndexAccess.bytes(entry.getKey());
                byte[] value = TermOccurrenceInSentence.serializeList(entry.getValue());
                try {
                batch.put(key, value);
                } catch (RocksDBException e) {
                    throw new IndexAccessException("Failed to put entry into batch for temp Annotation DB", tempDb.getIndexType(), IndexAccessException.ErrorType.WRITE_ERROR, e);
                }
            }
            tempDb.write(batch);
            logger.trace("Wrote batch of {} sentence entries for {} Annotations to {}", sentenceBatch.size(), annotationTypeForLog, tempDb.getIndexType());
        }
    }
}
