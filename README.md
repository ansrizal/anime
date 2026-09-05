# 🎬 ANIME - Plugin StreamCloud & CloudStream 3

Repositori resmi ekstensi streaming anime untuk **StreamCloud** dan **CloudStream 3**, dibuat oleh **Ans Rizal** (`@ansrizal`).

## ✨ Daftar Provider yang Tersedia:
### Anime & Streaming
- **sokuja Anime** : Streaming anime Subtitle Indonesia terlengkap.
- **Animasu**, **AnimeIndo**, **Anichin**, **Alqanime**, **AnimeSail**, **Animexin**, **Anoboy**, **Donghub**, **Hanime**, **Kuramanime**, **Kuronime**, **Nekopoi**, **Nimegami**, **NontonAnimeID**, **Otakudesu**, **Samehadaku**.

### Movies & TV Series
- **Filmkita**, **Filmlokal**, **Indomax**, **IndoMax21**, **Idlix**, **KlikXXi**, **LayarAsia**, **LayarKaca**, **LayarWarna**, **Moviebox**, **Ngefilm**, **NgeFilm21**, **Nomat**, **Oploverz**, **OppaDrama**, **Pahe**, **PencuriMovie**, **Pusatfilm**, **Pusatmovie**, **Rebahin**, **Sarangfilm**, **Savefilm**, **SemiRebahin**, **WGFilm21**.

### Special / Others
- **Drakor**, **Dubbindo**, **HidoriStream**, **JavHey**, **Kawanfilm**, **KlikxxiProvider**.

---

## 📱 Cara Memasang Ekstensi di Aplikasi StreamCloud / CloudStream 3:

1. Buka aplikasi **StreamCloud** atau **CloudStream 3** di HP Android, Android TV, atau Tablet Anda.
2. Masuk ke **Pengaturan (Settings)** > **Ekstensi / Plugins / Extensions**.
3. Klik tombol **Tambah Repositori (Add Repository)**.
4. Masukkan salah satu link Raw Repository berikut:

**Link Rekomendasi (Otomatis Update):**
```text
https://raw.githubusercontent.com/ansrizal/anime/builds/repo.json
```

*(Atau link alternatif jika yang di atas bermasalah):*
```text
https://raw.githubusercontent.com/ansrizal/anime/builds/plugins.json
```

5. Klik **Simpan & Sinkronisasikan**.
6. Provider **sokuja Anime** akan otomatis muncul dan siap digunakan untuk menonton anime!

---

## ⚙️ GitHub Actions Auto-Build:
Repositori ini telah dilengkapi dengan workflow CI/CD otomatis di `.github/workflows/build.yml`.
Setiap kali Anda melakukan `git push` ke branch `main`, GitHub Actions akan secara otomatis:
1. Memvalidasi file manifest dan kode JavaScript.
2. Mengompilasi dan mengemas file `.cs3` untuk setiap provider.
3. Menerbitkan rilis baru (jika dikonfigurasi).
4. Mengunggah file `plugins.json` dan `repo.json` terbaru ke branch `builds`.
