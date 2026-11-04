package app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * Таблица app_error_log — технические ошибки приложения.
 * Без FK на пользователя: ошибка может случиться до входа в приложение.
 * level: TRACE | DEBUG | INFO | WARN | ERROR | FATAL
 */
@Entity
@Table(name = "app_error_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class AppErrorLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "level", nullable = false)
    @ToString.Include
    private String level;

    @Column(name = "message", nullable = false)
    @ToString.Include
    private String message;

    /** Стек-трейс в виде текста. */
    @Column(name = "stack")
    private String stack;

    /** Контекст: какой экран, какое действие. */
    @Column(name = "context")
    private String context;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void fillCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
