# Perbaikan sokuja Anime dan Masalah Instalasi Modul

Rencana ini bertujuan untuk memperbaiki masalah "blank" pada provider sokuja dan mengatasi kegagalan instalasi pada 20 modul lainnya.

## User Review Required

> [!IMPORTANT]
> Saya akan mengubah `jvmTarget` dari `17` ke `1.8` pada semua modul untuk memastikan kompatibilitas maksimum dengan perangkat Android lama. Jika Anda secara khusus membutuhkan fitur Java 17, harap beri tahu saya.

## Masalah Teridentifikasi
1. **sokuja Blank:** Selektor HTML pada `sokujaProvider.kt` tidak sesuai dengan struktur website terbaru `x6.sokuja.uk`.
2. **Modul Gagal Install:**
   - Crash memory (OOM) saat proses build karena terlalu banyak modul (50 modul) yang berjalan secara paralel dengan alokasi RAM yang besar.
   - Ketidakkonsistenan format blok `cloudstream` di `build.gradle.kts`.
   - Penggunaan `jvmTarget` 17 yang mungkin tidak didukung oleh beberapa versi runtime CloudStream atau perangkat lama.

## Perubahan yang Diusulkan

### [Core Configuration]

#### [MODIFY] [gradle.properties](file:///D:/PROGRAM/streamcloud/REPOSITORI STREAMCLOUD ANSRIZAL/gradle.properties)
- Mengurangi penggunaan memori dan membatasi worker parallel untuk mencegah crash native memory.
- Mengubah `-Xmx` menjadi nilai yang lebih stabil.

### [sokuja Provider]

#### [MODIFY] [sokujaProvider.kt](file:///D:/PROGRAM/streamcloud/REPOSITORI STREAMCLOUD ANSRIZAL/sokujaProvider/src/main/kotlin/com/ansrizal/anime/sokujaProvider.kt)
- Memperbarui selektor CSS untuk `getMainPage` dan `search`.
- Memperbaiki ekstraksi judul dan poster.

### [Build & Compatibility Fix (Global)]

#### [MODIFY] [build.gradle.kts (Semua Modul)](file:///D:/PROGRAM/streamcloud/REPOSITORI STREAMCLOUD ANSRIZAL/)
Saya akan memperbarui file build pada modul-modul yang bermasalah (terutama yang ditambahkan terakhir):
- Mengubah `jvmTarget` ke `1.8`.
- Mengubah `compileSdk` ke `34` (lebih stabil daripada 35 untuk saat ini).
- Menyamakan format blok `cloudstream` agar sesuai dengan versi plugin terbaru.

## Rencana Verifikasi

### Manual Verification
- Pengguna dapat mencoba melakukan build ulang dengan `./gradlew clean assembleDebug`.
- Membuka sokuja di aplikasi CloudStream untuk memastikan konten muncul.
