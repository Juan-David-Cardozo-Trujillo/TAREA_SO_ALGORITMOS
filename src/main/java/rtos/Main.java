package rtos;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Demostración del simulador RTOS sincrónico con tres escenarios.
 *
 * <p>Compilar: {@code javac -d out rtos/*.java} — Ejecutar: {@code java -cp out rtos.Main}</p>
 */
public final class Main {

    private Main() { }

    /**
     * Punto de entrada.
     *
     * @param args sin uso.
     * @throws InterruptedException si el hilo principal es interrumpido.
     */
    public static void main(String[] args) throws InterruptedException {
        demoInfeasibleSingleCore();
        demoRateMonotonicStrict();
        demoEdfFlexible();
    }

    static List<String> captureDemos() throws InterruptedException {
        return List.of(
                capture(Main::demoInfeasibleSingleCore),
                capture(Main::demoRateMonotonicStrict),
                capture(Main::demoEdfFlexible));
    }

    private static String capture(Demo demo) throws InterruptedException {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            demo.run();
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Demo {
        void run() throws InterruptedException;
    }

    /**
     * Escenario 1 (pizarra): T1 (P=2, T=3) y T2 (P=1, T=2) en UN solo núcleo.
     * U = 2/3 + 1/2 = 116,6 % &gt; 100 % ⇒ el sistema rechaza la configuración.
     */
    private static void demoInfeasibleSingleCore() throws InterruptedException {
        System.out.println(">>> ESCENARIO 1: factibilidad en un solo núcleo (ejemplo de la pizarra)");
        RTOSSimulator sim = new RTOSSimulator(1, SchedulingPolicy.RATE_MONOTONIC, RTOSMode.STRICT, 1.0, 0, 1L)
                .addTask(Task.periodic("T1", 2, 3, 0))
                .addTask(Task.periodic("T2", 1, 2, 0));
        try {
            sim.run(12);
        } catch (SchedulabilityException e) {
            System.out.printf("NO PLANIFICABLE (U intentada = %.2f): %s%n%n", e.getAttemptedUtilization(), e.getMessage());
        }
    }

    /**
     * Escenario 2: Rate Monotonic estricto con 2 núcleos, 4 tareas periódicas y 1 aperiódica.
     * El mismo conjunto de la pizarra ahora es planificable al repartirse en dos núcleos.
     */
    private static void demoRateMonotonicStrict() throws InterruptedException {
        System.out.println(">>> ESCENARIO 2: Rate Monotonic, modo ESTRICTO, 2 núcleos");
        RTOSSimulator sim = new RTOSSimulator(2, SchedulingPolicy.RATE_MONOTONIC, RTOSMode.STRICT, 1.0, 0, 42L)
                .addTask(Task.periodic("T1", 2, 3, 0))
                .addTask(Task.periodic("T2", 1, 2, 0))
                .addTask(Task.periodic("T3", 1, 4, 0))
                .addTask(Task.periodic("T4", 1, 8, 0))
                .addTask(Task.aperiodic("A1", 1, 2, 0.20, 8));
        runSafely(sim, 16);
    }

    /**
     * Escenario 3: EDF flexible (límite de 2 movimientos por trabajo) con uso máximo 95 % por núcleo.
     */
    private static void demoEdfFlexible() throws InterruptedException {
        System.out.println(">>> ESCENARIO 3: Earliest Deadline First, modo FLEXIBLE, 2 núcleos");
        RTOSSimulator sim = new RTOSSimulator(2, SchedulingPolicy.EARLIEST_DEADLINE_FIRST, RTOSMode.FLEXIBLE, 0.95, 2, 7L)
                .addTask(Task.periodic("T1", 2, 3, 0))
                .addTask(Task.periodic("T2", 1, 2, 0))
                .addTask(Task.periodic("T3", 1, 3, 1))
                .addTask(Task.periodic("T4", 1, 6, 0))
                .addTask(Task.aperiodic("A1", 2, 3, 0.15, 6));
        runSafely(sim, 16);
    }

    /** Ejecuta un simulador capturando el rechazo por falta de factibilidad. */
    private static void runSafely(RTOSSimulator sim, int ticks) throws InterruptedException {
        try {
            sim.run(ticks);
        } catch (SchedulabilityException e) {
            System.out.println("NO PLANIFICABLE: " + e.getMessage());
        }
    }
}
