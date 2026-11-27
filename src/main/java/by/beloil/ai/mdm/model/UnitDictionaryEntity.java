package by.beloil.ai.mdm.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity representing the Master Data Management (MDM) unit of measurement dictionary.
 * Maps raw, unnormalized unit spellings to their standardized canonical designations
 * in the {@code dbo.MDM_Unit_Dictionary} table.
 */
@Entity
@Table(name = "MDM_Unit_Dictionary", schema = "dbo", catalog = "dwbeloil")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UnitDictionaryEntity {
    /**
     * Original, unnormalized unit of measurement spelling (primary key).
     */
    @Id
    @Column(name = "DirtyName", length = 150, nullable = false)
    private String dirtyName;

    /**
     * Standardized canonical designation mapped to the raw unit spelling.
     */
    @Column(name = "CanonicalName", length = 150)
    private String canonicalName;
    
    /**
     * Moderation and verification status of the mapping rule (defaults to {@link DictionaryStatus#AUTO_VERIFIED}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 20, nullable = false)
    private DictionaryStatus status = DictionaryStatus.AUTO_VERIFIED;
    
    /**
     * Diagnostic explanation or audit verdict detailing why the mapping rule was automatically verified,
     * quarantined for manual review, or rejected.
     */
    @Column(name = "ValidationReason", length = 255)
    private String validationReason;

}
