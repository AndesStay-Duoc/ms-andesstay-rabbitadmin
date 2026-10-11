package cl.andesstay.rabbitadmin.web.dto;

import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionExchange;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.EstadoExchange;
import cl.andesstay.rabbitadmin.service.modelo.TipoExchange;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Arrays;
import java.util.Locale;

/** Cuerpos de petición y respuesta de /api/v1/admin/exchanges. */
public final class ExchangeDtos {

    private ExchangeDtos() {
    }

    /** Sin distinguir mayúsculas. Debe coincidir con TipoExchange. */
    static final String TIPOS = "(?i)direct|topic|fanout";
    static final String MENSAJE_TIPOS = "debe ser direct, topic o fanout";

    /** POST /api/v1/admin/exchanges. */
    public record CrearExchangeRequest(
            @NombreRecurso
            String nombre,
            @NotBlank(message = "es obligatorio: " + MENSAJE_TIPOS)
            @Pattern(regexp = TIPOS, message = MENSAJE_TIPOS)
            String tipo,
            Boolean durable,
            Boolean autoEliminar) {

        public DefinicionExchange aDefinicion() {
            return new DefinicionExchange(nombre, aTipo(tipo),
                    ColaDtos.valor(durable, true), ColaDtos.valor(autoEliminar, false));
        }
    }

    /** PUT /api/v1/admin/exchanges/{nombre}. Reemplaza la definición completa. */
    public record ActualizarExchangeRequest(
            @NotBlank(message = "es obligatorio: " + MENSAJE_TIPOS)
            @Pattern(regexp = TIPOS, message = MENSAJE_TIPOS)
            String tipo,
            Boolean durable,
            Boolean autoEliminar) {

        public DefinicionExchange aDefinicion(String nombre) {
            return new DefinicionExchange(nombre, aTipo(tipo),
                    ColaDtos.valor(durable, true), ColaDtos.valor(autoEliminar, false));
        }
    }

    public record ExchangeResponse(
            String nombre,
            String tipo,
            boolean durable,
            boolean autoEliminar) {

        public static ExchangeResponse desde(EstadoExchange e) {
            return new ExchangeResponse(e.nombre(), e.tipo(), e.durable(), e.autoEliminar());
        }
    }

    /** La validación ya garantiza un tipo conocido; esta comprobación es la red de seguridad. */
    static TipoExchange aTipo(String tipo) {
        if (tipo == null) {
            throw new IllegalArgumentException("El tipo de exchange es obligatorio");
        }
        return TipoExchange.desdeAmqp(tipo.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tipo de exchange desconocido '%s'. Valores válidos: %s".formatted(tipo,
                                Arrays.stream(TipoExchange.values()).map(TipoExchange::valorAmqp).toList())));
    }
}
