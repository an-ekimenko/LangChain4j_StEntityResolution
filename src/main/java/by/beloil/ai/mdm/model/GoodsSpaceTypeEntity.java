package by.beloil.ai.mdm.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entity representing wholesale goods space type classifications
 * stored in the {@code dbo.GoodsWholesale_AISpaceType} table.
 */
@Entity
@Table(name = "GoodsWholesale_AISpaceType", schema = "dbo", catalog = "dwbeloil")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GoodsSpaceTypeEntity {

    /**
     * Composite primary key identifying the wholesale goods record.
     */
    @EmbeddedId
    private GoodsSpaceTypePK id;

    /**
     * AI-assigned or standardized space type classification for the goods.
     */
    @Column(name = "SpaceType", length = 150)
    private String spaceType;

    /**
     * Timestamp of the original source record containing unnormalized units of measurement;
     * used to track and purge obsolete entries from this auxiliary table when corresponding
     * records are deleted from the source table.
     */
    @Column(name = "DateStart")
    private LocalDateTime dateStart;

}

