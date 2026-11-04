package app.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Таблица exercises — справочник упражнений.
 * setup = ИП, movement = Движение, muscles = Мышцы, avoid = Чего избегать.
 */
@Entity
@Table(name = "exercises")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Exercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    @ToString.Include
    private Long id;

    @Column(name = "name", nullable = false, unique = true)
    @ToString.Include
    private String name;

    /** Исходное положение. */
    @Column(name = "setup")
    private String setup;

    /** Описание движения. */
    @Column(name = "movement")
    private String movement;

    /** Какие мышцы работают. */
    @Column(name = "muscles")
    private String muscles;

    /** Чего избегать (противопоказания, ошибки). */
    @Column(name = "avoid")
    private String avoid;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Инвентарь упражнения.
     * Именно эта связь, а не @ManyToMany: у связи есть quantity («3 резинки»).
     */
    @OneToMany(mappedBy = "exercise", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    @Builder.Default
    @ToString.Exclude
    private List<ExerciseEquipment> equipmentLinks = new ArrayList<>();

    @PrePersist
    void fillCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
