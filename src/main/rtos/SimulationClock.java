package rtos;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

/**
 * Reloj de simulación discreta (ticks) que sincroniza al coordinador con los hilos de los núcleos.
 *
 * <p>Cada tick tiene dos fases separadas por barreras ({@link CyclicBarrier} de N núcleos + 1 coordinador):</p>
 * <ol>
 *   <li><b>Barrera de inicio:</b> el coordinador ya liberó trabajos y migró colas; todos los núcleos arrancan a la vez.</li>
 *   <li><b>Barrera de fin:</b> todos los núcleos ejecutaron exactamente 1 tick; el coordinador puede leer
 *       los informes y modificar las colas sin condiciones de carrera.</li>
 * </ol>
 * Esto es lo que vuelve al modelo "sincrónico": ningún núcleo avanza al tick t+1 antes de que todos terminen el t.
 */
public final class SimulationClock {

    private final CyclicBarrier startBarrier;
    private final CyclicBarrier endBarrier;
    private volatile int tick;
    private volatile boolean running = true;

    /**
     * @param workers número de hilos de núcleo (el coordinador se suma como parte adicional).
     */
    public SimulationClock(int workers) {
        this.startBarrier = new CyclicBarrier(workers + 1);
        this.endBarrier = new CyclicBarrier(workers + 1);
    }

    // ---------------------------------------------------------------- lado coordinador

    /**
     * Publica el tick actual y libera a todos los núcleos.
     *
     * @param t tick que se va a simular.
     * @throws InterruptedException   si el coordinador es interrumpido.
     * @throws BrokenBarrierException si un núcleo falló y la barrera se rompió.
     */
    public void startTick(int t) throws InterruptedException, BrokenBarrierException {
        this.tick = t; // escritura volátil antes de la barrera: visible para los núcleos al despertar
        startBarrier.await();
    }

    /**
     * Espera a que todos los núcleos terminen el tick en curso.
     *
     * @throws InterruptedException   si el coordinador es interrumpido.
     * @throws BrokenBarrierException si un núcleo falló y la barrera se rompió.
     */
    public void awaitTickEnd() throws InterruptedException, BrokenBarrierException {
        endBarrier.await();
    }

    /**
     * Detiene el reloj: despierta a los núcleos con una señal de parada para que terminen su bucle.
     *
     * @throws InterruptedException   si el coordinador es interrumpido.
     * @throws BrokenBarrierException si la barrera ya estaba rota.
     */
    public void stop() throws InterruptedException, BrokenBarrierException {
        running = false;
        startBarrier.await();
    }

    /** Aborta la simulación (p. ej. un núcleo lanzó una excepción): rompe ambas barreras. */
    public void abort() {
        running = false;
        startBarrier.reset();
        endBarrier.reset();
    }

    // ---------------------------------------------------------------- lado núcleo

    /**
     * Bloquea al núcleo hasta el inicio del siguiente tick.
     *
     * @return el tick a simular, o -1 si el reloj fue detenido.
     * @throws InterruptedException   si el hilo es interrumpido.
     * @throws BrokenBarrierException si la barrera se rompió.
     */
    public int awaitTickStart() throws InterruptedException, BrokenBarrierException {
        startBarrier.await();
        return running ? tick : -1;
    }

    /**
     * Indica que el núcleo terminó su tick y espera a los demás.
     *
     * @throws InterruptedException   si el hilo es interrumpido.
     * @throws BrokenBarrierException si la barrera se rompió.
     */
    public void signalTickEnd() throws InterruptedException, BrokenBarrierException {
        endBarrier.await();
    }
}
