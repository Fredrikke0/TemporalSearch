package com.example.index.generators.stitch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.rocksdb.Options;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationTypeSource;
import com.example.index.NgramType;
import com.example.index.RocksDBConfig;
import com.example.logging.ProgressTracker;

/**
 * Generates a generalized "stitch" index by finding co-occurrences of N-grams (from a pre-built
 * temporary N-gram-by-sentence index) and annotations (from a primary annotation index)
 * within the same sentences. This generator expects the temporary annotation-by-sentence index
 * to be provided and populated externally.
 */
public class NgramAnnotationStitchGenerator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(NgramAnnotationStitchGenerator.class);

    private final String baseIndexDirectory;
    private final NgramType ngramType;
    private final AnnotationTypeSource annotationTypeSource;

    private final String sourceAnnotationIndexName;
    private final String outputStitchIndexName;

    private final Path annotationSourcePath; // Path to the primary annotation index (e.g., ner_date, ner, pos)
    private final Path stitchOutputPath;
    private final ProgressTracker progress;

    private IndexAccess annotationSourceIA;       // For reading main annotation index
    private IndexAccess stitchOutputIA;           // For final stitch index output

    private static final int DEFAULT_BATCH_WRITE_SIZE = 10000;

    // Static inner class for N-gram occurrences in temporary N-gram index
    public static record NgramInstance(String term, int beginChar, int endChar) {
        public static byte[] serializeList(List<NgramInstance> list) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (NgramInstance item : list) {
                sb.append(item.term().replace("\n", "<NL>").replace("\t", "<TAB>"))
                  .append("\t").append(item.beginChar())
                  .append("\t").append(item.endChar()).append("\n");
            }
            return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        public static List<NgramInstance> deserializeList(byte[] bytes) throws IOException {
            List<NgramInstance> list = new ArrayList<>();
            if (bytes == null || bytes.length == 0) return list;
            String fullString = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (fullString.isEmpty()) return list;
            String[] lines = fullString.split("\\n");
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    String[] parts = line.split("\\t");
                    if (parts.length == 3) {
                        list.add(new NgramInstance(
                            parts[0].replace("<NL>", "\n").replace("<TAB>", "\t"),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]))
                        );
                    }
                }
            }
            return list;
        }
    }

    // Modified Inner class TermOccurrenceInSentence
    public static class TermOccurrenceInSentence {
        public String annotationTypeString; // e.g., "PERSON", "NOUN"
        public int specificValueId;      // ID for "John Doe", "apple"
        public int beginChar;
        public int endChar;

        public TermOccurrenceInSentence(String annotationTypeString, int specificValueId, int beginChar, int endChar) {
            this.annotationTypeString = annotationTypeString;
            this.specificValueId = specificValueId;
            this.beginChar = beginChar;
            this.endChar = endChar;
        }
        public TermOccurrenceInSentence() {}

        public static byte[] serializeList(List<TermOccurrenceInSentence> list) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (TermOccurrenceInSentence item : list) {
                sb.append(item.annotationTypeString.replace("\n", "<NL>").replace("\t", "<TAB>"))
                  .append("\t").append(item.specificValueId)
                  .append("\t").append(item.beginChar)
                  .append("\t").append(item.endChar).append("\n");
            }
            return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        public static List<TermOccurrenceInSentence> deserializeList(byte[] bytes) throws IOException {
            List<TermOccurrenceInSentence> list = new ArrayList<>();
            if (bytes == null || bytes.length == 0) return list;
            String fullString = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (fullString.isEmpty()) return list;
            String[] lines = fullString.split("\\n");
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    String[] parts = line.split("\\t");
                    if (parts.length == 4) {
                        list.add(new TermOccurrenceInSentence(
                            parts[0].replace("<NL>", "\n").replace("<TAB>", "\t"),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]))
                        );
                    }
                }
            }
            return list;
        }
    }

    public NgramAnnotationStitchGenerator(String baseIndexDirectory, NgramType ngramType, AnnotationTypeSource annotationTypeSource, ProgressTracker progressTracker) throws IOException {
        this.baseIndexDirectory = Objects.requireNonNull(baseIndexDirectory);
        this.ngramType = Objects.requireNonNull(ngramType);
        this.annotationTypeSource = Objects.requireNonNull(annotationTypeSource);
        this.progress = Objects.requireNonNull(progressTracker);

        this.sourceAnnotationIndexName = this.annotationTypeSource.getSourceIndexName();
        this.outputStitchIndexName = "stitch_" + this.ngramType.name().toLowerCase() + "_" + this.annotationTypeSource.getTypeIdentifier();

        this.annotationSourcePath = Paths.get(baseIndexDirectory, sourceAnnotationIndexName);
        this.stitchOutputPath = Paths.get(baseIndexDirectory, outputStitchIndexName);
    }

    private Options getRocksDBOptions() {
        return RocksDBConfig.createOptimizedOptions();
    }

    // Renamed and adapted: This sets up IAs for annotation source, its temp, and final output.
    // It no longer touches N-gram source or N-gram temp IAs.
    private void setupStitchAccess() throws IndexAccessException {
        Options defaultOptions = getRocksDBOptions();
        this.annotationSourceIA = new IndexAccess(annotationSourcePath, sourceAnnotationIndexName + "_source_for_" + outputStitchIndexName, defaultOptions);

        cleanupDirectory(stitchOutputPath.toFile()); // Clean final output dir before generation
        this.stitchOutputIA = new IndexAccess(stitchOutputPath, outputStitchIndexName, defaultOptions);
    }

    /**
     * Generates the specific N-gram/Annotation stitch index.
     * It assumes the temporary N-gram-by-sentence index AND the temporary
     * annotation-by-sentence index are already created and provided.
     * @param temporaryNgramBySentenceIA IndexAccess to the shared temporary N-gram-by-sentence index.
     * @param temporaryAnnotationBySentenceIA IndexAccess to the shared temporary annotation-by-sentence index.
     */
    public void generateStitchIndex(IndexAccess temporaryNgramBySentenceIA, IndexAccess temporaryAnnotationBySentenceIA) throws IOException, IndexAccessException {
        logger.info("Starting {} stitch index generation from N-gram index {} and Annotation-by-sentence index {}.",
                    outputStitchIndexName,
                    temporaryNgramBySentenceIA.getIndexType(),
                    temporaryAnnotationBySentenceIA.getIndexType());
        long startTime = System.currentTimeMillis();
        this.progress.startIndex(outputStitchIndexName + " Join Phase", 0); // Using 0 for indeterminate progress
        try {
            setupStitchAccess(); // Sets up IAs for annotation source and final output

            logger.debug("Phase 2 (Join): Joining N-gram temp index {} and Annotation temp index {} to create final stitch index at {}.",
                        temporaryNgramBySentenceIA.getIndexType(), temporaryAnnotationBySentenceIA.getIndexType(), stitchOutputPath);
            joinNgramAndAnnotationTempIndexes(temporaryNgramBySentenceIA, temporaryAnnotationBySentenceIA);

            long endTime = System.currentTimeMillis();
            logger.info("{} stitch index generation completed successfully in {} ms.", outputStitchIndexName, (endTime - startTime));
        } finally {
            this.progress.completeIndex(); // Complete the "Join Phase"
            close(); // Closes IAs managed by this instance
        }
    }

    // Renamed from joinAndCreateStitchIndex
    private void joinNgramAndAnnotationTempIndexes(IndexAccess temporaryNgramBySentenceIA, IndexAccess temporaryAnnotationBySentenceIA)
            throws IOException, IndexAccessException {
        logger.debug("Starting Phase 2 Join: Joining N-gram temp index ({}) and Annotation temp index ({}).",
                    temporaryNgramBySentenceIA.getIndexType(), temporaryAnnotationBySentenceIA.getIndexType());
        long sentencesProcessed = 0;
        long stitchEntriesGenerated = 0;
        long stitchKeysWritten = 0;
        Map<String, PositionListSoA> finalStitchAggregator = new HashMap<>();

        RocksIterator ngramSentenceIterator = null;
        try {
            ngramSentenceIterator = temporaryNgramBySentenceIA.iterateFromFirst();
            for (ngramSentenceIterator.seekToFirst(); ngramSentenceIterator.isValid(); ngramSentenceIterator.next()) {
                byte[] sentenceKeyBytes = ngramSentenceIterator.key();
                String sentenceKeyString = IndexAccess.asString(sentenceKeyBytes);
                String[] docSentParts = sentenceKeyString.split("_");
                int docId = Integer.parseInt(docSentParts[0]);
                int sentId = Integer.parseInt(docSentParts[1]);

                byte[] ngramOccurrencesBytes = ngramSentenceIterator.value();
                // Use NgramInstance.deserializeList for N-gram data
                List<NgramInstance> ngramsInSentence = NgramInstance.deserializeList(ngramOccurrencesBytes);

                java.util.Optional<byte[]> annotationOccurrencesBytesOpt = temporaryAnnotationBySentenceIA.getRaw(sentenceKeyBytes);

                if (annotationOccurrencesBytesOpt.isPresent()) {
                    // Use TermOccurrenceInSentence.deserializeList for Annotation data (already correct)
                    List<TermOccurrenceInSentence> annotationsInSentence = TermOccurrenceInSentence.deserializeList(annotationOccurrencesBytesOpt.get());
                    if (!ngramsInSentence.isEmpty() && !annotationsInSentence.isEmpty()) {
                        for (NgramInstance ngramOcc : ngramsInSentence) {
                            for (TermOccurrenceInSentence annotationOcc : annotationsInSentence) {
                                String stitchKeyString = ngramOcc.term() + IndexAccessInterface.DELIMITER + annotationOcc.annotationTypeString;
                                PositionListSoA posList = finalStitchAggregator
                                        .computeIfAbsent(stitchKeyString, k -> new PositionListSoA());
                                posList.add(docId, sentId, ngramOcc.beginChar(), ngramOcc.endChar(), annotationOcc.specificValueId);
                                stitchEntriesGenerated++;
                            }
                        }
                    }
                }
                sentencesProcessed++;
                this.progress.updateIndex(1); // Update progress for each sentence processed

                if (finalStitchAggregator.size() >= DEFAULT_BATCH_WRITE_SIZE) {
                    writeStitchBatchToFinalDB(finalStitchAggregator, stitchOutputIA);
                    stitchKeysWritten += finalStitchAggregator.size();
                    finalStitchAggregator.clear();
                }
            }
            if (!finalStitchAggregator.isEmpty()) {
                writeStitchBatchToFinalDB(finalStitchAggregator, stitchOutputIA);
                stitchKeysWritten += finalStitchAggregator.size();
            }
        } finally {
            if (ngramSentenceIterator != null) {
                try {
                    ngramSentenceIterator.close();
                } catch (Exception e) {
                    logger.warn("Error closing RocksIterator in NgramAnnotationStitchGenerator: {}", e.getMessage(), e);
                }
            }
        }
        logger.debug("Finished joining for {}. Total sentences processed: {}, total stitch entries generated: {}, total unique stitch keys written: {}",
                     outputStitchIndexName, sentencesProcessed, stitchEntriesGenerated, stitchKeysWritten);
    }

    private void writeStitchBatchToFinalDB(Map<String, PositionListSoA> stitchBatch,
                                         IndexAccess finalDb) throws IOException, IndexAccessException {
        try (WriteBatch batch = finalDb.createWriteBatch()) {
            for (Map.Entry<String, PositionListSoA> entry : stitchBatch.entrySet()) {
                try {
                    batch.put(IndexAccess.bytes(entry.getKey()), entry.getValue().serializeToCompositeBlob());
                } catch (org.rocksdb.RocksDBException e) {
                    logger.error("Failed to put entry in WriteBatch for key: {}", entry.getKey(), e);
                    throw new IOException("Failed to put entry in WriteBatch for key: " + entry.getKey(), e);
                }
            }
            finalDb.write(batch);
            logger.trace("Wrote stitch batch of {} keys to {}", stitchBatch.size(), finalDb.getIndexType());
        }
    }

    @Override
    public void close() throws IOException {
        logger.debug("Closing IndexAccess instances for {}", outputStitchIndexName);
        // This generator is only responsible for IAs it directly creates and manages:
        // annotationSourceIA, stitchOutputIA.
        // The temporaryNgramBySentenceIA and temporaryAnnotationBySentenceIA are managed externally.
        closeIA(annotationSourceIA, sourceAnnotationIndexName + " Source");
        closeIA(stitchOutputIA, outputStitchIndexName + " Output");
    }

    private void closeIA(IndexAccess ia, String name) {
        if (ia != null) {
            try {
                ia.close();
            } catch (IndexAccessException e) {
                logger.warn("Error closing IndexAccess for '{}' (generator for {}): {}", name, outputStitchIndexName, e.getMessage(), e);
            }
        }
    }

    // cleanupDirectory method remains the same utility
    public static void cleanupDirectory(File directory) {
        if (directory != null && directory.exists() && directory.isDirectory()) {
            try {
                Files.walk(directory.toPath())
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
                if (directory.exists() && !directory.delete()) {
                     logger.warn("Could not delete directory itself after deleting contents: {}", directory.getAbsolutePath());
                } else if (!directory.exists()){
                     logger.debug("Successfully deleted directory: {}", directory.getAbsolutePath());
                } else {
                    // This case means directory.delete() returned true and it still exists, which is odd.
                    // However, the primary goal is to empty it. If it's empty and couldn't be deleted, log it.
                    // Or, if it *was* deleted, this branch is fine.
                    // Re-checking existence might be slightly racy but generally okay here.
                    if(directory.exists()) {
                        logger.warn("Directory contents deleted, but directory itself may still exist: {}", directory.getAbsolutePath());
                    } else {
                        logger.debug("Successfully deleted directory: {}", directory.getAbsolutePath());
                    }
                }
            } catch (IOException e) {
                logger.warn("Failed to delete directory {}: {}", directory.getAbsolutePath(), e.getMessage(), e);
            }
        } else if (directory != null) {
             logger.trace("Directory {} does not exist or is not a directory, no cleanup needed.", directory.getAbsolutePath());
        }
    }
}