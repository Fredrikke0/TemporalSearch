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
 * A simple lookup table that maps date strings to integer IDs to improve compression.
 * Instead of storing duplicate date strings like [2024-01-01, 2020-01-01, 1680-01-01],
 * we can store more compact IDs [0, 1, 2] and use this class to look up the original values.
 */
public class DateSynonyms implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DateSynonyms.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String FILENAME = "date_synonyms.ser";

    // Bidirectional mappings
    private final Map<String, Integer> dateToId = new ConcurrentHashMap<>();
    private final Map<Integer, String> idToDate = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Path storageFile;
    private volatile boolean closed = false;
    private boolean modified = false;

    /**
     * Creates a new DateSynonyms instance, loading existing mappings if available.
     *
     * @param baseDir Base directory for storing the synonym mappings
     * @throws IOException If there's an error loading existing mappings
     */
    public DateSynonyms(Path baseDir) throws IOException {
        try {
            Files.createDirectories(baseDir);
            this.storageFile = baseDir.resolve(FILENAME);
            loadMappings();
            logger.info("Initialized date synonyms at {}", storageFile);
        } catch (Exception e) {
            throw new IOException("Failed to initialize date synonyms", e);
        }
    }

    /**
     * Gets or creates an ID for the given date value.
     *
     * @param dateValue The date in YYYY-MM-DD format
     * @return The ID for the date
     * @throws IllegalArgumentException if the date format is invalid
     * @throws IllegalStateException if the synonyms database is closed
     */
    public int getOrCreateId(String dateValue) {
        if (closed) {
            throw new IllegalStateException("DateSynonyms is closed");
        }

        validateDateFormat(dateValue);

        // Check cache first
        Integer existingId = dateToId.get(dateValue);
        if (existingId != null) {
            return existingId;
        }

        // Create new ID
        synchronized(this) {
            if (closed) {
                throw new IllegalStateException("DateSynonyms was closed during operation");
            }

            // Check again in case another thread created it
            existingId = dateToId.get(dateValue);
            if (existingId != null) {
                return existingId;
            }

            int id = nextId.getAndIncrement();
            dateToId.put(dateValue, id);
            idToDate.put(id, dateValue);
            modified = true;

            //logger.debug("Created new date synonym: {} -> {}", dateValue, id);
            return id;
        }
    }

    /**
     * Gets the date value for a given synonym ID.
     *
     * @param synonymId The ID to look up
     * @return The date value, or null if not found
     * @throws IllegalStateException if the synonyms database is closed
     */
    public String getDateValue(int synonymId) {
        if (closed) {
            throw new IllegalStateException("DateSynonyms is closed");
        }

        return idToDate.get(synonymId);
    }

    /**
     * Validates that a date string is in the correct format (YYYY-MM-DD).
     *
     * @param dateValue The date string to validate
     * @throws IllegalArgumentException if the date format is invalid
     */
    private void validateDateFormat(String dateValue) {
        if (dateValue == null || !DATE_PATTERN.matcher(dateValue).matches()) {
            throw new IllegalArgumentException("Date must be in YYYY-MM-DD format");
        }

        try {
            LocalDate.parse(dateValue, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date: " + dateValue, e);
        }
    }

    /**
     * Loads existing mappings from disk if available.
     */
    @SuppressWarnings("unchecked")
    private void loadMappings() throws IOException {
        if (Files.exists(storageFile)) {
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(storageFile))) {
                Map<String, Integer> loadedDateToId = (Map<String, Integer>) ois.readObject();
                int maxId = 0;

                for (Map.Entry<String, Integer> entry : loadedDateToId.entrySet()) {
                    dateToId.put(entry.getKey(), entry.getValue());
                    idToDate.put(entry.getValue(), entry.getKey());
                    maxId = Math.max(maxId, entry.getValue());
                }

                nextId.set(maxId + 1);
                logger.info("Loaded {} date synonyms with next ID {}", dateToId.size(), nextId.get());
            } catch (ClassNotFoundException e) {
                throw new IOException("Failed to load date synonyms", e);
            }
        }
    }

    /**
     * Saves the current mappings to disk if they've been modified.
     */
    private void saveMappings() throws IOException {
        if (modified) {
            Files.createDirectories(storageFile.getParent());
            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(storageFile))) {
                oos.writeObject(new HashMap<>(dateToId)); // Create a serializable copy
                logger.info("Saved {} date synonyms", dateToId.size());
                modified = false;
            }
        }
    }

    /**
     * Validates the consistency of the synonym mappings by checking all entries.
     */
    public void validateSynonyms() {
        for (Map.Entry<Integer, String> entry : idToDate.entrySet()) {
            int id = entry.getKey();
            String date = entry.getValue();
            Integer mappedId = dateToId.get(date);

            if (mappedId == null || mappedId != id) {
                logger.error("Inconsistent synonym mappings detected: {} maps to {} but {} maps to {}",
                    date, mappedId, id, date);
            }
        }
    }

    /**
     * Returns the number of date synonyms in this collection.
     *
     * @return The number of date synonyms
     */
    public int size() {
        return dateToId.size();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                saveMappings();
                logger.info("Closed date synonyms");
            } catch (Exception e) {
                throw new IOException("Failed to close date synonyms", e);
            }
        }
    }
}