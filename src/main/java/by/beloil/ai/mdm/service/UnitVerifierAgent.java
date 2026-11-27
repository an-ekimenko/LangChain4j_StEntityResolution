package by.beloil.ai.mdm.service;

import by.beloil.ai.mdm.model.VerificationVerdict;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j declarative AI service interface acting as a secondary LLM-as-a-Judge auditor
 * to verify proposed unit of measurement (UoM) mappings before they are approved for production use.
 */
public interface UnitVerifierAgent {

    /**
     * Evaluates a single raw-to-canonical unit mapping pair to verify semantic, dimensional,
     * and packaging equivalence.
     *
     * @param dirtyName     the original, unnormalized unit spelling from the source system
     * @param canonicalName the proposed standardized canonical designation
     * @return a {@link VerificationVerdict} indicating whether the mapping is valid along with a brief explanation
     */
    @SystemMessage("""
        Ты — строгий аудитор качества мастер-данных (MDM). Твоя задача: проверить предложенную замену единицы измерения (ЕИ).
        Ответь isValid = true ТОЛЬКО если исходное значение (dirtyName) и эталон (canonicalName) означают абсолютно одну 
        и ту же единицу измерения и тип тары.
        Верни строго JSON формата: {"isValid": true, "reason": "краткое пояснение"}

        КРИТЕРИИ ОТКЛОНЕНИЯ (isValid = false):
        - Потерян или изменен тип упаковки ("канистра 5 л" -> "л" или "канистра 20 л" -> "бочка 20 л").
        - Изменены числовые параметры или размерность ("бочка 175 кг" -> "бочка 176 кг", "туб 400 мл" -> "туб 400 г").
        - Смешаны разные физические величины ("метр" -> "л", "погонный метр" -> "шт").
        - Потерян или изменен масштабный множитель ("тыс.шт." -> "шт", "млн. руб" -> "руб").


        КРИТЕРИИ ОДОБРЕНИЯ (isValid = true):
        - Убраны лишние точки или пробелы ("кг." -> "кг", "канистра 20л" -> "канистра 20 л").
        - Приведено к стандартному сокращению ("штука" -> "шт", "погонный метр" -> "м", "туб 400 гр" -> "туб 400 г").
        """)
    @UserMessage("Проверь замену: исходное = '{{dirtyName}}', предложенный эталон = '{{canonicalName}}'")
    VerificationVerdict verifyPair(@V("dirtyName") String dirtyName, @V("canonicalName") String canonicalName);
}
