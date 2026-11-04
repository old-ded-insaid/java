package app.desktop.ui;

/**
 * Строка списка инвентаря в редакторе шаблона.
 *
 * @param equipmentId id инвентаря (для отвязки)
 * @param text        подпись: «Гантель 8 кг» или «Резинка черная (2 шт)»
 * @param quantity    количество — нужно, когда инвентарь ещё не сохранён
 *                    (новое упражнение) и прикрепляется только при создании
 */
public record EquipmentRow(long equipmentId, String text, int quantity) {

    @Override
    public String toString() {
        return text;
    }
}
