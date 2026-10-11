# ms-andesstay-rabbitadmin

Microservicio administrador de RabbitMQ de AndesStay. Expone una API REST para **crear, consultar,
actualizar y eliminar colas, exchanges y bindings** del broker, sin entrar a la consola de RabbitMQ.

- Toda la API exige un **JWT de Microsoft Entra ID con el rol `Admin`**.
- La **topología del sistema** (`q.cmd.*`, `cmd.*`) está **protegida**: no se puede modificar ni
  borrar desde aquí. Ver [Recursos protegidos](#recursos-protegidos).
- Valida toda la entrada y responde errores claros, con el detalle por campo.

> **Estado de verificación.** El servicio compila y pasa **68 tests** (servicio con Mockito, capa
> web con la seguridad real y el contexto completo). **No se ha probado todavía contra un RabbitMQ
> real ni con un token real de Entra ID**: los ejemplos de respuesta de este documento salen del
> código y de los tests, no de una ejecución contra el broker. La prueba de punta a punta está en
> `infra/docs/prueba-e2e-rabbitmq.md`.

---

## Índice

1. [Cómo funciona](#cómo-funciona)
2. [Requisitos](#requisitos)
3. [Variables de entorno](#variables-de-entorno)
4. [Cómo levantarlo](#cómo-levantarlo)
5. [Obtener un token de Entra ID](#obtener-un-token-de-entra-id)
6. [Endpoints](#endpoints)
7. [Errores](#errores)
8. [Recursos protegidos](#recursos-protegidos)
9. [Despliegue en AWS](#despliegue-en-aws)
10. [Limitaciones conocidas y mejoras futuras](#limitaciones-conocidas-y-mejoras-futuras)

---

## Cómo funciona

```
Postman / curl ──JWT Admin──► controller ──► RabbitAdminService ──┬─ AMQP (5672) ─────► RabbitMQ
                                                                  └─ HTTP (15672) ────► API de gestión
```

| Paquete | Responsabilidad |
|---|---|
| `controller` | Rutas, verbos y códigos HTTP. Solo llaman a `RabbitAdminService` y no conocen RabbitMQ |
| `web`, `web.dto` | DTO con anotaciones de validación, traducción de excepciones a `CuerpoError` |
| `service` | `RabbitAdminService`: toda la lógica de administración. `RecursosProtegidos`: la protección |
| `rabbit` | `RabbitManagementClient`: lecturas en la API HTTP de gestión |
| `config` | Seguridad JWT y cliente HTTP de la API de gestión |

**Por qué dos vías hacia el broker.** Las escrituras (declarar y eliminar) van por **AMQP** con
`AmqpAdmin`, igual que en el resto de los microservicios. Pero AMQP no permite listar recursos, leer
los argumentos de una cola ni saber qué bindings existen, así que eso se lee de la **API HTTP de
gestión** (el plugin `management`, puerto 15672). El conteo exacto de mensajes de una cola puntual sí
sale de AMQP, porque las estadísticas de la API van unos segundos atrasadas.

**Sobre "actualizar".** RabbitMQ no permite modificar una cola o un exchange ya declarado: volver a
declararlo con otros argumentos falla con `PRECONDITION_FAILED`. Por eso `PUT` **elimina y recrea**
el recurso, y antes lee sus bindings para restaurarlos. Una cola solo se recrea si está **vacía y sin
consumidores**; si no, responde `409`.

---

## Requisitos

| Requisito | Versión | Notas |
|---|---|---|
| Java | 17 | Compila también con 21 |
| Maven | 3.9 | O construir con el `Dockerfile`, que trae su propio Maven |
| RabbitMQ | 3.13 con el plugin `management` | La imagen `rabbitmq:3.13-management` de `infra/mq/compose.yml` ya lo trae |
| Usuario de RabbitMQ | Permiso *configure* sobre el vhost y tag `management` o superior | El usuario de `RABBITMQ_DEFAULT_USER` del compose tiene ambos |
| Cuenta en Entra ID | Con el App Role **`Admin`** asignado | Ver [Obtener un token](#obtener-un-token-de-entra-id) |

---

## Variables de entorno

Copiar `.env.example` a `.env` y completar. El `.env` **no se versiona**.

| Variable | Por omisión | Para qué |
|---|---|---|
| `AZURE_TENANT_ID` | `055d11d1-8ae0-4221-a6f7-b50be0a623b4` | Tenant de Entra ID: de aquí salen el issuer y el JWKS |
| `AZURE_CLIENT_ID` | `704a544f-3d92-44f5-aef9-8559574cff34` | App Registration: el `aud` aceptado, en sus dos formas |
| `RABBITMQ_HOST` | `localhost` | Broker, por AMQP |
| `RABBITMQ_PORT` | `5672` | |
| `RABBITMQ_VHOST` | `andesstay` | Vhost administrado. Todo se crea y se lista dentro de él |
| `RABBITMQ_USER` / `RABBITMQ_PASS` | *(vacíos)* | Credenciales. Se usan para AMQP **y** para la API de gestión |
| `RABBITMQ_MANAGEMENT_URL` | `http://localhost:15672` | API HTTP de gestión |
| `RABBITMQ_MANAGEMENT_TIMEOUT_MS` | `5000` | Timeout de conexión y lectura contra la API de gestión |
| `RABBITADMIN_COLAS_PROTEGIDAS` | `q.cmd.*` | Ver [Recursos protegidos](#recursos-protegidos). Varias, separadas por comas |
| `RABBITADMIN_EXCHANGES_PROTEGIDOS` | `cmd.*` | Ídem |

`AZURE_TENANT_ID` y `AZURE_CLIENT_ID` **no son secretos**: son identificadores públicos que viajan
en el bundle del frontend. Son los mismos que usan el BFF y los demás microservicios
(`infra/docs/idaas/tenants.md`).

El puerto HTTP es el **8086**.

---

## Cómo levantarlo

### 1. Un broker

Desde la carpeta raíz donde están clonados los repositorios, con el `.env` de `infra`:

```bash
docker compose --env-file infra/.env -f infra/mq/compose.yml up -d rabbitmq1 rabbitmq2 rabbitmq-init
```

Levanta el clúster de dos nodos y aplica la política de alta disponibilidad. La consola queda en
`http://localhost:15672`.

### 2. El servicio

**Con Maven:**

```bash
cd ms-andesstay-rabbitadmin
cp .env.example .env          # y completar RABBITMQ_USER y RABBITMQ_PASS
set -a; source .env; set +a   # exporta las variables en Git Bash o Linux
mvn spring-boot:run
```

**Con Docker:**

```bash
cd ms-andesstay-rabbitadmin
docker build -t andesstay/rabbitadmin .
docker run --rm -p 8086:8086 --env-file .env \
  -e RABBITMQ_HOST=host.docker.internal \
  -e RABBITMQ_MANAGEMENT_URL=http://host.docker.internal:15672 \
  andesstay/rabbitadmin
```

Dentro de un contenedor, `localhost` es el propio contenedor: por eso se apunta al broker por
`host.docker.internal` (Docker Desktop).

### 3. Comprobar

```bash
curl -s http://localhost:8086/actuator/health
# {"status":"UP"}   ← UP solo si alcanza el broker por AMQP
```

### Tests

```bash
mvn test
# Tests run: 68, Failures: 0, Errors: 0, Skipped: 0
```

No necesitan broker ni conexión a Entra ID.

---

## Obtener un token de Entra ID

El sistema usa **una sola App Registration** (`704a544f-…`, tenant `055d11d1-…`), registrada como
*Single-page application*: no tiene client secret, así que el token se obtiene iniciando sesión en
el frontend. Es el mismo método que usa la colección de Postman del equipo
(`infra/docs/evidencias/autorizacion/coleccion-postman.json`).

### Requisito previo: el rol Admin

El App Role **`Admin`** ya existe; **no hay que crearlo**. Lo que sí hace falta es que la cuenta con
que se prueba lo tenga asignado:

*Portal de Azure → Microsoft Entra ID → Enterprise applications → AndesStay → Users and groups →
Add user/group* → elegir el usuario y el rol **Administrador** (valor `Admin`). En el plan gratuito
se asigna usuario por usuario; la asignación por grupo exige Entra ID P1.

### Pasos

1. Abrir el frontend: `http://localhost:4200` en local, o la URL de aplicación del despliegue.
2. Iniciar sesión con la cuenta que tiene el rol **Admin**.
3. Abrir las herramientas de desarrollo (F12) → **Application → Local Storage** → el origen de la app.
4. Buscar la clave que contiene **`accesstoken`** **y** `api://704a544f-3d92-44f5-aef9-8559574cff34/AndesStay.Access`.
   Puede haber otra para Microsoft Graph (`User.Read`): esa **no sirve**, su `aud` es de Graph y el
   servicio responderá `401`.
5. Copiar el valor del campo **`secret`**: es el JWT.
6. *(Opcional)* Revisarlo sin pegarlo en ningún sitio web:

   ```bash
   node -e "const p=process.argv[1].split('.')[1];console.log(JSON.stringify(JSON.parse(Buffer.from(p,'base64url')),null,2))" "<token>"
   ```

   Debe mostrar:

   | Claim | Valor esperado |
   |---|---|
   | `iss` | `https://login.microsoftonline.com/055d11d1-8ae0-4221-a6f7-b50be0a623b4/v2.0` |
   | `aud` | `704a544f-3d92-44f5-aef9-8559574cff34` |
   | `roles` | `["Admin"]` |
   | `scp` | `AndesStay.Access` |
   | `exp` | Una fecha futura. El token dura alrededor de una hora |

7. En **Postman**: pestaña *Authorization* → *Type*: **Bearer Token** → pegar el token.
   Con curl:

   ```bash
   export TOKEN="<token>"
   curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8086/api/v1/admin/queues
   ```

Cuando vence, se repite desde el paso 3.

**De qué claim lee el rol.** Del claim `roles`, que se convierte en la authority `ROLE_Admin`
conservando mayúsculas. Es el mismo converter que usan `ms-andesstay-report` y
`ms-andesstay-audit`.

**Alternativa no verificada.** El flujo *OAuth 2.0* propio de Postman (Authorization Code con PKCE)
exige registrar `https://oauth.pstmn.io/v1/callback` como redirect URI. Además, como la App
Registration es de tipo SPA, Entra ID exige una cabecera `Origin` en el canje del código (error
`AADSTS9002327`), y no se comprobó que Postman la envíe. Se recomienda el método de arriba.

---

## Endpoints

Base: `http://localhost:8086`. Todas las rutas exigen `Authorization: Bearer <token>` con el rol
**Admin**, salvo `GET /actuator/health`.

| Verbo | Ruta | Éxito | Errores propios |
|---|---|---|---|
| `GET` | `/api/v1/admin/exchanges` | `200` lista | |
| `GET` | `/api/v1/admin/exchanges/{nombre}` | `200` | `404` |
| `POST` | `/api/v1/admin/exchanges` | `201` + `Location` | `400`, `403`, `409` ya existe |
| `PUT` | `/api/v1/admin/exchanges/{nombre}` | `200` | `400`, `403`, `404` |
| `DELETE` | `/api/v1/admin/exchanges/{nombre}[?forzar=true]` | `204` | `403`, `404`, `409` tiene bindings |
| `GET` | `/api/v1/admin/queues` | `200` lista | |
| `GET` | `/api/v1/admin/queues/{nombre}` | `200` con conteo exacto | `404` |
| `POST` | `/api/v1/admin/queues` | `201` + `Location` | `400`, `403`, `404` el dead letter exchange no existe, `409` ya existe |
| `PUT` | `/api/v1/admin/queues/{nombre}` | `200` | `400`, `403`, `404`, `409` tiene mensajes o consumidores |
| `DELETE` | `/api/v1/admin/queues/{nombre}[?forzar=true]` | `204` | `403`, `404`, `409` tiene mensajes o consumidores |
| `GET` | `/api/v1/admin/bindings` | `200` lista | |
| `POST` | `/api/v1/admin/bindings` | `201` | `400`, `403`, `404` falta el exchange o el destino, `409` ya existe |
| `DELETE` | `/api/v1/admin/bindings?exchange=&destino=&tipoDestino=&routingKey=` | `204` | `400`, `403`, `404` |

Además, en todas: `401` sin token o con token inválido, `403` sin el rol Admin, `503` si el broker
no responde y `502` si el broker rechaza la operación.

Los ejemplos siguen un orden que se puede reproducir tal cual: primero los exchanges, porque la
cola usa uno como dead letter exchange, después la cola y al final el binding entre ambos. En los
ejemplos, `$TOKEN` es el token del paso anterior y `$API` es `http://localhost:8086/api/v1/admin`.

### Exchanges

#### Crear un exchange — `POST /exchanges`

| Campo | Obligatorio | Por omisión | Regla |
|---|---|---|---|
| `nombre` | Sí | | 1 a 255 caracteres: letras sin tilde, dígitos, `.` `_` `-` `:` |
| `tipo` | Sí | | `direct`, `topic` o `fanout` (sin distinguir mayúsculas) |
| `durable` | No | `true` | Sobrevive al reinicio del broker |
| `autoEliminar` | No | `false` | Se borra solo cuando pierde su último binding |

```bash
curl -s -X POST "$API/exchanges" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"nombre":"ex.demo","tipo":"topic"}'
```

`201 Created`, cabecera `Location: http://localhost:8086/api/v1/admin/exchanges/ex.demo`:

```json
{"nombre":"ex.demo","tipo":"topic","durable":true,"autoEliminar":false}
```

Para el ejemplo de la cola hace falta también su dead letter exchange:

```bash
curl -s -X POST "$API/exchanges" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"nombre":"ex.demo.dlx","tipo":"direct"}'
```

#### Consultar — `GET /exchanges` y `GET /exchanges/{nombre}`

```bash
curl -s "$API/exchanges/ex.demo" -H "Authorization: Bearer $TOKEN"
```

`200 OK`, con el mismo cuerpo que la creación. El listado no incluye el exchange por omisión.

#### Actualizar — `PUT /exchanges/{nombre}`

Reemplaza la definición completa: lo que no se envía vuelve a su valor por omisión. Recrea el
exchange y le devuelve los bindings que tenía, de origen y de destino.

```bash
curl -s -X PUT "$API/exchanges/ex.demo" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"tipo":"topic","durable":true}'
```

`200 OK` con la definición nueva. Lo que se publique en el instante entre la eliminación y la
recreación no tiene a dónde ir.

#### Eliminar — `DELETE /exchanges/{nombre}`

```bash
curl -s -X DELETE "$API/exchanges/ex.demo" -H "Authorization: Bearer $TOKEN" -w "%{http_code}\n"
```

`204 No Content`. Si el exchange tiene bindings, responde `409`:

```json
{"error":"RECURSO_EN_CONFLICTO",
 "mensaje":"El exchange 'ex.demo' tiene 1 binding(s) que se eliminarían con él. Use forzar=true para eliminarlo igual.",
 "timestamp":"2026-10-11T22:15:03.512Z"}
```

`DELETE $API/exchanges/ex.demo?forzar=true` lo elimina igual, junto con sus bindings.

### Colas

#### Crear una cola — `POST /queues`

| Campo | Obligatorio | Por omisión | Regla |
|---|---|---|---|
| `nombre` | Sí | | Igual que el de un exchange |
| `durable` | No | `true` | |
| `autoEliminar` | No | `false` | |
| `deadLetterExchange` | No | | Si se envía, no vacío y **debe existir**: RabbitMQ aceptaría uno inexistente y descartaría en silencio los mensajes rechazados |
| `deadLetterRoutingKey` | No | | Sin espacios. **Solo junto con `deadLetterExchange`** |
| `ttlMensajesMs` | No | sin límite | `0` a `4294967295` (máximo de RabbitMQ) |
| `maxMensajes` | No | sin límite | Mayor que `0` |

```bash
curl -s -X POST "$API/queues" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"nombre":"q.demo.email","deadLetterExchange":"ex.demo.dlx","deadLetterRoutingKey":"demo.dead","ttlMensajesMs":60000,"maxMensajes":1000}'
```

`201 Created`, cabecera `Location: http://localhost:8086/api/v1/admin/queues/q.demo.email`:

```json
{"nombre":"q.demo.email","durable":true,"autoEliminar":false,
 "argumentos":{"x-dead-letter-exchange":"ex.demo.dlx","x-dead-letter-routing-key":"demo.dead",
               "x-message-ttl":60000,"x-max-length":1000},
 "mensajes":0,"consumidores":0}
```

#### Consultar — `GET /queues` y `GET /queues/{nombre}`

```bash
curl -s "$API/queues/q.demo.email" -H "Authorization: Bearer $TOKEN"
```

`200 OK` con el cuerpo de arriba. En el detalle, `mensajes` y `consumidores` son exactos; en el
listado pueden ir unos segundos atrasados.

#### Actualizar — `PUT /queues/{nombre}`

Mismos campos que la creación, sin `nombre` (sale de la ruta). Reemplaza la definición completa.

```bash
curl -s -X PUT "$API/queues/q.demo.email" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"deadLetterExchange":"ex.demo.dlx","deadLetterRoutingKey":"demo.dead","ttlMensajesMs":120000}'
```

`200 OK` con la cola recreada y sus bindings restaurados. Si tiene mensajes, `409`:

```json
{"error":"RECURSO_EN_CONFLICTO",
 "mensaje":"La cola 'q.demo.email' tiene 3 mensaje(s): para actualizarla hay que recrearla y se perderían. Vacíela antes.",
 "timestamp":"2026-10-11T22:16:40.101Z"}
```

#### Eliminar — `DELETE /queues/{nombre}`

```bash
curl -s -X DELETE "$API/queues/q.demo.email" -H "Authorization: Bearer $TOKEN" -w "%{http_code}\n"
```

`204 No Content`. Con mensajes o consumidores responde `409` y pide `?forzar=true`.

### Bindings

Un binding no tiene nombre propio: lo identifican sus cuatro datos. Por eso no hay `GET` de detalle
y el `DELETE` los recibe como parámetros de consulta.

#### Crear un binding — `POST /bindings`

| Campo | Obligatorio | Por omisión | Regla |
|---|---|---|---|
| `exchange` | Sí | | Origen; debe existir |
| `destino` | Sí | | Cola o exchange de destino; debe existir |
| `tipoDestino` | No | `COLA` | `COLA` o `EXCHANGE` |
| `routingKey` | No | `""` | Sin espacios. Admite `*` y `#` en exchanges topic. Vacía es lo habitual en fanout |

```bash
curl -s -X POST "$API/bindings" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"exchange":"ex.demo","destino":"q.demo.email","routingKey":"demo.#"}'
```

`201 Created`:

```json
{"exchange":"ex.demo","destino":"q.demo.email","tipoDestino":"COLA","routingKey":"demo.#"}
```

#### Listar — `GET /bindings`

`200 OK` con una lista de objetos como el de arriba. No incluye los bindings implícitos del exchange
por omisión.

#### Eliminar — `DELETE /bindings`

```bash
curl -s -X DELETE "$API/bindings?exchange=ex.demo&destino=q.demo.email&tipoDestino=COLA&routingKey=demo.%23" \
  -H "Authorization: Bearer $TOKEN" -w "%{http_code}\n"
```

`204 No Content`. **El `#` va codificado como `%23`**: sin codificar, curl y el navegador lo toman
como inicio del fragmento y no lo envían. Si el binding no existe, `404`:

```json
{"error":"RECURSO_NO_ENCONTRADO",
 "mensaje":"No existe el binding 'ex.demo -> cola q.demo.email (routingKey='otra')'",
 "timestamp":"2026-10-11T22:18:02.734Z"}
```

---

## Errores

Todos los errores tienen la misma forma, la del esquema `Error` de los contratos del equipo:

```json
{"error":"CODIGO","mensaje":"Texto para una persona","timestamp":"2026-10-11T22:15:03.512Z"}
```

| Código HTTP | `error` | Cuándo |
|---|---|---|
| `400` | `SOLICITUD_INVALIDA` | Entrada inválida. Trae además `campos`, ver abajo |
| `401` | `TOKEN_INVALIDO` | Sin token, o vencido, con firma inválida, o de otro emisor o audiencia |
| `403` | `ROL_INSUFICIENTE` | Token válido sin el rol `Admin` |
| `403` | `OPERACION_NO_PERMITIDA` | Nombre reservado (`amq.*`) o [recurso protegido](#recursos-protegidos) |
| `404` | `RECURSO_NO_ENCONTRADO` | La cola, el exchange o el binding no existe |
| `409` | `RECURSO_EN_CONFLICTO` | Ya existe, o eliminarlo o recrearlo perdería mensajes, consumidores o bindings |
| `502` | `BROKER_RECHAZO` | RabbitMQ rechazó la operación (permisos, `PRECONDITION_FAILED`, credenciales) |
| `503` | `BROKER_NO_DISPONIBLE` | No se alcanza el broker por AMQP o la API de gestión |

### Errores de validación

Un `400` de validación **no llega a tocar el broker** y trae un error por cada campo o parámetro,
todos juntos, para corregirlos de una vez.

**Cola sin nombre** — `POST /queues` con `{"durable":true}`:

```json
{"error":"SOLICITUD_INVALIDA","mensaje":"La solicitud tiene un error de validación",
 "campos":[{"campo":"nombre","mensaje":"es obligatorio"}],
 "timestamp":"…"}
```

**Nombre con espacios o tildes** — `{"nombre":"cola de recepción"}`:

```json
{"campos":[{"campo":"nombre","mensaje":"solo admite letras sin tilde, dígitos y los caracteres . _ - :"}], …}
```

**Varios errores a la vez** — `{"nombre":"q.demo","ttlMensajesMs":-1,"maxMensajes":0}`:

```json
{"error":"SOLICITUD_INVALIDA","mensaje":"La solicitud tiene 2 errores de validación",
 "campos":[{"campo":"ttlMensajesMs","mensaje":"no puede ser negativo"},
           {"campo":"maxMensajes","mensaje":"debe ser mayor que cero: una cola de largo cero descartaría todo mensaje"}],
 "timestamp":"…"}
```

**Configuración incoherente** — `{"nombre":"q.demo","deadLetterRoutingKey":"demo.dead"}`, sin
`deadLetterExchange`. RabbitMQ la aceptaría e ignoraría la routing key, y quien la envió creería
tener una DLQ:

```json
{"campos":[{"campo":"deadLetterCoherente",
            "mensaje":"deadLetterRoutingKey solo tiene efecto junto con deadLetterExchange: envíe ambos o ninguno de los dos"}], …}
```

**Campo mal escrito** — `{"nombre":"q.demo","ttlMensajes":60000}`. Se rechaza en lugar de ignorarse,
porque ignorarlo crearía la cola sin el TTL pedido:

```json
{"campos":[{"campo":"ttlMensajes","mensaje":"campo desconocido; los válidos son [nombre, durable, …]"}], …}
```

**Tipo de exchange inválido** — `{"nombre":"ex.demo","tipo":"headers"}`:

```json
{"campos":[{"campo":"tipo","mensaje":"debe ser direct, topic o fanout"}], …}
```

**Binding incompleto** — `POST /bindings` con `{"routingKey":"a b","tipoDestino":"topic"}`:

```json
{"mensaje":"La solicitud tiene 4 errores de validación",
 "campos":[{"campo":"exchange","mensaje":"es obligatorio"},
           {"campo":"destino","mensaje":"es obligatorio"},
           {"campo":"tipoDestino","mensaje":"debe ser COLA o EXCHANGE"},
           {"campo":"routingKey","mensaje":"no puede contener espacios"}], …}
```

**Otros casos** que también responden `400` con `campos`: un valor de tipo incorrecto
(`"ttlMensajesMs":"un minuto"` → `tiene un valor de tipo incorrecto`), un parámetro obligatorio que
falta en `DELETE /bindings` (`es obligatorio`), `?forzar=quizas` (`no es válido; se esperaba
boolean`) y un nombre inválido en la ruta, por ejemplo `PUT /queues/cola%20mala`. Un JSON mal formado
responde `400` sin `campos`.

> El orden de los elementos de `campos` puede variar entre ejecuciones.

---

## Recursos protegidos

La topología de la que dependen `ms-andesstay-reservations` y `ms-andesstay-notify` no se puede
tocar desde el administrador, **ni siquiera con `forzar=true`**. Con los valores por omisión queda
protegido:

| Recurso | Nombres | Patrón |
|---|---|---|
| Exchanges | `cmd.direct`, `cmd.topic`, `cmd.dead.dlx` | `cmd.*` |
| Colas de trabajo | `q.cmd.email`, `q.cmd.housekeeping`, `q.cmd.voucher` | `q.cmd.*` |
| DLQ | `q.cmd.email.dlq`, `q.cmd.housekeeping.dlq`, `q.cmd.voucher.dlq` | `q.cmd.*` |

Son los nueve que declara `ms-andesstay-notify` en su `application.yml`. Un test
(`RabbitAdminApplicationTest`) comprueba los nueve contra los valores que de verdad carga la
configuración, así que si alguien cambia el patrón y deja uno fuera, el build falla.

**Qué se bloquea y qué no:**

| Operación | Resultado |
|---|---|
| Crear, modificar o eliminar una cola o exchange protegido | `403 OPERACION_NO_PERMITIDA` |
| Crear un binding **desde** un exchange protegido hacia una cola nueva | **Permitido**: solo agrega una copia de los mensajes |
| Crear un binding **hacia** una cola o exchange protegido | `403` |
| Eliminar un binding que sale de un exchange protegido o llega a un recurso protegido | `403` |
| Consultar (`GET`) | Permitido |

```json
{"error":"OPERACION_NO_PERMITIDA",
 "mensaje":"La cola 'q.cmd.email' coincide con el patrón protegido 'q.cmd.*' (andesstay.rabbitadmin.protegidos.colas): forma parte de la topología de la que dependen otros microservicios y no se puede eliminar desde el administrador",
 "timestamp":"…"}
```

**El `*` es un prefijo, no el comodín de AMQP.** Una entrada terminada en `*` abarca **cualquier
sufijo, con puntos incluidos**: `q.cmd.*` cubre `q.cmd.email` y también `q.cmd.email.dlq`. En un
binding topic, en cambio, `*` es exactamente una palabra; con esa semántica `cmd.*` no cubriría
`cmd.dead.dlx` ni `q.cmd.*` las tres DLQ. Una entrada sin `*` se compara de forma exacta.

Un nombre **nuevo** bajo el prefijo también queda protegido aunque hoy no exista: si notify agrega
`q.cmd.sms`, nace protegida.

**Cambiar la protección** exige cambiar `RABBITADMIN_COLAS_PROTEGIDAS` o
`RABBITADMIN_EXCHANGES_PROTEGIDOS` y reiniciar el servicio. Es intencional: no hay forma de
desactivarla por la API.

---

## Despliegue en AWS

> **No desplegado.** A la fecha, este servicio **no forma parte del despliegue en AWS**, y tampoco
> RabbitMQ ni `ms-andesstay-notify`. Lo que sigue describe cómo se haría; **no se ha ejecutado ni
> verificado**.

### Cómo se despliega hoy el resto del sistema

| Aspecto | Hoy |
|---|---|
| Infraestructura | Una instancia EC2 `t3.medium` (Ubuntu 24.04) con Elastic IP, creada por `infra/deploy/scripts/crear-infra.sh` con las credenciales personales del AWS Academy Learner Lab |
| Contenedores | `infra/deploy/compose.yml`: MySQL, reservations, catalog, report, audit, BFF y nginx con la SPA |
| Imágenes | Se construyen dentro de la instancia desde el `Dockerfile` de cada repositorio. No hay registro (ni ECR ni Docker Hub) |
| Entrada pública | Dos HTTP API de API Gateway, una con JWT Authorizer, que llegan a nginx y al BFF |
| CI/CD | No hay workflows de GitHub Actions |
| Secretos | `AndesStay/secrets/andesstay.env`, fuera de los repositorios. Lo genera `crear-infra.sh` y lo copia a la instancia `push-env.sh` |
| RabbitMQ | **No desplegado**. `infra/deploy/README.md`: "Kafka, Zookeeper y RabbitMQ no se despliegan: no caben". Reservations corre con `ANDESSTAY_EVENTS_RABBIT_ENABLED=false` |

Desplegar este servicio sin RabbitMQ en AWS no tiene sentido: no habría broker que administrar.

### Opciones

**A. Todo en la misma instancia.**

1. En `infra/deploy/compose.yml`: agregar un nodo de RabbitMQ, `notify` y `rabbitadmin`; poner
   `ANDESSTAY_EVENTS_RABBIT_ENABLED: "true"` y quitar `MANAGEMENT_HEALTH_RABBIT_ENABLED: "false"` en
   reservations.
2. Publicar `rabbitadmin` (8086) y la consola (15672) **solo en `127.0.0.1`**, y usarlos por túnel
   SSH:

   ```bash
   ssh -i ~/.ssh/andesstay-key.pem -L 8086:localhost:8086 -L 15672:localhost:15672 ubuntu@<ip>
   ```

3. En `crear-infra.sh`: generar `RABBITMQ_PASSWORD` junto a las de MySQL, y agregar
   `ms-andesstay-notify` y `ms-andesstay-rabbitadmin` a `REPOS` (también en `clonar-repos.sh` y
   `provision.sh`).
4. Desplegar con `bash scripts/crear-infra.sh --tipo t3.large`. Los contenedores actuales ya suman
   unos 3,2 GB de los 4 GB de una `t3.medium`, y estos tres agregan cerca de 1 GB.
   **Verificar antes que el Learner Lab permita `t3.large`.**

Queda un solo nodo de RabbitMQ, no el clúster de dos de `infra/mq`.

**B. Instancia `ec2-mq` aparte**, como planifica `infra/docs/validacion/rabbitmq.md` §8.8: clúster
de dos nodos y notify en una `t3.micro`, con su propio Security Group (5672 solo desde la instancia
de aplicaciones). Exige extender `crear-infra.sh` para una segunda instancia y mover las imágenes con
`docker save | docker load`, porque una `t3.micro` no alcanza a compilar Spring Boot. Bastante más
trabajo que la A.

### Qué hay que hacer a mano

| Dónde | Qué |
|---|---|
| AWS Learner Lab | *Start Lab*, pegar las credenciales en `credenciales-aws.txt` y ejecutar `crear-infra.sh`. El script crea el Security Group, la instancia, la IP y los API; no hace falta tocar la consola de AWS |
| GitHub | Nada: no hay secretos ni Actions que configurar |
| Entra ID | Nada nuevo. En un despliegue en una cuenta nueva, registrar los dos Redirect URI que imprime el script, como siempre |

---

## Limitaciones conocidas y mejoras futuras

- **Protección a nivel de broker (mejora futura).** Hoy la protección vive en este servicio: quien
  use otra vía (la consola, otro cliente con las mismas credenciales) puede tocar la topología. Lo
  robusto es darle al administrador un **usuario de RabbitMQ propio** cuyo permiso *configure* sobre
  el vhost excluya la topología, por ejemplo `^(?!q\.cmd\.|cmd\.).*`. Así el propio broker rechaza
  la operación. Requiere crear ese usuario en `infra` y gestionar una contraseña más. **No
  implementado ni verificado.**
- **Actualizar no es atómico.** Si el broker cae entre la eliminación y la recreación, el recurso
  puede quedar sin sus bindings. En ese caso el log del servicio deja escritos los bindings que
  faltó restaurar.
- **Sin argumentos de binding.** Por eso no se ofrecen exchanges `headers`, que enrutan solo por
  esos argumentos.
- **Un solo vhost.** El que indique `RABBITMQ_VHOST`.
- **Sin ruta en el API Gateway ni en el BFF.** Se usa directamente en el puerto 8086 (en local) o por
  túnel SSH. No tiene CORS configurado, porque no está pensado para llamarse desde el navegador.
- **Conteos del listado.** En `GET /queues` vienen de las estadísticas de la API de gestión, que se
  refrescan cada pocos segundos.
