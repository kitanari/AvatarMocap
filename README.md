# AvatarMocap: tracking facial para VTubers en Android

**Fase 1 (experimental): solo cara + lengua.** Después vienen manos y cuerpo.

La app usa la cámara frontal del teléfono y envía los datos a la PC por Wi-Fi:

| Salida | Funciona con | Modelos |
|---|---|---|
| **Protocolo iFacialMocap** (UDP 49983) | VSeeFace, VNyan, Warudo, Webcam Motion Receiver, VBridger, Blender (Faceit) | VRM / 3D |
| **API de plugins de VTube Studio** (WebSocket 8001) | VTube Studio (PC) | Live2D |

## Qué tiene que no tiene iFacialMocap en Android

- **Detección de lengua** (`tongueOut`) con un detector propio. MediaPipe no detecta la lengua. Además se calcula la **dirección de la lengua** (`AMTongueX/Y`) para VTube Studio.
- **Calibración neutral**: resta tu cara en reposo y la inclinación del teléfono.
- **Auto-rango**: aprende hasta dónde llega cada gesto en tu cara y lo estira hasta 100%, para que parpadeos y sonrisas lleguen al máximo.
- **Filtro One Euro** con suavizado ajustable: elimina el temblor sin agregar retraso cuando te movés rápido.
- **Los 52 blendshapes ARKit crudos** como parámetros personalizados en VTube Studio (`AMjawOpen`, `AMmouthPucker`...), para riggear al estilo VBridger.
- Se puede invertir cada eje e intercambiar izquierda y derecha desde la app.

## Arquitectura

```
CameraX (frontal, 640x480, RGBA)
   └─► FaceTracker (MediaPipe Face Landmarker: 478 puntos + 52 blendshapes + matriz de pose)
          └─► TongueDetector (visión clásica sobre el recorte de la boca)
                 └─► Calibration (neutral + auto-rango)
                        └─► OneEuroFilter
                               └─► FaceFrame ─┬─► IFacialMocapServer (UDP)
                                              └─► VTubeStudioPlugin (WebSocket)
```

```
app/src/main/java/com/avatarmocap/
├── MainActivity.kt / TrackingViewModel.kt
├── camera/CameraSource.kt
├── tracking/  ArKit, FaceTracker, TongueDetector, HeadPose, Calibration, OneEuroFilter, TrackingPipeline
├── output/    IFacialMocapServer, VTubeStudioPlugin, OutputSink
├── settings/  Settings
└── ui/        MainScreen (Jetpack Compose)
tools/ifm_receiver_test.py   ← receptor de prueba para la PC
```

Para agregar otro protocolo, como **VMC/OSC**, se implementa `OutputSink` y se lo registra en `TrackingViewModel.rebuildSinks`.

## Compilar

1. Instalá **Android Studio** (Ladybug 2024.2 o más nuevo).
2. *File → Open* → elegí la carpeta `AvatarMocap`.
3. Conectá el teléfono con la **depuración USB** activada y tocá ▶ *Run*.

El modelo `face_landmarker.task` (~3.7 MB) **se descarga solo** la primera vez que se compila (tarea `downloadFaceModel`). Si no hay internet, bajalo a mano desde
`https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task`
y guardalo en `app/src/main/assets/`.

Requisitos: Android 8.0 (API 26) o más nuevo. Se recomienda un teléfono de gama media o alta del 2020 en adelante para mantener 30 fps o más con GPU.

## Uso

**Primero, calibrá:**
1. Apoyá el teléfono fijo, a la altura de los ojos y a unos 40-60 cm.
2. **Calibrar neutral**: cara relajada mirando a la cámara.
3. **Lengua**: tocá *1. Boca abierta* (abrí la boca sin sacar la lengua) y después *2. Lengua afuera*. El recorte de depuración muestra en **verde** lo que se detecta como lengua.
4. Hacé tus expresiones al máximo un par de veces para que el auto-rango aprenda.

### VSeeFace / VNyan / Warudo (modelos VRM)
- La PC y el teléfono tienen que estar **en la misma red Wi-Fi**.
- En la PC, elegí la fuente **iFacialMocap** y escribí la **IP del teléfono** que muestra la app.
- En VSeeFace: configuración general → opción de tracking con iPhone → elegí iFacialMocap.
- Si la PC no se conecta sola, poné la IP de la PC en "IP fija de la PC" dentro de la app.
- Para que la lengua se vea, tu VRM tiene que tener un blendshape `tongueOut` (perfect sync / ARKit).

### VTube Studio (modelos Live2D)
1. En VTube Studio (PC): *Ajustes → Start API (allow plugins)* activado, puerto 8001.
2. En la app: activá *Salida VTube Studio* y tocá **Buscar VTube Studio en la red**, o escribí la IP de la PC.
3. Aceptá el popup **"AvatarMocap quiere conectarse"** en VTS.
4. En la configuración del modelo, asigná los parámetros: `TongueOut`, `MouthOpen`, `FaceAngleX`... o los personalizados `AMtongueOut`, `AMTongueX`, `AMjawOpen`, etc.

> Tu modelo Live2D tiene que tener un parámetro de lengua riggeado para que se vea.

### Probar sin VSeeFace
```bash
python tools/ifm_receiver_test.py 192.168.1.50   # IP del teléfono
```

## Problemas comunes

| Síntoma | Solución |
|---|---|
| La cabeza gira al revés | "Invertir giro (yaw)" / "pitch" / "roll" |
| Guiñar el ojo derecho cierra el izquierdo | "Intercambiar izquierda/derecha" |
| La lengua se activa sola | Recalibrá con más luz de frente; evitá luz roja o cálida |
| La lengua no se detecta | Sacala más hacia abajo; mirá el recorte verde |
| VTS no conecta | Firewall de Windows: permitir VTube Studio en redes privadas |
| Pocos fps | Bajá la resolución en `CameraSource` (p.ej. 480x360) |

## Plan

- [x] **Fase 1 – Cara (experimental)**: 52 blendshapes, pose de cabeza, ojos, lengua heurística, iFacialMocap, VTS.
- [ ] **Fase 1b – Mejor lengua**: grabar recortes de boca desde la app → entrenar un clasificador/regresor TFLite chico (MobileNetV3-small, 64x64) → reemplazar la heurística. Agregar mejillas infladas (`cheekPuff`) por geometría, ya que MediaPipe la detecta mal.
- [ ] **Fase 1c**: salida **VMC protocol** (OSC), servicio en primer plano para trackear con la pantalla apagada y perfiles por avatar.
- [ ] **Fase 2 – Manos**: MediaPipe Hand Landmarker (21 puntos por mano) → rotaciones de dedos → VMC (`/VMC/Ext/Bone/Pos`) y parámetros de manos de VTS. Probablemente con un segundo teléfono o la cámara trasera, porque la frontal ve poco las manos.
- [ ] **Fase 3 – Cuerpo**: MediaPipe Pose Landmarker (33 puntos) → IK del torso y brazos → VMC.
- [ ] **Otras plataformas**: extraer el núcleo (calibración, filtros, protocolos) a Kotlin Multiplatform y hacer iOS y escritorio.
