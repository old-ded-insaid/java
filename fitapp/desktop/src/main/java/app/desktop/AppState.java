package app.desktop;

/**
 * Короткоживущее состояние между экранами.
 *
 * Например, главное меню создаёт тренировку и передаёт её id экрану тренировки.
 * Значение живёт только в памяти и сбрасывается при возврате в меню.
 */
public final class AppState {

    private Long workoutId;

    /** Тренировка — свежий черновик (создана кнопкой «Новая тренировка»), BUG-01. */
    private boolean draft;

    public Long workoutId() {
        return workoutId;
    }

    public void setWorkoutId(Long workoutId) {
        this.workoutId = workoutId;
    }

    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public void clearWorkout() {
        this.workoutId = null;
        this.draft = false;
    }
}
