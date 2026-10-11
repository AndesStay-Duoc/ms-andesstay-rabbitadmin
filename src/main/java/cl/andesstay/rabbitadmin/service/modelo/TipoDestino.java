package cl.andesstay.rabbitadmin.service.modelo;

/** A qué apunta un binding: una cola o, en un binding exchange-a-exchange, otro exchange. */
public enum TipoDestino {
    COLA,
    EXCHANGE
}
