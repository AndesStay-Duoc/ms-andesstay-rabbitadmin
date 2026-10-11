package cl.andesstay.rabbitadmin.service.modelo;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tipos de exchange que el administrador permite crear.
 *
 * No incluye "headers": un exchange de ese tipo enruta por los argumentos del binding (x-match y
 * los encabezados a comparar), y este administrador no gestiona argumentos de binding. Ofrecerlo
 * permitiría crear un exchange al que no se le podría asociar ningún binding útil.
 */
public enum TipoExchange {
    DIRECT("direct"),
    TOPIC("topic"),
    FANOUT("fanout");

    private final String valorAmqp;

    TipoExchange(String valorAmqp) {
        this.valorAmqp = valorAmqp;
    }

    /** Nombre del tipo tal como lo entiende RabbitMQ. */
    public String valorAmqp() {
        return valorAmqp;
    }

    public static Optional<TipoExchange> desdeAmqp(String valor) {
        return Arrays.stream(values()).filter(t -> t.valorAmqp.equals(valor)).findFirst();
    }
}
