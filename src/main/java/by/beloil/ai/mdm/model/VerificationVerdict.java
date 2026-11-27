package by.beloil.ai.mdm.model;

import dev.langchain4j.model.output.structured.Description;

/**
 * Structured output model representing the verification verdict from an LLM judge/critic
 * validating whether a raw unit spelling accurately maps to a proposed canonical designation.
 *
 * @param isValid {@code true} if the raw and canonical values represent the exact same physical quantity,
 *                unit of measurement, and packaging type; {@code false} otherwise
 * @param reason  concise explanation (up to 150 characters) justifying why the mapping is valid or invalid
 */
public record VerificationVerdict(
    @Description("true if the original and canonical values represent the exact same physical quantity, "
    				+ "unit of measurement, and packaging type; otherwise false")
    boolean isValid,

    @Description("Brief explanation in Russian (up to 150 characters) explaining why the substitution is valid or erroneous")
    String reason
) {}
