package by.beloil.ai.mdm.service;

import by.beloil.ai.mdm.model.DictionaryStatus;
import by.beloil.ai.mdm.model.UnitDictionaryEntity;
import by.beloil.ai.mdm.model.VerificationVerdict;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import java.util.Locale;
import java.util.Set;

/**
 * Multi-stage validation pipeline that audits candidate unit of measurement mappings
 * before persisting them to the MDM dictionary.
 * <p>
 * Combines fast deterministic heuristics (exact match bypass, forbidden token detection,
 * length inflation bounds, Jaro-Winkler lexical similarity) with an isolated LLM-as-a-Judge
 * verification step ({@link UnitVerifierAgent}), routing suspicious or failed mappings
 * to a quarantine state ({@link DictionaryStatus#NEEDS_REVIEW}).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MappingValidationService {

    private final UnitVerifierAgent verifierAgent;
    private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();

    // Standard well-known unit abbreviations where low lexical similarity is acceptable (e.g., "штука" -> "шт")
    private static final Set<String> STANDARD_SHORT_UNITS = Set.of(
            "кг", "г", "т", "л", "мл", "шт", "м", "см", "мм", "м2", "м3", "упак", "пач", "рул", "компл"
    );

    // Disallowed placeholder or refusal tokens that indicate LLM hallucinations
    private static final Set<String> FORBIDDEN_TOKENS = Set.of(
            "null", "unknown", "неизвестно", "ошибка", "error", "n/a"
    );

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

        // 2. Check for forbidden placeholder words or refusal hallucinations
        String lowerCanonical = cleanCanonical.toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_TOKENS) {
            if (lowerCanonical.contains(forbidden)) {
                return quarantine(cleanDirty, cleanCanonical, "Forbidden token detected: " + forbidden);
            }
        }

        // 3. Guard against abnormal length inflation of the canonical designation
        if (cleanCanonical.length() > cleanDirty.length() + 6) {
            return quarantine(cleanDirty, cleanCanonical,
                    String.format("Canonical length (%d) exceeds dirty length (%d) + 6", cleanCanonical.length(), cleanDirty.length()));
        }

        // 4. Lexical similarity check (Jaro-Winkler Similarity)
        double similarity = computeNormalizedSimilarity(cleanDirty, cleanCanonical);
        boolean isBasicShortUnit = STANDARD_SHORT_UNITS.contains(lowerCanonical);
        if (!isBasicShortUnit && similarity < 0.72) {
            return quarantine(cleanDirty, cleanCanonical,
                    String.format(Locale.ROOT, "Low lexical similarity (Jaro-Winkler = %.2f < 0.72)", similarity));
        }

        // 5. Isolated semantic audit via LLM-as-a-Judge (invoked only for non-trivial substitutions)
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

