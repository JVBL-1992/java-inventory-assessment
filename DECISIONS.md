# Decisiones y supuestos

## Contrato y reglas de negocio

- El paquete `com.store.inventory.api` y la firma original de `Inventory.create` se conservan.
- Cada pedido tiene una sola reserva. Un reenvío con el mismo pedido, SKU y cantidad devuelve la reserva original, incluso si ya expiró o se confirmó. No extiende el plazo ni vuelve a descontar unidades. Para volver a comprar tras expirar hay que usar un pedido nuevo.
- Una clave de idempotencia queda asociada permanentemente al pedido. Reutilizar una clave o un pedido con otros datos falla con `IllegalArgumentException`. Un pedido puede tener varias claves, todas con los mismos datos. No se persisten errores del negocio, por lo que una petición fallida puede intentarse nuevamente tras reabastecer.
- Confirmar dos veces es idempotente; confirmar un pedido desconocido o expirado falla. En el instante exacto del vencimiento la reserva ya está expirada.
- `stock` representa unidades disponibles. Reservar lo disminuye; expirar lo aumenta; confirmar conserva el descuento. Los cambios de estado impiden devolver las unidades dos veces.
- Repetir el registro con la misma categoría es idempotente. Cambiar una categoría existente se rechaza para no alterar reglas de pedidos en curso.
- Las políticas se inyectan mediante `CategoryPolicy` e `InventoryOptions`, sin condicionales por categoría dentro del flujo. Agregar categorías nuevas al enum requiere una evolución del contrato original, que esta entrega tiene prohibido modificar.
- Registrar un producto con cero unidades no envía avisos. Añadir existencias o reservar con disponibilidad <= 5 crea un aviso. Cada reabastecimiento positivo reinicia el aviso; si sigue habiendo <= 5 se avisa de nuevo. Expirar una reserva no reinicia el aviso porque no es reabastecimiento.
- Se valida cantidad positiva, identificadores de 1 a 200 caracteres y overflow incluyendo unidades reservadas pendientes de devolver.

## Persistencia y concurrencia

Se usa JDBC con H2. La fábrica original crea una base en memoria aislada por servicio. Una conexión de mantenimiento conserva esa base mientras vive el servicio. La fábrica configurable permite compartir una URL H2 en memoria entre instancias de la misma JVM, usar una base H2 en archivo o conectar instancias de procesos separados a un servidor H2. Dos URLs de memoria iguales en JVM distintas **no** comparten datos.

Cada transacción toma `SELECT ... FOR UPDATE` sobre una fila de control. Esto serializa cambios, lecturas con expiración, claves de idempotencia, cuota y reclamación de avisos en todas las instancias que usan la misma base. Las claves primarias y restricciones de la base complementan el bloqueo. El código usa aislamiento READ_COMMITTED y realiza el callback de correo fuera de la transacción.

El bloqueo global se eligió para hacer explícita y comprobable la consistencia de esta entrega pequeña; limita el throughput y aumenta p99 con contención. Antes de producción, sustituirlo por bloqueos por SKU y por clave/pedido con un orden de adquisición definido, o actualizaciones condicionales y control optimista. Habría que probar deadlocks, errores de serialización y carreras contra el motor elegido. H2 es el único motor validado, no se promete portabilidad SQL directa a PostgreSQL/MySQL.

