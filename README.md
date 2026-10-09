# Simulador RTOS sincrónico

Simulador en Java de un sistema operativo de tiempo real con **planificación
multinúcleo particionada**. El coordinador (`RTOSSimulator`) ejecuta un reloj
de ticks discreto y cada núcleo (`Core`) corre en su propio hilo, sincronizados
mediante barreras (`SimulationClock`): ningún núcleo avanza al tick `t+1` antes
de que todos terminen el tick `t`.

## Conceptos

- **Tarea (`Task`)**: plantilla de trabajo. *Periódica* (ráfaga `P`, período `T`,
  primera llegada `t_i`, utilización `P/T`) o *aperiódica* (llegadas con
  probabilidad por tick, deadline relativo).
- **Trabajo (`Job`)**: activación concreta de una tarea; es lo que viaja por las
  colas de listos de los núcleos.
- **Planificación (`Scheduler`)**: asigna tareas a núcleos con una prueba de
  admisión de utilización (First-Fit Decreasing) y define prioridades:
  - `RATE_MONOTONIC`: prioridad estática, menor `T` ⇒ mayor prioridad
    (admite la cota de Liu & Layland).
  - `EARLIEST_DEADLINE_FIRST`: prioridad dinámica por deadline absoluto.
- **Modo (`RTOSMode`)**:
  - `STRICT` (tiempo real duro): los trabajos no migran entre núcleos y el que
    incumple su deadline es abortado.
  - `FLEXIBLE` (tiempo real blando): un trabajo en espera puede moverse a un
    núcleo ocioso hasta un máximo de veces (`maxMigrations`) y el trabajo
    tardío sigue ejecutándose.

## Compilación y ejecución

La interfaz gráfica es la forma principal de ejecutar el simulador. Requiere JDK
25 y Maven. Desde la carpeta raíz, ejecuta:

```bash
mvn javafx:run
```

La ventana muestra un informe HTML con el resultado, la configuración, el uso
por núcleo, los trabajos terminados y las migraciones de los tres escenarios.
Abre el detalle de cada escenario para consultar la traza completa por tick.
Maven descarga JavaFX la primera vez que se ejecuta.

Para ejecutar los escenarios en consola, compila y ejecuta `rtos.Main`:

```bash
javac -d out rtos/*.java
java -cp out rtos.Main
```

`rtos.Main` ejecuta los mismos tres escenarios en la consola:

1. Conjunto no planificable en un solo núcleo (`U = 2/3 + 1/2 ≈ 116 % > 100 %`).
2. Rate Monotonic estricto con 2 núcleos (4 periódicas + 1 aperiódica).
3. EDF flexible con 2 núcleos, uso máximo 95 % y límite de 2 migraciones.

## Salida por consola

Por cada tick muestra el trabajo en ejecución, largo de cola, liberaciones y
migraciones; al final imprime el diagrama de Gantt por núcleo y un resumen con
uso real, trabajos terminados, deadlines incumplidos y migraciones.

## Archivos

| Archivo | Función |
| --- | --- |
| `pom.xml` | Dependencias JavaFX y configuración del comando `mvn javafx:run` |
| `RTOSApplication.java` | Punto de entrada JavaFX; captura los resultados para la interfaz |
| `src/main/resources/rtos/index.html` | Interfaz HTML/CSS/JavaScript cargada en el `WebView` |
| `Main.java` | Escenarios de demostración y ejecución por consola |
| `RTOSSimulator.java` | Coordinador: bucle maestro, liberación de trabajos y balanceo |
| `Core.java` | Núcleo simulado (hilo con cola de listos propia) |
| `Scheduler.java` | Políticas de prioridad, prueba de admisión y asignación |
| `SimulationClock.java` | Reloj con barreras que sincroniza la simulación |
| `Task.java` / `Job.java` | Tareas y sus activaciones |
| `TickReport.java` | Informe inmutable de un tick (los núcleos no imprimen) |
| `RTOSMode.java`, `SchedulingPolicy.java`, `TaskType.java` | Enumeraciones |
| `SchedulabilityException.java` | Rechazo por tarea no planificable |
