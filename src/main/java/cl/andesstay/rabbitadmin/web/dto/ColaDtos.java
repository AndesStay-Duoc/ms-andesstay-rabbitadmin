package cl.andesstay.rabbitadmin.web.dto;

import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionCola;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.EstadoCola;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Cuerpos de petición y respuesta de /api/v1/admin/queues. */
public final class ColaDtos {

    private ColaDtos() {
    }

    /**
     * POST /api/v1/admin/queues. Solo {@code nombre} es obligatorio; durable es true y
     * autoEliminar false si no se indican, que es lo que una cola de trabajo necesita.
     */
    public record CrearColaRequest(
            @NombreRecurso
            String nombre,
            Boolean durable,
            Boolean autoEliminar,
            @Size(max = Restricciones.LARGO_MAXIMO, message = "admite como máximo " + Restricciones.LARGO_MAXIMO + " caracteres")
            @Pattern(regexp = Restricciones.NOMBRE_OPCIONAL, message = Restricciones.MENSAJE_CARACTERES_DE_NOMBRE + ", y si se envía no puede ir vacío")
            String deadLetterExchange,
            @Size(max = Restricciones.LARGO_MAXIMO, message = "admite como máximo " + Restricciones.LARGO_MAXIMO + " caracteres")
            @Pattern(regexp = Restricciones.ROUTING_KEY, message = Restricciones.MENSAJE_ROUTING_KEY)
            String deadLetterRoutingKey,
            @PositiveOrZero(message = "no puede ser negativo")
            @Max(value = Restricciones.TTL_MAXIMO_MS, message = "no puede superar " + Restricciones.TTL_MAXIMO_MS + " ms, el máximo de RabbitMQ")
            Long ttlMensajesMs,
            @Positive(message = "debe ser mayor que cero: una cola de largo cero descartaría todo mensaje")
            Long maxMensajes) {

        /** Ver {@link ColaDtos#deadLetterCoherente}. */
        @JsonIgnore
        @AssertTrue(message = "deadLetterRoutingKey solo tiene efecto junto con deadLetterExchange: envíe ambos o ninguno de los dos")
        public boolean isDeadLetterCoherente() {
            return deadLetterCoherente(deadLetterExchange, deadLetterRoutingKey);
        }

        public DefinicionCola aDefinicion() {
            return new DefinicionCola(nombre, valor(durable, true), valor(autoEliminar, false),
                    deadLetterExchange, deadLetterRoutingKey, ttlMensajesMs, maxMensajes);
        }
    }

    /**
     * PUT /api/v1/admin/queues/{nombre}. Reemplaza la definición completa: lo que no se envía vuelve
     * a su valor por omisión, no conserva el anterior. Es la semántica de PUT y evita que una cola
     * quede con una mezcla de argumentos viejos y nuevos.
     */
    public record ActualizarColaRequest(
            Boolean durable,
            Boolean autoEliminar,
            @Size(max = Restricciones.LARGO_MAXIMO, message = "admite como máximo " + Restricciones.LARGO_MAXIMO + " caracteres")
            @Pattern(regexp = Restricciones.NOMBRE_OPCIONAL, message = Restricciones.MENSAJE_CARACTERES_DE_NOMBRE + ", y si se envía no puede ir vacío")
            String deadLetterExchange,
            @Size(max = Restricciones.LARGO_MAXIMO, message = "admite como máximo " + Restricciones.LARGO_MAXIMO + " caracteres")
            @Pattern(regexp = Restricciones.ROUTING_KEY, message = Restricciones.MENSAJE_ROUTING_KEY)
            String deadLetterRoutingKey,
            @PositiveOrZero(message = "no puede ser negativo")
            @Max(value = Restricciones.TTL_MAXIMO_MS, message = "no puede superar " + Restricciones.TTL_MAXIMO_MS + " ms, el máximo de RabbitMQ")
            Long ttlMensajesMs,
            @Positive(message = "debe ser mayor que cero: una cola de largo cero descartaría todo mensaje")
            Long maxMensajes) {

        @JsonIgnore
        @AssertTrue(message = "deadLetterRoutingKey solo tiene efecto junto con deadLetterExchange: envíe ambos o ninguno de los dos")
        public boolean isDeadLetterCoherente() {
            return deadLetterCoherente(deadLetterExchange, deadLetterRoutingKey);
        }

        public DefinicionCola aDefinicion(String nombre) {
            return new DefinicionCola(nombre, valor(durable, true), valor(autoEliminar, false),
                    deadLetterExchange, deadLetterRoutingKey, ttlMensajesMs, maxMensajes);
        }
    }

    public record ColaResponse(
            String nombre,
            boolean durable,
            boolean autoEliminar,
            Map<String, Object> argumentos,
            long mensajes,
            int consumidores) {

        public static ColaResponse desde(EstadoCola e) {
            return new ColaResponse(e.nombre(), e.durable(), e.autoEliminar(), e.argumentos(),
                    e.mensajes(), e.consumidores());
        }
    }

    /**
     * RabbitMQ acepta x-dead-letter-routing-key sin x-dead-letter-exchange, pero entonces la ignora:
     * quien la envía cree tener una DLQ que no existe. Es una configuración inválida aunque el
     * broker no se queje.
     */
    static boolean deadLetterCoherente(String deadLetterExchange, String deadLetterRoutingKey) {
        return deadLetterRoutingKey == null || deadLetterExchange != null;
    }

    static boolean valor(Boolean valor, boolean porOmision) {
        return valor == null ? porOmision : valor;
    }
}
