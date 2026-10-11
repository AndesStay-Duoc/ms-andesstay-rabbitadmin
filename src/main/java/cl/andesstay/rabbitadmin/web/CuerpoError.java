package cl.andesstay.rabbitadmin.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Cuerpo de toda respuesta de error, con la forma del esquema Error de los contratos OpenAPI del
 * equipo (infra/docs/contracts/openapi): un código estable para que el cliente decida, y un
 * mensaje legible para quien lo lea.
 *
 * {@code campos} solo aparece en los 400 de validación y trae un error por campo o parámetro, para
 * que el cliente pueda señalar exactamente qué corregir.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CuerpoError(String error, String mensaje, String timestamp, List<ErrorDeCampo> campos) {

    public record ErrorDeCampo(String campo, String mensaje) {
    }

    public static CuerpoError de(String error, String mensaje) {
        return new CuerpoError(error, mensaje, Instant.now().toString(), null);
    }

    public static CuerpoError deValidacion(String mensaje, List<ErrorDeCampo> campos) {
        return new CuerpoError("SOLICITUD_INVALIDA", mensaje, Instant.now().toString(), campos);
    }
}
