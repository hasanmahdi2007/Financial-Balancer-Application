package com.hasan.budget.costofliving.classpath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A committed CSV, read from the classpath.
 *
 * <p>Hand-written rather than pulled from a library, and the reason is the rule about dependencies:
 * the seed files are ours, their shape is fixed, and they contain no escaping beyond quoted fields
 * holding commas. A CSV library would be a dependency added to this project's frozen {@code pom.xml}
 * to parse ten files nobody else will ever write.
 *
 * <p>Lines beginning with {@code #} are comments and are skipped. The seed files lean on that
 * heavily: a figure with no explanation of where it came from is the thing this whole module exists
 * to avoid, and a comment in the data is read by whoever edits the data.
 */
final class CsvTable {

    private final List<String> headers;
    private final List<List<String>> rows;

    private CsvTable(List<String> headers, List<List<String>> rows) {
        this.headers = headers;
        this.rows = rows;
    }

    static CsvTable read(String classpathResource) {
        try (InputStream in = CsvTable.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("missing committed seed file: " + classpathResource);
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> headers = null;
                List<List<String>> rows = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    List<String> fields = splitFields(line);
                    if (headers == null) {
                        headers = fields;
                    } else {
                        rows.add(fields);
                    }
                }
                if (headers == null) {
                    throw new IllegalStateException("seed file has no header row: " + classpathResource);
                }
                return new CsvTable(List.copyOf(headers), List.copyOf(rows));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + classpathResource, e);
        }
    }

    /** Each row as column-name to value, with empty strings preserved as empty rather than null. */
    List<Map<String, String>> rows() {
        List<Map<String, String>> out = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            Map<String, String> byName = new java.util.LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                byName.put(headers.get(i), i < row.size() ? row.get(i) : "");
            }
            out.add(Map.copyOf(byName));
        }
        return List.copyOf(out);
    }

    /** Empty where the column is blank, which is how a nullable column such as a quintile arrives. */
    static Optional<String> optional(Map<String, String> row, String column) {
        String value = row.get(column);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    static String required(Map<String, String> row, String column) {
        return optional(row, column)
                .orElseThrow(() -> new IllegalStateException("seed row is missing " + column + ": " + row));
    }

    private static List<String> splitFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                boolean escapedQuote = quoted && i + 1 < line.length() && line.charAt(i + 1) == '"';
                if (escapedQuote) {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }
}
