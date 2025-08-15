package com.example.index.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SynonymManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SynonymManager.class);

    private final RocksDB db;
    private final Path dbPath;
    private final Map<String, Integer> termToIdCache;
    private final Map<Integer, String> idToTermCache;
    private final AtomicInteger nextId;

    private static final byte[] NEXT_ID_KEY = "__NEXT_ID__".getBytes(StandardCharsets.UTF_8);

    public SynonymManager(Path dbPath) throws RocksDBException {
        this.dbPath = dbPath;
        RocksDB.loadLibrary();
        final Options options = new Options().setCreateIfMissing(true);

        try {
            Files.createDirectories(dbPath.getParent());
            this.db = RocksDB.open(options, dbPath.toString());
        } catch (IOException e) {
            throw new RocksDBException("Failed to create directories for RocksDB: " + dbPath + ". Original error: " + e.getMessage());
        }

        this.termToIdCache = new HashMap<>();
        this.idToTermCache = new HashMap<>();
        this.nextId = new AtomicInteger(loadNextId());
        loadAllMappings(); // Load existing mappings into cache
    }

    private int loadNextId() throws RocksDBException {
        byte[] value = db.get(NEXT_ID_KEY);
        if (value != null) {
            return Integer.parseInt(new String(value, StandardCharsets.UTF_8));
        }
        return 1; // Start IDs from 1
    }

    private void saveNextId() throws RocksDBException {
        db.put(NEXT_ID_KEY, Integer.toString(nextId.get()).getBytes(StandardCharsets.UTF_8));
    }

    private void loadAllMappings() throws RocksDBException {
        try (final RocksIterator iterator = db.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                byte[] keyBytes = iterator.key();
                String keyString = new String(keyBytes, StandardCharsets.UTF_8);

                if (keyString.equals(new String(NEXT_ID_KEY, StandardCharsets.UTF_8))) {
                    continue; // Skip the next_id metadata key
                }

                // Key format: "term:<term>" or "id:<id>"
                String[] parts = keyString.split(":", 2);
                if (parts.length < 2) {
                    logger.warn("Skipping malformed key in SynonymManager: {}", keyString);
                    continue;
                }
                String prefix = parts[0];
                String actualKey = parts[1];

                byte[] valueBytes = iterator.value();

                if ("term".equals(prefix)) {
                    String term = actualKey;
                    int id = Integer.parseInt(new String(valueBytes, StandardCharsets.UTF_8));
                    termToIdCache.put(term, id);
                    idToTermCache.put(id, term);
                }
            }
        }
        logger.info("Loaded {} term-ID mappings from {}", termToIdCache.size(), dbPath);
    }

    public synchronized int getId(String term) throws RocksDBException {
        if (term == null || term.isEmpty()) {
            throw new IllegalArgumentException("Term cannot be null or empty");
        }
        if (termToIdCache.containsKey(term)) {
            return termToIdCache.get(term);
        }

        int newId = nextId.getAndIncrement();
        byte[] termKey = ("term:" + term).getBytes(StandardCharsets.UTF_8);
        byte[] idValue = Integer.toString(newId).getBytes(StandardCharsets.UTF_8);

        byte[] idKey = ("id:" + newId).getBytes(StandardCharsets.UTF_8);
        byte[] termValue = term.getBytes(StandardCharsets.UTF_8);

        // Store bi-directional mapping
        db.put(termKey, idValue);
        db.put(idKey, termValue);

        termToIdCache.put(term, newId);
        idToTermCache.put(newId, term);
        saveNextId();
        return newId;
    }

    public Optional<String> getTerm(int id) throws RocksDBException {
        if (idToTermCache.containsKey(id)) {
            return Optional.of(idToTermCache.get(id));
        }
        // Fallback to DB if not in cache (should be rare if loadAllMappings is comprehensive)
        byte[] idKey = ("id:" + id).getBytes(StandardCharsets.UTF_8);
        byte[] termBytes = db.get(idKey);
        if (termBytes != null) {
            String term = new String(termBytes, StandardCharsets.UTF_8);
            idToTermCache.put(id, term);
            if (!termToIdCache.containsKey(term)) {
                 termToIdCache.put(term, id);
            }
            return Optional.of(term);
        }
        return Optional.empty();
    }

    public Map<Integer, String> getTerms(Set<Integer> ids) throws RocksDBException {
        Map<Integer, String> result = new HashMap<>();
        List<Integer> idsNotInCache = new ArrayList<>();
        List<byte[]> keysToFetchFromDB = new ArrayList<>();

        for (Integer id : ids) {
            if (idToTermCache.containsKey(id)) {
                result.put(id, idToTermCache.get(id));
            } else {
                idsNotInCache.add(id);
                keysToFetchFromDB.add(("id:" + id).getBytes(StandardCharsets.UTF_8));
            }
        }

        if (!keysToFetchFromDB.isEmpty()) {
            logger.debug("Attempting to fetch {} terms from RocksDB using multiGetAsList for getTerms operation.", keysToFetchFromDB.size());
            List<byte[]> valuesFromDB = db.multiGetAsList(keysToFetchFromDB);

            for (int i = 0; i < valuesFromDB.size(); i++) {
                byte[] termBytes = valuesFromDB.get(i);
                Integer idToFetch = idsNotInCache.get(i);

                if (termBytes != null) {
                    String term = new String(termBytes, StandardCharsets.UTF_8);
                    result.put(idToFetch, term);
                    idToTermCache.put(idToFetch, term);
                    if (termToIdCache.containsKey(term) && termToIdCache.get(term).intValue() != idToFetch.intValue()) {
                        logger.warn("SynonymManager cache conflict: Term '{}' (fetched for ID {}) already exists in termToIdCache with a different ID ({}). Caching new ID->Term mapping for {}.",
                                    term, idToFetch, termToIdCache.get(term), idToFetch);
                    } else if (!termToIdCache.containsKey(term)) {
                        termToIdCache.put(term, idToFetch);
                    }
                    logger.trace("Fetched term '{}' for ID {} from DB via multiGetAsList and cached.", term, idToFetch);
                } else {
                    logger.warn("Synonym ID {} not found in RocksDB during multiGetAsList operation for getTerms.", idToFetch);
                }
            }
        }
        return result;
    }

    public synchronized void putBatch(Map<String, Integer> batch) throws RocksDBException {
        // This is a simplified batch put, assuming new terms.
        // A more robust version would check for existing terms or handle conflicts.
        for (Map.Entry<String, Integer> entry : batch.entrySet()) {
            String term = entry.getKey();
            int id = entry.getValue(); // Assuming pre-assigned IDs for batch load

            if (term == null || term.isEmpty()) {
                logger.warn("Skipping null or empty term in batch put.");
                continue;
            }
            if (termToIdCache.containsKey(term) || idToTermCache.containsKey(id)) {
                 logger.warn("Term '{}' or ID '{}' already exists. Skipping in batch put.", term, id);
                 continue;
            }

            byte[] termKey = ("term:" + term).getBytes(StandardCharsets.UTF_8);
            byte[] idValue = Integer.toString(id).getBytes(StandardCharsets.UTF_8);
            byte[] idKey = ("id:" + id).getBytes(StandardCharsets.UTF_8);
            byte[] termValue = term.getBytes(StandardCharsets.UTF_8);

            db.put(termKey, idValue);
            db.put(idKey, termValue);

            termToIdCache.put(term, id);
            idToTermCache.put(id, term);

            // Ensure nextId is updated if batch IDs are higher
            if (id >= nextId.get()) {
                nextId.set(id + 1);
            }
        }
        saveNextId(); // Save the potentially updated nextId
    }


    @Override
    public void close() {
        if (db != null) {
            try {
                saveNextId(); // Ensure nextId is saved on close
            } catch (RocksDBException e) {
                logger.error("Failed to save next ID on close for DB at " + dbPath, e);
            }
            db.close();
        }
    }

    // Utility to delete the database directory for testing or reset
    public void deleteDatabaseFiles() throws IOException {
        close(); // Ensure DB is closed before deleting
        if (Files.exists(dbPath)) {
            Files.walk(dbPath)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
            logger.info("Deleted database files at {}", dbPath);
        }
    }
}