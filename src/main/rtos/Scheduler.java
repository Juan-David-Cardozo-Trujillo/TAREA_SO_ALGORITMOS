package rtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planificador: define el orden de prioridades y decide a qué núcleo se asigna cada tarea
 * mediante una <i>prueba de admisión</i> (factibilidad de uso de CPU).
 */
public final class Scheduler {

    /** Tolerancia numérica para comparar utilizaciones en coma flotante. */
    private static final double EPS = 1e-9;

    private final SchedulingPolicy policy;

    /**
     * @param policy política de planificación (RM o EDF).
     */
    public Scheduler(SchedulingPolicy policy) {
        this.policy = policy;
    }

    /** @return la política en uso. */
    public SchedulingPolicy getPolicy() { return policy; }

    /**
     * Comparador de prioridad entre trabajos: el MENOR según el comparador es el MÁS prioritario
     * (es el que queda en la cabeza de la {@code PriorityQueue} del núcleo).
     *
     * <ul>
     *   <li><b>RM:</b> clave = período T (menor T ⇒ mayor frecuencia f=1/T ⇒ mayor prioridad). Las aperiódicas
     *       reciben la clave más baja posible (se ejecutan en el "tiempo libre").</li>
     *   <li><b>EDF:</b> clave = deadline absoluto del trabajo (prioridad dinámica).</li>
     * </ul>
     * Los desempates (liberación, nombre, nº de activación) dan un orden total y determinista.
     *
     * @return comparador de {@link Job}s.
     */
    public Comparator<Job> jobComparator() {
        Comparator<Job> tieBreak = Comparator
                .comparingLong(Job::getReleaseTime)
                .thenComparing((Job j) -> j.getTask().getName())
                .thenComparingInt(Job::getJobNumber);
        if (policy == SchedulingPolicy.RATE_MONOTONIC) {
            return Comparator
                    .comparingInt((Job j) -> rmKey(j.getTask()))
                    .thenComparingLong(Job::getAbsoluteDeadline)
                    .thenComparing(tieBreak);
        }
        return Comparator.comparingLong(Job::getAbsoluteDeadline).thenComparing(tieBreak);
    }

    /**
     * Clave de prioridad Rate Monotonic de una tarea.
     *
     * @param t tarea.
     * @return su período T, o {@link Integer#MAX_VALUE} si es aperiódica.
     */
    private static int rmKey(Task t) {
        return t.getType() == TaskType.PERIODIC ? t.getPeriod() : Integer.MAX_VALUE;
    }

    /**
     * Cota de utilización de Liu &amp; Layland para Rate Monotonic: U ≤ n·(2^(1/n) − 1).
     * Es una condición <i>suficiente</i> (no necesaria): n=1 → 1.0, n=2 → 0.828, n→∞ → ln 2 ≈ 0.693.
     *
     * @param n número de tareas periódicas en el núcleo (&ge; 1).
     * @return cota máxima de utilización garantizada.
     */
    public static double liuLaylandBound(int n) {
        return n * (Math.pow(2.0, 1.0 / n) - 1.0);
    }

    /**
     * Prueba de admisión: ¿cabe la tarea en el núcleo sin romper la factibilidad?
     *
     * <p>Se calcula la utilización resultante U' = ΣP_i/T_i + P/T y se compara con el límite:</p>
     * <ul>
     *   <li>RM: min(porcentaje máximo del núcleo, cota de Liu-Layland con n+1 tareas).</li>
     *   <li>EDF: min(porcentaje máximo del núcleo, 1.0) — en EDF con deadline = período, U ≤ 1 es
     *       necesario y suficiente.</li>
     * </ul>
     * Ejemplo de pizarra: P1/T1 + P2/T2 = 2/3 + 1/2 = 1.166 &gt; 1 ⇒ no caben juntas en un núcleo.
     *
     * @param core núcleo candidato.
     * @param task tarea a admitir.
     * @return {@code true} si la tarea es admisible en el núcleo.
     */
    public boolean admits(Core core, Task task) {
        if (task.getType() == TaskType.APERIODIC) {
            return true; // las aperiódicas no reservan capacidad
        }
        return core.utilization() + task.utilization() <= limitFor(core, core.periodicCount() + 1) + EPS;
    }

    /**
     * Límite de utilización aplicable a un núcleo con {@code n} tareas periódicas.
     *
     * @param core núcleo (aporta su porcentaje máximo de uso).
     * @param n    número de tareas periódicas tras la admisión.
     * @return utilización máxima permitida.
     */
    private double limitFor(Core core, int n) {
        double schedBound = policy == SchedulingPolicy.RATE_MONOTONIC ? liuLaylandBound(n) : 1.0;
        return Math.min(core.getMaxUtilization(), schedBound);
    }

    /**
     * Reparte las tareas entre los núcleos (particionado) con <b>First-Fit Decreasing</b> por utilización:
     * las periódicas más pesadas se colocan primero en el primer núcleo que las admite;
     * las aperiódicas van al núcleo menos cargado.
     *
     * @param tasks tareas a repartir.
     * @param cores núcleos disponibles.
     * @return mapa tarea → núcleo asignado (en orden de asignación).
     * @throws SchedulabilityException si alguna tarea periódica no cabe en ningún núcleo.
     */
    public Map<Task, Core> assign(List<Task> tasks, List<Core> cores) throws SchedulabilityException {
        Map<Task, Core> assignment = new LinkedHashMap<>();

        List<Task> periodic = new ArrayList<>();
        List<Task> aperiodic = new ArrayList<>();
        for (Task t : tasks) {
            (t.getType() == TaskType.PERIODIC ? periodic : aperiodic).add(t);
        }
        // Decreasing: mayor utilización primero (heurística clásica de empaquetado)
        periodic.sort(Comparator.comparingDouble(Task::utilization).reversed()
                .thenComparing(Task::getName));

        for (Task t : periodic) {
            Core target = null;
            for (Core c : cores) { // First-Fit
                if (admits(c, t)) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                // Utilización que se habría obtenido en el núcleo más descargado (informativo)
                Core lightest = cores.stream().min(Comparator.comparingDouble(Core::utilization)).orElseThrow();
                double attempted = lightest.utilization() + t.utilization();
                throw new SchedulabilityException(String.format(
                        "La tarea %s no es planificable: en el núcleo más libre (N%d) la utilización sería %.1f %% "
                                + "(límite %.1f %%) con %s.",
                        t, lightest.getId(), attempted * 100,
                        limitFor(lightest, lightest.periodicCount() + 1) * 100, policy), attempted);
            }
            target.addTask(t);
            assignment.put(t, target);
        }

        for (Task t : aperiodic) {
            Core target = cores.stream()
                    .min(Comparator.comparingDouble(Core::utilization).thenComparingInt(Core::taskCount))
                    .orElseThrow();
            target.addTask(t);
            assignment.put(t, target);
        }
        return assignment;
    }
}
