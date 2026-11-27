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
            Ты — эксперт MDM по нормализации единиц измерения (ЕИ). Сгруппируй входной список и выбери эталон (canonicalName).

            ПРАВИЛА:
            1. Если подходящий эталон есть в списке утвержденных — используй его.
            2. Базовые меры сокращай без точек: "кг."->"кг", "шт."/"штука"->"шт", "л."->"л", "метр"/"погонный метр"->"м", "упак."->"упак".
               - Сокращения тары и штук с числами: "бан"/"бан." -> "банка", "1 штука"/"2 штуки" -> "1 шт"/"2 шт".
            3. НЕ удаляй и НЕ меняй тару (канистра, бочка, туб, флакон, ведро, мешок): "канистра 20л" и "канистра 20 л" -> "канистра 20 л".
            4. НЕ объединяй разные числа или единицы измерения: "флакон 455 гр" и "флакон 650 мл" — РАЗНЫЕ группы.
            5. КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО отбрасывать масштабные множители ("тыс.", "млн."):
               - "тыс.шт.", "тыс. шт.", "тысяча штук" -> эталон "тыс. шт" (НИКОГДА не сворачивать в "шт").
               - "тыс. л", "тыс.л." -> эталон "тыс. л".
            6. Используй ТОЛЬКО русский язык (кириллицу), запрещено переводить сокращения на английский ("компл" -> "компл", не "comp").
            7. Каждое входное значение должно попасть в variants без изменения символов.
            8. Верни строго JSON формата: {"groups": [{"canonicalName": "эталон", "variants": ["вариант1", "вариант2"]}]}
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
