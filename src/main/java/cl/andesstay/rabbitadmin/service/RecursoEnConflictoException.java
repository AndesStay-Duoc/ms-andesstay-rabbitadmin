package cl.andesstay.rabbitadmin.service;

/**
 * La operación choca con el estado actual del broker: el recurso ya existe, o eliminarlo o
 * recrearlo perdería mensajes, consumidores o bindings sin que se haya pedido forzar.
 */
public class RecursoEnConflictoException extends RuntimeException {

    public RecursoEnConflictoException(String mensaje) {
        super(mensaje);
    }
}
