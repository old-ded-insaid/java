package app.desktop;

import javafx.application.Application;

/**
 * Точка входа для IDE и Maven.
 *
 * Класс НЕ наследует Application — иначе при запуске из IDE JavaFX
 * пишет «JavaFX runtime components are missing» и просит настраивать VM options.
 * С таким «прокладкой» кнопка Run работает как есть.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }
}
