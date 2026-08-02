# STS Device Bridge

Android app that bridges a USB thermal receipt (ESC/POS) printer to a local HTTP server. Keep the app open on your device and send print jobs by posting raw ESC/POS data to the print endpoint.

**Powered by santoserve**

---

## What it does

- Detects and connects to the first available USB printer (ESC/POS thermal receipt printer).
- Runs a local HTTP server on **port 12345**.
- Accepts raw ESC/POS bytes via **POST** and sends them to the printer.

---

## Print API

**Endpoint:** `http://localhost:12345/print`  
**Method:** `POST`  
**Body:** Raw ESC/POS bytes (e.g. receipt text and commands).

### From the same device (e.g. Chrome on the Android device)

```javascript
fetch("http://localhost:12345/print", {
  method: "POST",
  headers: { "Content-Type": "application/octet-stream" },
  body: "\x1B\x40Hello Receipt\n\n\x1D\x56\x00"
}).then(r => r.json()).then(console.log)
```

### From another machine on the same network

Use the Android device’s IP address instead of `localhost`:

```
http://<device-ip>:12345/print
```

Example: `http://192.168.1.100:12345/print`

### Response

- **Success:** `{ "status": "printed" }`
- **No printer:** `{ "status": "error", "message": "printer not connected" }`
- **Other errors:** `{ "status": "error", "message": "..." }`

---

## Requirements

- Android device with **USB host** support (or USB OTG).
- Supported **thermal receipt (ESC/POS)** printer connected via USB.
- Keep **STS Device Bridge open** (do not close or swipe away); if the app is closed, printing stops.

---

## Build & install

1. Clone the repo and open in Android Studio (or use the command line).
2. (Optional) Add your app icon as `app/img/icon-512x512.png` (see `app/img/README.txt`).
3. Build and run:
   - **Debug:** `./gradlew installDebug`
   - **Release APK:** `./gradlew assembleRelease`  
     Output: `app/build/outputs/apk/release/app-release.apk`
   - **Release AAB:** `./gradlew bundleRelease`  
     Output: `app/build/outputs/bundle/release/app-release.aab`

GitHub Actions can build the app: see [.github/workflows/build-android.yml](.github/workflows/build-android.yml). The workflow produces **app-release.apk** and **app-release.aab** as artifacts.

---

## First run

1. Install the app and open **STS Device Bridge**.
2. Connect the USB printer and tap **Allow** when asked for USB access.
3. Leave the app on the landing page (or in the background). The server runs while the app is open.
4. Send POST requests to `http://localhost:12345/print` (or `http://<device-ip>:12345/print` from another machine) with your ESC/POS data in the body.

---

## Project structure

- `MainActivity.kt` – UI, server startup, USB permission.
- `UsbPrinterManager.kt` – USB connection and `print(data: ByteArray)`.
- `PrintServer.kt` – NanoHTTPD server, `POST /print` → `UsbPrinterManager.print()`.

---

## License

See repository or project settings.
