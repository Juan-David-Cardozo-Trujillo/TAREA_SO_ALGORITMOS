package rtos;

import java.util.List;

/**
 * Resultado inmutable de lo ocurrido en un núcleo durante un tick. Los núcleos NO escriben en consola:
 * entregan este informe y el coordinador imprime en orden, lo que hace la salida determinista.
 *
 * @param coreId      identificador del núcleo.
 * @param tick        tick simulado (intervalo [tick, tick+1)).
 * @param runningJob  id del trabajo ejecutado, o {@code null} si el núcleo estuvo ocioso.
 * @param completed   {@code true} si el trabajo terminó en este tick.
 * @param missedJobs  descripciones de los trabajos que incumplieron su deadline al final del tick.
 * @param queueLength longitud de la cola de listos al final del tick.
 */
public record TickReport(int coreId, int tick, String runningJob, boolean completed,
                         List<String> missedJobs, int queueLength) { }
