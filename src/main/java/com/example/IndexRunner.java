package com.example;

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

import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationTypeSource;
import com.example.index.LevelDBConfig;
import com.example.index.NgramType;
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
import com.example.index.generators.stitch.NgramAnnotationStitchGenerator.TermOccurrenceInSentence;
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
            int batchSize, List<String> requestedIndexTypes, String customTempDirStr, boolean force) throws Exception {

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

        Set<String> indexTypesToProcess = new LinkedHashSet<>();
        if (requestedIndexTypes.contains("all")) {
            indexTypesToProcess.addAll(List.of("unigram", "bigram", "trigram", "dependency", "hypernym", "ner_date", "pos", "ner", "nash"));
            if (requestedIndexTypes.contains("stitches")) {
                 indexTypesToProcess.add("stitches");
            }
        } else {
            indexTypesToProcess.addAll(requestedIndexTypes.stream().map(String::toLowerCase).collect(Collectors.toCollection(LinkedHashSet::new)));
        }

        logger.debug("Effective index types to process by IndexRunner: {}", indexTypesToProcess);
        setupIndexDirectories(indexDir, new ArrayList<>(indexTypesToProcess), force);

        Stopwatch totalTime = Stopwatch.createStarted();
        IndexingMetrics metrics = new IndexingMetrics();
        ProgressTracker progress = new ProgressTracker();
        Path indexPath = Paths.get(indexDir);
        Files.createDirectories(indexPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try {
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
                                logger.info("Index directory for type '{}' exists but is empty: '{}'. Proceeding with generation.", type, specificIndexDir.toAbsolutePath());
                            }
                        }
                    } else if (force && Files.exists(specificIndexDir)) {
                        logger.info("--force is true for type '{}'. Directory '{}' will be (or has been) cleaned by Pipeline or setupIndexDirectories. Proceeding with generation.", type, specificIndexDir.toAbsolutePath());
                    }

                    if (!generateThisIndex) {
                        continue;
                    }

                    long documentCountForType = getDocumentCountForType(conn, type);
                    progress.startIndex(type, documentCountForType);

                    if (type.equals("unigram")) {
                        metrics.startBatch(batchSize, "unigram");
                        try (UnigramIndexGenerator gen = new UnigramIndexGenerator(
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
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
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
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
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
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
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
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
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
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
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
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
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
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
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
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
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating nash index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
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
                                    temporaryNgramBySentenceIA = new IndexAccess(tempNgramBySentenceOutputPath, "temp_" + currentNgramType.name().toLowerCase() + "_by_sentence_reused", LevelDBConfig.createOptimizedOptions());
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
                                                indexDir, currentAnnotationType, sourceAnnotationIndexPath, tempAnnotationBySentenceOutputPath, indexPath
                                            );
                                        } else {
                                            logger.debug("Reusing existing temporary Annotation-by-sentence index for {} from {}", currentAnnotationType.getTypeIdentifier(), tempAnnotationBySentenceOutputPath);
                                            temporaryAnnotationBySentenceIA = new IndexAccess(tempAnnotationBySentenceOutputPath, "temp_" + currentAnnotationType.getTypeIdentifier() + "_by_sentence_reused", LevelDBConfig.createOptimizedOptions());
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
                                            try (NgramAnnotationStitchGenerator generator = new NgramAnnotationStitchGenerator(indexDir, currentNgramType, currentAnnotationType)) {
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

        NgramAnnotationStitchGenerator.cleanupDirectory(tempNgramBySentenceOutputPath.toFile());
        Files.createDirectories(tempNgramBySentenceOutputPath);

        IndexAccess sourceNgramIA = null;
        IndexAccess tempOutputNgramIA = null;
        Options defaultOptions = LevelDBConfig.createOptimizedOptions();

        try {
            sourceNgramIA = new IndexAccess(sourceNgramIndexPath, ngramType.name().toLowerCase() + "_source_for_tempgen", defaultOptions);
            tempOutputNgramIA = new IndexAccess(tempNgramBySentenceOutputPath, "temp_" + ngramType.name().toLowerCase() + "_by_sentence", defaultOptions);

            long termsProcessed = 0;
            long positionsProcessed = 0;
            long sentencesWrittenToTemp = 0;
            Map<String, List<TermOccurrenceInSentence>> sentenceAggregator = new HashMap<>();
            final int TEMP_INDEX_BATCH_WRITE_SIZE = 5000;

            try (DBIterator iterator = sourceNgramIA.iterateFromFirst()) {
                for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
                    byte[] termKeyBytes = iterator.peekNext().getKey();
                    String term = IndexAccess.asString(termKeyBytes);
                    byte[] positionListBytes = iterator.peekNext().getValue();
                    PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(positionListBytes);

                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        int docId = positions.getDocIdAt(i);
                        int sentId = positions.getSentenceIdAt(i);
                        int beginChar = positions.getBeginCharAt(i);
                        int endChar = positions.getEndCharAt(i);
                        String sentenceKey = docId + "_" + sentId;
                        List<TermOccurrenceInSentence> occurrences = sentenceAggregator
                                .computeIfAbsent(sentenceKey, k -> new ArrayList<>());
                        occurrences.add(new TermOccurrenceInSentence(term, beginChar, endChar));
                        positionsProcessed++;
                    }
                    termsProcessed++;
                    if (sentenceAggregator.size() >= TEMP_INDEX_BATCH_WRITE_SIZE) {
                        writeSentenceBatchToTempNgramDB(sentenceAggregator, tempOutputNgramIA, ngramType.name());
                        sentencesWrittenToTemp += sentenceAggregator.size();
                        sentenceAggregator.clear();
                        if (termsProcessed % 10000 == 0) {
                            logger.trace("Wrote {} sentence entries to temp {} index. Total terms processed: {}, positions: {}",
                                         sentencesWrittenToTemp, ngramType, termsProcessed, positionsProcessed);
                        }
                    }
                }
                if (!sentenceAggregator.isEmpty()) {
                    writeSentenceBatchToTempNgramDB(sentenceAggregator, tempOutputNgramIA, ngramType.name());
                    sentencesWrittenToTemp += sentenceAggregator.size();
                }
            }
            logger.info("Finished populating temporary index for '{}' N-grams. Total terms: {}, positions: {}, unique sentences written: {}",
                         ngramType, termsProcessed, positionsProcessed, sentencesWrittenToTemp);

            sourceNgramIA.close();
            return tempOutputNgramIA;

        } catch (Exception e) {
            if (sourceNgramIA != null) try { sourceNgramIA.close(); } catch (IndexAccessException ignored) {}
            if (tempOutputNgramIA != null) try { tempOutputNgramIA.close(); } catch (IndexAccessException ignored) {}
            NgramAnnotationStitchGenerator.cleanupDirectory(tempNgramBySentenceOutputPath.toFile());
            throw e;
        }
    }

    private static void writeSentenceBatchToTempNgramDB(Map<String, List<TermOccurrenceInSentence>> sentenceBatch,
                                            IndexAccess tempDb, String ngramTypeForLog) throws IOException, IndexAccessException {
        try (WriteBatch batch = tempDb.createWriteBatch()) {
            for (Map.Entry<String, List<TermOccurrenceInSentence>> entry : sentenceBatch.entrySet()) {
                byte[] key = IndexAccess.bytes(entry.getKey());
                byte[] value = TermOccurrenceInSentence.serializeList(entry.getValue());
                batch.put(key, value);
            }
            tempDb.write(batch);
            logger.trace("Wrote batch of {} sentence entries for {} N-grams to {}", sentenceBatch.size(), ngramTypeForLog, tempDb.getIndexType());
        }
    }

    private static IndexAccess generateTemporaryAnnotationBySentenceIndex(
        String baseIndexDir, // not directly used for path construction, but for context
        AnnotationTypeSource annotationTypeSource,
        Path sourceAnnotationIndexPath,
        Path tempAnnotationBySentenceOutputPath,
        Path mainIndexPath // not directly used here, context
    ) throws IOException, IndexAccessException {

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

        NgramAnnotationStitchGenerator.cleanupDirectory(tempAnnotationBySentenceOutputPath.toFile()); // Clean before use
        Files.createDirectories(tempAnnotationBySentenceOutputPath);

        IndexAccess sourceAnnotationIA = null;
        IndexAccess tempOutputAnnotationIA = null;
        Options defaultOptions = LevelDBConfig.createOptimizedOptions();
        final int TEMP_INDEX_BATCH_WRITE_SIZE = 5000; // Same as it was in NgramAnnotationStitchGenerator

        try {
            sourceAnnotationIA = new IndexAccess(sourceAnnotationIndexPath, annotationTypeSource.getSourceIndexName() + "_source_for_temp_annot_gen", defaultOptions);
            tempOutputAnnotationIA = new IndexAccess(tempAnnotationBySentenceOutputPath, "temp_" + annotationTypeSource.getTypeIdentifier() + "_by_sentence", defaultOptions);

            long termsProcessed = 0;
            long positionsProcessed = 0;
            long sentencesWrittenToTemp = 0;
            Map<String, List<TermOccurrenceInSentence>> sentenceAggregator = new HashMap<>();

            try (DBIterator iterator = sourceAnnotationIA.iterateFromFirst()) {
                for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
                    byte[] termKeyBytes = iterator.peekNext().getKey();
                    String term = IndexAccess.asString(termKeyBytes);
                    byte[] positionListBytes = iterator.peekNext().getValue();
                    PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(positionListBytes);

                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        int docId = positions.getDocIdAt(i);
                        int sentId = positions.getSentenceIdAt(i);
                        int beginChar = positions.getBeginCharAt(i);
                        int endChar = positions.getEndCharAt(i);
                        String sentenceKey = docId + "_" + sentId;
                        List<TermOccurrenceInSentence> occurrences = sentenceAggregator
                                .computeIfAbsent(sentenceKey, k -> new ArrayList<>());
                        occurrences.add(new TermOccurrenceInSentence(term, beginChar, endChar));
                        positionsProcessed++;
                    }
                    termsProcessed++;
                    if (sentenceAggregator.size() >= TEMP_INDEX_BATCH_WRITE_SIZE) {
                        writeSentenceBatchToTempAnnotationDB(sentenceAggregator, tempOutputAnnotationIA, annotationTypeSource.getTypeIdentifier());
                        sentencesWrittenToTemp += sentenceAggregator.size();
                        sentenceAggregator.clear();
                        if (termsProcessed % 10000 == 0) {
                            logger.trace("Wrote {} sentence entries to temp Annotation index for {}. Total terms processed: {}, positions: {}",
                                         sentencesWrittenToTemp, annotationTypeSource.getTypeIdentifier(), termsProcessed, positionsProcessed);
                        }
                    }
                }
                if (!sentenceAggregator.isEmpty()) {
                    writeSentenceBatchToTempAnnotationDB(sentenceAggregator, tempOutputAnnotationIA, annotationTypeSource.getTypeIdentifier());
                    sentencesWrittenToTemp += sentenceAggregator.size();
                }
            }
            logger.info("Finished populating temporary index for '{}' Annotations. Total terms: {}, positions: {}, unique sentences written: {}",
                         annotationTypeSource.getTypeIdentifier(), termsProcessed, positionsProcessed, sentencesWrittenToTemp);

            sourceAnnotationIA.close(); // Close source IA once done
            return tempOutputAnnotationIA; // Return the populated temp IA

        } catch (Exception e) {
            if (sourceAnnotationIA != null) try { sourceAnnotationIA.close(); } catch (IndexAccessException ignored) {}
            if (tempOutputAnnotationIA != null) try { tempOutputAnnotationIA.close(); } catch (IndexAccessException ignored) {} // Close if error during population
            NgramAnnotationStitchGenerator.cleanupDirectory(tempAnnotationBySentenceOutputPath.toFile()); // Clean up on failure
            throw e;
        }
    }

    private static void writeSentenceBatchToTempAnnotationDB(Map<String, List<TermOccurrenceInSentence>> sentenceBatch,
                                                      IndexAccess tempDb, String annotationTypeForLog) throws IOException, IndexAccessException {
        try (WriteBatch batch = tempDb.createWriteBatch()) {
            for (Map.Entry<String, List<TermOccurrenceInSentence>> entry : sentenceBatch.entrySet()) {
                byte[] key = IndexAccess.bytes(entry.getKey());
                byte[] value = TermOccurrenceInSentence.serializeList(entry.getValue());
                batch.put(key, value);
            }
            tempDb.write(batch);
            logger.trace("Wrote batch of {} sentence entries for {} Annotations to {}", sentenceBatch.size(), annotationTypeForLog, tempDb.getIndexType());
        }
    }
}
