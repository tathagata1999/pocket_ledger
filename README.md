# Pocket Ledger — Android MVP

A privacy-first personal expense tracker built with Kotlin, Jetpack Compose, Room, and Android Keystore.

## Open in Android Studio

Open this folder in Android Studio. Android Studio, Java, Gradle, and the Android SDK are not currently detected on this computer. Install Android Studio from https://developer.android.com/studio, then install Android SDK Platform 36 when prompted. This project uses JDK 17 and Android Gradle Plugin 8.13.2 / Gradle 8.13.

## Current scope

- Monthly spending dashboard and recent transaction list
- Manual expense entry in Indian rupees
- Amounts stored as integer paise
- Room database stores transaction fields as AES-GCM ciphertext; the non-exportable AES key is held in Android Keystore
- No SMS permission, OTP, network access, or cloud sync
- No backup of the local database; losing the app's Keystore key means the encrypted data cannot be recovered

## Next implementation steps

1. Add editing/deletion, category summaries, month navigation, and robust key/data recovery design.
2. Add SMS ingestion only after validating Android permission and distribution requirements.
3. Add authentication only if a backend or cross-device sync is introduced.

This is an MVP scaffold. It has not been build-verified because Android Studio/JDK/Android SDK are not installed on this computer yet.
