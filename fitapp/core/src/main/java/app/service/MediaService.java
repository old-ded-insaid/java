package app.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Файлы картинок на диске.
 *
 * В БД хранится только ОТНОСИТЕЛЬНЫЙ путь от корня медиа-папки
 * (app.media.dir из application.properties), например:
 *   measurements/2026-11/2f1c...-a9.png
 *
 * Почему не в папке проекта: `mvn clean` и jpackage её не сохраняют,
 * а пользовательские фото должны переживать пересборку.
 */
public class MediaService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg");

    private final Path root;

    public MediaService(Path mediaRoot) {
        this.root = mediaRoot.toAbsolutePath();
    }

    /** Корень медиа-папки (для сообщений об ошибках). */
    public Path root() {
        return root;
    }

    /**
     * Копирует выбранный файл в media/measurements/ГГГГ-ММ/&lt;uuid&gt;.ext.
     *
     * @return путь относительно корня — его и пишем в media.file_path
     */
    public String importImage(Path source) {
        if (source == null) {
            throw new ValidationException("Файл не выбран");
        }
        if (!Files.isReadable(source)) {
            throw new ValidationException("Файл недоступен: " + source);
        }

        String extension = extensionOf(source.getFileName().toString());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ValidationException("Поддерживаются только PNG и JPG, получено: " + extension);
        }

        String relativePath = "measurements/" + YearMonth.now()
                + "/" + UUID.randomUUID() + extension;
        Path target = root.resolve(relativePath);

        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ValidationException("Не удалось скопировать картинку: " + e.getMessage());
        }

        return relativePath;
    }

    /** Относительный путь -> абсолютный. */
    public Path resolve(String relativePath) {
        return root.resolve(relativePath);
    }

    /** Удаляет файл, если он есть; ошибки не пробрасывает (мусор не должен ломать экран). */
    public void deleteQuietly(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(root.resolve(relativePath));
        } catch (IOException ignored) {
            // файл занят или уже удалён — не критично
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
