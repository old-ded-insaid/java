# fitapp — домашние тренировки

Локальное desktop-приложение для ведения дневника тренировок и замеров.
Один пользователь, без сервера: данные лежат в PostgreSQL на этом же компьютере.

## Стек

| Слой | Технологии |
|---|---|
| Язык | Java 17 (LTS) |
| Сборка | Maven, многомодульный проект (`core`, `desktop`) |
| Интерфейс | JavaFX 21 + FXML + CSS (тема, масштабируемый шрифт) |
| База | PostgreSQL 18 |
| Схема | Flyway (все изменения — миграциями `V1..V16`) |
| ORM | Hibernate 6 / JPA (`hbm2ddl=none`) |
| JSON / Excel | Jackson, Apache POI |
| Тесты | JUnit 5 + Mockito |
| Логи | slf4j + logback |

## Возможности

- справочник упражнений (ИП, движение, мышцы, ограничения) и инвентарь;
- шаблоны тренировок: состав, порядок, целевые подходы/повторы/вес;
- проведение тренировки: главный таймер, таймер отдыха (сохраняемый шаг и длительность),
  запись подходов, пропуск/отмена пропуска, инвентарь на тренировку (снимок);
- история тренировок, удаление ошибочных записей;
- экспорт истории в Excel и полный бэкап/восстановление базы в JSON;
- экспорт и импорт отдельной тренировки в JSON;
- замеры (вес, пульс покоя, подвижность) с фото и графиком динамики;
- расписание тренировок (цикл шаблонов с шагом в днях);
- настройки: тема, акцент, размер шрифта (в т.ч. горячие клавиши Ctrl + / Ctrl -), звук таймера.

## Структура

```
fitapp/
├── pom.xml              # родительский POM
├── core/                # модели, репозитории, сервисы, миграции БД
│   └── src/main/java/app/
│       ├── model/       # сущности JPA
│       ├── repository/  # JPQL-запросы
│       ├── service/     # бизнес-правила, транзакции
│       ├── db/          # Database, DatabaseConfig (Flyway + Hibernate)
│       └── dto/         # DTO для импорта/экспорта
├── desktop/             # JavaFX: контроллеры, FXML, CSS
│   └── src/main/
│       ├── java/app/desktop/
│       └── resources/app/desktop/{fxml,css}
├── run.bat              # запуск (сам находит JDK и Maven)
├── launch_hidden.vbs    # запуск без чёрного окна консоли
└── create_shortcut.ps1  # ярлык на рабочем столе
```

## Требования

- JDK 17 (например, Eclipse Adoptium);
- Maven 3.9+ (можно из IntelliJ IDEA);
- PostgreSQL 18, база `fitapp`.

## Настройка базы

Параметры подключения — `core/src/main/resources/application.properties`:

```properties
db.url=jdbc:postgresql://localhost:5432/fitapp?ApplicationName=fitapp
db.username=postgres
db.password=postgres
```

Пароль можно не хранить в файле, а передавать через переменную окружения
`FITAPP_DB_PASSWORD` (в конфиге поддерживаются `${...}`-подстановки).

Создать базу:

```sql
CREATE DATABASE fitapp;
```

Схема и данные инвентаря создаются автоматически при первом запуске (Flyway).

## Запуск

```bat
run.bat        :: сборка + запуск
run.bat fast   :: без mvn install (быстрее, если менялся только desktop)
```

Или напрямую Maven:

```bat
mvn -pl desktop javafx:run -Djavafx.main.class=app.desktop.Launcher
```

Данные приложения (картинки замеров, логи) — `%LOCALAPPDATA%\fitapp`.

## Тесты

```bat
mvn test
```

## Замечания

- Пароль `postgres/postgres` в конфиге — локальная настройка разработчика.
  Для публичного использования вынесите его в `FITAPP_DB_PASSWORD`.
- `run.bat` ищет JDK в `PATH`, `JAVA_HOME`, `~/.jdks` и `Program Files`,
  поэтому на другой машине путь к JDK править не нужно.
