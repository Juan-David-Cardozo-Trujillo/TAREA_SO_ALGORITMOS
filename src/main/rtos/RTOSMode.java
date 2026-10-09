package rtos;

/**
 * Rigurosidad del RTOS (pizarra: "RTOS → Estricto / Flexible (límite para mover P_i de una cola a otra)").
 */
public enum RTOSMode {
    /**
     * Tiempo real duro: los trabajos NO migran entre colas de núcleos (particionado puro)
     * y un trabajo que incumple su deadline es abortado.
     */
    STRICT,
    /**
     * Tiempo real blando: un trabajo puede moverse a otro núcleo ocioso hasta un número máximo
     * de veces (límite de movimiento) y un trabajo tardío continúa ejecutándose.
     */
    FLEXIBLE
}
