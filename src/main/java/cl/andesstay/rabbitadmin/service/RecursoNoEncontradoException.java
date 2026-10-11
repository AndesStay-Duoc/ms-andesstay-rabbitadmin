package cl.andesstay.rabbitadmin.service;

/** La cola, el exchange o el binding pedido no existe en el broker. */
public class RecursoNoEncontradoException extends RuntimeException {

    private final String tipo;
    private final String nombre;

    public RecursoNoEncontradoException(String tipo, String nombre) {
        super("No existe %s '%s'".formatted(tipo, nombre));
        this.tipo = tipo;
        this.nombre = nombre;
    }

    public String getTipo() {
        return tipo;
    }

    public String getNombre() {
        return nombre;
    }
}
