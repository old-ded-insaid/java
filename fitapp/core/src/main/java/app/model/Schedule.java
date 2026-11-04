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
import java.util.ArrayList;
import java.util.List;

/**
 * Таблица schedule — расписание тренировок.
 *
 * start_date     — с какого дня цикл начался
 * interval_days  — через сколько дней следующая тренировка (1 = каждый день)
 * items          — очередь планов: 1 = A, 2 = B, 3 = C, 4 = D
 *
 * Логику «что сегодня» считает ScheduleService, сущность только хранит данные.
 */
@Entity
@Table(name = "schedule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "name", nullable = false)
    @ToString.Include
    private String name;

    @Column(name = "start_date", nullable = false)
    @ToString.Include
    private LocalDate startDate;

    @Column(name = "interval_days", nullable = false)
    @ToString.Include
    private int intervalDays;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * С какого шаблона начинается ротация.
     * null — с первого шаблона правила (position = 1).
     * В БД FK на workout_plans с ON DELETE SET NULL.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "start_plan_id")
    @ToString.Exclude
    private WorkoutPlan startPlan;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    @Builder.Default
    @ToString.Exclude
    private List<ScheduleItem> items = new ArrayList<>();
}
