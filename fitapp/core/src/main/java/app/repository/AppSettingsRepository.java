package app.repository;

import app.model.AppSettings;

import java.util.Optional;

/** Доступ к таблице app_settings. */
public interface AppSettingsRepository extends CrudRepository<AppSettings, Long> {

    /** Настройки конкретного пользователя (в БД на пользователя одна строка). */
    Optional<AppSettings> findByUserId(long userId);
}
