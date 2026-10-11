package cl.andesstay.rabbitadmin.web.dto;

/** Límites de validación compartidos por los DTO de la API. */
public final class Restricciones {

    private Restricciones() {
    }

    /** Límite de RabbitMQ para nombres de colas, exchanges y routing keys (shortstr de AMQP). */
    public static final int LARGO_MAXIMO = 255;

    /** '*' y no '+': el vacío lo reporta @NotBlank y así no sale dos veces el mismo error. */
    public static final String CARACTERES_DE_NOMBRE = "[A-Za-z0-9._:-]*";
    public static final String MENSAJE_CARACTERES_DE_NOMBRE =
            "solo admite letras sin tilde, dígitos y los caracteres . _ - :";

    /** Igual que un nombre pero sin el vacío, para los nombres opcionales (null sí es válido). */
    public static final String NOMBRE_OPCIONAL = "[A-Za-z0-9._:-]+";

    /** Una routing key admite los comodines * y # de los exchanges topic, pero no espacios. */
    public static final String ROUTING_KEY = "\\S*";
    public static final String MENSAJE_ROUTING_KEY = "no puede contener espacios";

    /** x-message-ttl es un entero sin signo de 32 bits en RabbitMQ. */
    public static final long TTL_MAXIMO_MS = 4_294_967_295L;
}
