# Prácticas SIP

## Contenidos
- [Prácticas SIP](#prácticas-sip)
  - [Contenidos](#contenidos)
  - [Ejecución](#ejecución)
  - [Plan de pruebas](#plan-de-pruebas)
    - [Entrega 1](#entrega-1)


## Ejecución

**UA**
```bash
java UA <usuarioSIP> <puertoUA> <ipProxy> <puertoProxy> <debug:true|false> <expires_seg>
```

**Proxy**
```bash
java Proxy <puertoProxy> <loose-routing:true|false> <debug:true|false>
```

## Plan de pruebas

### Entrega 1

| Arranque y REGISTER | Implementación |
|---------------------|-|
|El REGISTER se reenvía cada 2 segundos hasta recibir respuesta del proxy | |
|Si el usuario no está en la lista de usuarios permitidos se recibe un 404 | |
|Si el usuario sí está en la lista de usuarios permitidos se recibe un 200 | |
|Si un usuario se registra una segunda vez se actualiza su dirección SIP de registro a la última, borrándose la primera | |

|INVITE y BYE (sin Loose Routing)| Implementación |
|--------------------------------|-|
|Un INVITE recibe un 404 si A o B no están registrados| |
|Un proxy devuelve un 100 al recibir un INVITE si no está procesando una transacción  y un 503 si ya lo está| |
|Un UA al recibir un INVITE devuelve un 486 si el usuario está en llamada o un 180 si no lo está| |
|Un UA muestra por pantalla que ha llegado una nueva llamada al enviar el 180 al otro extremo y  Permite que el usuario acepte o rechace la llamada| |
|Se implementa el temporizado que a los 10 segundos sin responder genera un 408| |
|Los UA mantienen el estado de la llamada | |
|Los UAs imprimen por pantalla los cambios de estado en la máquina de estados del INVITE| |
|Los proxies mantienen el estado para las 2 conexiones de la transacción de INVITE e imprimen por pantalla los cambios| |
|Se implementan los temporizadores en estado “completed” tanto en máquina de estados llamante como llamada,  Tanto en proxy como en UA| |
|El proxy añade o elimina las Vias en todos los mensajes| |
|Cuando la llamada se cuelga en el llamado se invierten el To y el From y se cambia el destination  Del BYE respecto al del INVITE| |
|No hay 2 invocaciones de métodos SIP con el mismo CSeq| |
|No hay 2 llamadas con el mismo CallID| |
|El proxy procesa el Max-forwards| |
|El cerrar y volver a abrir el UA o el proxy no genera excepciones en las otras aplicaciones ni al volver a arrancarlas| |
|Si se pierde un mensaje 4xx o 5xx se reenvía al cabo de 200ms| |
|Si se pierde un ACK respuesta a un error, se recibe de nuevo el error al cabo de 200ms| |
|Si se cierra el proxy y se vuelve a arrancar, las llamadas de los usuarios previamente registrados reciben un 404| |
|Las trazas se muestran para todos los mensajes enviados y recibidos de acuerdo con si el modo debug está o no habilitado| |
|Se puede hacer una segunda llamada tras colgar la primera| |
|La aplicación funciona ejecutando los UAs y el proxy en máquinas distintas| |
