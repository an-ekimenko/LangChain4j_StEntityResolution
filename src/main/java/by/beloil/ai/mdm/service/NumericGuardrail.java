package by.beloil.ai.mdm.service;

import by.beloil.ai.mdm.model.UnitGroup;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processing guardrail that validates AI-generated {@link UnitGroup} instances
 * to ensure that unit variants containing different numeric values (e.g., package weights or volumes)
 * are never merged into the same canonical group.
 */
@Slf4j
public class NumericGuardrail {

    // Matches all integer and decimal numbers separated by a dot or comma
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:[.,]\\d+)?");

    /**
     * Validates a list of AI-grouped units and splits any group where variants
     * have mismatched numeric signatures into separate, safe subgroups.
     *
     * @param rawGroups list of unit groups returned by the LLM
     * @return validated list of unit groups where all variants within a group share identical numeric values
     */
    public List<UnitGroup> validateAndSplit(List<UnitGroup> rawGroups) {
        List<UnitGroup> safeGroups = new ArrayList<>();

        for (UnitGroup group : rawGroups) {
            Map<String, List<String>> byNumericSignature = new LinkedHashMap<>();

            for (String variant : group.variants()) {
                String signature = extractNumericSignature(variant);
                byNumericSignature.computeIfAbsent(signature, k -> new ArrayList<>()).add(variant);
            }

            if (byNumericSignature.size() <= 1) {
                safeGroups.add(group);
            } else {
                log.warn("Guardrail triggered! The model attempted to merge variants with different "
                		+ "numeric values into group '{}': {}",
                        group.canonicalName(), group.variants());
                // Split the invalid group into safe subgroups based on their numeric signature
                for (List<String> splitVariants : byNumericSignature.values()) {
                    String safeCanonical = splitVariants.stream()
                            .min(Comparator.comparingInt(String::length))
                            .orElse(splitVariants.get(0));
                    safeGroups.add(new UnitGroup(safeCanonical, splitVariants));
                }
            }

        }
        return safeGroups;
    }

    /**
     * Extracts and normalizes all numbers from the given text into a canonical signature string.
     *
     * @param text input unit string
     * @return pipe-delimited string of normalized numbers (e.g., "175|20.5"), or empty string if none found
     */
    private String extractNumericSignature(String text) {
        if (text == null) return "";
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        List<String> numbers = new ArrayList<>();
        while (matcher.find()) {
            // Normalize commas to dots so "4,5" and "4.5" produce the exact same signature
            numbers.add(matcher.group().replace(',', '.'));
        }
        return String.join("|", numbers);
    }
}
