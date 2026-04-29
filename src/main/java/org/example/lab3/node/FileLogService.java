package org.example.lab3.node;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * FileLogService manages reading and writing FileLog JSON files to disk.
 *
 * Log files are stored in the "logs/" directory inside the node's working folder.
 * This service is used by the OWNER node — the node that received a replica.
 *
 * Key operations:
 *   - save(log)       — write or overwrite a log file
 *   - load(filename)  — read a log file back
 *   - delete(filename)— remove a log file when a file is fully deleted
 *   - exists(filename)— check if a log exists for a given file
 */
@Service
@Profile("node")
public class FileLogService {

    @Value("${node.logs.dir:logs}")
    private String logsDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private void ensureDir() {
        try {
            Files.createDirectories(Paths.get(logsDir));
        } catch (Exception e) {
            System.err.println("[FileLogService] Could not create logs dir: " + e.getMessage());
        }
    }

    /** Saves a FileLog to disk as JSON at logs/{filename}.json */
    public void save(FileLog log) {
        ensureDir();
        try {
            File file = Paths.get(logsDir, log.getFilename() + ".json").toFile();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, log);
            System.out.println("[FileLogService] Saved log for: " + log.getFilename());
        } catch (Exception e) {
            System.err.println("[FileLogService] Failed to save log for "
                    + log.getFilename() + ": " + e.getMessage());
        }
    }

    /** Loads a FileLog from disk. Returns null if not found. */
    public FileLog load(String filename) {
        try {
            File file = Paths.get(logsDir, filename + ".json").toFile();
            if (!file.exists()) return null;
            return mapper.readValue(file, FileLog.class);
        } catch (Exception e) {
            System.err.println("[FileLogService] Failed to load log for "
                    + filename + ": " + e.getMessage());
            return null;
        }
    }

    /** Deletes a log file from disk. */
    public void delete(String filename) {
        try {
            Path path = Paths.get(logsDir, filename + ".json");
            Files.deleteIfExists(path);
            System.out.println("[FileLogService] Deleted log for: " + filename);
        } catch (Exception e) {
            System.err.println("[FileLogService] Failed to delete log for "
                    + filename + ": " + e.getMessage());
        }
    }

    /** Returns true if a log exists for this filename. */
    public boolean exists(String filename) {
        return Paths.get(logsDir, filename + ".json").toFile().exists();
    }
}