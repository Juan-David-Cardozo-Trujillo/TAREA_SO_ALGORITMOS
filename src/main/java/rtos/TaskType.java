package rtos;

/**
 * Tipos de tarea soportados por el simulador (según la pizarra: "Proceso → Periódico / Aperiódico").
 */
public enum TaskType {
    /** Tarea que se libera cada T ticks, comenzando en el instante t_i (primera atención en CPU). */
    PERIODIC,
    /** Tarea cuyos instantes de llegada siguen una distribución de probabilidad (t_i: primera llegada). */
    APERIODIC
}
