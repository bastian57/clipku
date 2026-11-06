# ClipKu v1.0.2 — FIX-NOTES (NewPipe + audio-peak + Whisper)

Gas: 2026-11-04 19:09 WIB, scope "full" (NewPipe + Whisper).
Versi: versionName 1.0.2, versionCode 3, package tetap `com.daftech.clipku`.
Status: SOURCE SAJA. APK belum ada sampai GitHub Actions hijau + verifikasi
independen (byte size, testzip, sig block, gerbang sertifikat). Jangan
klaim v1.0.2 siap sebelum itu.

## 1. Akar masalah yang diperbaiki (bukan diagnosa ulang)

v1.0.1 membuktikan lewat diagnostik di HP (screenshot 18:54 WIB):
video https://youtu.be/ezLxfx1qMyE (film ALI, 91 menit, Deen Stories TV)
mengembalikan track caption 0, transkrip 0 kalimat sumber NONE, heatmap 0,
audio URL tidak ada — dari Innertube WEB DAN ANDROID, padahal judul dan
durasi terambil. Video seperti ini kemungkinan memang tidak punya caption,
dan stream WEB+ANDROID berbentuk signatureCipher yang tidak dibaca dan
tidak di-decipher oleh ClipKu v1/v1.0.1. Akibatnya app tidak punya sinyal
apa pun, dan menolak mengarang clip (desain itu benar, mesinnya yang
kurang). Kontras sudah beres di v1.0.1 (teks putih terbaca) dan TIDAK
diubah di sini.

Video Kompas sebelumnya (kU6ZTFmnNNY, ada CC) mungkin sudah tertolong
fallback v1.0.1, tapi video tanpa caption + cipher tidak mungkin tertolong
fallback itu. Itulah yang diperbaiki v1.0.2.

## 2. Perubahan, file per file

- `settings.gradle` — tambah repository JitPack. NewPipeExtractor hanya
  didistribusikan lewat JitPack, tidak ada di Maven Central. Komentar
  lama "sengaja tanpa JitPack" diperbarui, karena alasan itu sudah lewat.
- `app/build.gradle` — versionCode 2 -> 3, versionName 1.0.1 -> 1.0.2,
  tambah `com.github.teamnewpipe:NewPipeExtractor:v0.26.4`. AGP 8.5.2,
  Kotlin 1.9.25, compileSdk/targetSdk 34, minSdk 24, media3 pin 1.4.1
  TIDAK berubah. Workflow sudah menurunkan nama APK dari versionName,
  jadi asset baru wajib `ClipKu-v1.0.2.apk`.
- `youtube/OkHttpNewPipeDownloader.java` (BARU) — adaptor Downloader
  NewPipe di atas OkHttp yang sudah dipakai app. Ditulis Java dengan
  `public execute()` dengan sengaja: di lini 0.26.x visibilitas
  Downloader.execute() berubah public -> protected di beberapa revisi,
  dan override Java public sah untuk kedua varian itu, sedangkan
  override Kotlin atas metode protected Java adalah titik rapuh.
  Tidak memakai anotasi @Nonnull supaya tidak bergantung pada jsr305.
- `youtube/NewPipeStreamExtractor.kt` (BARU) — sumber stream PERTAMA.
  `NewPipe.init(downloader)` sekali (guard synchronized),
  `ServiceList.YouTube.getStreamExtractor(watch-url)`, `fetchPage()`,
  lalu ambil: audio terbaik (diutamakan audio/mp4, sisanya audio
  pertama ber-URL), video <=720p tertinggi (kalau tidak ada, video
  terendah di atas 720p), dan subtitle dari `getSubtitlesDefault()`.
  Gagal apa pun -> hasil kosong, TIDAK PERNAH melempar ke UI.
  NewPipe TIDAK dipakai untuk menulis decipher sendiri: decipher
  dilakukan parser player JS NewPipe sendiri, itu inti dari dependensi
  ini.
- `youtube/InnertubeClient.kt` — tambah `lastStreamSource`
  ("WEB" / "WEB+ANDROID" / "NONE") untuk diagnostik, dan URL VTT
  menjadi sadar-fmt: track yang URL-nya sudah mengandung `fmt=`
  (mis. subtitle NewPipe) tidak lagi ditumpuk `&fmt=vtt`.
  Rantai WEB -> TIMEDTEXT -> ANDROID TIDAK diubah.
