package rtos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Coordinador del simulador RTOS sincrónico (Opción 2 de la pizarra).
 *
 * <p>Ejecuta en el hilo que lo invoca el bucle maestro del reloj; en cada tick:</p>
 * <ol>
 *   <li>Libera los trabajos que llegan (periódicos por T, aperiódicos por probabilidad).</li>
 *   <li>Si el modo es {@link RTOSMode#FLEXIBLE}, mueve trabajos en espera hacia núcleos ociosos
 *       respetando el límite de movimiento.</li>
 *   <li>Libera a los núcleos (barrera de inicio), espera que terminen (barrera de fin) e imprime el estado.</li>
 * </ol>
 */
public final class RTOSSimulator {

    private final int coreCount;
    private final double maxUtilizationPerCore;
    private final RTOSMode mode;
    private final int maxMigrations;
    private final Scheduler scheduler;
    private final Random random;
    private final List<Task> tasks = new ArrayList<>();
    private final Set<String> names = new HashSet<>();

    /**
     * @param coreCount             número de núcleos (&ge; 1).
     * @param policy                política de planificación (RM o EDF).
     * @param mode                  modo estricto o flexible.
     * @param maxUtilizationPerCore porcentaje máximo de uso por núcleo, en (0, 1].
     * @param maxMigrations         límite de movimientos por trabajo (solo modo FLEXIBLE).
     * @param seed                  semilla del generador para las llegadas aperiódicas (reproducibilidad).
     * @throws IllegalArgumentException si {@code coreCount} &lt; 1.
     */
    public RTOSSimulator(int coreCount, SchedulingPolicy policy, RTOSMode mode,
                         double maxUtilizationPerCore, int maxMigrations, long seed) {
        if (coreCount < 1) {
            throw new IllegalArgumentException("Se requiere al menos 1 núcleo");
        }
        this.coreCount = coreCount;
        this.mode = mode;
        this.maxUtilizationPerCore = maxUtilizationPerCore;
        this.maxMigrations = mode == RTOSMode.FLEXIBLE ? maxMigrations : 0;
        this.scheduler = new Scheduler(policy);
        this.random = new Random(seed);
    }

    /**
     * Registra una tarea en el sistema.
     *
     * @param task tarea a agregar.
     * @return este simulador (para encadenar llamadas).
     * @throws IllegalArgumentException si ya existe una tarea con el mismo nombre.
     */
    public RTOSSimulator addTask(Task task) {
        if (!names.add(task.getName())) {
            throw new IllegalArgumentException("Nombre de tarea duplicado: " + task.getName());
        }
        tasks.add(task);
        return this;
    }

    /**
     * Ejecuta la simulación durante {@code horizon} ticks e imprime el resultado en consola.
     *
     * @param horizon número de ticks a simular (&gt; 0).
     * @throws SchedulabilityException si el conjunto de tareas no es admisible con los núcleos configurados.
     * @throws InterruptedException    si el hilo coordinador es interrumpido.
     * @throws IllegalStateException   si un hilo de núcleo falla durante la simulación.
     */
    public void run(int horizon) throws SchedulabilityException, InterruptedException {
        // --- 1. Construcción de núcleos y asignación (prueba de admisión)
        List<Core> cores = new ArrayList<>();
        for (int i = 1; i <= coreCount; i++) {
            cores.add(new Core(i, scheduler.jobComparator(), maxUtilizationPerCore, mode));
        }
        Map<Task, Core> assignment = scheduler.assign(tasks, cores);

        printHeader(cores, horizon);

        // --- 2. Preparación de concurrencia: un hilo por núcleo + reloj con barreras
        SimulationClock clock = new SimulationClock(coreCount);
        cores.forEach(c -> c.bind(clock));
        ExecutorService pool = Executors.newFixedThreadPool(coreCount, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        cores.forEach(pool::execute);

        Map<Task, Integer> jobCounters = new HashMap<>();
        Map<Task, Job> lastJob = new HashMap<>();

        try {
            // --- 3. Bucle maestro del reloj
            for (int tick = 0; tick < horizon; tick++) {
                List<String> events = new ArrayList<>();

                releaseJobs(tick, assignment, jobCounters, lastJob, events);
                if (mode == RTOSMode.FLEXIBLE) {
                    balanceQueues(cores, events);
                }

                clock.startTick(tick);   // los núcleos ejecutan 1 tick en paralelo
                clock.awaitTickEnd();    // sincronización: nadie pasa al tick siguiente antes que todos

                printTick(tick, cores, events);
            }
            clock.stop();
        } catch (java.util.concurrent.BrokenBarrierException e) {
            Throwable cause = cores.stream().map(Core::getFailure).filter(f -> f != null).findFirst().orElse(e);
            throw new IllegalStateException("La simulación se abortó por un fallo en un núcleo", cause);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        printSummary(cores, horizon);
    }

    /**
     * Libera los trabajos que llegan en el tick dado.
     *
     * <p>Periódica: se libera en t_i + k·T con deadline = t + T (deadline implícito).
     * Aperiódica: llega en t_i y luego, en cada tick, con probabilidad p (distribución geométrica de
     * inter-llegadas); solo se admite una activación pendiente por tarea para evitar acumulación.</p>
     */
    private void releaseJobs(int tick, Map<Task, Core> assignment, Map<Task, Integer> counters,
                             Map<Task, Job> lastJob, List<String> events) {
        for (Map.Entry<Task, Core> e : assignment.entrySet()) {
            Task t = e.getKey();
            boolean arrives;
            if (t.getType() == TaskType.PERIODIC) {
                arrives = tick >= t.getFirstArrival() && (tick - t.getFirstArrival()) % t.getPeriod() == 0;
            } else {
                Job prev = lastJob.get(t);
                boolean busy = prev != null && prev.isActive();
                if (tick == t.getFirstArrival()) {
                    arrives = true;
                } else {
                    // Se consume siempre el número aleatorio para mantener la secuencia reproducible
                    double draw = random.nextDouble();
                    arrives = tick > t.getFirstArrival() && !busy && draw < t.getArrivalProbability();
                }
            }
            if (arrives) {
                int n = counters.merge(t, 1, Integer::sum) - 1;
                Job job = new Job(t, n, tick, (long) tick + t.getRelativeDeadline());
                lastJob.put(t, job);
                e.getValue().release(job);
                events.add("+" + job.getId() + "→N" + e.getValue().getId());
            }
        }
    }

    /**
     * Balanceo (solo FLEXIBLE): por cada núcleo con cola vacía intenta robar un trabajo en espera del núcleo
     * más cargado, respetando el límite de movimiento por trabajo.
     */
    private void balanceQueues(List<Core> cores, List<String> events) {
        for (Core idle : cores) {
            if (idle.queueSize() != 0) {
                continue;
            }
            Core busiest = null;
            for (Core c : cores) {
                if (c != idle && c.queueSize() >= 2 && (busiest == null || c.queueSize() > busiest.queueSize())) {
                    busiest = c;
                }
            }
            if (busiest == null) {
                continue;
            }
            Job moved = busiest.pollMigratable(maxMigrations);
            if (moved != null) {
                idle.receiveMigrated(moved);
                events.add(String.format("↔%s N%d→N%d (mov %d/%d)", moved.getId(), busiest.getId(),
                        idle.getId(), moved.getMigrations(), maxMigrations));
            }
        }
    }

    // ------------------------------------------------------------------ salida por consola

    private void printHeader(List<Core> cores, int horizon) {
        System.out.println("=".repeat(78));
        System.out.printf("RTOS SINCRÓNICO | política=%s | modo=%s | núcleos=%d | uso máx/núcleo=%.0f%% | ticks=%d%n",
                scheduler.getPolicy(), mode, coreCount, maxUtilizationPerCore * 100, horizon);
        System.out.println("=".repeat(78));
        System.out.println("Asignación de tareas (prueba de admisión superada):");
        for (Core c : cores) {
            StringBuilder sb = new StringBuilder();
            for (Task t : c.getAssignedTasks()) {
                sb.append(t).append("  ");
            }
            double u = c.utilization();
            String bound = scheduler.getPolicy() == SchedulingPolicy.RATE_MONOTONIC && c.periodicCount() > 0
                    ? String.format(" (cota RM n=%d: %.1f%%)", c.periodicCount(),
                            Scheduler.liuLaylandBound(c.periodicCount()) * 100)
                    : "";
            System.out.printf("  N%d  U=%.1f%%%s  ->  %s%n", c.getId(), u * 100, bound,
                    sb.length() == 0 ? "(sin tareas)" : sb.toString().trim());
        }
        System.out.println("-".repeat(78));
    }

    private void printTick(int tick, List<Core> cores, List<String> events) {
        StringBuilder line = new StringBuilder(String.format("t=%02d |", tick));
        List<String> misses = new ArrayList<>();
        for (Core c : cores) {
            TickReport r = c.getLastReport();
            String what = r.runningJob() == null ? "IDLE" : r.runningJob() + (r.completed() ? "✓" : "");
            line.append(String.format(" N%d:%-7s(q=%d) |", c.getId(), what, r.queueLength()));
            for (String m : r.missedJobs()) {
                misses.add("✘DEADLINE " + m + " en N" + c.getId());
            }
        }
        List<String> all = new ArrayList<>(events);
        all.addAll(misses);
        if (!all.isEmpty()) {
            line.append(' ').append(String.join(", ", all));
        }
        System.out.println(line);
    }

    private void printSummary(List<Core> cores, int horizon) {
        System.out.println("-".repeat(78));
        System.out.println("Diagrama de Gantt (una celda por tick, '.' = ocioso):");
        StringBuilder axis = new StringBuilder("     ");
        for (int t = 0; t < horizon; t++) {
            axis.append(String.format("%-4d", t));
        }
        System.out.println(axis);
        for (Core c : cores) {
            System.out.printf("N%d:  %s%n", c.getId(), c.getTimeline());
        }
        System.out.println("-".repeat(78));
        System.out.println("Resumen por núcleo:");
        int totalMissed = 0;
        for (Core c : cores) {
            double realUse = 100.0 * c.getBusyTicks() / horizon;
            System.out.printf("  N%d: uso real=%.1f%% | terminados=%d | deadlines incumplidos=%d | migraciones recibidas=%d%n",
                    c.getId(), realUse, c.getCompletedJobs(), c.getMissedDeadlines(), c.getMigrationsIn());
            totalMissed += c.getMissedDeadlines();
        }
        System.out.println(totalMissed == 0
                ? "RESULTADO: ✔ todos los deadlines se cumplieron."
                : "RESULTADO: ✘ se incumplieron " + totalMissed + " deadline(s).");
        System.out.println();
    }
}
