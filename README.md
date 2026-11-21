# MoonyFace

Seguimiento facial para VTubers en Android: tu teléfono rastrea tu cara y la envía por Wi-Fi a tu
programa de avatar en la PC (modelos **VRM 3D** y **Live2D**).

## Qué hace

- 52 gestos faciales + giro de cabeza + mirada (iris) + **lengua** + **cachetes inflados**.
- **Calibración guiada** de las expresiones básicas de VRM (neutral, parpadeo, A I U E O, alegría,
  enojo, tristeza, diversión, sorpresa) en 5 ángulos de cabeza, con flechas en pantalla.
- **Ajuste por gesto** (reposo / máximo / intensidad) y **perfiles** por persona o avatar.
- Envío a **60 fps** con interpolación, exposición medida sobre la cara, cámara frontal o trasera.
- Interfaz en **español e inglés**.

## Salidas

| Salida | Puerto | Para |
|---|---|---|
| Rostro (UDP) | 49983 | Programas de avatar 3D que reciben tracking facial desde un teléfono |
| Expresiones VRM (OSC) | 39539 | Clips de expresión del modelo VRM (A, I, U, E, O, Blink, Joy…) + `TongueOut` |
| Live2D (WebSocket, API de plugins) | 8001 | Programas Live2D con API de plugins |

## Compilar

GitHub Actions compila el APK en cada push (pestaña **Actions → artefacto `AvatarMocap-apk`**).
Localmente: abrir la carpeta en Android Studio y ▶ Run. El modelo de rostro se descarga solo la
primera vez que se compila.

## Estructura

```
app/src/main/java/com/avatarmocap/
├── camera/     cámara (2 hilos, triple buffer, exposición por la cara)
├── tracking/   malla, calibración por ángulos, lengua, cachetes, pose rígida de cabeza, VRM
├── output/     salidas (rostro, expresiones VRM, Live2D) + envío a 60 fps
├── i18n/       español / inglés
└── ui/         interfaz (tema violeta, cara 3D, pestañas)
```

## Plan

- [x] Fase 1 – Cara
- [ ] Fase 2 – Manos
- [ ] Fase 3 – Cuerpo
- [ ] Otras plataformas
