package app.db;

import app.model.AppErrorLogEntry;
import app.model.AppSettings;
import app.model.AuditEntry;
import app.model.Comment;
import app.model.Equipment;
import app.model.Exercise;
import app.model.ExerciseEquipment;
import app.model.Measurement;
import app.model.MediaItem;
import app.model.PlanExercise;
import app.model.Schedule;
import app.model.ScheduleItem;
import app.model.User;
import app.model.Workout;
import app.model.WorkoutExercise;
import app.model.WorkoutPlan;
import app.model.WorkoutSet;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Настройка подключения: читает application.properties, гоняет миграции Flyway
 * и создаёт EntityManagerFactory.
 *
 * Использование в desktop-модуле:
 * <pre>
 *   DatabaseConfig config = DatabaseConfig.load();
 *   Database database = config.start();     // миграции + подключение
 *   ...
 *   database.close();                       // при выходе из приложения
 * </pre>
 */
public final class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private static final String BUILTIN_PROPERTIES = "application.properties";

    /** ${LOCALAPPDATA}, ${FITAPP_DB_PASSWORD} и т.п. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)}");

    /**
     * Все сущности перечислены явно: никакого сканирования classpath,
     * никакой магии — новую таблицу забыть невозможно, компилятор подскажет.
     */
    private static final List<Class<?>> ENTITY_CLASSES = List.of(
            User.class,
            AppSettings.class,
            Equipment.class,
            Exercise.class,
            ExerciseEquipment.class,
            WorkoutPlan.class,
            PlanExercise.class,
            Schedule.class,
            ScheduleItem.class,
            Workout.class,
            WorkoutExercise.class,
            WorkoutSet.class,
            MediaItem.class,
            Comment.class,
            Measurement.class,
            AuditEntry.class,
            AppErrorLogEntry.class
    );

    private final Properties properties;

    private DatabaseConfig(Properties properties) {
        this.properties = properties;
    }

    /** Настройки из classpath (встроенный application.properties). */
    public static DatabaseConfig load() {
        return load(externalFile());
    }

    /**
     * @param externalFile необязательный внешний файл (рядом с .exe), перекрывает встроенный
     */
    public static DatabaseConfig load(Path externalFile) {
        Properties props = new Properties();
        readBuiltin(props);
        readExternal(props, externalFile);

        // пароль из переменной окружения важнее файла: так его можно не хранить на диске
        String envPassword = System.getenv("FITAPP_DB_PASSWORD");
        if (envPassword != null && !envPassword.isBlank()) {
            props.setProperty("db.password", envPassword);
        }

        resolvePlaceholders(props);
        return new DatabaseConfig(props);
    }

    /** Миграции + EntityManagerFactory. */
    public Database start() {
        String url = required("db.url");
        String username = required("db.username");
        String password = properties.getProperty("db.password", "");

        migrate(url, username, password);
        var entityManagerFactory = createEntityManagerFactory(url, username, password);

        log.info("База готова: {}", url);
        return new Database(entityManagerFactory);
    }

    /** Значение настройки с значением по умолчанию. */
    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /** Путь из настроек с уже подставленными переменными окружения. */
    public Path path(String key) {
        return Path.of(required(key));
    }

    // ------------------------------------------------------------------ Flyway

    private void migrate(String url, String username, String password) {
        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations(get("flyway.locations", "classpath:db/migration"))
                .baselineOnMigrate(Boolean.parseBoolean(get("flyway.baselineOnMigrate", "true")))
                // clean отключён навсегда: он удаляет все таблицы
                .cleanDisabled(true)
                .load();

        var result = flyway.migrate();
        log.info("Flyway: применено миграций — {}", result.migrationsExecuted);
    }

    // --------------------------------------------------------------- Hibernate

    private SessionFactory createEntityManagerFactory(String url, String username, String password) {
        StandardServiceRegistryBuilder registryBuilder = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", get("db.driver", "org.postgresql.Driver"))
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", username)
                .applySetting("hibernate.connection.password", password)
                .applySetting("hibernate.dialect", get("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"))
                // схему создаёт Flyway — Hibernate таблицами не управляет
                .applySetting("hibernate.hbm2ddl.auto", get("hibernate.hbm2ddl.auto", "none"))
                .applySetting("hibernate.show_sql", get("hibernate.show_sql", "false"))
                .applySetting("hibernate.format_sql", get("hibernate.format_sql", "true"))
                .applySetting("hibernate.jdbc.batch_size", get("hibernate.jdbc.batch_size", "30"))
                .applySetting("hibernate.order_inserts", "true")
                .applySetting("hibernate.order_updates", "true")
                .applySetting("hibernate.jdbc.time_zone", get("hibernate.jdbc.time_zone", "UTC"));

        StandardServiceRegistry registry = registryBuilder.build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            ENTITY_CLASSES.forEach(sources::addAnnotatedClass);

            // SessionFactory в Hibernate 6 является EntityManagerFactory
            return sources.buildMetadata().buildSessionFactory();
        } catch (RuntimeException e) {
            // без этого Hibernate оставит висеть потоки/ресурсы
            StandardServiceRegistryBuilder.destroy(registry);
            throw new IllegalStateException("Не удалось создать EntityManagerFactory. "
                    + "Проверьте, что PostgreSQL запущен и база fitapp существует.", e);
        }
    }

    // -------------------------------------------------------------- properties

    private static void readBuiltin(Properties props) {
        try (InputStream in = DatabaseConfig.class.getClassLoader()
                .getResourceAsStream(BUILTIN_PROPERTIES)) {
            if (in == null) {
                throw new IllegalStateException("Не найден " + BUILTIN_PROPERTIES
                        + " в classpath (должен лежать в core/src/main/resources)");
            }
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + BUILTIN_PROPERTIES, e);
        }
    }

    private static void readExternal(Properties props, Path externalFile) {
        if (externalFile == null || !Files.isReadable(externalFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(externalFile, StandardCharsets.UTF_8)) {
            props.load(reader);
            log.info("Загружены внешние настройки: {}", externalFile.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + externalFile, e);
        }
    }

    /** -Dfitapp.config=C:\fitapp\local.properties или аргумент приложения. */
    private static Path externalFile() {
        String path = System.getProperty("fitapp.config");
        if (path != null && !path.isBlank()) {
            return Path.of(path);
        }
        return null;
    }

    private static void resolvePlaceholders(Properties props) {
        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name);
            if (value != null && value.contains("${")) {
                props.setProperty(name, resolve(value));
            }
        }
    }

    private static String resolve(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = System.getenv(key);
            if (replacement == null) {
                replacement = System.getProperty(key);
            }
            if (replacement == null) {
                replacement = matcher.group(0); // оставляем ${...} как есть
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String required(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("В application.properties не задан ключ " + key);
        }
        return value;
    }
}
