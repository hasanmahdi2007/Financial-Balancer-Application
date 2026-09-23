package com.hasan.budget.ingestion.plaid;

import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plaid's category vocabulary, translated into ours, from a committed table.
 *
 * <p>The table is data rather than a switch so that adding a category is one row and touches no
 * code. It was built by reading what the sandbox actually returns rather than the documented list,
 * which is not a stylistic preference: a gym arrives as {@code PERSONAL_CARE}, payroll can arrive as
 * a restaurant, and a card's own bill payment arrives tagged as salary. None of those are guessable.
 *
 * <p>Validated on load rather than on use. A malformed row is a mistake in a file nobody runs, and
 * discovering it when a user connects their bank - or worse, not discovering it, because the row
 * silently never matches - is far more expensive than failing at startup.
 */
public class PfcMapping {

    private static final String FILE = "pfc-mapping.csv";

    private final Map<String, Classification> byDetailedValue;

    private PfcMapping(Map<String, Classification> byDetailedValue) {
        this.byDetailedValue = Map.copyOf(byDetailedValue);
    }

    public static PfcMapping fromClasspath() {
        try (InputStream stream = PfcMapping.class.getResourceAsStream(FILE)) {
            if (stream == null) {
                throw new IllegalStateException(FILE + " is missing from the classpath");
            }
            return parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + FILE, e);
        }
    }

    /**
     * Visible so the validation below can be tested against a bad table.
     *
     * <p>Worth testing rather than trusting: the whole point of failing on load is that a malformed
     * row is caught before anybody's bank is connected, and a validation nothing exercises is a
     * validation that might not fire.
     */
    static PfcMapping parse(java.io.Reader source) {
        try (BufferedReader reader = new BufferedReader(source)) {
            return read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the category mapping", e);
        }
    }

    private static PfcMapping read(BufferedReader reader) throws IOException {
        Map<String, Classification> mapping = new HashMap<>();
        String line;
        boolean headerSeen = false;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (!headerSeen) {
                headerSeen = true;
                continue;
            }
            // Five columns then free text, so a reason may contain commas without quoting.
            String[] columns = line.split(",", 6);
            if (columns.length < 5) {
                throw new IllegalStateException("malformed row in " + FILE + ": " + line);
            }
            String detailed = columns[0].trim();
            if (mapping.put(detailed, classificationFrom(columns, line)) != null) {
                throw new IllegalStateException(detailed + " appears twice in " + FILE);
            }
        }
        if (mapping.isEmpty()) {
            throw new IllegalStateException(FILE + " contains no mappings");
        }
        return new PfcMapping(mapping);
    }

    private static Classification classificationFrom(String[] columns, String line) {
        TransactionKind kind = TransactionKind.valueOf(columns[1].trim());
        String categoryName = columns[2].trim();
        boolean savings = "savings".equalsIgnoreCase(columns[3].trim());
        if (kind == TransactionKind.SPEND) {
            if (categoryName.isEmpty()) {
                throw new IllegalStateException("a SPEND row needs a category: " + line);
            }
            if (savings) {
                throw new IllegalStateException("spending cannot also be saving: " + line);
            }
            return Classification.spend(SpendCategory.valueOf(categoryName));
        }
        if (!categoryName.isEmpty()) {
            throw new IllegalStateException("only SPEND rows may carry a category: " + line);
        }
        if (savings && kind != TransactionKind.TRANSFER_INTERNAL) {
            throw new IllegalStateException(
                    "only a transfer between the user's own accounts can be saving: " + line);
        }
        return savings ? Classification.savings() : Classification.notSpending(kind);
    }

    /** Empty for a value this table has never heard of, which the caller must not treat as nothing. */
    public Optional<Classification> classify(String detailedCategory) {
        return Optional.ofNullable(byDetailedValue.get(detailedCategory));
    }

    /** Every value the table covers. Exposed so a test can hold it against the recorded fixtures. */
    public Set<String> knownCategories() {
        return byDetailedValue.keySet();
    }
}
