package by.beloil.ai.mdm.service;

import by.beloil.ai.mdm.model.DictionaryStatus;
import by.beloil.ai.mdm.model.UnitDictionaryEntity;
import by.beloil.ai.mdm.model.VerificationVerdict;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Multi-stage validation pipeline that audits candidate unit of measurement mappings
 * before persisting them to the MDM dictionary.
 * <p>
 * Combines fast deterministic heuristics (exact match bypass, unauthorized Latin script detection,
 * forbidden token checks, length inflation bounds, Jaro-Winkler lexical similarity) with an isolated
 * LLM-as-a-Judge verification step ({@link UnitVerifierAgent}), routing suspicious or failed mappings
 * to a quarantine state ({@link DictionaryStatus#NEEDS_REVIEW}).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MappingValidationService {

    private final UnitVerifierAgent verifierAgent;
    private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();

    // Precompiled pattern for detecting Latin characters
    private static final Pattern LATIN_PATTERN = Pattern.compile(".*[a-zA-Z]+.*");

    // Standard well-known unit abbreviations where low lexical similarity is acceptable (e.g., "штука" -> "шт")
    private static final Set<String> STANDARD_SHORT_UNITS = Set.of(
            "кг", "г", "т", "л", "мл", "шт", "м", "см", "мм", 
            "м2", "м3", "упаковка", "пачка", "рулон", "комплект",
            "пара", "банка", "бочка","ведро", "канистра", "набор",
            "п.м.", "бутылка", "баллон"
    );
    
    // Strict whitelist of allowed raw source terms for single-letter canonical units (л, м, г, т)
    private static final Map<String, Set<String>> SINGLE_LETTER_WHITELIST = Map.of(
            "л", Set.of("л", "л.", "литр", "литра", "литров", "литры"),
            "м", Set.of("м", "м.", "метр", "метра", "метров", "метры", "погонный метр", "пог. м", "п.м", "п.м."),
            "г", Set.of("г", "г.", "гр", "гр.", "грамм", "грамма", "граммов", "граммы"),
            "т", Set.of("т", "т.", "тонна", "тонны", "тонн")
    );
    
    // Disallowed placeholder or refusal tokens that indicate LLM hallucinations
    private static final Set<String> FORBIDDEN_TOKENS = Set.of(
            "null", "unknown", "неизвестно", "ошибка", "error", "n/a"
    );

    // Scale multipliers that must never be dropped or altered
    private static final java.util.List<String> SCALE_MULTIPLIERS = java.util.List.of("тыс", "млн", "млрд");

    /**
     * Validates a proposed raw-to-canonical unit mapping and constructs a {@link UnitDictionaryEntity}
     * with an appropriate lifecycle status ({@code AUTO_VERIFIED} or {@code NEEDS_REVIEW}) and audit reason.
     *
     * @param dirtyName        the raw, unnormalized unit spelling
     * @param canonicalName    the proposed standardized canonical designation
     * @param isKnownCanonical {@code true} if the canonical name already existed in the approved dictionary
     * @return populated {@link UnitDictionaryEntity} ready for persistence
     */
    public UnitDictionaryEntity validateAndBuildEntity(String dirtyName, String canonicalName, boolean isKnownCanonical) {
        String cleanDirty = dirtyName.trim();
        String cleanCanonical = canonicalName.trim();

        // 1. Identical pair (no substitution needed) — auto-approve immediately without invoking the LLM judge
        if (cleanDirty.equals(cleanCanonical)) {
            return new UnitDictionaryEntity(cleanDirty, cleanCanonical, DictionaryStatus.AUTO_VERIFIED, "Exact match (no change required)");
        }

        // 2. Block unauthorized transliteration or translation into Latin script (e.g., "компл" -> "comp")
        if (LATIN_PATTERN.matcher(cleanCanonical).matches() && !LATIN_PATTERN.matcher(cleanDirty).matches()) {
            return quarantine(cleanDirty, cleanCanonical, "Unexpected Latin characters generated for Cyrillic input");
        }

        // 3. Check for forbidden placeholder words or refusal hallucinations
        String lowerDirty = cleanDirty.toLowerCase(Locale.ROOT);
        String lowerCanonical = cleanCanonical.toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_TOKENS) {
            if (lowerCanonical.contains(forbidden)) {
                return quarantine(cleanDirty, cleanCanonical, "Forbidden token detected: " + forbidden);
            }
        }

        // 4. Guard against dropping or altering scale multipliers (e.g., "тыс.шт." -> "шт", "млн. руб" -> "руб")
        for (String multiplier : SCALE_MULTIPLIERS) {
            if (lowerDirty.contains(multiplier) != lowerCanonical.contains(multiplier)) {
                return quarantine(cleanDirty, cleanCanonical,
                        String.format("Scale multiplier mismatch ('%s'): cannot drop or alter order of magnitude", multiplier));
            }
        }

        // 5. Guard against abnormal length inflation of the canonical designation
        if (cleanCanonical.length() > cleanDirty.length() + 6) {
            return quarantine(cleanDirty, cleanCanonical,
                    String.format("Canonical length (%d) exceeds dirty length (%d) + 6", cleanCanonical.length(), cleanDirty.length()));
        }

        // 6. Lexical similarity check (Jaro-Winkler Similarity)
        double similarity = computeNormalizedSimilarity(cleanDirty, cleanCanonical);
        boolean isBasicShortUnit = STANDARD_SHORT_UNITS.contains(lowerCanonical);
        if (!isBasicShortUnit && similarity < 0.72) {
            return quarantine(cleanDirty, cleanCanonical,
                    String.format(Locale.ROOT, "Low lexical similarity (Jaro-Winkler = %.2f < 0.72)", similarity));
        }

        // 7. Guard against erroneously collapsing full words (e.g., "лист", "лента", "мешок") into a single-letter unit ("л", "м")
        if (cleanCanonical.length() == 1 && SINGLE_LETTER_WHITELIST.containsKey(lowerCanonical)) {
            Set<String> allowedSources = SINGLE_LETTER_WHITELIST.get(lowerCanonical);
            if (!allowedSources.contains(lowerDirty)) {
                return quarantine(cleanDirty, cleanCanonical,
                        String.format("Blocked unverified collapse into single-letter unit '%s'", cleanCanonical));
            }
        }

        // 8. Isolated semantic audit via LLM-as-a-Judge (invoked only for non-trivial substitutions)
        try {
            VerificationVerdict verdict = verifierAgent.verifyPair(cleanDirty, cleanCanonical);
            if (verdict == null || !verdict.isValid()) {
                String judgeReason = (verdict != null && verdict.reason() != null)
                        ? verdict.reason()
                        : "Rejected by LLM-as-a-Judge";
                return quarantine(cleanDirty, cleanCanonical, "LLM Judge: " + truncate(judgeReason, 220));
            }
            String approvedReason = String.format(Locale.ROOT, "Verified (sim=%.2f, known=%s): %s",
                    similarity, isKnownCanonical, truncate(verdict.reason(), 180));
            return new UnitDictionaryEntity(cleanDirty, cleanCanonical, DictionaryStatus.AUTO_VERIFIED, approvedReason);

        } catch (Exception ex) {
            log.warn("LLM judge invocation failed for pair '{}' -> '{}'. Sending to quarantine.", cleanDirty, cleanCanonical, ex);
            return quarantine(cleanDirty, cleanCanonical, "LLM Judge timeout/error: " + truncate(ex.getMessage(), 200));
        }
    }


    /**
     * Computes Jaro-Winkler lexical similarity between two unit strings after stripping periods
     * and normalizing whitespace.
     *
     * @param s1 first string
     * @param s2 second string
     * @return similarity score in the range {@code [0.0, 1.0]}
     */
    private double computeNormalizedSimilarity(String s1, String s2) {
        // Strip periods and collapse extra whitespace before comparing strings
        String n1 = s1.toLowerCase(Locale.ROOT).replace(".", "").replaceAll("\\s+", " ").trim();
        String n2 = s2.toLowerCase(Locale.ROOT).replace(".", "").replaceAll("\\s+", " ").trim();
        Double score = jaroWinkler.apply(n1, n2);
        return score != null ? score : 0.0;
    }

    /**
     * Logs a warning and constructs a quarantined {@link UnitDictionaryEntity} with status {@link DictionaryStatus#NEEDS_REVIEW}.
     *
     * @param dirty     raw unit spelling
     * @param canonical proposed canonical designation
     * @param reason    diagnostic explanation for quarantine
     * @return quarantined dictionary entity
     */
    private UnitDictionaryEntity quarantine(String dirty, String canonical, String reason) {
        log.warn("Mapping rule quarantined (NEEDS_REVIEW): '{}' -> '{}'. Reason: {}", dirty, canonical, reason);
        return new UnitDictionaryEntity(dirty, canonical, DictionaryStatus.NEEDS_REVIEW, truncate(reason, 255));
    }

    /**
     * Truncates a string to fit within a maximum database column length, appending an ellipsis if shortened.
     *
     * @param text   input string
     * @param maxLen maximum allowed character length
     * @return truncated string, or empty string if input is {@code null}
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 3) + "...";
    }
}
