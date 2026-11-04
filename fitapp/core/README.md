# fitapp — core (этап 2)

Модуль `core` — «мозг» приложения без единой строчки JavaFX.

## Что внутри

```
app.model       — 17 JPA-сущностей (по одной на таблицу из V1__init.sql)
app.repository  — интерфейсы репозиториев + реализация на EntityManager
app.service     — бизнес-логика: тренировки, таймер, расписание, аудит, экспорт
app.db          — Database (транзакции) и DatabaseConfig (Flyway + Hibernate)
db/migration    — V1__init.sql, схема БД
```

## Требования

- JDK 17 (LTS)
- Maven 3.9+
- PostgreSQL 18, база `fitapp`

## Подготовка БД (один раз)

```sql
CREATE DATABASE fitapp;
```

Остальное сделает Flyway при первом запуске: таблицы создадутся из `V1__init.sql`.

## Пароль

`application.properties` содержит `db.password=postgres` — замените на свой.
Либо не храните пароль в файле, а задайте переменную окружения:

```powershell
$env:FITAPP_DB_PASSWORD = "ваш_пароль"
```

## Сборка

```powershell
cd fitapp
mvn -q clean package
```

## Запуск миграций вручную (необязательно)

```powershell
mvn -q -pl core compile
```

`DatabaseConfig.start()` вызывает Flyway сам — отдельный запуск не нужен.

## Как этим пользуется desktop (этап 3)

```java
DatabaseConfig config = DatabaseConfig.load();
Database database = config.start();

WorkoutService workouts = new WorkoutService(database);
Workout workout = workouts.createWorkout(1L, null, LocalDate.now(), "Вечерняя");
```

Правило слоёв: экран → сервис → репозиторий → БД. Экран SQL не знает.
