package cl.andesstay.rabbitadmin.service.modelo;

import java.util.Map;

/**
 * Lo que se pide crear o actualizar (Definicion*) y lo que existe en el broker (Estado*).
 *
 * Es el vocabulario con que los controladores hablan con RabbitAdminService: ninguno de estos tipos
 * es de Spring AMQP ni de la API de gestión, así que la capa web no conoce detalles de RabbitMQ.
 */
public final class Definiciones {

    private Definiciones() {
    }

    /**
     * @param deadLetterExchange   exchange al que van los mensajes rechazados o expirados; null si
     *                             la cola no tiene dead lettering
     * @param deadLetterRoutingKey routing key con que se reenvían; null conserva la original
     * @param ttlMensajesMs        tiempo de vida de cada mensaje; null sin límite
     * @param maxMensajes          largo máximo de la cola; null sin límite
     */
    public record DefinicionCola(
            String nombre,
            boolean durable,
            boolean autoEliminar,
            String deadLetterExchange,
            String deadLetterRoutingKey,
            Long ttlMensajesMs,
            Long maxMensajes) {
    }

    public record DefinicionExchange(
            String nombre,
            TipoExchange tipo,
            boolean durable,
            boolean autoEliminar) {
    }

    /** {@code routingKey} vacío es válido: es lo habitual en un exchange fanout. */
    public record DefinicionBinding(
            String exchange,
            String destino,
            TipoDestino tipoDestino,
            String routingKey) {
    }

    /** {@code mensajes} y {@code consumidores} son exactos al consultar una cola puntual. */
    public record EstadoCola(
            String nombre,
            boolean durable,
            boolean autoEliminar,
            Map<String, Object> argumentos,
            long mensajes,
            int consumidores) {
    }

    /** {@code tipo} es texto y no TipoExchange porque el broker puede tener exchanges headers. */
    public record EstadoExchange(
            String nombre,
            String tipo,
            boolean durable,
            boolean autoEliminar) {
    }

    public record EstadoBinding(
            String exchange,
            String destino,
            TipoDestino tipoDestino,
            String routingKey) {
    }
}
