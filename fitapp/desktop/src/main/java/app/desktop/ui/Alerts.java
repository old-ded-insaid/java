package app.desktop.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.util.List;

/**
 * Единый вид диалогов: одинаковый заголовок, тема и кнопки.
 * Диалоги в JavaFX — отдельная сцена, поэтому стили передаём вручную
 * (ThemeManager.apply вызывает setStylesheets).
 */
public final class Alerts {

    private static List<String> stylesheets = List.of();

    private Alerts() {
    }

    public static void setStylesheets(List<String> css) {
        stylesheets = List.copyOf(css);
    }

    public static void info(String title, String message) {
        prepare(new Alert(Alert.AlertType.INFORMATION), title, message).showAndWait();
    }

    public static void warn(String title, String message) {
        prepare(new Alert(Alert.AlertType.WARNING), title, message).showAndWait();
    }

    public static void error(String title, String message) {
        prepare(new Alert(Alert.AlertType.ERROR), title, message).showAndWait();
    }

    /** true — пользователь нажал OK, false — Cancel/закрыл окно. */
    public static boolean confirm(String title, String message) {
        Alert alert = prepare(new Alert(Alert.AlertType.CONFIRMATION), title, message);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private static Alert prepare(Alert alert, String title, String message) {
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().addAll(stylesheets);
        return alert;
    }
}
