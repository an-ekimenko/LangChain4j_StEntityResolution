package by.beloil.ai.mdm.model;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * Structured output container returned by the AI normalizer agent, holding
 * all categorized and grouped units of measurement.
 *
 * @param groups list of normalized unit groups mapping raw variants to canonical names
 */
public record UnitNormalizationResult(
    @Description("List of grouped units of measurement")
    List<UnitGroup> groups
) {}