Referencia del motor: [bloqueos y timeouts de H2](https://h2database.com/html/features.html).

## Reintentos

Reintentos de transacciones JDBC con número máximo configurable (3 intentos por defecto, máximo 10), backoff exponencial y jitter completo, limitado a 1 segundo por espera. Se reintenta solamente SQLState `40001`, `HYT00` y el error de timeout de bloqueo H2 `50200`, después de hacer rollback. Los fallos del negocio, rate limiting, problemas de conexión y rollback fallido no se reintentan. La interrupción conserva el indicador del hilo y termina la operación.

La cuota se confirma en una transacción separada antes de intentar la reserva: los reintentos internos de la reserva no vuelven a cobrar cuota. Una desconexión durante commit puede dejar un resultado incierto; no se reintenta automáticamente. El cliente puede reenviar `reserve` con la misma clave para recuperar el resultado. `addStock` carece de clave en el contrato original: no es seguro reenviarlo tras un resultado incierto; para producción debe tener su propio comando idempotente.

## Rate limiting

Ventana fija global para llamadas a `reserve`, almacenada en la misma base y compartida por instancias. Los reenvíos idempotentes y rechazos del negocio consumen cuota; los intentos internos JDBC no. Al agotarla se lanza `RateLimitExceededException` con `retryAfter`. La fábrica original lo deja desactivado (`requestsPerWindow = 0`) para conservar el contrato sin imponer una cuota arbitraria. La fábrica configurable lo activa con un valor positivo.

Todas las instancias de una base deben usar las mismas políticas, cuota, ventana y relojes sincronizados. No existe identidad de usuario en este contrato, por eso la cuota no es por cliente. Antes de exponer HTTP: autenticación, límites por cliente y globales, mapear cuota a 429/Retry-After e idempotencia a una cabecera `Idempotency-Key`. La ventana fija admite ráfagas en sus límites; si el tráfico lo requiere, sustituir por token bucket o ventana deslizante.

## Avisos y expiración

El aviso se guarda en un outbox en la misma transacción que el cambio de inventario. Un lease de 60 segundos y un token de reclamación permiten despachadores concurrentes; el token impide que un trabajador viejo borre una reclamación nueva. Un fallo del listener conserva el evento y no transforma una reserva ya confirmada en un error. `dispatchAlerts()` permite volver a intentar avisos pendientes y limita cada lote a 100.

La garantía es **al menos una vez**, porque un proceso puede caer después de enviar el correo y antes de borrar el evento, o un callback puede durar más que el lease. El contrato del listener no recibe ID del evento y no permite garantizar exactamente una vez en el destino. No se generan eventos nuevos repetidos antes de un reabastecimiento. Para producción: consumidor con deduplicación por ID de evento, renovación del lease, backoff de entrega, límite de intentos, dead-letter queue y trazabilidad de errores.

La disponibilidad y mutaciones procesan expiraciones de forma perezosa; también existe `expireReservations()` para ejecución periódica por un scheduler externo. No se crean hilos de fondo ocultos. Antes de producción agregar workers de expiración y outbox con intervalos, lifecycle y observabilidad definidos. Una transacción rechazada revierte también sus expiraciones: una lectura posterior u otro worker las materializa sin afectar la corrección de disponibilidad.

## P99 y validación

`metrics()` devuelve llamadas, fallos, reintentos JDBC, fallos de avisos y p99 en milisegundos. Se mide con `System.nanoTime()`, independiente del reloj de negocio. P99 usa nearest rank sobre las últimas 4096 llamadas medidas por instancia, incluyendo validación, espera de bloqueo, reintentos y despacho automático del listener. Es una ventana de muestras limitada, no un histograma agregado ni un SLO. Se mezclan operaciones; `dispatchAlerts` explícito y consulta de métricas no se incluyen.

`mvn test` verifica el contrato original, categorías y plazos, límite FLASH_SALE, validación, idempotencia y conflictos, expiración exacta, confirmación repetida, avisos/reabastecimiento, entrega fallida y recuperación, cuota compartida, carreras entre instancias, lock timeout real, reintentos acotados, overflow y cálculo de p99. La prueba concurrente imprime p99 observado por instancia; los números dependen del equipo y no son un benchmark de producción ni un umbral de aceptación.

Antes de producción: histogramas por operación/resultados, exportación a un backend de métricas, carga sostenida con llegadas controladas, warmup, pool JDBC, límites de concurrencia, timeouts por operación, SLO acordado y pruebas sobre una base externa compartida por procesos distintos.

## Fuera de alcance

No se agrega servidor HTTP, proveedor de correo, despliegue ni infraestructura externa: el repositorio es una biblioteca con un contrato Java. No hay migraciones versionadas, autenticación, manejo de secretos, limpieza de pedidos/claves antiguos, ni garantía de entrega exactamente una vez. Inicializar tablas con `CREATE TABLE IF NOT EXISTS` basta para una base nueva de ejercicio; usar migraciones para despliegues existentes.

El servicio configurable implementa `AutoCloseable`; cerrarlo libera la conexión de mantenimiento. La app debe cerrarlo al finalizar y detener primero los workers. Las reservas, claves y outbox sólo sobreviven al cierre total cuando se usa persistencia en archivo/servidor; una base en memoria desaparece al cerrarse su última conexión. No se ha creado un PR remoto: el workspace no contiene un repositorio Git propio del proyecto.
