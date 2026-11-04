package app.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Таблица workouts — одна фактическая тренировка (история).
 *
 * plan == null: тренировка была «свободной» или шаблон удалили
 * (в БД это ON DELETE SET NULL — история важнее шаблона).
 */
@Entity
@Table(name = "workouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Workout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    /** Шаблон, по которому тренировались. Может быть null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    @ToString.Exclude
    private WorkoutPlan plan;

    @Column(name = "workout_date", nullable = false)
    @ToString.Include
    private LocalDate workoutDate;

    @Column(name = "name")
    @ToString.Include
    private String name;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** Длительность в секундах: считается при завершении тренировки. */
    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "note")
    private String note;

    @Column(name = "avg_pulse")
    private Integer avgPulse;

    @Column(name = "total_calories")
    private Integer totalCalories;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Черновик: тренировка создана кнопкой «Новая тренировка» и ещё не завершена.
     * Только такие записи может удалить автоочистка (BUG-01).
     */
    @Column(name = "draft", nullable = false)
    @Builder.Default
    private boolean draft = false;

    /** Упражнения тренировки по порядку. */
    @OneToMany(mappedBy = "workout", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @BatchSize(size = 50)
    @Builder.Default
    @ToString.Exclude
    private List<WorkoutExercise> exercises = new ArrayList<>();

    @PrePersist
    void fillCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
