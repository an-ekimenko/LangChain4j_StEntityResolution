package by.beloil.ai.mdm.service;

import by.beloil.ai.mdm.model.UnitGroup;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processing guardrail that validates and splits AI-generated {@link UnitGroup} clusters.
 * <p>
 * Ensures that unit variants with differing numeric quantities, measurement dimensions
 * (e.g., {@code мл} vs. {@code г}), or packaging/container types (e.g., {@code канистра} vs. {@code бочка})
 * are never merged together, and verifies that the LLM-proposed canonical name preserves the exact signature.
 */
@Slf4j
public class NumericGuardrail {

    // Scale multiplier prefixes that alter unit magnitude by orders of 10 (must never be dropped)
    private static final List<String> SCALE_MULTIPLIERS = List.of("тыс", "млн", "млрд");

	
    // Captures a number (integer or decimal with dot/comma) and an optional trailing unit/dimension token
    private static final Pattern NUMBER_AND_UNIT_PATTERN =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*([\\p{L}0-9³²]+\\.?)?");

    // Normalizes common measurement unit aliases appearing after a numeric value
    private static final Map<String, String> UNIT_ALIASES = Map.ofEntries(
            Map.entry("г.", "г"),
            Map.entry("гр", "г"),
            Map.entry("гр.", "г"),
            Map.entry("кг.", "кг"),
            Map.entry("т.", "т"),
            Map.entry("л.", "л"),
            Map.entry("мл.", "мл"),
            Map.entry("шт.", "шт"),
            Map.entry("м.", "м"),
            Map.entry("см.", "см"),
            Map.entry("мм.", "мм"),
            Map.entry("м³", "м3")
    );

    // Anchor keywords representing container/packaging types that must not be dropped or substituted
    private static final List<String> CONTAINER_ANCHORS = List.of(
            "канистра", "бочка", "туб", "флакон", "ведро", "баллон",
            "мешок", "банка", "бутылка", "коробка", "пакет", "упак", "пач", "рул", "бидон"
    );

    /**
     * Audits raw LLM unit groups, splitting mismatched variants into strict signature buckets
     * and falling back to the safest variant if the proposed canonical name distorts the signature.
     *
     * @param rawGroups list of unit groups returned by the LLM
     * @return validated list of safe, consistent {@link UnitGroup} instances
     */
    public List<UnitGroup> validateAndSplit(List<UnitGroup> rawGroups) {
        List<UnitGroup> safeGroups = new ArrayList<>();

        for (UnitGroup group : rawGroups) {
            if (group == null || group.variants() == null || group.variants().isEmpty()) {
                continue;
            }

            // Step 1: Partition variants by strict composite signature (container type + number + dimension)
            Map<String, List<String>> buckets = new LinkedHashMap<>();
            for (String variant : group.variants()) {
                if (variant == null || variant.isBlank()) {
                    continue;
                }
                String fullSignature = buildFullSignature(variant);
                buckets.computeIfAbsent(fullSignature, k -> new ArrayList<>()).add(variant);
            }

            // Step 2: Verify that the LLM-proposed canonicalName matches each subgroup's signature
            for (Map.Entry<String, List<String>> entry : buckets.entrySet()) {
                String expectedSignature = entry.getKey();
                List<String> validVariants = entry.getValue();

                String proposedCanonical = group.canonicalName() != null ? group.canonicalName().trim() : "";
                String canonicalSignature = buildFullSignature(proposedCanonical);

                if (proposedCanonical.isEmpty() || !expectedSignature.equals(canonicalSignature)) {
                    String fallbackCanonical = pickSafestCanonical(validVariants);
                    log.warn("Guardrail adjusted canonical name: '{}' -> '{}' for variants {}",
                            proposedCanonical, fallbackCanonical, validVariants);
                    safeGroups.add(new UnitGroup(fallbackCanonical, validVariants));
                } else {
                    safeGroups.add(new UnitGroup(proposedCanonical, validVariants));
                }
            }
        }
        return safeGroups;
    }

    /**
     * Builds a composite structural signature in the format:
     * {@code [scale_multiplier]:[container_anchor]#[number:normalized_unit]}.
     * <p>
     * Examples:
     * <ul>
     *     <li>{@code "тыс.шт."}       &rarr; {@code "тыс:#"}</li>
     *     <li>{@code "шт."}           &rarr; {@code ":#"}</li>
     *     <li>{@code "туб 400 мл"}    &rarr; {@code ":туб#400:мл"}</li>
     *     <li>{@code "канистра 4,5 л"} &rarr; {@code ":канистра#4.5:л"}</li>
     * </ul>
     *
     * @param text input unit string
     * @return normalized composite signature string
     */
    private String buildFullSignature(String text) {
        if (text == null) {
            return ":#";
        }
        String lower = text.toLowerCase(Locale.ROOT).trim();

        // 1. Detect scale multiplier prefixes (e.g., "тыс", "млн")
        String scalePrefix = "";
        for (String multiplier : SCALE_MULTIPLIERS) {
            if (lower.contains(multiplier)) {
                scalePrefix = multiplier;
                break;
            }
        }

        // 2. Detect packaging/container anchor keyword
        String containerAnchor = "";
        for (String anchor : CONTAINER_ANCHORS) {
            if (lower.contains(anchor)) {
                containerAnchor = anchor;
                break;
            }
        }

        // 3. Extract and normalize all "number + dimension" pairs
        Matcher matcher = NUMBER_AND_UNIT_PATTERN.matcher(lower);
        List<String> numTokens = new ArrayList<>();
        while (matcher.find()) {
            String number = matcher.group(1).replace(',', '.');
            // Strip insignificant trailing zeros after the decimal point (e.g., "4.50" -> "4.5", "5.0" -> "5")
            if (number.contains(".")) {
                number = number.replaceAll("0+$", "").replaceAll("\\.$", "");
            }

            String rawUnit = matcher.group(2);
            String normalizedUnit = "";
            if (rawUnit != null && !rawUnit.isBlank()) {
                normalizedUnit = UNIT_ALIASES.getOrDefault(rawUnit, rawUnit.replaceAll("\\.+$", ""));
            }
            numTokens.add(number + ":" + normalizedUnit);
        }

        return scalePrefix + ":" + containerAnchor + "#" + String.join("|", numTokens);
    }


    /**
     * Selects the shortest trimmed variant as a deterministic fallback canonical name.
     *
     * @param variants non-empty list of valid unit variants sharing the same signature
     * @return shortest trimmed variant string
     */
    private String pickSafestCanonical(List<String> variants) {
        return variants.stream()
                .map(String::trim)
                .min(Comparator.comparingInt(String::length))
                .orElse(variants.get(0).trim());
    }
}
