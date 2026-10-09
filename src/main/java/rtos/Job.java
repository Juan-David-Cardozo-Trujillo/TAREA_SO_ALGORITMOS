package rtos;

/**
 * Activación concreta de una {@link Task}. Es el elemento que viaja por las colas de los núcleos.
 *
 * <p>Los campos mutables se modifican únicamente desde el hilo del núcleo que ejecuta el trabajo
 * (durante la fase de tick) o desde el coordinador (entre ticks, con los núcleos detenidos en la barrera);
 * la barrera de {@link SimulationClock} garantiza la relación happens-before.</p>
 */
public final class Job {

    private final Task task;
    private final int jobNumber;
    private final long releaseTime;
    private final long absoluteDeadline;
    private int remaining;
    private long completionTime = -1;
    private int migrations;
    private boolean missed;
    private boolean aborted;

    /**
     * Crea una activación.
     *
     * @param task             tarea de origen.
     * @param jobNumber        número de activación (0, 1, 2…).
     * @param releaseTime      instante de liberación.
     * @param absoluteDeadline instante absoluto límite de finalización.
     */
    public Job(Task task, int jobNumber, long releaseTime, long absoluteDeadline) {
        this.task = task;
        this.jobNumber = jobNumber;
        this.releaseTime = releaseTime;
        this.absoluteDeadline = absoluteDeadline;
        this.remaining = task.getBurst();
    }

    /** Consume un tick de CPU del trabajo. */
    void executeOneTick() {
        if (remaining > 0) {
            remaining--;
        }
    }

    /** @return {@code true} si ya recibió toda su ráfaga. */
    public boolean isFinished() { return remaining == 0; }

    /** @return {@code true} si aún compite por CPU (no terminó ni fue abortado). */
    public boolean isActive() { return remaining > 0 && !aborted; }

    /** @return identificador legible, p. ej. {@code T1.2}. */
    public String getId() { return task.getName() + "." + jobNumber; }

    /** @return tarea de origen. */
    public Task getTask() { return task; }
    /** @return número de activación. */
    public int getJobNumber() { return jobNumber; }
    /** @return instante de liberación. */
    public long getReleaseTime() { return releaseTime; }
    /** @return deadline absoluto. */
    public long getAbsoluteDeadline() { return absoluteDeadline; }
    /** @return ticks de CPU pendientes. */
    public int getRemaining() { return remaining; }
    /** @return instante de finalización, o -1 si no terminó. */
    public long getCompletionTime() { return completionTime; }
    /** @param time instante en que el trabajo terminó. */
    void setCompletionTime(long time) { this.completionTime = time; }
    /** @return cuántas veces ha sido movido entre colas. */
    public int getMigrations() { return migrations; }
    /** Registra una migración entre colas. */
    void incrementMigrations() { migrations++; }
    /** @return {@code true} si incumplió su deadline. */
    public boolean isMissed() { return missed; }
    /** Marca el incumplimiento de deadline. */
    void markMissed() { missed = true; }
    /** @return {@code true} si fue abortado (modo STRICT tras incumplir). */
    public boolean isAborted() { return aborted; }
    /** Aborta el trabajo. */
    void abort() { aborted = true; }

    @Override
    public String toString() {
        return String.format("%s(rem=%d, d=%d)", getId(), remaining, absoluteDeadline);
    }
}
