# 🎬 ANIME - Plugin StreamCloud & CloudStream 3

Repositori resmi ekstensi streaming anime untuk **StreamCloud** dan **CloudStream 3**, dibuat oleh **Ans Rizal** (`@ansrizal`).

## ✨ Daftar Provider yang Tersedia:
- **Shokuja Anime** (`https://x6.shokuja.uk`): Streaming anime Subtitle Indonesia terlengkap dengan berbagai resolusi dan server video cepat.

---

## 📱 Cara Memasang Ekstensi di Aplikasi StreamCloud / CloudStream 3:

1. Buka aplikasi **StreamCloud** atau **CloudStream 3** di HP Android, Android TV, atau Tablet Anda.
2. Masuk ke **Pengaturan (Settings)** > **Ekstensi / Plugins / Extensions**.
3. Klik tombol **Tambah Repositori (Add Repository)**.
4. Masukkan salah satu link Raw Repository berikut:

```text
https://raw.githubusercontent.com/ansrizal/anime/main/plugins.json
```
*(atau jika menggunakan build branch)*:
```text
https://raw.githubusercontent.com/ansrizal/anime/build/plugins.json
```

5. Klik **Simpan & Sinkronisasikan**.
6. Provider **Shokuja Anime** akan otomatis muncul dan siap digunakan untuk menonton anime!

---

## ⚙️ GitHub Actions Auto-Build CS3:
Repositori ini telah dilengkapi dengan workflow CI/CD otomatis di `.github/workflows/build-cs3.yml`.
Setiap kali Anda melakukan `git push` ke branch `main`, GitHub Actions akan secara otomatis:
1. Memvalidasi file manifest dan kode JavaScript.
2. Mengompilasi dan mengemas file `.cs3` untuk setiap provider.
3. Menerbitkan rilis baru di GitHub Release.
4. Mengunggah file `plugins.json` terbaru ke branch `build`.
