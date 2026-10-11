package cl.andesstay.rabbitadmin.service;

/**
 * La operación apunta a un recurso reservado de RabbitMQ: el exchange por omisión o cualquier
 * nombre que empiece con "amq.". El broker los rechaza con ACCESS_REFUSED; se detecta antes para
 * responder con un mensaje claro en lugar de un error de canal.
 */
public class OperacionNoPermitidaException extends RuntimeException {

    public OperacionNoPermitidaException(String mensaje) {
        super(mensaje);
    }
}
