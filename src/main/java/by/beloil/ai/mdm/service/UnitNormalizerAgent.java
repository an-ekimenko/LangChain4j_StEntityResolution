package by.beloil.ai.mdm.service;

import by.beloil.ai.mdm.model.UnitNormalizationResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;
import java.util.Set;

/**
 * LangChain4j AI service interface responsible for normalizing and grouping
 * Master Data Management (MDM) units of measurement (UoM).
 */
public interface UnitNormalizerAgent {

    /**
     * Groups raw unit of measurement variants and maps them to existing canonical names
     * or proposes new standardized canonical designations.
     *
     * @param existingCanonicals set of already approved canonical unit names from the MDM database
     * @param dirtyUnits         list of raw, unnormalized unit strings to be classified and grouped
     * @return structured normalization result containing grouped variants and their canonical names
     */
    @SystemMessage("""
        Ты — эксперт по нормализации корпоративных справочников MDM и единиц измерения (ЕИ).
        Твоя задача: сгруппировать новые варианты написания ЕИ и сопоставить их с эталонами.

        ПРАВИЛА:
        1. Объединяй ТОЛЬКО те значения, которые означают абсолютно одну и ту же единицу измерения или фасовку.
        2. Если подходящий эталон УЖЕ ЕСТЬ в списке утвержденных эталонов — ОБЯЗАТЕЛЬНО используй его в поле canonicalName.
        3. Если в утвержденных эталонах подходящего нет — выбери наиболее краткое и стандартное обозначение (например: 'кг', 'шт', 'л', 'т').
        4. КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО объединять тару с разными числовыми параметрами веса или объема!
           Пример: 'бочка 175 кг' и 'бочка 176 кг' — это РАЗНЫЕ группы. 'канистра 20 кг' и 'канистра 20 л' — РАЗНЫЕ группы.
        5. Каждое значение из входного списка должно попасть в поле variants ровно один раз без изменения символов.
        """)
    @UserMessage("""
        Уже утвержденные эталоны в базе:
        {{existingCanonicals}}

        Новые грязные значения для классификации:
        {{dirtyUnits}}
        """)
    UnitNormalizationResult normalizeBatch(
        @V("existingCanonicals") Set<String> existingCanonicals,
        @V("dirtyUnits") List<String> dirtyUnits
    );
}


