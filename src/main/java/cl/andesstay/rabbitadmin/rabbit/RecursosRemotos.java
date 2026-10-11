package cl.andesstay.rabbitadmin.rabbit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Forma en que la API de gestión de RabbitMQ describe cada recurso. Solo se mapean los campos que
 * este servicio usa; el resto se ignora.
 *
 * Son tipos de borde: no salen de este paquete hacia los controladores. RabbitAdminService los
 * traduce al modelo propio de service.modelo.
 */
public final class RecursosRemotos {

    private RecursosRemotos() {
    }

    /**
     * {@code messages} y {@code consumers} vienen de las estadísticas de la API, que se refrescan
     * cada pocos segundos: pueden faltar en una cola recién creada. Para decisiones que dependen
     * del conteo exacto se usa AMQP (AmqpAdmin.getQueueInfo), no estos campos.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColaRemota(
            String name,
            boolean durable,
            @JsonProperty("auto_delete") boolean autoDelete,
            Map<String, Object> arguments,
            Long messages,
            Integer consumers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExchangeRemoto(
            String name,
            String type,
            boolean durable,
            @JsonProperty("auto_delete") boolean autoDelete,
            boolean internal) {
    }

    /** {@code destinationType} es "queue" o "exchange". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BindingRemoto(
            String source,
            String destination,
            @JsonProperty("destination_type") String destinationType,
            @JsonProperty("routing_key") String routingKey,
            Map<String, Object> arguments) {

        public static final String DESTINO_COLA = "queue";
        public static final String DESTINO_EXCHANGE = "exchange";
    }
}
