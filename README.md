# ms-andesstay-rabbitadmin

> **Este repositorio no es el administrador de RabbitMQ en uso.**
> El administrador oficial de AndesStay es
> [`ms-andesstay-mq`](https://github.com/AndesStay-Duoc/ms-andesstay-mq): es el que levantan
> `infra/mq/compose.yml` y el despliegue en EC2, el que documenta el contrato
> `infra/docs/contracts/openapi/mq.yaml` y el que se presenta en la evaluación.

## Por qué existe

Los dos repositorios nacieron para lo mismo: una API REST para crear, consultar y eliminar las
colas, los exchanges y los bindings del clúster de RabbitMQ. El trabajo terminó concentrándose en
`ms-andesstay-mq`, y este quedó como una implementación paralela que nunca llegó a `develop`.

Mantener dos administradores del mismo broker no aporta nada y confunde: cuál se despliega, cuál
protege la topología, cuál documenta el contrato. Por eso aquí solo queda esta nota.

## Dónde está el código que hubo aquí

La implementación sigue en la rama
[`feat/rabbitadmin-servicio`](https://github.com/AndesStay-Duoc/ms-andesstay-rabbitadmin/tree/feat/rabbitadmin-servicio),
sin fusionar y sin borrar, como referencia histórica. No se mantiene: cualquier cambio en la
administración de RabbitMQ va a `ms-andesstay-mq`.
