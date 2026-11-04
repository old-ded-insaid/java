package app.desktop;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Идентификатор приложения для панели задач Windows (AppUserModelID).
 *
 * Зачем: Windows группирует окна и разрешает «Закрепить на панели задач»
 * только по AppUserModelID. Без него Java-приложение и его ярлык для системы
 * — разные программы: кнопка «Закрепить» либо неактивна, либо создаёт второй
 * ярлык (и светится два значка).
 *
 * Вызывается один раз при старте, до показа окна.
 */
public final class AppUserModelId {

    /**
     * Уникальный идентификатор приложения.
     *
     * Формат произвольный, главное — постоянство между запусками: именно
     * по этой строке Windows связывает ярлык, процесс и окно.
     */
    public static final String ID = "com.fitapp.desktop";

    private AppUserModelId() {
    }

    /**
     * Выставляет AppUserModelID текущему процессу.
     *
     * Не-Windows и отсутствие JNA — не повод не запускаться: метод молча
     * выходит, приложение работает как раньше (просто без «закрепить»).
     */
    public static void apply() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }

        try {
            int result = Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString(ID));
            if (result != 0) {
                // 0 = S_OK; любое другое значение — Windows отказала
                System.err.println("AppUserModelID не установлен, код " + result);
            }
        } catch (Throwable error) {
            System.err.println("AppUserModelID недоступен: " + error.getMessage());
        }
    }

    /**
     * Минимальное объявление нужной функции.
     *
     * Своя обёртка, потому что в jna-platform интерфейсе Shell32 этой функции нет.
     * Имя экспортируется без суффикса «A/W», поэтому грузим библиотеку без
     * W32APIOptions (иначе JNA искала бы «...ExplicitAppUserModelIDW»).
     */
    private interface Shell32 extends StdCallLibrary {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class);

        /** @return 0 (S_OK) при успехе; вызывается до создания окон */
        int SetCurrentProcessExplicitAppUserModelID(WString appId);
    }
}
