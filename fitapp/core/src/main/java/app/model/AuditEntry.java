package app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Таблица audit_log — журнал изменений («кто, что, когда, было/стало»).
 *
 * old_value и new_value — JSON-строки. Сравнение делает AuditService:
 * если JSON одинаковый, запись не создаётся.
 *
 * user может быть null: в БД это ON DELETE SET NULL, чтобы журнал не исчезал
 * вместе с пользователем.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @ToString.Exclude
    private User user;

    /** Имя таблицы/сущности: workouts, exercises, sets... */
    @Column(name = "entity_type", nullable = false)
    @ToString.Include
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    @ToString.Include
    private long entityId;

    /** INSERT | UPDATE | DELETE. */
    @Column(name = "action", nullable = false)
    @ToString.Include
    private String action;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void fillCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
