# ClipKu v1.0.0 — BUILD NOTES

**Status: SOURCE ONLY. APK BELUM SIAP.**
Tidak ada APK yang diklaim siap sampai GitHub Actions hijau DAN
gerbang keystore + gerbang sertifikat lolos DAN release `latest`
terverifikasi. Jangan install apa pun dari lampiran chat — pengiriman
APK hanya lewat link release GitHub langsung, dengan ukuran byte
yang dicocokkan.

Source lengkap ada di `source/` di folder ini (isi folder itu yang
di-push ke root repo, BUKAN folder `source/` itu sendiri, supaya
workflow ada di `.github/workflows/` repo).

- Package: `com.daftech.clipku`, nama app ClipKu (provisional sampai
  Putra konfirmasi nama; rename package nanti harus mekanis — semua
  referensi package terpusat di `app/build.gradle` + `AndroidManifest.xml`
  + nama folder `java/com/daftech/clipku/`).
- Versi: 1.0.0, versionCode 1.
- Template: AGP 8.5.2, Kotlin 1.9.25, compileSdk/targetSdk 34,
  minSdk 24, JDK 17, Gradle 8.7, Compose BOM 2024.06.00,
  media3 PIN 1.4.1 (1.5.x butuh compileSdk 35 — pelajaran 2026-10-17).

## Scope yang dikunci (gas 2026-11-04 14:30 WIB) — dan hanya ini

- Tempel link YouTube -> APK menonton/menganalisa video panjang ->
  auto highlight beberapa clip ±1 menit -> suguhkan sebagai clip video
  yang bisa di-preview.
- Download PER CLIP atas tap user ke Downloads/ClipKu, MP4, rasio asli,
  default 720p. Tidak ada auto-download semua, tidak ada auto-publish.
- Auto highlight ONLY. Tidak ada mode cari topik/keyword.
- Tidak ada edit kreatif: tidak ada transisi, teks, musik, filter,
  reframe vertikal, voiceover, atau thumbnail yang dibakar.
  Potong di batas highlight, snap ke batas kalimat (hasil 50-70 detik
  masih dihitung ±1 menit), adalah ekstraksi.
- Narasi per clip, on-demand, gaya Nalar (kritis, independen, cerdas,
  antimainstream, mencerdaskan): teks FINAL siap copas ke SuaraKu,
  110-140 kata, plain text tanpa markdown/label/timestamp/emoji/URL/
  arahan panggung. Tombol Salin hanya menyalin blok final itu.
  APK TIDAK generate audio sendiri.
- Groq API key diisi user di Settings in-app, tidak pernah di-hardcode,
  tidak pernah di chat/file.

Versi timestamp-only dari audit 13:33 WIB 2026-11-04 SUDAH DIBATALKAN
oleh klarifikasi Putra 13:41 WIB dan TIDAK berlaku lagi.

## Engine: pitched vs implemented

### 1. Stream/metadata — DITURUNKAN, dengan alasan (bukan diam-diam)
- Pitched: NewPipeExtractor utama via JitPack.
- Implemented v1: **Innertube player API via OkHttp** (`youtube/InnertubeClient.kt`).
- Alasan: (a) build JitPack NewPipeExtractor tidak bisa diverifikasi
  di VM ini (Gradle tidak jalan di sandbox — lihat "Compile check"
  di bawah), jadi membundelnya adalah taruhan buta di build pertama;
  (b) NewPipeExtractor GPL-3.0 — membundelnya tanpa catatan lisensi
  akan jadi kejutan kalau APK ini suatu hari dijual closed-source;
  (c) satu respons Innertube sudah memberi metadata + streamingData +
  captionTracks + (best-effort) heatmap, tanpa dependensi tambahan.
- Fallback terdokumentasi, berurutan kalau Innertube patah:
  1) NewPipeExtractor via JitPack (perlu uji build + keputusan lisensi
     dulu), 2) yt-dlp-android (lihat di bawah).
- Catatan kunci: file ini memuat kunci WEB PUBLIK YouTube
  `AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8` yang dipakai klien web
  resmi. Itu BUKAN kredensial Putra dan BUKAN Groq key. Tidak ada
  secret Putra di source mana pun.

### 2. yt-dlp-android — TIDAK dibundel, fallback terdokumentasi saja
- Alasan: bundel Python 60-80 MB, Chaquopy, dan cerita update yang
  rapuh. Tidak dibutuhkan karena Innertube sudah menutup kebutuhan v1.
  Hanya dipakai kalau Innertube DAN NewPipeExtractor sama-sama patah.

### 3. Highlight scoring — implemented penuh, semua best-effort
- Heatmap: `youtube/HeatmapParser.kt`, pemindai JSON rekursif untuk
  objek heatmap. Kalau tidak ditemukan, hasilnya kosong — itu normal
  untuk video baru/sepi, bukan error.
- Transkrip: `youtube/VttParser.kt`, caption id > en, manual > auto,
  dipecah jadi kalimat dengan timestamp.
