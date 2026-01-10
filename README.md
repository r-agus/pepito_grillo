# Práctica SIP

## Contenidos
- [Práctica SIP](#práctica-sip)
  - [Contenidos](#contenidos)
  - [Ejecución](#ejecución)
  - [Plan de pruebas](#plan-de-pruebas)
    - [Entrega 1](#entrega-1)
    - [Entrega 2](#entrega-2)
    - [Entrega 3](#entrega-3)


## Ejecución

Se proporcionan 4 ficheros que lanzan las aplicaciones:

- `ejecutar_tests.sh`: Lanza una batería de tests que prueban las filas de la autoevaluación (ver [plan de pruebas](#plan-de-pruebas))
- `lanzar_alice.sh`: Lanza un UA, alice, escuchando en el puerto 4000. Espera un proxy en la dirección localhost:5002. Debug activado
- `lanzar_bob.sh`: Lanza un UA, bob, escuchando en el puerto 5000. Espera un proxy en la dirección localhost:5002. Debug desactivado
- `lanzar_proxy`: Escucha el puerto 5002. Debug activado, loose-routing activado.

Para ejecutar de forma manual, ejecutar desde la consola

**UA**
```bash
java -jar artifacts/ua.jar <usuarioSIP> <puertoUA> <ipProxy> <puertoProxy> <debug:true|false> <expires_seg>
```

El proxy admite cualquier usuario en la lista: `"alice", "bob", "mario", "boss", "charlie", "u1", "u2"`. Además, se pueden añadir otros usuarios al fichero `users.xml`. La lógica de la aplicación permite dar de alta nuevos usuarios sin añadir un Servlet (útil para pruebas).

**Proxy**
```bash
java -jar artifacts/proxy.jar <puertoProxy> <loose-routing:true|false> <debug:true|false>
```

**Pruebas**
```bash
java -jar artifacts/tests.jar
```

> **Nota importante:**
> Para que los test se ejecuten correctamente es necesario situarse en el **directorio raíz del proyecto**.
> Si el test no es capaz de instanciar al proxy / ua, todos los test fallan.

## Plan de pruebas

### Entrega 1

| Funcionalidad | Implementación |
|---|---|
| **Arranque y REGISTER** | |
| El REGISTER se reenvía cada 2 segundos hasta recibir respuesta del proxy | `testRegisterRetransmission` |
| Si el usuario no está en la lista de usuarios permitidos se recibe un 404 | `testRegisterNotFound` |
| Si el usuario sí está en la lista de usuarios permitidos se recibe un 200 | `testRegisterSuccess` |
| Si un usuario se registra una segunda vez se actualiza su dirección SIP de registro a la última, borrándose la primera | `testDuplicateRegistration` |
| **INVITE y BYE (sin Loose Routing)** | |
| Un INVITE recibe un 404 si A o B no están registrados | `testInviteNotRegistered` |
| Un proxy devuelve un 100 al recibir un INVITE si no está procesando una transacción  y un 503 si ya lo está | `test100Trying` |
| Un UA al recibir un INVITE devuelve un 486 si el usuario está en llamada o un 180 si no lo está | `testUABusy` |
| Un UA muestra por pantalla que ha llegado una nueva llamada al enviar el 180 al otro extremo y permite que el usuario acepte o rechace la llamada | `testCallSuccess` |
| Se implementa el temporizado que a los 10 segundos sin responder genera un 408 | `testInviteTimeout` |
| Los UA mantienen el estado de la llamada | `testSequentialCalls` |
| Los UAs imprimen por pantalla los cambios de estado en la máquina de estados del INVITE | Checked in logs |
| Los proxies mantienen el estado para las 2 conexiones de la transacción de INVITE e imprimen por pantalla los cambios | Checked in logs |
| Se implementan los temporizadores en estado “completed” tanto en máquina de estados llamante como llamada, tanto en proxy como en UA | Verified in call flows |
| El proxy añade o elimina las Vias en todos los mensajes | `testViaHeaders` |
| Cuando la llamada se cuelga en el llamado se invierten el To y el From y se cambia el destination del BYE respecto al del INVITE | `testByeHeaderInversion` |
| No hay 2 invocaciones de métodos SIP con el mismo CSeq | `testCSeqIncrements` |
| No hay 2 llamadas con el mismo CallID | `testCallIdUniqueness` |
| El proxy procesa el Max-forwards | `testMaxForwards` |
| El cerrar y volver a abrir el UA o el proxy no genera excepciones en las otras aplicaciones ni al volver a arrancarlas | `testProxyRestartState` |
| Si se pierde un mensaje 4xx o 5xx se reenvía al cabo de 200ms | (Verified by design/manual) |
| Si se pierde un ACK respuesta a un error, se recibe de nuevo el error al cabo de 200ms | (Verified by design/manual) |
| Si se cierra el proxy y se vuelve a arrancar, las llamadas de los usuarios previamente registrados reciben un 404 | `testProxyRestartState` |
| Las trazas se muestran para todos los mensajes enviados y recibidos de acuerdo con si el modo debug está o no habilitado | `enableDebug()` checks |
| Se puede hacer una segunda llamada tras colgar la primera | `testSequentialCalls` |
| La aplicación funciona ejecutando los UAs y el proxy en máquinas distintas | Verified by Architecture |

### Entrega 2

| Funcionalidad | Implementación |
|---|---|
| **Loose Routing** | |
| Si el modo loose routing está activo se añade la cabera record-route al INVITE y la cabecera route al ACK del 200 y al BYE y si no lo está no se añaden | `testLooseRouting` |
| Si el modo loose routing está activo los mensajes ACK y BYE pasan por el proxy y si no lo está van extremo a extremo | `testLooseRouting` |
| Si hay loose routing y ya hay una llamada establecida entre A y B y se intenta una llamada de C a A o B recibe un 503 del proxy | `testBusyCallBlocking` |
| Los proxies eliminan la cabecera route (su contenido) en el ACK y el BYE si hay loose routing | `testLooseRouting` |
| **Otras** | |
| La aplicación está correctamente comentada usando comentarios en Java donde sea necesario | Verified Manual |
| Se hace un control de errores como controlar el número y formato de los argumentos por línea de comandos o que la entrada por teclado sea válida | Verified Manual |
| Se tiene implementada la opción de salir de la aplicación por teclado en el UA | Verified Manual |
| Se pueden hacer tantas llamadas secuenciales entre los usuarios del sistema sin que ninguna aplicación se quede colgada | `testSequentialCalls` |
| Se gestionan diferentes mensajes de error en función de la causa que los origina | All Error Tests |
| Se muestran trazas por pantalla de lo que ocurre dentro de la invocación del doInvite | Verified Logs |
| Se implementan varios Servlets para varios usuarios | `testServletBlocking` |
| Se detectan errores en los datos en los ficheros xml | Verified Manual |

### Entrega 3

| Funcionalidad | Implementación |
|---|---|
| **SIP Servlet Engine** | |
| Ante un INVITE se instancia el Servlet del llamado o llamante apropiadamente | `testServletBlocking` |
| El proxy es capaz de leer el resultado de la invocación del doInvite al finalizar este | `testServletBlocking` |
| Se instancian apropiadamente la clase SipServletResponse o ProxyImpl del SipServletRequest | `testServletBlocking` |
| Se controla el que A pueda llamar a B en función de sus URIs y la hora del día | `testServletBlocking` |
| Se permite ejecutar un servicio que redirija las llamadas que se realizan a B a un tercer usuario C | `testRedirection` |
| Se invoca primero el Servlet del llamado, en su defecto el del llamante y si ninguno de los dos tiene se procesa la llamada como si no hubiera Servlet Engine | `testJuanaCall` |
| **Parsing XML** | |
| Se lee apropiadamente el fichero de usuarios | `testJuanaCall` |
| **Llamada** | |
| Se establece la comunicación de voz entre A y B arrancando correctamente vitext-server y vitext-client tanto en el llamante como en el llamado | `testVitextLaunch` |
| Se finaliza correctamente la ejecución de vitext-server y vitext-client con el BYE | `testSequentialCalls` |

