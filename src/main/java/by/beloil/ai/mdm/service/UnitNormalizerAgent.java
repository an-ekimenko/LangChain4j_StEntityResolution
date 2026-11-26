package by.beloil.ai.mdm.service;

import by.beloil.ai.mdm.model.UnitNormalizationResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;
import java.util.Set;

/**
 * LangChain4j declarative AI service interface responsible for normalizing and grouping
 * Master Data Management (MDM) units of measurement (UoM) under strict domain constraints.
 * <p>
 * Enforces preservation of packaging/container types (e.g., canister, barrel, tube),
 * strict separation of distinct numeric quantities and physical dimensions (mass vs. volume vs. length),
 * and reuse of previously approved canonical designations.
 */
public interface UnitNormalizerAgent {

    /**
     * Classifies and groups a batch of raw unit of measurement strings, mapping them to existing
     * canonical designations where applicable or proposing standardized canonical forms.
     *
     * @param existingCanonicals set of already approved canonical unit names from the MDM dictionary
     * @param dirtyUnits         batch of raw, unnormalized unit strings to classify and group
     * @return structured normalization result containing grouped variants and their canonical designations
     */
    @SystemMessage("""
        Ты — строгий эксперт по нормализации корпоративных справочников MDM и единиц измерения (ЕИ).
        Твоя задача: сгруппировать новые варианты написания ЕИ и сопоставить их с эталонами.

        ЖЕСТКИЕ ПРАВИЛА:
        1. Объединяй ТОЛЬКО те значения, которые означают абсолютно одну и ту же физическую величину или фасовку.
        2. Если подходящий эталон УЖЕ ЕСТЬ в списке утвержденных эталонов — ОБЯЗАТЕЛЬНО используй его в поле canonicalName.
        3. Базовые меры сворачивай к краткому стандартному обозначению без точек:
           - ["кг", "кг.", "килограмм"] -> "кг"
           - ["шт", "шт.", "штука", "штуки"] -> "шт"
           - ["л", "л.", "литр"] -> "л"
           - ["м", "м.", "метр", "погонный метр", "пог. м"] -> "м"
           - ["т", "т.", "тонна"] -> "т"
        4. КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО удалять или подменять тип тары/упаковки (канистра, бочка, туб, флакон, ведро, банка, мешок)!
           - "канистра 5 л" НЕЛЬЗЯ сворачивать в "л" -> эталон "канистра 5 л".
           - "канистра 0,9 л" -> эталон "канистра 0,9 л".
           - "канистра 20 л" НЕЛЬЗЯ менять на "бочка 20 л" -> это РАЗНАЯ тара.
        5. КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО объединять разные числа или разные размерности (массу и объем, длину и объем)!
           - "бочка 175 кг" и "бочка 176 кг" -> РАЗНЫЕ группы.
           - "туб 400 мл" и "туб 400 г" -> РАЗНЫЕ группы (мл != г).
           - "туб 400 г" и "туб 400 гр" -> ОДНА группа -> эталон "туб 400 г".
           - "канистра 4,5 л" и "канистра 4.5л" -> ОДНА группа -> эталон "канистра 4,5 л".
        6. ЗАПРЕЩЕНО объединять меры длины ("метр", "погонный метр") с объемом ("л") или штуками ("шт").
        7. Каждое значение из входного списка должно попасть в поле variants ровно один раз без изменения его исходных символов.
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
