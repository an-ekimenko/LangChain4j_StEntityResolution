package by.beloil.ai.mdm.model;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * Represents a normalized group of units of measurement (UoM), mapping raw spelling
 * variants to a single standardized canonical designation.
 *
 * @param canonicalName the standardized, concise reference designation for the unit (e.g., 'кг', 'шт', 'бочка 175 кг')
 * @param variants      list of original, unnormalized input strings belonging to this canonical unit
 */
public record UnitGroup(
    @Description("The reference, most concise, and closest to the standard designation of the unit of measurement "
            + "(for example: 'кг', 'шт', 'бочка 175 кг')")
    String canonicalName,

    @Description("All original spelling variants from the input list related to this standard.")
    List<String> variants
) {}
