package rtos;

/**
 * Políticas de planificación de prioridades deterministas disponibles.
 */
public enum SchedulingPolicy {
    /** Rate Monotonic: prioridad estática; a menor período T, mayor prioridad (f = 1/T). */
    RATE_MONOTONIC,
    /** Earliest Deadline First: prioridad dinámica; gana el trabajo con el deadline absoluto más cercano. */
    EARLIEST_DEADLINE_FIRST
}
