package app.desktop.ui;

import javafx.scene.control.TextFormatter;

/**
 * Маска времени hh:mm:ss и перевод в секунды.
 *
 * Как работает маска: пользователь вводит только цифры, двоеточия
 * расставляются автоматически ("013005" -> "01:30:05").
 * Больше 6 цифр (99:59:59) не принимаем.
 */
public final class TimeMask {

    public static final String ZERO = "00:00:00";

    private TimeMask() {
    }

    public static TextFormatter<String> formatter() {
        return new TextFormatter<>(change -> {
            if (!change.isContentChange()) {
                return change;
            }

            // всё, что не цифра, выбрасываем
            String digits = change.getControlNewText().replaceAll("[^0-9]", "");
            if (digits.length() > 6) {
                return null; // отклонить изменение
            }

            // переписываем поле целиком в формате hh:mm:ss
            change.setRange(0, change.getControlText().length());
            change.setText(group(digits));
            return change;
        });
    }

    /** 3725 -> "01:02:05" */
    public static String format(int totalSeconds) {
        int safe = Math.max(0, totalSeconds);
        int hours = safe / 3600;
        int minutes = (safe % 3600) / 60;
        int seconds = safe % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /** "01:02:05" -> 3725. Мусор и пустая строка дают 0. */
    public static int toSeconds(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        String[] parts = text.trim().split(":");
        try {
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Integer.parseInt(parts[2]);
            }
            if (parts.length == 2) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String group(String digits) {
        if (digits.length() <= 2) {
            return digits;
        }
        if (digits.length() <= 4) {
            return digits.substring(0, 2) + ":" + digits.substring(2);
        }
        return digits.substring(0, 2) + ":" + digits.substring(2, 4) + ":" + digits.substring(4);
    }
}
