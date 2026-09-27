# Quasar ⚡

Transferencia de archivos entre teléfonos Android por **Wi-Fi Direct** con respaldo **Bluetooth**.
Rápida, sin internet, con estética espacial estilo SpaceX.

> Antes se llamaba **DrexShare** (v1.0 / v1.1). Desde la v2.0 la app se llama **Quasar**.

## Funciones

- Wi-Fi Direct como ruta principal + Bluetooth como respaldo
- Selección múltiple con explorador propio: miniaturas de fotos y videos, filtros por tipo, tamaño y fecha
- TCP optimizado: buffers grandes, hasta 4 streams paralelos en archivos grandes, velocidad real en MB/s
- Onboarding con slides en la primera apertura
- Navegación inferior: Enviar · Recibir · Historial
- Historial de transferencias (enviados y recibidos)
- Animaciones: radar de descubrimiento, fondo estrellado con estrellas fugaces, celebración de éxito con partículas
- Archivos recibidos en `Descargas/Quasar`

## Compilar

```bash
./build.sh
```

Compilación con `aapt2` + `javac` + `d8` + `zipalign`, sin dependencias externas.
Firmar el APK con tu propio keystore:

```bash
apksigner sign --ks tu-keystore.jks --out Quasar-2.0.apk Quasar-2.0-unsigned.apk
```

> El keystore **no** se guarda en este repo por seguridad.

## Uso

1. Instala Quasar en ambos teléfonos y concede los permisos.
2. Emisor: pestaña **Enviar** → elige archivos → toca el receptor en el radar.
3. Receptor: pestaña **Recibir** y mantén la app abierta.

## Notas

- Android 8.0 (API 26) o superior.
- Wi-Fi Direct puede variar entre fabricantes; Bluetooth es más lento pero funciona como respaldo.
- No probado teléfono a teléfono en laboratorio (sin hardware Android en el servidor).
