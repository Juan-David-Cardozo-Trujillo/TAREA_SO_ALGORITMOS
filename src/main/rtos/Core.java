package rtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Núcleo simulado (N_i). Es un {@link Runnable} que corre en su propio hilo y mantiene
 * su <b>propia cola de listos</b> ordenada por prioridad.
 *
 * <p>Cada tick ejecuta como máximo 1 unidad de trabajo del {@link Job} más prioritario
 * (esto implementa la <i>expropiación</i>: si llega un trabajo más prioritario, desplaza al actual en el
 * siguiente tick). Todo acceso al estado compartido se protege con un {@link ReentrantLock}.</p>
 */
public final class Core implements Runnable {

    private final int id;
    private final double maxUtilization;
    private final RTOSMode mode;
    private final ReentrantLock lock = new ReentrantLock();

    // ---- estado protegido por 'lock'
    private final PriorityQueue<Job> readyQueue;
    private final Comparator<Job> comparator;
    private final List<Task> assignedTasks = new ArrayList<>();
    private final StringBuilder timeline = new StringBuilder();
    private long busyTicks;
    private long idleTicks;
    private int completedJobs;
    private int missedDeadlines;
    private int migrationsIn;

    // ---- comunicación con el coordinador
    private SimulationClock clock;
    private volatile TickReport lastReport;
    private volatile Throwable failure;

    /**
     * @param id             identificador del núcleo (1..N).
     * @param comparator     orden de prioridad de los trabajos (ver {@link Scheduler#jobComparator()}).
     * @param maxUtilization porcentaje máximo de uso permitido en el núcleo, en (0, 1].
     * @param mode           modo del RTOS (afecta el tratamiento de los deadlines incumplidos).
     * @throws IllegalArgumentException si {@code maxUtilization} está fuera de (0, 1].
     */
    public Core(int id, Comparator<Job> comparator, double maxUtilization, RTOSMode mode) {
        if (maxUtilization <= 0.0 || maxUtilization > 1.0) {
            throw new IllegalArgumentException("maxUtilization debe estar en (0,1]");
        }
        this.id = id;
        this.comparator = comparator;
        this.maxUtilization = maxUtilization;
        this.mode = mode;
        this.readyQueue = new PriorityQueue<>(comparator);
    }

