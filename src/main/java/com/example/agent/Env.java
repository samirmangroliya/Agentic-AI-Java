package com.example.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tiny .env reader so we don't need an extra library. Real environment variables win over .env. */
public final class Env {
    private static final Map<String, String> FILE_VALUES = load();

    private Env() {}

    private static Map<String, String> load() {
        Map<String, String> values = new HashMap<>();
        Path path = Path.of(".env");
        if (!Files.exists(path)) {
            return values;
        }
        try {
            List<String> lines = Files.readAllLines(path);
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int eq = line.indexOf('=');
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                // allow API_KEY="abc" or API_KEY='abc'
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            System.err.println("Could not read .env: " + e.getMessage());
        }
        return values;
    }

    public static String get(String key, String defaultValue) {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        String fromFile = FILE_VALUES.get(key);
        return (fromFile != null && !fromFile.isBlank()) ? fromFile : defaultValue;
    }
}