- `ui/ClipKuViewModel.kt` — urutan analisa baru:
  Innertube (judul/durasi/caption/heatmap) -> NewPipe (merge stream)
  -> transkrip fallback -> analisa audio -> scoring. NewPipe menang
  hanya untuk yang diberikannya: audioUrl menimpa audio Innertube,
  videoUrl dipakai sebagai videoOnlyUrl (BUKAN progressive, karena
  stream video NewPipe umumnya video-only; menandainya progressive
  akan membuat download memotong tanpa audio). Subtitle NewPipe
  hanya dipakai kalau caption Innertube kosong; kalau transkrip
  akhirnya datang dari track itu, label diagnostik dikoreksi dari
  "WEB" menjadi "NEWPIPE". Diagnostik lama dipertahankan semua,
  ditambah "sumber stream: ..." — label ini mengacu pada sumber
  AUDIO (sinyal yang menentukan analisa); videoOnly dari NewPipe
  tetap dipakai untuk download/preview walau label audio-nya
  Innertube. Status tanpa transkrip diperbarui: bukan lagi "narasi
  tidak bisa dibuat", melainkan ajakan tap Buat Narasi via Whisper.
  `generateNarrationFor` ditulis ulang (lihat bagian 4).
- `model/Models.kt` — `Clip` tambah `narrationSource` (metadata UI
  "Transkrip"/"Whisper", null kalau narasi belum dibuat). Field ini
  TIDAK PERNAH ikut tersalin: `copyNarration` hanya menyalin
  `clip.narration`, tidak berubah.
- `ui/MainScreen.kt` — baris metadata narasi menampilkan
  "sumber: Transkrip/Whisper • ..." di LUAR blok narasi final.
- `analysis/Scoring.kt` — TIDAK ADA perubahan logika, hanya komentar
  yang mendokumentasikan jalur audio-only yang sudah ada (lihat
  bagian 3). Mengubah scoring yang sudah bekerja hanya untuk
  "kelihatan berubah" adalah risiko yang tidak perlu.
- `cutting/ClipDownload.kt` — tambah `cutClipToTemp()` untuk Whisper:
  sama seperti downloadClip (unduh sumber ke cache sesi, potong
  lewat CuttingEngine), tapi hasilnya file temp di cache, BUKAN
  disalin ke Downloads/ClipKu. Pemanggil wajib menghapus temp.
- `groq/WhisperClient.kt` (BARU) — klien Groq Whisper, lihat bagian 4.
- `README.md` — versi dan nama file APK diperbarui ke 1.0.2.

## 3. Urutan fallback dan aturan scoring v1.0.2

Urutan stream (pertama yang memberi URL menang, per komponen):
  NewPipe (decipher) -> Innertube WEB -> Innertube ANDROID.
Urutan transkrip (tidak berubah, ditambah subtitle NewPipe sebagai
kandidat langkah pertama kalau caption Innertube kosong):
  caption Innertube/NewPipe -> TIMEDTEXT direct (id, lalu en, json3)
  -> caption ANDROID. Sumbernya dicatat di diagnostik
  (WEB / TIMEDTEXT / ANDROID / NEWPIPE / NONE).

Aturan scoring:
  - Transkrip ada: bobot lama tidak berubah (heatmap 0.45, LLM 0.35,
    audio 0.20, didistribusikan ulang proporsional kalau satu sinyal
    kosong).
  - Transkrip kosong DAN heatmap kosong TAPI audio ada: clip TETAP
    dibuat dari puncak energi audio saja. Mekanismenya sudah ada di
    Scoring.selectClips dan diverifikasi di code review v1.0.2:
    bobot kosong menjadi 0, bobot tersisa dinormalisasi (audio
    0.20 -> 1.0), puncak diambil dari skor audio yang dihaluskan
    15 detik, jarak minimal 75 detik, Top 6, dan snapToSentences
    punya fallback kalimat kosong (puncak - 30 detik sampai + 60
    detik, dijepit durasi video). Yang baru di v1.0.2 adalah audio
    itu kini bisa didapat lewat NewPipe — sebelumnya jalur ini mati
    karena audioUrl null.
  - Semua sinyal kosong (heatmap + transkrip + audio): TETAP tolak
    jujur dengan diagnostik, bukan clip dari detik 0 atau dari sunyi.

