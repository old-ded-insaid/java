package app.desktop;

/**
 * Экран, которому нужно освободить ресурсы при уходе с него (BUG-17):
 * остановить Timeline, звук, таймеры.
 *
 * Navigator вызывает {@link #dispose()} у предыдущего контроллера перед
 * загрузкой следующего экрана.
 */
public interface Disposable {

    void dispose();
}
