package app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Таблица measurements — измерения тела и подвижности.
 * kind: mobility | pulse_rest | body_weight | other
 *
 * value_num — число, value_unit — единица («см», «кг», «уд/мин»).
 * media_id — необязательная картинка (например фото наклона вперёд).
 */
@Entity
@Table(name = "measurements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Measurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "measured_at", nullable = false)
    @ToString.Include
    private LocalDateTime measuredAt;

    @Column(name = "kind", nullable = false)
    @ToString.Include
    private String kind;

    /** Название измерения: «наклон вперёд», «вес утром». */
    @Column(name = "name", nullable = false)
    @ToString.Include
    private String name;

    /** NUMERIC(8,2). */
    @Column(name = "value_num", precision = 8, scale = 2)
    private BigDecimal valueNum;

    @Column(name = "value_unit")
    private String valueUnit;

    @Column(name = "note")
    private String note;

    /** Фото/график измерения. Может быть null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id")
    @ToString.Exclude
    private MediaItem media;
}
