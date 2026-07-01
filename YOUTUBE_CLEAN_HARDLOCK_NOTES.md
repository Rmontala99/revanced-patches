# YouTube Clean HardLock

Sistema de autocontrol añadido sobre el fork `app.morphe` de ReVanced. No incluye
ad-block, bypass de premium, ni ninguna otra funcionalidad fuera de las
restricciones del proyecto. No usa permisos Android peligrosos.

## Componentes

### 1. Temporizador obligatorio (`hardlock/TimerManager.java`, `hardlock/TimerPatch.kt`)

- Al abrir la app sin sesión activa, muestra un selector de tiempo (5/10/15/30/60 min).
- Al agotarse la sesión, entra en cooldown obligatorio (mapeo 5→60min, 10→60min,
  15→60min, 30→120min, 60→180min) y envía al Home.
- `TimerManager.initialize(Context)` se inyecta en `Application.onCreate` mediante
  `TimerPatch.kt`, reutilizando el fingerprint `applicationInitHook` (el mismo que
  usa el hook de contexto de la extensión) e insertando una llamada en el índice 1,
  justo después de que se establece el contexto en el índice 0.

### 2. Lista prohibida irreversible (`hardlock/BanListManager.java`, `hardlock/BanListPatch.kt`)

- Lista de términos/canales sólo puede crecer (`addTerm`), nunca se pueden borrar
  entradas.
- Se inyecta en `KeywordContentFilter` (`extensions/.../patches/components/KeywordContentFilter.java`):
  `parseKeywords()` fusiona `Settings.HIDE_KEYWORD_CONTENT_PHRASES` con
  `BanListManager.getBanListPhrases()`, y `hideKeywordSettingIsActive()` fuerza el
  filtrado activo si la lista tiene entradas, independientemente de los toggles
  de home/search/subscriptions.
- `VideoInformation.setVideoInformation()` llama a `BanListManager.onChannelDetected()`
  al final; si el canal del vídeo abierto coincide con un término prohibido, se
  envía al Home (bloquea el acceso directo aunque no pase por el feed/búsqueda).
- Anti-tamper: fail-closed. Si el HMAC de la lista no verifica, `isTermBanned()`
  devuelve `true` para todo (bloquea todo hasta resolver la corrupción).

### 3. Permanent Lock (`hardlock/PermanentLockManager.java`, `hardlock/PermanentLockPatch.kt`)

- `PermanentLockManager.activate(context, keys...)` congela settings por clave,
  de forma append-only (una clave bloqueada nunca se puede desbloquear).
- `Setting.java` expone un mecanismo genérico `SaveInterceptor` (interfaz +
  lista estática + hook en `save()`, línea ~402) que no depende de ningún
  paquete específico de YouTube.
- `PermanentLockManager.initialize(context)` registra un `SaveInterceptor` que
  bloquea el guardado cuando `newValue == false` y la clave está en
  `isLocked()`, mostrando un toast. Esto sólo bloquea la dirección "menos
  restrictiva"; los ajustes bloqueados se pueden seguir guardando en su
  valor restrictivo.
- Anti-tamper: fail-closed. Si el HMAC no verifica, `isLocked()` devuelve
  `true` para cualquier clave.
- `PermanentLockManager.HARD_LOCKED_SETTINGS` fija en `true` y bloquea permanentemente:
  - Shorts: `HIDE_SHORTS_SHELF`, `HIDE_SHORTS_NAVIGATION_BAR`, `HIDE_SHORTS_TOOLBAR`.
  - Feed/recomendaciones: `HIDE_NAVIGATION_HOME_BUTTON` (quita la pestaña Home),
    `HIDE_RELATED_VIDEOS` (quita vídeos relacionados/recomendados bajo el player).
  - Autoplay: `HIDE_PLAYER_AUTOPLAY_BUTTON`, `HIDE_AUTOPLAY_PREVIEW`.

  **Limitación conocida:** este fork no expone ningún `Setting` que controle el
  interruptor nativo de "reproducción automática" de YouTube (ese toggle vive en
  las preferencias propias de la app, fuera del sistema `Setting`/`SharedPrefCategory`
  de ReVanced). Lo que se bloquea aquí es la UI relacionada (el botón de autoplay
  en el reproductor y la previsualización del siguiente vídeo), no la reproducción
  automática en sí. Si se necesita bloquear el comportamiento real, hace falta un
  patch nuevo que intercepte el valor nativo de autoplay.

## Almacenamiento y anti-tamper

- `HardLockPrefs.java` centraliza dos SharedPreferences privadas
  (`ytch_timer`, `ytch_hardlock`) y helpers HMAC-SHA256 con clave derivada de
  un UUID de instalación (sin permisos Android adicionales).
- Todo valor sensible (fin de sesión, cooldown, lista prohibida, settings
  bloqueados) se guarda junto con su HMAC. Si al leer el HMAC no coincide,
  el dato se trata como manipulado y cada componente falla en modo cerrado
  (bloquea en vez de permitir).

## Wiring en Application.onCreate

Los tres patches (`TimerPatch`, `BanListPatch`, `PermanentLockPatch`) dependen de
`sharedExtensionPatch` (para que las clases de `hardlock/` se incluyan en el dex
fusionado) e insertan cada uno una única instrucción `invoke-static` en el índice 1
del método fingerprint de `applicationInitHook`. El orden relativo entre estos tres
patches no importa: cada inserción en el índice 1 empuja hacia abajo lo que ya
hubiera ahí, así que las tres llamadas terminan siempre después de que el contexto
se establece en el índice 0.

Cada uno tiene título propio ("HardLock: Timer", "HardLock: Ban list",
"HardLock: Permanent lock") para que morphe-cli los liste y se puedan
seleccionar explícitamente con `-e "HardLock: Timer"` etc. al parchear
(a diferencia de `sharedExtensionPatch`, que no tiene título porque solo
se usa como dependencia transitiva).

## Pendiente / fuera de alcance de esta sesión

- No hay UI dentro de los ajustes de YouTube para añadir términos a la lista
  prohibida ni para activar el Permanent Lock; se invoca programáticamente
  (`BanListManager.addTerm(...)`, `PermanentLockManager.activate(...)`) desde
  donde se decida integrarlo (por ejemplo, un `StringSetting` dedicado en una
  futura sesión).
- No se ha compilado el proyecto completo (requiere el toolchain de Android/Gradle
  con el plugin `app.morphe.patches` y la APK base de YouTube).