    /**
     * Asocia el reloj de simulación que sincroniza a este núcleo.
     *
     * @param clock reloj compartido.
     */
    void bind(SimulationClock clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------ gestión de tareas

    /**
     * Registra una tarea como asignada a este núcleo (la admisión ya fue validada por {@link Scheduler}).
     *
     * @param task tarea asignada.
     */
    public void addTask(Task task) {
        lock.lock();
        try {
            assignedTasks.add(task);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Utilización actual del núcleo: Σ P_i / T_i de las tareas periódicas asignadas
     * (es el "% de uso del núcleo" de la pizarra; 1.0 = 100 %).
     *
     * @return utilización en [0, ∞).
     */
    public double utilization() {
        lock.lock();
        try {
            return assignedTasks.stream().mapToDouble(Task::utilization).sum();
        } finally {
            lock.unlock();
        }
    }

    /** @return número de tareas periódicas asignadas. */
    public int periodicCount() {
        lock.lock();
        try {
            return (int) assignedTasks.stream().filter(t -> t.getType() == TaskType.PERIODIC).count();
        } finally {
            lock.unlock();
        }
    }

    /** @return número total de tareas asignadas (periódicas y aperiódicas). */
    public int taskCount() {
        lock.lock();
        try {
            return assignedTasks.size();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ cola de listos

    /**
     * Inserta un trabajo recién liberado en la cola de listos.
     *
     * @param job trabajo liberado.
     */
    public void release(Job job) {
        lock.lock();
        try {
            readyQueue.add(job);
        } finally {
            lock.unlock();
        }
    }

    /** @return longitud actual de la cola de listos (incluye al trabajo en ejecución). */
    public int queueSize() {
        lock.lock();
        try {
            return readyQueue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Extrae para migración el trabajo <i>en espera</i> de menor prioridad que aún no haya agotado su
     * límite de movimientos. Nunca extrae el trabajo de la cabeza (el que se ejecutaría en este núcleo).
     *
     * @param maxMigrations límite de movimiento por trabajo.
     * @return el trabajo extraído, o {@code null} si no hay candidato.
     */
    public Job pollMigratable(int maxMigrations) {
        lock.lock();
        try {
            if (readyQueue.size() < 2) {
                return null;
            }
            List<Job> ordered = new ArrayList<>(readyQueue);
            ordered.sort(comparator); // ordered[0] es la cabeza: no se toca
            for (int i = ordered.size() - 1; i >= 1; i--) {
                Job candidate = ordered.get(i);
                if (candidate.getMigrations() < maxMigrations) {
                    readyQueue.remove(candidate);
                    return candidate;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Recibe un trabajo migrado desde otra cola, contabilizando el movimiento.
     *
     * @param job trabajo migrado.
     */
    public void receiveMigrated(Job job) {
        lock.lock();
        try {
            job.incrementMigrations();
            migrationsIn++;
            readyQueue.add(job);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ ejecución

    /**
     * Ejecuta un tick de CPU: avanza el trabajo más prioritario y detecta incumplimientos de deadline.
     *
     * <p>Convención temporal: el tick {@code t} cubre el intervalo [t, t+1). Un trabajo que termina
     * en ese tick tiene tiempo de finalización t+1 y cumple si t+1 ≤ deadline.</p>
     *
     * @param tick tick simulado.
     * @return informe de lo ocurrido en el tick.
     */
    TickReport executeTick(int tick) {
        lock.lock();
        try {
            String running = null;
            boolean completed = false;
            String cell = ".";

            // 1) Despacho: la cabeza de la cola es, por construcción, el trabajo más prioritario.
            Job current = readyQueue.peek();
            if (current != null) {
                running = current.getId();
                cell = current.getTask().getName();
                current.executeOneTick();
                busyTicks++;
                if (current.isFinished()) {
                    readyQueue.poll();
                    current.setCompletionTime(tick + 1L);
                    completedJobs++;
                    completed = true;
                }
            } else {
                idleTicks++;
            }

            // 2) Verificación de deadlines: al llegar al instante 'now', todo trabajo pendiente cuyo
            //    deadline absoluto ya venció (<= now) lo ha incumplido.
            final long now = tick + 1L;
            List<String> missed = new ArrayList<>();
            for (Job j : new ArrayList<>(readyQueue)) {
                if (!j.isMissed() && j.getAbsoluteDeadline() <= now) {
                    j.markMissed();
                    missedDeadlines++;
                    if (mode == RTOSMode.STRICT) {
                        // Tiempo real duro: un resultado tardío carece de valor → se aborta.
                        readyQueue.remove(j);
                        j.abort();
                        missed.add(j.getId() + " (abortado)");
                    } else {
                        missed.add(j.getId() + " (tardío)");
                    }
                }
            }

            timeline.append(String.format("%-4s", cell));
            return new TickReport(id, tick, running, completed, missed, readyQueue.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Bucle del hilo del núcleo: espera el inicio de tick, ejecuta, y notifica el fin del tick,
     * hasta que el reloj emite la señal de parada. Cualquier excepción inesperada aborta la simulación.
     */
    @Override
    public void run() {
        try {
            while (true) {
                int tick = clock.awaitTickStart();
                if (tick < 0) {
                    break; // reloj detenido
                }
                lastReport = executeTick(tick);
                clock.signalTickEnd();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException e) {
            // otra parte abortó la simulación; terminar silenciosamente
        } catch (RuntimeException e) {
            failure = e;
            clock.abort();
        }
    }

    // ------------------------------------------------------------------ consulta de resultados

    /** @return identificador del núcleo. */
    public int getId() { return id; }
    /** @return porcentaje máximo de uso permitido (1.0 = 100 %). */
    public double getMaxUtilization() { return maxUtilization; }
    /** @return último informe de tick (visible tras la barrera de fin). */
    public TickReport getLastReport() { return lastReport; }
    /** @return la excepción que mató al hilo, o {@code null}. */
    public Throwable getFailure() { return failure; }

    /** @return ticks en los que el núcleo ejecutó trabajo. */
    public long getBusyTicks() { lock.lock(); try { return busyTicks; } finally { lock.unlock(); } }
    /** @return ticks ociosos. */
    public long getIdleTicks() { lock.lock(); try { return idleTicks; } finally { lock.unlock(); } }
    /** @return trabajos terminados. */
    public int getCompletedJobs() { lock.lock(); try { return completedJobs; } finally { lock.unlock(); } }
    /** @return deadlines incumplidos. */
    public int getMissedDeadlines() { lock.lock(); try { return missedDeadlines; } finally { lock.unlock(); } }
    /** @return trabajos recibidos por migración. */
    public int getMigrationsIn() { lock.lock(); try { return migrationsIn; } finally { lock.unlock(); } }
    /** @return línea de tiempo (diagrama de Gantt en texto) del núcleo. */
    public String getTimeline() { lock.lock(); try { return timeline.toString(); } finally { lock.unlock(); } }
    /** @return copia de las tareas asignadas. */
    public List<Task> getAssignedTasks() { lock.lock(); try { return new ArrayList<>(assignedTasks); } finally { lock.unlock(); } }
}
