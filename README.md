<div align="center">

# ⚡ TWEAKORA
### More Control. More Power.

**An advanced, transparent Android device control centre featuring a Liquid Glass UI, real hardware diagnostics, reversible system tweaks, gaming telemetry, and root-level tools.**

[![Download Latest APK](https://img.shields.io/github/v/release/shahalamlaskarofficial/Tweakora?label=Download%20Latest%20APK&style=for-the-badge&color=9F7AEA)](https://github.com/shahalamlaskarofficial/Tweakora/releases/latest)
[![Build Status](https://img.shields.io/github/actions/workflow/status/shahalamlaskarofficial/Tweakora/build-apk.yml?style=for-the-badge&label=Cloud%20Build&color=38BDF8)](https://github.com/shahalamlaskarofficial/Tweakora/actions)
![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-34D399?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-4F8FF7?style=for-the-badge&logo=kotlin&logoColor=white)

</div>

---

## 🎯 Core Philosophy

Tweakora is engineered around **transparency and real telemetry**—zero fake toggles, zero fabricated stats, and zero background battery drain. Every control adapts automatically to what your device supports across a **3-tier privilege engine**:

| Access Tier | How It Works | What It Unlocks |
| :--- | :--- | :--- |
| **Standard SDK** | Works out of the box on all Android 8.0+ phones | System DNA, Live Monitor, Benchmarks, Touch Hz & FPS Analyzer, Ping/Jitter Lab, RAM & Junk Cleaner, Smart Charge Guard, Privacy Auditor, Tweakora AI, Smart Rules, Time Machine Snapshots |
| **Shizuku (No Root)** | Uses [Shizuku](https://shizuku.rikka.app/) ADB shell bridge | Direct applying & reverting of System/Global/Secure tweaks (animations, refresh rates, Cached Apps Freezer, Private DNS, Phantom Process control), deep RAM trim & cache trim |
| **Root (`libsu`)** | Supports Magisk, KernelSU & APatch | Full Root Laboratory, CPU Scaling Governors, Kernel VM Swappiness, Hardware PMIC Bypass Charging, Kernel Profile Vault, Root Module Scanner & Boot Script Auditor |

---

## ✨ Key Features

### 🧊 Liquid Glass Interface & Battery Eco Design
* **GPU-Accelerated Sliding Dock:** Smooth floating capsule navigation bar and segmented tabs powered by `graphicsLayer` spring physics.
* **Eye-Comfort Themes:** Switch live between **Liquid Dark**, **AMOLED**, and **Frosted Light** modes with 4 curated accents (*Soft Violet, Ocean Cyan, Nordic Blue, Warm Amber*).
* **0% Background Drain:** Static hardware specs are cached once at startup, live polling defaults to **4s Eco Mode** (configurable: 2s / 4s / 8s / Manual), and monitoring pauses automatically when minimized.
* **Default Control App Support:** Set Tweakora as your Default Digital Assistant in Android Settings to launch it from anywhere.

### 🧬 System DNA & Hardware Inspector
* Detects exact **SoC, CPU cores & live clocks, GPU renderer/OpenGL via EGL14, RAM, Storage, Display resolution & refresh rate, physical sensors, Android patch level, and Kernel version**.
* One-tap **Export Complete Device DNA** report to clipboard.

### 🎛️ Tweak Library (24 Controls) & System Time Machine
* **24 Real System Tweaks** across UI, Performance, Gaming, Display, Battery, Thermal, Background, Storage, Network, Privacy, and Root categories.
* Automatic **Compatibility Detection**, **Conflict Warnings**, **Original-Value Preservation**, and **1-Tap Undo History**.
* **System Time Machine:** Capture baseline configuration snapshots, view side-by-side **Config Diffs**, restore settings, and **Export/Import JSON backups**.

### 🎮 Gaming Centre & Network Stability Lab
* **Game Library & Profiles:** Auto-detects installed games (plus manual app addition) with per-game *Competitive*, *Balanced*, and *Battery* profiles.
* **Game Session Autopsy:** Tracks live session duration, battery delta, thermal curve, and bottlenecks, with side-by-side **Session Comparison**.
* **Touch Response Inspector:** Interactive hardware digitizer pad measuring real live & peak **Touch Sampling Rate (Hz)** and input latency (ms).
* **FPS & Frame-Time Analyzer:** Uses Android `Choreographer` to measure real average FPS, 1% low FPS, and live frame pacing.
* **Network Stability Lab:** Real 10-packet TCP probe measuring **Average/Min/Max Latency, Jitter, and Packet Loss**, alongside Wi-Fi link speed, RSSI, and 2.4/5 GHz band inspection.

### 📊 System Monitor, Performance, Battery & Thermal Labs
* **Performance Consistency Test:** Real 6-pass multi-core SHA-256 compute benchmark measuring raw score, sustained stability %, throttling drop-off, and temperature rise.
* **Bottleneck Detector & Optimization Simulator:** Identifies live RAM, Storage, and Thermal bottlenecks and previews configuration trade-offs while clearly separating **Measured** vs **Estimated** metrics.
* **Battery & Thermal Intelligence:** Live current (mA), wattage (W), voltage (mV), health status, **Battery Detective**, and Linux `/sys/class/thermal` zone scanning.

### 🧹 Cleaner, Bypass Charging, Privacy & Automation
* **RAM & Phone Junk Cleaner:** Safely trims background processes with real MB freed measurements, and scans app caches, `.tmp`/`.log`/`.apk` leftovers, and empty folders with mandatory confirmation before deletion.
* **Hardware Bypass Charging + Smart Charge Guard:** Probes Linux PMIC charging switches (OPLUS/OPPO, Qualcomm, MediaTek, Samsung, OnePlus) on rooted phones, and offers an honest **Smart Charge Guard** alert (75%–90% limit + heat warning) on all unrooted phones.
* **App Manager & Privacy Exposure Score:** Search, launch, inspect, or uninstall apps while auditing granted Camera, Mic, GPS, Contacts, SMS, and Call Log permissions.
* **Tweakora Intelligence (Offline AI) & Smart Rules:** On-device rule engine for thermal, battery, charging, and RAM alerts plus plain-English diagnostics.
* **Update Impact Tracker:** Logs Android OS, security patch, and kernel changes over time.

### 🧪 Root Laboratory
* Dedicated advanced hub featuring **CPU Scaling Governor** controls, **VM Swappiness**, **Kernel Profile Vault**, **Magisk/KernelSU Module Scanner**, **Boot Script Auditor (`service.d`)**, and searchable **`getprop` System Property Viewer**.

---

## 📥 Installation

1. Go to the **[Latest Release](https://github.com/shahalamlaskarofficial/Tweakora/releases/latest)** page.
2. Download **`Tweakora.apk`**.
3. Open the downloaded file on your Android device and allow *"Install from unknown sources"* if prompted.
4. *(Optional)* Install and start **Shizuku** (no root needed) or grant **Superuser** access inside Tweakora to unlock system-level tweaks.

---

## 🛠️ Tech Stack

* **Language:** Kotlin 2.0
* **UI:** Jetpack Compose + Material 3 (Custom Liquid Glass Engine)
* **Concurrency:** Kotlin Coroutines
* **Navigation:** Jetpack Navigation Compose
* **Privilege Bridges:** `dev.rikka.shizuku` (Non-Root Shell) & `com.github.topjohnwu.libsu` (Root)
* **CI/CD:** Automated cloud APK compilation & publishing via GitHub Actions

---

## 👨‍💻 Developer

Built by **Shahalam Laskar**

* **Email:** [shahalamlaskar.official@gmail.com](mailto:shahalamlaskar.official@gmail.com)
* **GitHub:** [@shahalamlaskarofficial](https://github.com/shahalamlaskarofficial)

> *Tweakora preserves Android security, device stability, and hardware thermal protections. Use advanced and root-level controls responsibly.*
