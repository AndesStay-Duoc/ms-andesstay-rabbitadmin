package cl.andesstay.rabbitadmin.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Optional;

/**
 * Colas y exchanges que el administrador no puede crear, modificar ni eliminar: la topología de la
 * que dependen los demás microservicios. Se configura en andesstay.rabbitadmin.protegidos.
 *
 * Cada entrada es un nombre exacto o un PREFIJO terminado en '*'. Ese '*' significa "cualquier
 * sufijo, puntos incluidos", y NO el comodín de un binding topic de RabbitMQ, donde '*' es
 * exactamente una palabra. La diferencia importa: con la semántica topic, "q.cmd.*" no cubriría
 * q.cmd.email.dlq ni "cmd.*" cubriría cmd.dead.dlx, y cuatro de los nueve recursos del caso
 * quedarían sin proteger.
 *
 * La protección no se puede saltar con forzar=true. Cambiarla exige cambiar la configuración y
 * volver a desplegar, que es justamente el punto.
 *
 * @param colas     patrones de colas protegidas
 * @param exchanges patrones de exchanges protegidos
 */
@ConfigurationProperties(prefix = "andesstay.rabbitadmin.protegidos")
public record RecursosProtegidos(List<String> colas, List<String> exchanges) {

    private static final String COMODIN = "*";

    public RecursosProtegidos {
        colas = limpiar(colas);
        exchanges = limpiar(exchanges);
    }

    /** El patrón que protege la cola, o vacío si no está protegida. */
    public Optional<String> patronDeCola(String nombre) {
        return primerPatronQueCalza(colas, nombre);
    }

    /** El patrón que protege el exchange, o vacío si no está protegido. */
    public Optional<String> patronDeExchange(String nombre) {
        return primerPatronQueCalza(exchanges, nombre);
    }

    private static Optional<String> primerPatronQueCalza(List<String> patrones, String nombre) {
        if (nombre == null) {
            return Optional.empty();
        }
        return patrones.stream().filter(p -> calza(p, nombre)).findFirst();
    }

    static boolean calza(String patron, String nombre) {
        if (patron.endsWith(COMODIN)) {
            return nombre.startsWith(patron.substring(0, patron.length() - COMODIN.length()));
        }
        return nombre.equals(patron);
    }

    /** Admite la lista vacía o ausente (sin protección) y descarta entradas en blanco. */
    private static List<String> limpiar(List<String> patrones) {
        return patrones == null ? List.of() : patrones.stream()
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .toList();
    }
}
