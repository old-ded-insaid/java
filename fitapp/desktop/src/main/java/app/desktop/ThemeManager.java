package app.desktop;

import app.desktop.ui.Alerts;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

import java.net.URL;
import java.util.List;
import java.util.Objects;

/**
 * Темы оформления.
 *
 * Как это работает:
 *  1. base.css — общая раскладка (отступы, радиусы, шрифты), цвета в нём — переменные.
 *  2. theme_*.css — только значения переменных (-fx-bg, -fx-text, -fx-accent...).
 *  3. Акцентный цвет из настроек применяется inline-стилем к корню сцены
 *     и перекрывает -fx-accent из CSS-файла темы.
 */
public class ThemeManager {

    /** Один вариант темы: id в БД, подпись, путь к CSS и акцент по умолчанию. */
    public record ThemeOption(String id, String title, String cssPath, String defaultAccent) {

        @Override
        public String toString() {
            return title;
        }
    }

    private static final String BASE_CSS = "/app/desktop/css/base.css";

    /** Размер шрифта по умолчанию, если в настройках ничего нет. */
    public static final int DEFAULT_FONT_SIZE = 13;

    /** Границы размера шрифта: те же, что в настройках и в БД (V4). */
    public static final int MIN_FONT_SIZE = 12;
    public static final int MAX_FONT_SIZE = 24;

    private static final List<ThemeOption> OPTIONS = List.of(
            new ThemeOption("gray_blue", "Серо-синяя",
                    "/app/desktop/css/theme_gray_blue.css", "#4F8EF7"),
            new ThemeOption("turquoise", "Бирюзовая",
                    "/app/desktop/css/theme_turquoise.css", "#2FD3B5"),
            new ThemeOption("purple", "Фиолетовая",
                    "/app/desktop/css/theme_purple.css", "#A97BFF"));

    private Scene scene;

    public void attach(Scene scene) {
        this.scene = scene;
    }

    public List<ThemeOption> options() {
        return OPTIONS;
    }

    /** Неизвестный id (например, из старой БД) безопасно превращается в серо-синюю. */
    public ThemeOption find(String themeId) {
        return OPTIONS.stream()
                .filter(option -> option.id().equals(themeId))
                .findFirst()
                .orElse(OPTIONS.get(0));
    }

    public void apply(String themeId, String accentColor) {
        apply(themeId, accentColor, DEFAULT_FONT_SIZE);
    }

    /**
     * Применяет тему, акцент и базовый размер шрифта.
     * Размер задаётся inline на корне сцены — все наследуемые размеры
     * (13px, 14px и т.д. в CSS) масштабируются относительно него.
     */
    public void apply(String themeId, String accentColor, int fontSize) {
        if (scene == null) {
            return;
        }

        ThemeOption theme = find(themeId);

        scene.getStylesheets().setAll(
                url(BASE_CSS),
                url(theme.cssPath()));

        // акцентный цвет пользователя важнее значения по умолчанию из темы
        String accent = (accentColor == null || accentColor.isBlank())
                ? theme.defaultAccent()
                : accentColor;

        int size = Math.max(12, Math.min(24, fontSize <= 0 ? DEFAULT_FONT_SIZE : fontSize));

        scene.getRoot().setStyle("-fx-accent: " + accent + "; -fx-font-size: " + size + "px;");

        // диалоги (Alert) живут в отдельной сцене — отдаём им те же стили
        Alerts.setStylesheets(scene.getStylesheets());
    }

    /** #4F8EF7 из ColorPicker. */
    public static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    /** Обратное преобразование; при ошибке — акцент по умолчанию. */
    public static Color fromHex(String hex) {
        try {
            return Color.web(hex);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Color.web("#4F8EF7");
        }
    }

    private static String url(String path) {
        URL resource = ThemeManager.class.getResource(path);
        return Objects.requireNonNull(resource, "Не найден CSS: " + path).toExternalForm();
    }
}
