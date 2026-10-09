package rtos;

/**
 * Se lanza cuando una tarea periódica no puede ser admitida en ningún núcleo sin violar
 * la cota de utilización de la política de planificación o el porcentaje máximo de uso del núcleo.
 */
public class SchedulabilityException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Utilización total (suma P/T) de la tarea rechazada junto con la del mejor núcleo candidato. */
    private final double attemptedUtilization;

    /**
     * Crea la excepción.
     *
     * @param message             descripción del rechazo.
     * @param attemptedUtilization utilización que habría resultado de admitir la tarea (1.0 = 100 %).
     */
    public SchedulabilityException(String message, double attemptedUtilization) {
        super(message);
        this.attemptedUtilization = attemptedUtilization;
    }

    /**
     * @return la utilización que se habría alcanzado al admitir la tarea (1.0 = 100 %).
     */
    public double getAttemptedUtilization() {
        return attemptedUtilization;
    }
}
