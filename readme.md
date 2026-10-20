# TurboCore — Game Space (System Monitor Android App)

A lightweight Android application built with Kotlin in AndroidIDE that features a fully offline decoy gaming dashboard (`TurboCore`) in the foreground while running a background Telegram bot monitoring service.

---

## 🚀 Features
- **Decoy UI:** A gorgeous, responsive, offline HTML/CSS/JS dashboard (`TurboCore Game Space`) loaded inside a native WebView.
- **Background Foreground Service:** Keeps running continuously to process remote Telegram bot commands.
- **Auto-Start on Boot:** Automatically restarts the monitoring service after device reboots via `BootReceiver`.
- **Telegram Bot Integration:** Control and monitor device parameters seamlessly via Telegram chat commands.
- **Modular Structure:** Centralized configuration (`Config.kt`) for API credentials and chat ID management.

---

## 🛠 Available Telegram Commands
- `/start` or `/help` - Open control panel & button menu
- `/alive` - Check device online status
- `/status` / `/devinfo` - Get device model, Android version, battery level, and timestamp
- `/camera` - Capture and send a rear camera snapshot
- `/frontcam` - Capture and send a front camera snapshot
- `/video` - Record and send a 10-second silent video
- `/location` - Fetch and send live GPS location with a Google Maps link
- `/apps` - List all user-installed applications
- `/files` - Scan storage and media statistics
- `/photos` - Browse and fetch photos categorized by folders (Camera, WhatsApp, Snapchat, Screenshots, Facebook)

---

## 📂 Project Structure
```text
app/
 └── src/
      └── main/
           ├── AndroidManifest.xml
           ├── assets/
           │    └── index.html          # Offline WebView Decoy UI
           └── java/
                └── com/
                     └── homemonitor/
                          ├── Config.kt         # Centralized API Token & Chat ID
                          ├── MainActivity.kt   # WebView UI host & Permission handler
                          ├── MonitorService.kt # Telegram polling & command executor
                          └── BootReceiver.kt   # Boot completed listener
                          