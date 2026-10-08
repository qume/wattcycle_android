# Wattcycle Android 🔋

A simple, open-source, no-nonsense Android app for monitoring **Wattcycle / XDZN LiFePO4 batteries** over Bluetooth (BLE).

No accounts, no cloud, no ads, and no manual pairing required—just open the app and it lists all nearby Wattcycle batteries with real-time stats.

---

## 📥 Download the App

👉 **[Download the Latest Android Release (.apk)](https://github.com/qume/wattcycle_android/releases/latest)**

Click on **`app-debug.apk`** under the **Assets** section at the bottom of the release page to download it to your phone.

---

## 📲 How to Install on Your Android Phone

Because this app is open source and distributed directly via GitHub instead of the Google Play Store, Android will show a standard safety prompt. Here is how to install it in a few seconds:

1. **Download the file:** Tap the link above, tap the latest release, and download **`app-debug.apk`**.
2. **Handle the warning:** 
   - Your browser (Chrome, Firefox, etc.) will likely show: *"File might be harmful. Do you want to download app-debug.apk anyway?"*
   - Tap **"Download anyway"**.
3. **Open the downloaded file:**
   - Tap the download notification, or open your phone's **Files / Downloads** app and tap `app-debug.apk`.
4. **Allow installation from unknown sources:**
   - If prompted with *"For your security, your phone is not allowed to install unknown apps from this source"*, tap **Settings**.
   - Toggle **"Allow from this source"** (or tap **"Install anyway"** / **"More details -> Install anyway"**).
5. **Install & Open:** Tap **Install**, then tap **Open**!
6. **Permissions:** Grant the requested Bluetooth & Location permissions when prompted so your phone can scan for nearby BLE devices.

---

## ✨ Features

- **Auto-Discovery:** Automatically detects all visible Wattcycle / XDZN batteries within Bluetooth range.
- **Essential Metrics at a Glance:**
  - **State of Charge (SOC %)**
  - **Voltage (V)**
  - **Current & Power (A / W)** with clear Charging / Discharging / Idle indicators
  - **Temperature (°C)**
- **Diagnostic Log Viewer:** Includes an in-app log viewer with a **"Copy Logs"** button so you can easily copy and share debug info if anything unexpected happens.
- **100% Local & Private:** No telemetry, no background tracking, and no internet access required.

---

## 🛠 Related Projects

- **[qume/wattcycle_ble](https://github.com/qume/wattcycle_ble)** - Standalone Python library, reverse-engineered BLE protocol documentation, and CLI tools for Wattcycle batteries.

---

## 📄 License

This project is open-source under the [MIT License](LICENSE).