## 4. Whisper per-clip (bagian "full") — alur persisnya

  1. User tap "Buat Narasi" pada clip yang transkripnya kosong.
  2. App memotong clip itu SAJA (~60 detik) ke file temp MP4 di
     cache lewat `ClipDownload.cutClipToTemp()` (CuttingEngine yang
     sama dengan jalur Download; sumber video cache dipakai ulang
     kalau sudah ada dari sesi yang sama).
  3. File temp dikirim ke `POST
     https://api.groq.com/openai/v1/audio/transcriptions`,
     multipart form, model `whisper-large-v3-turbo`, response_format
     json, memakai Groq key dari Settings (tidak pernah di-hardcode).
     Parameter bahasa TIDAK dikirim dengan sengaja: v1.0.2 menargetkan
     berbagai macam video (Indonesia, Inggris, dll) dan Whisper
     mendeteksi bahasa otomatis kalau parameter dihilangkan; mengunci
     "id" akan merusak video berbahasa lain.
  4. File temp DIHAPUS di blok finally, apa pun hasil Whisper.
  5. Transkrip Whisper dipakai sebagai dasar narasi LLM yang sama
     (prompt Nalar, 110-140 kata, satu kali retry kalau di luar
     rentang, lalu tampilkan jumlah kata jujur). Teks narasi tetap
     plain text final saja; label "sumber: Whisper" hanya metadata
     UI di luar blok, tidak ikut tersalin.
  6. Kalau Whisper gagal (key kosong/kuota/jaringan) atau tidak
     menemukan ucapan di clip, narasi DITOLAK dengan pesan jelas,
     tapi clip-nya TETAP tersedia untuk download dan edit di CapCut.
     Narasi TIDAK PERNAH dibuat dari judul saja — dasarnya selalu
     teks ucapan nyata (transkrip video atau transkrip Whisper).

  Trade-off yang harus diketahui user: Whisper pertama untuk satu
  video bisa mengunduh sumber video penuh ke cache (biaya yang sama
  seperti satu tap Download), karena CuttingEngine memotong dari
  file sumber. Yang dikirim ke Whisper tetap hanya file clip ~60
  detik — video utuh TIDAK PERNAH dikirim, dan transkrip video utuh
  (91 menit) TIDAK PERNAH dibuat. Ini harga yang dibayar untuk
  memakai jalur potong yang sudah teruji, bukan menulis pemotong
  audio-only baru yang belum teruji.

## 5. Durasi / memori (10 menit sampai berjam-jam)

  - Analisa audio tetap streaming per detik dari file audio cache
    yang dihapus habis analisa (pola AudioEnergy yang sudah ada).
    Video/audio utuh TIDAK dimuat ke RAM. HP 6GB tetap masuk akal.
  - Video berjam-jam: waktu analisa lebih lama (unduh audio +
    decode per detik + NewPipe fetchPage), dan Top 6 diambil dari
    seluruh durasi — untuk video 3 jam, 6 clip adalah 6 puncak
    teratas se-video, bukan 6 per jam. Itu perilaku yang sudah ada,
    bukan diubah diam-diam di sini.
  - File sumber cache (`clipku-source/`) dipertahankan selama sesi
    supaya clip berikutnya tidak mengunduh ulang; file temp Whisper
    dihapus tiap selesai.

## 6. Lisensi NewPipeExtractor (GPL-3.0) — dicatat, bukan disembunyikan

  NewPipeExtractor berlisensi GPL-3.0. Pemakaian di ClipKu saat ini
  aman untuk pemakaian pribadi Putra karena source ClipKu sudah
  publik di GitHub (repo bastian57/clipku) — kewajiban GPL soal
  penyediaan source sudah terpenuhi oleh repo publik itu. Kalau
  suatu hari ClipKu mau dijual sebagai APK tertutup, lisensi ini
  HARUS dievaluasi ulang sebelum penjualan (opsi saat itu: tetap
  buka source, ganti mesin stream, atau konsultasi lisensi). Jangan
  menjual APK v1.0.2 tertutup tanpa evaluasi itu.

## 7. Yang TIDAK berubah / TIDAK ditambahkan

  Tidak ada: cari topik/keyword, edit kreatif (transisi/teks/musik/
  filter/reframe/voiceover/thumbnail bakar), auto-download semua,
  auto-publish, generate suara di dalam APK (Whisper adalah
  transkripsi, kebalikan dari TTS — suara tetap dibuat user di
  SuaraKu dari teks yang disalin). Aturan narasi (110-140 kata,
  plain text final, Salin hanya blok final), Top-6, jarak 75 detik,
  dan snap kalimat TIDAK berubah. Kontras v1.0.1 TIDAK diubah.
  Signing dan gerbang fingerprint/sertifikat TIDAK diubah.

## 8. Batasan yang tersisa (jujur, untuk acceptance)

  - Kalau NewPipe juga diblokir/ditolak YouTube untuk satu video,
    stream bisa tetap null — diagnostik akan menunjukkan
    "sumber stream" Innertube/NONE dan audio 0 detik, dan app tetap
    menolak jujur. Itu sinyal untuk fix berikutnya (mis. yt-dlp),
    bukan untuk mengarang clip.
  - Whisper butuh Groq key yang valid + jaringan. Clip tanpa ucapan
    (mis. musik instrumental murni) akan ditolak narasinya secara
    jujur, walau clip-nya tetap ada.
  - Subtitle NewPipe hanya dipakai kalau caption Innertube kosong,
    dan hanya yang berformat VTT yang bisa diparse VttParser; track
    non-VTT jatuh ke langkah TIMEDTEXT/ANDROID berikutnya.
  - NewPipe menambah satu fetchPage per analisa (beberapa detik);
    analisa total sedikit lebih lama dari v1.0.1.
  - NewPipe video dipakai sebagai videoOnlyUrl, bukan progressive:
    preview clip yang hanya punya stream NewPipe akan memutar video
    tanpa audio di preview (preview memakai progressive dulu, lalu
    videoOnly). Download tetap benar karena jalur adaptive
    menggabungkan videoOnly + audio.

