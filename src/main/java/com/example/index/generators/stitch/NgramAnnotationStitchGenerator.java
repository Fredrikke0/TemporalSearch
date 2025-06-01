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

    private IndexAccess annotationSourceIA;       // For reading main annotation index
    private IndexAccess stitchOutputIA;           // For final stitch index output

    private static final int DEFAULT_BATCH_WRITE_SIZE = 10000;

    // Inner class TermOccurrenceInSentence remains the same for now
    public static class TermOccurrenceInSentence {
        public String term;
        public int beginChar;
        public int endChar;

        public TermOccurrenceInSentence(String term, int beginChar, int endChar) {
            this.term = term;
            this.beginChar = beginChar;
            this.endChar = endChar;
        }
        public TermOccurrenceInSentence() {}

        public static byte[] serializeList(List<TermOccurrenceInSentence> list) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (TermOccurrenceInSentence item : list) {
                sb.append(item.term.replace("\n", "<NL>").replace("\t", "<TAB>"))
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
                    if (parts.length == 3) {
                        list.add(new TermOccurrenceInSentence(
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

    public NgramAnnotationStitchGenerator(String baseIndexDirectory, NgramType ngramType, AnnotationTypeSource annotationTypeSource) throws IOException {
        this.baseIndexDirectory = Objects.requireNonNull(baseIndexDirectory);
        this.ngramType = Objects.requireNonNull(ngramType);
        this.annotationTypeSource = Objects.requireNonNull(annotationTypeSource);

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
        try {
            setupStitchAccess(); // Sets up IAs for annotation source and final output

            logger.debug("Phase 2 (Join): Joining N-gram temp index {} and Annotation temp index {} to create final stitch index at {}.",
                        temporaryNgramBySentenceIA.getIndexType(), temporaryAnnotationBySentenceIA.getIndexType(), stitchOutputPath);
            joinNgramAndAnnotationTempIndexes(temporaryNgramBySentenceIA, temporaryAnnotationBySentenceIA);

            long endTime = System.currentTimeMillis();
            logger.info("{} stitch index generation completed successfully in {} ms.", outputStitchIndexName, (endTime - startTime));
        } finally {
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
                List<TermOccurrenceInSentence> ngramsInSentence = TermOccurrenceInSentence.deserializeList(ngramOccurrencesBytes);

                java.util.Optional<byte[]> annotationOccurrencesBytesOpt = temporaryAnnotationBySentenceIA.getRaw(sentenceKeyBytes);

                if (annotationOccurrencesBytesOpt.isPresent()) {
                    List<TermOccurrenceInSentence> annotationsInSentence = TermOccurrenceInSentence.deserializeList(annotationOccurrencesBytesOpt.get());
                    if (!ngramsInSentence.isEmpty() && !annotationsInSentence.isEmpty()) {
                        for (TermOccurrenceInSentence ngramOcc : ngramsInSentence) {
                            for (TermOccurrenceInSentence annotationOcc : annotationsInSentence) {
                                String stitchKeyString = ngramOcc.term + IndexAccessInterface.DELIMITER + annotationOcc.term;
                                PositionListSoA posList = finalStitchAggregator
                                        .computeIfAbsent(stitchKeyString, k -> new PositionListSoA());
                                posList.add(docId, sentId, ngramOcc.beginChar, ngramOcc.endChar);
                                stitchEntriesGenerated++;
                            }
                        }
                    }
                }
                sentencesProcessed++;
                if (finalStitchAggregator.size() >= DEFAULT_BATCH_WRITE_SIZE) {
                    writeStitchBatchToFinalDB(finalStitchAggregator, stitchOutputIA);
                    stitchKeysWritten += finalStitchAggregator.size();
                    finalStitchAggregator.clear();
                     logger.trace("Wrote a batch of {} stitch keys to final index {}. Total sentences processed: {}, individual stitch entries: {}",
                                 stitchKeysWritten, outputStitchIndexName, sentencesProcessed, stitchEntriesGenerated);
                }
                if (sentencesProcessed % 100000 == 0) {
                     logger.debug("Join Phase for {}: Processed {} sentences, generated {} stitch entries, written {} unique stitch keys so far.",
                                 outputStitchIndexName, sentencesProcessed, stitchEntriesGenerated, stitchKeysWritten);
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