- LLM Groq: `analysis/GroqClient.kt`, SATU panggilan batch untuk semua
  segmen ~90 detik (teks per segmen dipotong 1200 karakter), model
  `openai/gpt-oss-120b` utama, fallback `openai/gpt-oss-20b`. Kalau
  gagal / tidak ada key, scoring lanjut dengan sinyal lain.
- Audio: `analysis/AudioEnergy.kt`, decode audio-only ke PCM via
  MediaExtractor + MediaCodec, RMS per detik ternormalisasi. File audio
  hanya di cache sementara dan DIHAPUS setelah analisa. Kalau decode
  gagal, hasilnya map kosong, bukan crash.
- Gabung: `analysis/Scoring.kt`, bobot dasar heatmap 0.45 / LLM 0.35 /
  audio 0.20, didistribusikan ulang kalau satu sinyal kosong.
  Haluskan 15 detik, puncak lokal diurutkan, jarak minimal 75 detik
  antar clip, Top 6, snap ke batas kalimat (target 60 detik, terima
  50-70 detik).

### 4. Cutting — implemented, FFmpeg DITURUNKAN dengan alasan
- Utama: Media3 Transformer 1.4.1 (`cutting/CuttingEngine.kt`,
  EditedMediaItem + ClippingConfiguration).
- Fallback: MediaExtractor + MediaMuxer murni Android (untuk sumber
  adaptive video-only + audio, dan kalau Transformer gagal).
- FFmpeg TIDAK dibundel: FFmpegKit arthenica sudah pensiun dan
  binary-nya dicabut (Jan-Apr 2025); fork maintained membutuhkan
  build dari source + dukungan 16KB page size yang belum terverifikasi
  di CI ini. Menambah FFmpeg hanya dibenarkan kalau Transformer +
  MediaMuxer terbukti tidak cukup presisi untuk satu format —
  bukan untuk fitur edit.

### 5. UI — implemented sesuai pitched
- Kotlin + Compose, dark premium, satu layar utama + Settings.
- Per kartu clip: judul, timestamp, alasan, skor, durasi, Preview
  (ExoPlayer + ClippingConfiguration, streaming langsung),
  Download, Buat Narasi, Salin Narasi.
- Aturan penamaan ViewModel dipatuhi: tidak ada fungsi pola setter
  yang bertabrakan dengan var Compose (pelajaran SuaraKu 2026-10-21).
  Semua file ViewModel dikompilasi-check secara statis (lihat bawah);
  kompilasi penuh menunggu Actions.

### 6. Narasi — implemented sesuai locked spec SuaraKu
- On-demand per clip, `analysis/GroqClient.generateNarration` +
  `analysis/Narration.clean`.
- Penolakan jujur: tanpa Groq key -> tolak; tanpa transkrip clip ->
  tolak (tidak dibuat dari judul saja).
- Pembersihan: hapus markdown, label di awal baris, timestamp, URL,
  simbol mentah, emoji. Satu kali percobaan ulang kalau di luar
  110-140 kata; kalau masih di luar, teks tetap ditampilkan apa
  adanya dengan jumlah kata yang JUJUR di UI (di luar blok teks),
  bukan dipotong diam-diam.
- Salin: `ClipKuViewModel.copyNarration` memakai ClipboardManager
  sistem dan hanya menyalin blok narasi final. Judul, timestamp,
  alasan, jumlah kata, dan estimasi durasi adalah UI terpisah dan
  tidak pernah masuk clipboard.

### 7. Download/simpan — implemented
- `cutting/ClipDownload.kt`: unduh sumber SEKALI ke cache (dipakai
  ulang untuk clip lain dari video yang sama), potong, salin ke
  Downloads/ClipKu via MediaStore (API 29+), fallback ke folder file
  eksternal app kalau MediaStore gagal.
- Nama file: `NN_judul-singkat.mp4`, MP4 rasio asli.

## Keterbatasan yang diketahui (bukan bug yang disembunyikan)

1. Heatmap bisa kosong (video baru / sepi / YouTube mengubah format
   respons). Scoring tetap jalan, tapi kualitas highlight turun.
2. Tanpa transkrip, narasi ditolak. Tanpa Groq key, skor LLM dan
   narasi ditolak. Analisa dasar (heatmap + audio) tetap jalan.
3. YouTube bisa mengubah respons Innertube sewaktu-waktu. Kalau itu
   terjadi, analisa gagal dengan pesan jelas — perbaiki parser,
   jangan mengarang hasil.
4. Potong MediaMuxer mengikuti keyframe terdekat untuk video;
   batas potong bisa meleset beberapa frame dari batas kalimat.
   Transformer lebih presisi untuk sumber progressive.
5. Preview streaming bisa gagal untuk video yang hanya punya stream
   adaptive tanpa progressive — tombol Download (jalur file) tetap
   menjadi jalan keluar.
6. Transkrip segmen untuk LLM dipotong 1200 karakter per segmen;
   video sangat panjang tetap bisa dinilai, tapi detail di luar
   potongan itu tidak ikut dinilai.