## 9. Cek yang sudah dijalankan (jujur)

  - Brace/paren/bracket balance: 0 selisih di semua 9 file Kotlin/Java
    yang diubah/ditambah.
  - Semua XML di res well-formed (tidak ada XML yang diubah di fix ini).
  - Groovy: app/build.gradle def-only, tidak ada `val`; root
    build.gradle dan settings.gradle tidak memakai `val`.
  - Tidak ada nama fungsi ViewModel berpola setter-clash.
  - Tidak ada secret Putra di code yang diubah: tidak ada Groq key,
    tidak ada keystore/base64. Satu-satunya kunci yang muncul di scan
    adalah kunci web publik YouTube yang SUDAH ADA di InnertubeClient
    sejak v1 (bukan secret, tidak diubah di fix ini).
  - Tidak ada placeholder TODO/FIXME di code yang diubah.
  - Tidak ada code cari topik, auto-download, auto-publish, atau TTS.
    (Kata "TTS" hanya muncul di komentar lama Narration.kt soal teks
    yang dibaca TTS aneh — bukan fitur, tidak diubah.)
  - Kompilasi Gradle PENUH TIDAK BISA dijalankan di sandbox ini
    (tidak ada JDK/Gradle) — TIDAK DIKLAIM hijau. Actions adalah
    gerbang kompilasi.

  RISIKO KOMPIL TERTINGGI (dibaca sebelum panik kalau Actions merah):
  1. Resolusi dependensi NewPipeExtractor via JitPack (build tag
     v0.26.4 di JitPack + dependensi transitifnya, termasuk potensi
     konflik versi OkHttp yang dibawa transitif) DAN pemakaian API
     NewPipe (`ServiceList.YouTube.getStreamExtractor`,
     `fetchPage`, `getAudioStreams`, `getVideoStreams`,
     `getVideoOnlyStreams`, `getSubtitlesDefault`, `getUrl`,
     `getResolution`, `getLanguageTag`, `getFormat().getMimeType`,
     `NewPipe.init`). API-API ini diverifikasi terhadap javadoc
     NewPipeExtractor v0.26.5 dan contoh Downloader OkHttp yang
     beredar, tapi javadoc 0.26.5 belum tentu identik 100% dengan
     tag 0.26.4 yang di-pin — kalau merah di file NewPipe, perbaiki
     file itu dulu, jangan menyentuh Scoring/Whisper.
  2. Berikutnya: risiko lama Media3 Transformer di CuttingEngine
     (tidak disentuh di fix ini).

## 10. Blok Termux untuk Putra (dari ~/clipku/source, commit BARU)

  Secret KEYSTORE_B64 untuk bastian57/clipku SUDAH diset — jangan
  set ulang, jangan bikin keystore baru. Setelah push, Actions jalan
  otomatis. JANGAN Re-run commit lama; commit baru wajib.

```bash
cd ~/clipku/source && git add -A && git commit -m "ClipKu v1.0.2: NewPipe + audio-peak + Whisper" && git push origin main
```

  Catatan: blok di atas mengasumsikan zip source v1.0.2 sudah
  diekstrak menimpa ~/clipku seperti pola v1.0.1 (zip dari Download
  -> $PREFIX/tmp -> unzip -o ke ~/clipku, lalu cek versionName
  1.0.2 di app/build.gradle) sebelum commit. Kalau folder source di
  HP masih v1.0.1, JANGAN push dulu — ekstrak zip v1.0.2 dulu.

  Acceptance setelah hijau + verifikasi: install update di atas
  v1.0.1 (sama keystore, tanpa uninstall) -> tempel
  https://youtu.be/ezLxfx1qMyE -> Analisa -> Diagnostik harus
  menunjukkan sumber stream NEWPIPE dan audio >0 detik, clip
  muncul walau transkrip NONE -> Preview/Download per clip ->
  Buat Narasi pada clip tanpa transkrip -> metadata harus menulis
  "sumber: Whisper" -> Salin -> paste di SuaraKu (yang tersalin
  hanya teks narasi, bukan label sumber) -> edit di CapCut.
  Tes kedua: video Kompas kU6ZTFmnNNY tetap harus jalan seperti
  yang diharapkan dari v1.0.1 (transkrip dari caption, narasi
  "sumber: Transkrip").
