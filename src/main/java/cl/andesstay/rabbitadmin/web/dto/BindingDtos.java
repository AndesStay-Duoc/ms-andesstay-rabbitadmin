package cl.andesstay.rabbitadmin.web.dto;

import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionBinding;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.EstadoBinding;
import cl.andesstay.rabbitadmin.service.modelo.TipoDestino;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Arrays;
import java.util.Locale;

/** Cuerpos de petición y respuesta de /api/v1/admin/bindings. */
public final class BindingDtos {

    private BindingDtos() {
    }

    /** Sin distinguir mayúsculas; ausente equivale a COLA. */
    public static final String TIPOS_DESTINO = "(?i)cola|exchange";
    public static final String MENSAJE_TIPOS_DESTINO = "debe ser COLA o EXCHANGE";

    /**
     * POST /api/v1/admin/bindings. {@code tipoDestino} es COLA si no se indica; {@code routingKey}
     * vacía es válida y es lo habitual con un exchange fanout.
     */
    public record CrearBindingRequest(
            @NombreRecurso
            String exchange,
            @NombreRecurso
            String destino,
            @Pattern(regexp = TIPOS_DESTINO, message = MENSAJE_TIPOS_DESTINO)
            String tipoDestino,
            @Size(max = Restricciones.LARGO_MAXIMO, message = "admite como máximo " + Restricciones.LARGO_MAXIMO + " caracteres")
            @Pattern(regexp = Restricciones.ROUTING_KEY, message = Restricciones.MENSAJE_ROUTING_KEY)
            String routingKey) {

        public DefinicionBinding aDefinicion() {
            return definicion(exchange, destino, tipoDestino, routingKey);
        }
    }

    public record BindingResponse(
            String exchange,
            String destino,
            TipoDestino tipoDestino,
            String routingKey) {

        public static BindingResponse desde(EstadoBinding e) {
            return new BindingResponse(e.exchange(), e.destino(), e.tipoDestino(), e.routingKey());
        }
    }

    /** Usado también por el DELETE, que recibe los mismos cuatro datos como parámetros de consulta. */
    public static DefinicionBinding definicion(String exchange, String destino, String tipoDestino, String routingKey) {
        return new DefinicionBinding(exchange, destino, tipoDestino(tipoDestino), routingKey == null ? "" : routingKey);
    }

    /** La validación ya garantiza un valor conocido; esta comprobación es la red de seguridad. */
    static TipoDestino tipoDestino(String tipo) {
        if (tipo == null) {
            return TipoDestino.COLA;
        }
        try {
            return TipoDestino.valueOf(tipo.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Tipo de destino desconocido '%s'. Valores válidos: %s"
                    .formatted(tipo, Arrays.toString(TipoDestino.values())));
        }
    }
}
