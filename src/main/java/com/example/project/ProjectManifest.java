package com.example.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lightweight JSON manifest stored inside each project index directory.
 * Currently records only the absolute path to the SQLite database file
 * that the indexes were generated from. Additional metadata fields can be
 * added later without breaking backwards-compatibility (e.g., pipeline
 * version, creation timestamp).
 */
public final class ProjectManifest {

    private static final String FILE_NAME = "project.manifest.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dbFile;

    private ProjectManifest(Path dbFile) {
        this.dbFile = dbFile;
    }

    /**
     * Returns the absolute path to the SQLite database file referenced by this manifest.
     */
    public Path dbFile() {
        return dbFile;
    }

    // ---------------------------------------------------------------------
    // Static helpers
    // ---------------------------------------------------------------------

    public static ProjectManifest load(Path manifestPath) throws IOException {
        var node = (ObjectNode) MAPPER.readTree(Files.newBufferedReader(manifestPath));
        String dbFileStr = node.get("db_file").asText();
        return new ProjectManifest(Path.of(dbFileStr));
    }

    public static ProjectManifest loadFromProjectDir(Path projectDir) throws IOException {
        return load(projectDir.resolve(FILE_NAME));
    }

    public static void write(Path projectDir, Path dbFile) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(dbFile, "dbFile");

        ObjectNode node = MAPPER.createObjectNode();
        node.put("db_file", dbFile.toAbsolutePath().toString());
        node.put("created_at", Instant.now().toString());

        Path manifestPath = projectDir.resolve(FILE_NAME);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(Files.newBufferedWriter(manifestPath), node);
    }

    public static String defaultFileName() {
        return FILE_NAME;
    }
}