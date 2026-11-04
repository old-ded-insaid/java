package app.service;

import app.db.Database;
import app.model.AppSettings;
import app.model.User;
import app.repository.Repositories;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Профиль и оформление: один пользователь + его настройки темы.
 *
 * Дополняет core минимально: экрану настроек нужны ЧТЕНИЕ и ЗАПИСЬ темы,
 * а напрямую в репозитории экран ходить не должен.
 */
public class ProfileService {

    /** Имя единственного пользователя домашнего приложения. */
    public static final String DEFAULT_USERNAME = "owner";

    private static final String DEFAULT_THEME = "gray_blue";
    private static final String DEFAULT_ACCENT = "#4F8EF7";

    private final Database database;

    public ProfileService(Database database) {
        this.database = database;
    }

    /**
     * Возвращает единственного пользователя, создавая его при первом запуске.
     * Модель данных поддерживает нескольких, приложение — одного.
     */
    public User ensureDefaultUser() {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            return repos.users().findAll().stream().findFirst().orElseGet(() -> {
                User user = repos.users().save(User.builder()
                        .username(DEFAULT_USERNAME)
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build());

                repos.appSettings().save(AppSettings.builder()
                        .user(user)
                        .themeName(DEFAULT_THEME)
                        .accentColor(DEFAULT_ACCENT)
                        .build());

                return user;
            });
        });
    }

    /** Настройки пользователя; если строки нет — создаём значения по умолчанию. */
    public AppSettings loadSettings(long userId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            return repos.appSettings().findByUserId(userId).orElseGet(() -> {
                User user = repos.users().findById(userId)
                        .orElseThrow(() -> new ValidationException("Пользователь не найден: " + userId));

                return repos.appSettings().save(AppSettings.builder()
                        .user(user)
                        .themeName(DEFAULT_THEME)
                        .accentColor(DEFAULT_ACCENT)
                        .build());
            });
        });
    }

    /** Сохраняет выбранную тему и акцентный цвет. */
    public AppSettings saveTheme(long userId, String themeName, String accentColor) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            AppSettings settings = repos.appSettings().findByUserId(userId)
                    .orElseThrow(() -> new ValidationException("Настройки не найдены для пользователя " + userId));

            settings.setThemeName(themeName);
            settings.setAccentColor(accentColor);
            return settings;
        });
    }

    /**
     * Сохраняет всё оформление разом: тема, акцент, размер шрифта, звук таймера.
     *
     * @param soundPath абсолютный путь к mp3/wav или null (системный сигнал)
     */
    public AppSettings saveAppearance(long userId,
                                      String themeName,
                                      String accentColor,
                                      int fontSize,
                                      String soundPath) {

        if (fontSize < 12 || fontSize > 24) {
            throw new ValidationException("Размер шрифта должен быть от 12 до 24, получено: " + fontSize);
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            AppSettings settings = repos.appSettings().findByUserId(userId)
                    .orElseThrow(() -> new ValidationException("Настройки не найдены для пользователя " + userId));

            settings.setThemeName(themeName);
            settings.setAccentColor(accentColor);
            settings.setFontSize(fontSize);
            settings.setSoundPath(soundPath == null || soundPath.isBlank() ? null : soundPath.trim());
            return settings;
        });
    }

    /**
     * Сохраняет длительность отдыха между подходами.
     *
     * Значение приходит со спиннеров экрана тренировки (минуты * 60 + секунды),
     * поэтому проверяем границы здесь, а не в UI: миграция V15 держит в БД
     * CHECK (rest_timer_seconds BETWEEN 0 AND 5999), и падать на нём некрасиво.
     *
     * @param seconds 0 («без отдыха») .. 99:59
     */
    public AppSettings updateRestTimer(long userId, int seconds) {
        if (seconds < 0 || seconds > AppSettings.MAX_REST_TIMER_SECONDS) {
            throw new ValidationException("Отдых должен быть от 0 до "
                    + AppSettings.MAX_REST_TIMER_SECONDS + " секунд, получено: " + seconds);
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            AppSettings settings = repos.appSettings().findByUserId(userId)
                    .orElseThrow(() -> new ValidationException(
                            "Настройки не найдены для пользователя " + userId));

            settings.setRestTimerSeconds(seconds);
            return settings;
        });
    }

    /**
     * Сохраняет шаг стрелок таймера отдыха (1/5/10/15/30 секунд).
     *
     * Проверяем границы здесь же: в БД стоит CHECK (rest_step_seconds
     * BETWEEN 1 AND 300), и падать на нём из UI некрасиво.
     */
    public AppSettings updateRestStep(long userId, int seconds) {
        if (seconds < 1 || seconds > AppSettings.MAX_REST_STEP_SECONDS) {
            throw new ValidationException("Шаг отдыха должен быть от 1 до "
                    + AppSettings.MAX_REST_STEP_SECONDS + " секунд, получено: " + seconds);
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            AppSettings settings = repos.appSettings().findByUserId(userId)
                    .orElseThrow(() -> new ValidationException(
                            "Настройки не найдены для пользователя " + userId));

            settings.setRestStepSeconds(seconds);
            return settings;
        });
    }
}
