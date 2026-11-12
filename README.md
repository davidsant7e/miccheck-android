# MicCheck

<p align="center"><img src="docs/carta.svg" alt="XX · El Juicio" width="180"></p>


App Android que revisa si el micrófono del celular funciona bien y da sugerencias cuando no.

## Qué hace

- **Elegir el micrófono**: Automático, el del celular, audífonos con cable, USB o **Bluetooth**. Con Bluetooth la app abre el «modo llamada» del audífono (perfil HFP/SCO), que es el único en el que Android deja usar su micrófono, y lo cierra al terminar. Avisa si Android terminó grabando con otro micrófono. La elección se recuerda.
- **Diagnóstico completo** (unos 20 s, 3 pasos):
  1. *Ruido de fondo*: el usuario guarda silencio y se mide el ruido.
  2. *Tu voz*: el usuario habla; se mide cuánto sobresale la voz del ruido y si hay saturación.
  3. *Parlante*: suena un pitido de 1 kHz y se mide si el micrófono lo capta (algoritmo de Goertzel).
  Al final muestra cada prueba con ✅ ⚠️ ❌ ℹ️ y qué hacer.
- **Medidor en vivo**: barra de nivel con pico retenido; avisa de silencio absoluto, de saturación y de que otra app tiene el micrófono.
- **Grabar y escuchar**: graba 5 s y los reproduce.
- **Tu equipo**: entradas de audio, audífonos/Bluetooth, micrófonos físicos, efectos (eco, ruido, ganancia), volumen en cero.

El audio solo se analiza en memoria: no se guarda ni se envía.

## Abrir el proyecto

1. Descomprimir en `C:\Git\miccheck-android`.
2. Android Studio → **File → Open** → elegir la carpeta `miccheck-android`.
3. Esperar el *Gradle sync* (cerrar el navegador para ahorrar RAM).
4. Elegir el **motorola edge 50 fusion** en el selector de dispositivos y pulsar **Run ▶**.

Pruebas unitarias: clic derecho en `app/src/test` → *Run 'Tests in…'*, o en PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat test
```

## Estructura

```
app/src/main/java/com/dseu1/miccheck/
├── MainActivity.kt            Única Activity; arranca Compose.
├── audio/
│   ├── LevelMath.kt           Cálculos: RMS, pico, saturación, Goertzel, promedio en dB. Sin Android.
│   ├── AudioFrame.kt          Bloque de 100 ms con sus niveles.
│   ├── MicCapture.kt          AudioRecord como Flow (graba mientras se recolecta), con micrófono preferido.
│   ├── MicKind.kt             Tipos de micrófono y la opción elegida. Sin Android.
│   ├── MicInputs.kt           Micrófonos conectados (AudioManager.getDevices).
│   ├── MicRoute.kt            Abre/cierra el camino al micrófono (Bluetooth: modo llamada).
│   ├── PcmPlayer.kt           AudioTrack: reproduce PCM y genera el tono.
│   ├── EnvironmentInfo.kt     Datos del entorno (clase simple).
│   └── AudioEnvironment.kt    Lee el entorno desde AudioManager.
├── diagnosis/
│   └── Diagnosis.kt           Mediciones → resultados y sugerencias. Umbrales en `Thresholds`.
└── ui/
    ├── MainViewModel.kt       Estado de las pantallas y coordinación del audio (MVVM).
    ├── MicCheckApp.kt         Elige pantalla: permiso, inicio o diagnóstico.
    ├── components/LevelMeter.kt
    ├── screens/               PermissionScreen, HomeScreen, DiagnosisScreen.
    └── theme/Theme.kt         Material 3 con colores dinámicos (Android 12+).
```

## Versiones

AGP 9.4.0 (Kotlin integrado 2.4.10), Gradle 9.6.0, Compose BOM 2026.09.00, compileSdk 37, targetSdk 36, minSdk 26. Sin Hilt ni Room: no hacen falta todavía.

## Estado

- Primera versión escrita sin compilar en Android Studio. La lógica (`LevelMath`, `Diagnosis`) pasa 16 pruebas unitarias; la capa de audio y el ViewModel se verificaron contra la API de Android 16. Las pantallas Compose se revisan en el primer build.
- Los umbrales (`Thresholds` en `Diagnosis.kt`) son estimados: hay que ajustarlos probando en el Motorola y en el Redmi 9.
- Los niveles son dBFS, no decibeles SPL calibrados.
- La app no puede cambiar ajustes del sistema ni la ganancia del hardware: solo sugiere.

## Ideas siguientes

- Probar cada micrófono físico por separado (`AudioRecord.setPreferredMicrophoneDirection` / `setPreferredDevice`).
- Guardar el historial de diagnósticos para comparar.
- Pasar los textos a `strings.xml` (traducciones).
