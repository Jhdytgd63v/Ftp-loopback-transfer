# Ftp-loopback
Ftp loopback untuk transfer file antar ruang sandbox.

## Build

### Local Build
```bash
./gradlew assembleDebug
```

### GitHub Actions
Proyek ini dikonfigurasi untuk build otomatis di GitHub Actions dengan workflow berikut:

- **Android CI** (`android-build.yml`): Build debug APK saat push/PR ke branch main
- **Android Build & Test** (`android-ci.yml`): Build debug & release APK + run tests saat push ke main/develop
- **Build Android APK** (`build-apk.yml`): Build debug APK (dapat dijalankan manual via workflow_dispatch)

APK hasil build dapat didownload dari tab "Actions" di GitHub repository.

