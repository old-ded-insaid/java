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

/**
 * Таблица app_settings — тема и акцентный цвет.
 * На пользователя ровно одна строка (в БД это UNIQUE(user_id)).
 */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class AppSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** Владелец настроек. LAZY — пользователь подгрузится только при обращении. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "theme_name", nullable = false)
    @Builder.Default
    @ToString.Include
    private String themeName = "gray_blue";

    @Column(name = "accent_color", nullable = false)
    @Builder.Default
    @ToString.Include
    private String accentColor = "#4F8EF7";

    /** Абсолютный путь к mp3/wav; null — системный сигнал. */
    @Column(name = "sound_path")
    private String soundPath;

    /** Базовый размер шрифта интерфейса, px. */
    @Column(name = "font_size", nullable = false)
    @Builder.Default
    private int fontSize = 13;

    /**
     * Длительность отдыха между подходами, секунды.
     *
     * Хранится в настройках, чтобы таймер отдыха не приходилось выставлять
     * заново на каждой тренировке. 45 = «00:45» по умолчанию (V15).
     */
    @Column(name = "rest_timer_seconds", nullable = false)
    @Builder.Default
    private int restTimerSeconds = DEFAULT_REST_TIMER_SECONDS;

    /** Значение по умолчанию: 45 секунд — ровно «00:45» на экране тренировки. */
    public static final int DEFAULT_REST_TIMER_SECONDS = 45;

    /** Разумные границы: 0 («без отдыха») .. 99:59. */
    public static final int MAX_REST_TIMER_SECONDS = 99 * 60 + 59;

    /**
     * Шаг стрелок таймера отдыха, секунды (V16).
     *
     * Хранится, чтобы выбранный шаг (1/5/10/15/30) не сбрасывался на 5
     * при каждом открытии тренировки.
     */
    @Column(name = "rest_step_seconds", nullable = false)
    @Builder.Default
    private int restStepSeconds = DEFAULT_REST_STEP_SECONDS;

    /** Шаг по умолчанию: 5 секунд — привычный «пятисекундный» интервал. */
    public static final int DEFAULT_REST_STEP_SECONDS = 5;

    /** Предел шага: больше пяти минут на клик — уже не удобно. */
    public static final int MAX_REST_STEP_SECONDS = 300;
}
