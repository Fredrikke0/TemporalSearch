package com.example.index;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages synonym mappings for a single, specific annotation type.
 * Stores mappings in a .ser file within the provided base directory.
 */
public class TypedAnnotationSynonymStore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TypedAnnotationSynonymStore.class);

    private static final Map<AnnotationType, String> FILE_NAME_MAP = new HashMap<>();
    static {
        FILE_NAME_MAP.put(AnnotationType.DATE, "date_synonyms.ser");
        FILE_NAME_MAP.put(AnnotationType.NER, "ner_synonyms.ser");
        FILE_NAME_MAP.put(AnnotationType.POS, "pos_synonyms.ser");
        FILE_NAME_MAP.put(AnnotationType.DEPENDENCY, "dependency_synonyms.ser");
        // Add other types if they get dedicated synonym files
    }

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Map<String, Integer> valueToId = new ConcurrentHashMap<>();
    private final Map<Integer, String> idToValue = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final Path storageFile;
    private final AnnotationType managedType;
    private boolean modified = false;
    private volatile boolean closed = false;

    /**
     * Creates a new TypedAnnotationSynonymStore.
     *
     * @param synonymFilePath The direct path to the synonym file (e.g., index_dir/synonyms.dat).
     * @param type The annotation type this store manages (for validation purposes).
     * @throws IOException If an I/O error occurs during loading.
     */
    public TypedAnnotationSynonymStore(Path synonymFilePath, AnnotationType type) throws IOException {
        this.managedType = type;
        this.storageFile = synonymFilePath;
        // The caller (generator or IndexAccess) is responsible for ensuring the parent directory exists before calling this constructor,
        // or this constructor can ensure it.
        if (storageFile.getParent() != null) {
            Files.createDirectories(storageFile.getParent()); // Ensure directory exists
        }

        loadMappings();
        logger.info("Initialized {} annotation synonyms from file: {}", managedType, storageFile.toAbsolutePath());
    }

    public AnnotationType getManagedType() {
        return managedType;
    }

    public int getOrCreateId(String value) {
        if (closed) {
            throw new IllegalStateException(managedType + " AnnotationSynonyms is closed");
        }
        validateValue(value);

        Integer existingId = valueToId.get(value);
        if (existingId != null) {
            return existingId;
        }

        synchronized(this) {
            if (closed) {
                throw new IllegalStateException(managedType + " AnnotationSynonyms was closed during operation");
            }
            existingId = valueToId.get(value);
            if (existingId != null) {
                return existingId;
            }

            int id = nextId.getAndIncrement();
            valueToId.put(value, id);
            idToValue.put(id, value);
            modified = true;

            //logger.debug("Created new {} synonym: {} -> {}", managedType, value, id);
            return id;
        }
    }

    public String getValue(int id) {
        if (closed) {
            throw new IllegalStateException(managedType + " AnnotationSynonyms is closed");
        }
        return idToValue.get(id);
    }

    public int size() {
        return valueToId.size();
    }

    private void validateValue(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Value cannot be null or empty for " + managedType);
        }

        switch (managedType) {
            case DATE:
                if (!DATE_PATTERN.matcher(value).matches()) {
                    throw new IllegalArgumentException("Date must be in YYYY-MM-DD format: " + value);
                }
                try {
                    LocalDate.parse(value, DATE_FORMATTER);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid date: " + value, e);
                }
                break;
            case NER:
            case POS:
            case DEPENDENCY:
                // No specific validation besides non-empty for these types currently
                break;
            default:
                throw new IllegalStateException("Validation not implemented for type: " + managedType);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadMappings() throws IOException {
        logger.info("SYNONYM_STORE_DEBUG: loadMappings() called for type '{}'. Checking path: '{}'",
            this.managedType, this.storageFile.toAbsolutePath());
        if (Files.exists(storageFile)) {
            logger.info("SYNONYM_STORE_DEBUG: File FOUND at path: '{}' for type '{}'. Attempting to load.",
                this.storageFile.toAbsolutePath(), this.managedType);
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(storageFile))) {
                Map<String, Integer> loadedValueToId = (Map<String, Integer>) ois.readObject();
                int maxId = 0;

                for (Map.Entry<String, Integer> entry : loadedValueToId.entrySet()) {
                    valueToId.put(entry.getKey(), entry.getValue());
                    idToValue.put(entry.getValue(), entry.getKey());
                    maxId = Math.max(maxId, entry.getValue());
                }
                nextId.set(maxId + 1);
                logger.info("Loaded {} {} synonyms with next ID {}",
                           valueToId.size(), managedType, nextId.get());
            } catch (ClassNotFoundException | ClassCastException e) {
                throw new IOException("Failed to load " + managedType + " synonyms from " + storageFile, e);
            }
        } else {
            logger.info("SYNONYM_STORE_DEBUG: File NOT FOUND at path: '{}' for type '{}'. Will create new store.",
                this.storageFile.toAbsolutePath(), this.managedType);
        }
    }

    private void saveMappings() throws IOException {
        logger.info("SYNONYM_STORE_DEBUG: saveMappings() called for type '{}'. File: '{}'. Modified: {}",
            this.managedType, this.storageFile.toAbsolutePath(), this.modified);
        if (!modified) {
            logger.info("SYNONYM_STORE_DEBUG: Not saving, modified is false for type '{}'.", this.managedType);
            return;
        }

        Files.createDirectories(storageFile.getParent());
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(storageFile))) {
            oos.writeObject(new HashMap<>(valueToId)); // Save a copy for thread-safety during serialization
            logger.info("Saved {} {} synonyms to {}", valueToId.size(), managedType, storageFile);
            modified = false;
        }
    }

    public void validateSynonyms() {
        for (Map.Entry<Integer, String> entry : idToValue.entrySet()) {
            int id = entry.getKey();
            String value = entry.getValue();
            Integer mappedId = valueToId.get(value);

            if (mappedId == null || mappedId.intValue() != id) {
                logger.error("Inconsistent {} synonym mappings detected: ID {} -> Value '{}', but Value '{}' -> ID {}",
                    managedType, id, value, value, mappedId);
                // Consider throwing an exception for critical validation failure
            }
        }
        logger.debug("Validation complete for {} synonyms.", managedType);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            synchronized(this) {
                if (closed) return;
                closed = true;
                try {
                    saveMappings();
                    logger.info("Closed {} annotation synonyms. File: {}", managedType, storageFile);
                } catch (Exception e) {
                    throw new IOException("Failed to close " + managedType + " annotation synonyms at " + storageFile, e);
                }
            }
        }
    }
}