7. Narasi bisa tetap di luar 110-140 kata setelah satu percobaan ulang;
   UI menampilkan jumlah kata sebenarnya, bukan angka palsu.
8. RISIKO KOMPILE TERTINGGI: API Media3 Transformer 1.4.1
   (Composition / EditedMediaItemSequence / Listener). Kalau Actions
   merah di `CuttingEngine.kt`, itu titik pertama yang diperbaiki —
   fallback MediaMuxer di file yang sama sudah siap dan tidak
   bergantung pada API itu.

## Compile check yang BENAR-BENAR dijalankan (VM ini)

- Gradle TIDAK bisa jalan di sandbox ini (tidak ada JDK/Gradle
  terpasang, sesuai skill build APK) — jadi TIDAK ADA klaim
  "kompilasi lolos" di sini. Kompilasi penuh adalah gerbang Actions.
- Yang sudah dijalankan dan lolos:
  - Brace balance: 17 file Kotlin, semua seimbang.
  - XML well-formed: 21 file XML (manifest + res) lolos parser.
  - Groovy: tidak ada `val` di `build.gradle` / `app/build.gradle` /
    `settings.gradle` (hanya `def`).
  - Tidak ada fungsi pola setter di ViewModel (hanya komentar lama
    yang sudah dihapus).
  - Tidak ada secret Putra di source (grep untuk pola key Groq
    kosong; satu-satunya kunci di source adalah kunci web publik
    YouTube yang didokumentasikan di atas).

## Yang masih diblokir untuk Actions hijau

1. Putra membuat repo kosong `bastian57/clipku` di GitHub
   (GitHub App tidak bisa membuat repo — 403).
2. Putra push source via Termux + PAT (GitHub App tidak bisa push
   `.github/workflows` — 403). Lihat blok di bawah.
3. Putra set secret `KEYSTORE_B64` (lihat blok di bawah).
4. Actions pertama jalan: baca baris `e:` teratas di build.log
   (bukan ekor stack trace) kalau merah.
5. Verifikasi: gerbang keystore + gerbang sertifikat lolos, release
   `latest` memuat `ClipKu-v1.0.0.apk`, cocokkan ukuran byte,
   `apksigner verify` + `aapt dump badging` sebelum install.
6. Acceptance di HP: install -> tempel link -> Analisa -> Preview
   -> Download per clip -> cek file di Downloads/ClipKu ->
   Buat Narasi -> Salin -> paste di SuaraKu -> generate suara ->
   edit di CapCut.

## Blok Termux Putra (SATU blok copy-paste, path home-relative)

Jalankan SETELAH source ClipKu ini sampai ke HP (mis. zip diekstrak
ke `~/clipku`, sehingga isi source ada di `~/clipku/source`) DAN
repo kosong `bastian57/clipku` sudah dibuat di GitHub.

```bash
cd ~/clipku/source && git init -b main && git add -A && git commit -m "ClipKu v1.0.0" && git remote add origin https://github.com/bastian57/clipku.git && git push -u origin main
```

Kalau `git push` ditolak 403, itu karena GitHub App / token tanpa
izin workflow — ulangi push dengan PAT Putra sebagai password, atau
upload file `.github/workflows/clipku-build.yml` lewat web UI GitHub
setelah push utama berhasil. Jangan pernah paste PAT di chat.

## Set secret KEYSTORE_B64 (dari file, JANGAN paste di web UI)

Keystore yang dipakai adalah kunci tetap Putra yang sama dengan
SuaraKu/SwapKu (file induk di HP Putra, mis. `~/suaraku-keystore.jks`).
Jangan kirim isi keystore atau base64-nya lewat chat.

```bash
base64 ~/suaraku-keystore.jks | tr -d '\n' > ~/clipku-keystore.b64 && gh secret set KEYSTORE_B64 --repo bastian57/clipku < ~/clipku-keystore.b64 && rm ~/clipku-keystore.b64 && echo "SECRET OK"
```

Verifikasi secret SEBELUM menyimpulkan build gagal (di home, BUKAN
di /tmp — Termux tidak punya /tmp, tes di /tmp memberi hasil palsu):

```bash
test -s ~/suaraku-keystore.jks && keytool -list -v -keystore ~/suaraku-keystore.jks -storepass android -alias androiddebugkey | grep -i "SHA256:"
```

Hasil yang diharapkan: `0A:A5:2A:F9:10:17:42:AE:5D:5A:13:80:F7:F2:E8:A4:F1:F5:88:03:80:6F:B6:09:43:72:D3:3C:78:10:30:20`.
Kalau beda, JANGAN lanjut build — kunci akan salah dan APK tidak
bisa timpa-install.

## Peringatan sebelum install pertama

ClipKu adalah package baru (`com.daftech.clipku`), jadi install
pertama tidak menimpa app lain. Tapi kalau suatu saat CI menandatangani
dengan kunci yang salah, update berikutnya akan ditolak Android dan
memaksa uninstall (data app hilang, termasuk Groq key) — itulah
gunanya gerbang sertifikat di workflow: build yang salah kunci
DIBATALKAN sebelum upload, bukan dikirim ke Putra.
