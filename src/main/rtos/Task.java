package rtos;

/**
 * Descripción inmutable de una tarea (plantilla de la cual se instancian {@link Job}s).
 *
 * <p>Notación de la pizarra: <b>P</b> = ráfaga (tiempo de CPU por activación),
 * <b>T</b> = período, <b>t_i</b> = tiempo de primera atención en CPU.
 * La frecuencia es f = 1/T.</p>
 */
public final class Task {

    private final String name;
    private final TaskType type;
    private final int burst;
    private final int period;
    private final int firstArrival;
    private final double arrivalProbability;
    private final int relativeDeadline;

    private Task(String name, TaskType type, int burst, int period, int firstArrival,
                 double arrivalProbability, int relativeDeadline) {
        this.name = name;
        this.type = type;
        this.burst = burst;
        this.period = period;
        this.firstArrival = firstArrival;
        this.arrivalProbability = arrivalProbability;
        this.relativeDeadline = relativeDeadline;
    }

    /**
     * Crea una tarea periódica con deadline implícito (deadline = período).
     *
     * @param name         identificador corto y único (se usa en la salida de consola, máx. 3 caracteres recomendado).
     * @param burst        ráfaga P en ticks (&gt; 0).
     * @param period       período T en ticks (&gt;= P).
     * @param firstArrival instante t_i de la primera liberación (&gt;= 0).
     * @return la tarea periódica.
     * @throws IllegalArgumentException si algún parámetro es inválido.
     */
    public static Task periodic(String name, int burst, int period, int firstArrival) {
        validateCommon(name, burst, firstArrival);
        if (period <= 0) {
            throw new IllegalArgumentException("El período debe ser > 0");
        }
        if (burst > period) {
            throw new IllegalArgumentException("La ráfaga P no puede superar al período T (utilización > 100 %)");
        }
        return new Task(name, TaskType.PERIODIC, burst, period, firstArrival, 0.0, period);
    }

    /**
     * Crea una tarea aperiódica. Tras su primera llegada (t_i), en cada tick posterior vuelve a llegar
     * con probabilidad {@code arrivalProbability} (inter-llegadas geométricas).
     *
     * @param name               identificador corto y único.
     * @param burst              ráfaga P en ticks (&gt; 0).
     * @param firstArrival       instante t_i de la primera llegada (&gt;= 0).
     * @param arrivalProbability probabilidad por tick de una nueva llegada, en [0, 1].
     * @param relativeDeadline   deadline relativo a cada llegada, en ticks (&gt;= P).
     * @return la tarea aperiódica.
     * @throws IllegalArgumentException si algún parámetro es inválido.
     */
    public static Task aperiodic(String name, int burst, int firstArrival,
                                 double arrivalProbability, int relativeDeadline) {
        validateCommon(name, burst, firstArrival);
        if (arrivalProbability < 0.0 || arrivalProbability > 1.0) {
            throw new IllegalArgumentException("La probabilidad debe estar en [0,1]");
        }
        if (relativeDeadline < burst) {
            throw new IllegalArgumentException("El deadline relativo no puede ser menor que la ráfaga");
        }
        return new Task(name, TaskType.APERIODIC, burst, 0, firstArrival, arrivalProbability, relativeDeadline);
    }

    /** Validaciones compartidas por ambos constructores estáticos. */
    private static void validateCommon(String name, int burst, int firstArrival) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("La tarea necesita un nombre");
        }
        if (burst <= 0) {
            throw new IllegalArgumentException("La ráfaga debe ser > 0");
        }
        if (firstArrival < 0) {
            throw new IllegalArgumentException("t_i debe ser >= 0");
        }
    }

    /**
     * Utilización de CPU que aporta la tarea: P/T. Las aperiódicas no reservan capacidad (retorna 0).
     *
     * @return fracción de CPU en [0, 1].
     */
    public double utilization() {
        return type == TaskType.PERIODIC ? (double) burst / period : 0.0;
    }

    /**
     * Frecuencia de la tarea f = 1/T (0 para aperiódicas).
     *
     * @return frecuencia en activaciones por tick.
     */
    public double frequency() {
        return type == TaskType.PERIODIC ? 1.0 / period : 0.0;
    }

    /** @return nombre de la tarea. */
    public String getName() { return name; }
    /** @return tipo de la tarea. */
    public TaskType getType() { return type; }
    /** @return ráfaga P en ticks. */
    public int getBurst() { return burst; }
    /** @return período T en ticks (0 si es aperiódica). */
    public int getPeriod() { return period; }
    /** @return instante t_i de primera atención. */
    public int getFirstArrival() { return firstArrival; }
    /** @return probabilidad por tick de llegada (solo aperiódicas). */
    public double getArrivalProbability() { return arrivalProbability; }
    /** @return deadline relativo en ticks. */
    public int getRelativeDeadline() { return relativeDeadline; }

    @Override
    public String toString() {
        return type == TaskType.PERIODIC
                ? String.format("%s[P=%d, T=%d, ti=%d, U=%.3f]", name, burst, period, firstArrival, utilization())
                : String.format("%s[APERIÓDICA P=%d, ti=%d, prob=%.2f, D=%d]",
                        name, burst, firstArrival, arrivalProbability, relativeDeadline);
    }
}
