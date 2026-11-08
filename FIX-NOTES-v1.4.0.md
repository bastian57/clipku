# FIX-NOTES — ClipKu v1.4.0 (audit P1+P2, "selesaikan semua masalah sisa")

Versi: **1.4.0 / versionCode 14** — dari v1.3.0 fix2 (vc13).
Gas: Putra, 2026-11-08 10:55 WIB ("Gas audit P1+P2").
Cakupan: SEMUA item audit P1-1..P1-11 dan P2-1..P2-10 dalam SATU build.
Aturan yang dipegang: TIDAK ada kenaikan versi library (media3 tetap 1.9.0,
Room tetap 2.6.1, Kotlin 1.9.25, compileSdk/targetSdk 35, AGP 8.7.3,
Gradle 8.9); keystore/signing tidak diubah; perilaku #1 (pre-filter Top-12
+ paralel 3 model), HandlerThread segmen, narasi, Whisper, dan History
tidak diubah kecuali yang disebut di bawah.

## P1 — masalah fungsional

### P1-1 Label "segment" jujur
- `ClipDownload.DownloadResult` kini membawa `actualDurationMs` (durasi
  AKTUAL file hasil potong, dibaca via `MediaExtractor` SEBELUM file
  temp dihapus `saveToDownloads`).
- `ClipKuViewModel.requestDownload` mengukur WAKTU download (detik dari
  tap sampai tersimpan) dan menulis keduanya ke diagnostik per clip:
  `clip 1: segment, durasi 58 dtk, waktu 4.2 dtk`.
- **Limit jujur (tetap): Transformer TIDAK mengekspos byte counter —
  v1.4.0 tetap TIDAK mengukur byte dan TIDAK mengklaim hemat byte.**
  Yang diukur hanya durasi aktual + waktu. Kalau mau bukti hemat byte,
  itu butuh perbandingan ukuran file, bukan klaim dari method.

### P1-2 Preview adaptive ber-audio
- `ClipKuViewModel.previewPlayer` kini mengembalikan `PreviewResult`
  (player + hasAudio). Video adaptive (video-only + audio terpisah)
  diputar sebagai `MergingMediaSource` (video+audio, keduanya di-clip
  ke rentang yang sama) — dulu hanya track video yang diputar dan clip
  terdengar bisu.
- Kalau HANYA video-only yang tersedia, preview tetap jalan tapi
  `hasAudio=false` dan MainScreen menampilkan label
  "Preview tanpa audio — hanya video yang tersedia untuk clip ini."
- Error playback async (dulu diam) kini masuk `previewError` dan
  ditampilkan di bawah player.

### P1-3 Cache sumber dibatasi
- `ClipDownload.MAX_SOURCE_CACHE_BYTES` = 500 MB untuk
  `cacheDir/clipku-source`. Eviksi LRU (file tertua dihapus dulu)
  berjalan di awal `downloadClipDetailed` dan `cutClipToTemp`.
- Settings menampilkan ukuran cache (`cacheInfo`, dihitung ulang di
  init + setelah bersih-bersih) + tombol "Bersihkan Cache" yang
  menghapus file sumber (bukan History).
- History Room TIDAK ikut dihapus oleh tombol ini.

### P1-4 Whisper cross-check bahasa
- `WhisperClient.transcribeDetailed()` (baru) memakai
  `response_format: "verbose_json"` → `WhisperResult(text, language)`
  dengan bahasa dinormalisasi ke kode 2 huruf
  (indonesian→id, english→en, javanese→jv, sundanese→su, malay→ms,
  "in"→"id"). `transcribe()` lama kini mendelegasikan ke fungsi ini.
- `ClipKuViewModel.generateNarrationFor`: kalau bahasa terdeteksi
  Whisper berbeda dari bahasa yang diharapkan (bahasa caption/track
  yang dipilih), track audio dipilih ulang SEKALI via
  `NewPipeStreamExtractor.extractAudioUrlForLanguage(videoId, bahasa)`
  (hanya URL yang locale-nya benar-benar cocok), transkrip diulang,
  dan peringatan ditampilkan di kartu narasi (`narrationNote`, mis.
  "Bahasa terdeteksi en, berbeda dari id — track audio dipilih ulang
  sekali (hasil: id).").
- **Penyederhanaan (jujur):** deteksi bahasa memakai audio clip yang
  dikirim ke Whisper (~60 detik), BUKAN sampel 30 detik terpisah —
  tidak ada panggilan Whisper ekstra, dan audio lebih panjang =
  deteksi lebih andal. **Limit:** re-pick bekerja lewat jalur
  segmen-audio; kalau jalur itu gagal dan fallback memakai file
  progressive yang sudah ter-cache, audio hasil re-pick tidak
  terpakai (file progressive berisi track aslinya).

### P1-5 Samakan label audio
- NewPipe: `describeAudio` selalu membawa sumber, mis.
  "id, original (NewPipe)".
- Innertube: `describeInnertubeAudio` kini menulis
  "id, default (Innertube — belum tentu original)" — label "default"
  Innertube TIDAK LAGI tampil seolah setara "original" NewPipe.
- Diagnostik menampilkan deskripsi track pemenang apa adanya.

### P1-6 Fallback null-locale
- `NewPipeStreamResult.audioLanguageUnknown` (true kalau track audio
  yang menang locale-nya null). ViewModel juga menganggap bahasa
  tidak diketahui kalau deskripsi Innertube diawali "?"
  (bahasa null).
- Kalau tidak diketahui: ranking memakai bahasa caption sebagai
  penentu (perilaku lama dipertahankan) + peringatan eksplisit di
  diagnostik: "PERINGATAN: bahasa audio tidak diketahui — memakai
  bahasa caption (id) sebagai penentu ranking audio."

### P1-7 Rantai callChat: 413 fail-fast, 429 backoff
- Loop model di `GroqClient.rerankClips` kini: 413 → berhenti langsung
  (prompt yang sama gagal di semua model — jangan buang token),
  429 → jeda eksponensial singkat (2 dtk, lalu 4 dtk) sebelum model
  berikutnya. Sebelumnya loop ini tanpa hygiene 413/429.
- Chunked scoring (`scoreWindowWithFallback`) SUDAH punya hygiene ini
  sejak v1.0.8 — diverifikasi, tidak diubah.

### P1-8 Timeout total tahap transkrip
- `InnertubeClient.TRANSCRIPT_TOTAL_TIMEOUT_MS` = 120 detik membungkus
  SELURUH rantai WEB → TIMEDTEXT → ANDROID (`withTimeoutOrNull`).
  Timeout = hasil kosong + `lastTranscriptTimedOut = true` (ditulis di
  diagnostik: "Transkrip: waktu habis (batas total 120 detik) —
  sebagian track belum sempat dicoba."), bukan gantung tanpa progres.
- `fetchText` kini me-rethrow `CancellationException` — menelannya
  membuat timeout total / tombol Batal tidak pernah selesai.
- Paralel maks 4 batch + maks 12 track dari v1.3.0 dipertahankan
  (tidak ditulis ulang).

### P1-9 Workflow CI
- Step SDK + Gradle kini `curl -f -sL --retry 3` + `test -s <file>`
  sebelum unzip (`-f` = gagal kalau HTTP error, bukan menyimpan
  halaman error sebagai zip). Ini flake download Gradle v1.0.5.
- Dipilih pola curl-hardened (bukan beralih ke `./gradlew` wrapper),
  supaya satu pola saja yang dipakai.

### P1-10 Tombol Batal
- ViewModel menyimpan Job: `analysisJob` (analisa),
  `downloadJobs` per clip, `narrationJobs` per clip.
- MainScreen: tombol "Batal" saat analisa berjalan; "Batal Download"
  dan "Batal Narasi" per kartu clip saat masing-masing berjalan.
  Cancel → coroutine dibatalkan, flag direset (`isAnalysing` false,
  `isDownloading`/`isGeneratingNarration` false), status kembali
  normal ("Analisa dibatalkan." — BUKAN error). `onCleared()`
  membatalkan semua Job.
- **Limit jujur:** pembatalan tidak memotong request HTTP yang sedang
  blokir di thread IO (OkHttp `execute()` tidak bisa di-interrupt
  dari coroutine) — request itu selesai sendiri (timeout OkHttp),
  tapi hasilnya dibuang dan UI langsung bebas.

### P1-11 Narasi Whisper berprogres
- Tahap ditampilkan di kartu clip (`Clip.narrationStage`):
  "menyiapkan…" → "memotong audio clip…" / "mengunduh audio clip…"
  (via callback `onProgress` di `cutClipToTemp`) → "mentranskrip…" →
  "membuat narasi…". Tahap di-reset (null) saat selesai/gagal/batal.
- `WhisperClient.MAX_UPLOAD_BYTES` = 25 MB: file temp dicek
  SEBELUM upload — ditolak dengan pesan jelas kalau >25 MB
  ("File clip X MB melebihi batas 25 MB Whisper — narasi tidak
  dibuat. Coba clip lain."), bukan sesudah Groq menjawab 413.
- File temp `clipku-whisper-*` yang tertinggal dibersihkan saat app
  startup (init ViewModel) — selain `finally` yang sudah ada saat
  narasi selesai.

## P2 — kualitas / kebersihan

### P2-1 WINDOW_MS/WINDOW_OVERLAP_MS dipakai
- Dipilih: bangun jendela dari startMs segmen (bukan hapus konstanta).
  `GroqClient.scoreSegmentsChunked` kini: jendela = segmen yang
  startMs-nya jatuh di [awal, awal + 12 menit), maks 8 segmen per
  jendela (pengaman kalau kalimat sangat rapat); jendela berikutnya
  mulai 10,5 menit setelah sebelumnya (overlap 90 dtk ≈ 1 segmen,
  sama seperti dulu). `SegmentInput` mendapat field `startMs`
  (default 0L — call site lama tetap jalan); ViewModel mengisinya
  dari `segment.startMs`. Dedupe overlap antar-jendela (skor tertinggi
  menang) tetap seperti dulu.

### P2-2 Rerank
- Skor rerank kini jadi tie-breaker: urutan =
  rank naik, lalu skor rerank turun (sebelumnya hanya rank; rank
  sama/tanpa rank diurutkan asal).
- Kandidat audio-only (transkrip kosong) dinilai dari judul+alasan
  ("Judul: … Alasan: …"), bukan teks kosong.
- Id rerank yang tidak dikenal (bukan kandidat) dicatat di diagnostik
  ("rerank: id tak dikenal … (diabaikan, bukan kandidat)"), bukan
  diam-diam dibuang ke akhir.

### P2-3 Key Groq belum disimpan
- Dipilih: indikator status (bukan auto-save). Settings menampilkan
  "Belum disimpan — tekan \"Simpan Key\" supaya key ini yang dipakai
  analisa dan narasi." kalau teks field berbeda dari prefs tersimpan
  (`groqKeySaved`, di-update saat simpan/hapus); "Key tersimpan."
  kalau sudah sama dan tidak kosong.

### P2-4 Duplikat MediaStore
- `ClipDownload.uniqueDownloadName`: kalau nama file sudah ada di
  Downloads/ClipKu (cek via MediaStore `DISPLAY_NAME`), tambahkan
  suffix angka (`..._2.mp4`, `..._3.mp4`, …; fallback timestamp kalau
  sampai _99). Tidak timpa diam-diam, tidak duplikat diam-diam.

### P2-5 Validasi segmen
- `SegmentExport.validateClip`: toleransi diketatkan ke 0.8×–1.2×
  untuk clip 50–70 detik (48–72 dtk untuk clip 60 dtk); di luar
  rentang itu (mis. video pendek) tetap 0.4×–1.6× yang longgar.
- Kartu clip menampilkan durasi AKTUAL file hasil download
  ("58 dtk (aktual)") kalau sudah di-download; kalau belum,
  durasi yang diminta seperti dulu.

### P2-6 muxTrim
- Kondisi mati `if (audioFile != null || true)` DIHAPUS.
- `videoExtractor` dan `audioExtractor` masing-masing dibungkus
  try/finally sendiri — selalu di-release walau `copyTrack` melempar;
  kandidat audio di-release kalau tidak ada track audio atau saat
  exception.

### P2-7 downloadToFile tanpa UA/Referer
- **VERIFIKASI SAJA — sudah ada sejak v1.0.9, tidak dikerjakan ulang.**
  `ClipDownload.downloadToFile` mengirim `User-Agent` browser +
  `Referer: https://www.youtube.com/` (dicek masih ada di source
  v1.4.0).

### P2-8 Salin Diagnostik
- Tombol "Salin Diagnostik" di MainScreen (di bawah teks diagnostik)
  menyalin teks diagnostik lengkap ke clipboard via ClipboardManager.

### P2-9 Unit test minimal + CI
- 5 file test baru di `app/src/test/java/com/daftech/clipku/`
  (total 39 test, tanpa network/key):
  - `ScoringTest` (10): buildSegments ~90 dtk, selectClips
    audio-only / semua-kosong / transcript-only (ditandai),
    pre-filter cap Top-12, dedupe overlap, snapToSentences,
    startMs segmen untuk jendela berbasis waktu.
  - `GroqClientParseTest` (6): parseScores valid + preamble,
    malformed → kosong, clamp 0–100, item tanpa id dilewati,
    truncasi judul/alasan. (`parseScores` diubah private → internal
    supaya bisa diuji; logika tidak berubah.)
  - `VttParserTest` (5): 2 cue, strip tag HTML, pecah kalimat
    multi-kalimat, input kosong, timestamp jam.
  - `AudioRankTest` (9): rank 0/1/2/3/4/5, normalisasi "in"→"id",
    `pickAudioStreams` (original-id menang atas dubbed-en walau
    bitrate lebih rendah), input kosong/null.
  - `VideoIdTest` (9): watch, youtu.be, shorts/embed/live, /v/,
    music.youtube.com, attribution_link (u= ter-encode), id mentah,
    invalid → null.
- Workflow: step `gradle testDebugUnitTest` SEBELUM assemble
  (gagal test = build merah). `testImplementation` junit 4.13.2 +
  org.json 20240303 (org.json asli di classpath test — android.jar
  stub akan melempar "Stub!" untuk JSONObject di unit test JVM).
- `NewPipeStreamExtractor.pickAudioStreams` dan `audioRank` diubah
  private → internal supaya bisa diuji; logika tidak berubah.

### P2-10 VideoId.extract
- Menangani `music.youtube.com`, path `/v/`, dan ekstraksi `u=` dari
  `attribution_link` (decode URL dulu, lalu ekstrak rekursif satu
  level). Bentuk lama tetap jalan.

## File yang diubah / ditambah

Diubah:
- `app/build.gradle` — versionCode 13→14, versionName 1.3.0→1.4.0;
  testImplementation junit + org.json (library lain TIDAK berubah).
- `app/src/main/java/com/daftech/clipku/model/Models.kt` — Clip:
  `actualDurationMs`, `narrationStage`, `narrationNote` (semua
  defaulted; mapping History tidak berubah).
- `app/src/main/java/com/daftech/clipku/cutting/SegmentExport.kt` —
  toleransi validateClip (P2-5).
- `app/src/main/java/com/daftech/clipku/cutting/CuttingEngine.kt` —
  muxTrim (P2-6).
- `app/src/main/java/com/daftech/clipku/cutting/ClipDownload.kt` —
  DownloadResult.actualDurationMs, batas cache 500 MB + LRU +
  clearSourceCache, nama download unik (P2-4), cutClipToTemp
  audioUrlOverride + onProgress (P1-4/P1-11).
- `app/src/main/java/com/daftech/clipku/groq/WhisperClient.kt` —
  WhisperResult + transcribeDetailed (verbose_json) + cek 25 MB
  (P1-4/P1-11).
- `app/src/main/java/com/daftech/clipku/youtube/NewPipeStreamExtractor.kt` —
  audioLanguageUnknown (P1-6), extractAudioUrlForLanguage (P1-4),
  describeAudio "(NewPipe)" (P1-5), pickAudioStreams/audioRank
  private→internal (P2-9).
- `app/src/main/java/com/daftech/clipku/youtube/InnertubeClient.kt` —
  label "(Innertube — belum tentu original)" (P1-5), timeout total
  transkrip 120 dtk + lastTranscriptTimedOut + rethrow
  CancellationException (P1-8).
- `app/src/main/java/com/daftech/clipku/youtube/VideoId.kt` —
  music.youtube.com, /v/, attribution_link u= (P2-10).
- `app/src/main/java/com/daftech/clipku/analysis/GroqClient.kt` —
  jendela berbasis waktu (P2-1), rerank 413 fail-fast + 429 backoff
  (P1-7), parseScores private→internal (P2-9).
- `app/src/main/java/com/daftech/clipku/ui/ClipKuViewModel.kt` —
  Jobs + cancel (P1-10), preview MergingMediaSource + error (P1-2),
  peringatan bahasa audio (P1-6), timeout transkrip (P1-8), durasi
  aktual + waktu download (P1-1), rerank tie-breaker + audio-only +
  id tak dikenal (P2-2), cross-check Whisper + re-pick (P1-4),
  tahap narasi (P1-11), copyDiagnostic (P2-8), cache info/clear
  (P1-3), groqKeySaved (P2-3), cleanup clipku-whisper-* saat startup
  (P1-11), SegmentInput.startMs (P2-1).
- `app/src/main/java/com/daftech/clipku/ui/MainScreen.kt` —
  tombol Batal (analisa/download/narasi), Salin Diagnostik,
  durasi aktual di kartu clip, label "preview tanpa audio" + error
  preview, tahap + catatan narasi.
- `app/src/main/java/com/daftech/clipku/ui/SettingsScreen.kt` —
  indikator "belum disimpan" (P2-3), info cache + Bersihkan Cache
  (P1-3).
- `.github/workflows/clipku-build.yml` — curl -f --retry 3 + cek
  ukuran (P1-9), step testDebugUnitTest sebelum assemble (P2-9).

Ditambah:
- `app/src/test/java/com/daftech/clipku/ScoringTest.kt`
- `app/src/test/java/com/daftech/clipku/GroqClientParseTest.kt`
- `app/src/test/java/com/daftech/clipku/VttParserTest.kt`
- `app/src/test/java/com/daftech/clipku/AudioRankTest.kt`
- `app/src/test/java/com/daftech/clipku/VideoIdTest.kt`

## Yang TIDAK diubah (sengaja)
- Semua versi library (media3 1.9.0, Room 2.6.1, Kotlin 1.9.25,
  compileSdk/targetSdk 35, AGP 8.7.3, Gradle 8.9).
- Keystore/signing, pre-filter Top-12 + paralel 3 model,
  HandlerThread segmen, narasi (struktur/teks), History.
- Tidak ada item yang di-skip. Dua penyederhanaan yang dicatat
  jujur di atas: P1-4 deteksi bahasa memakai audio clip penuh
  (~60 dtk, bukan sampel 30 dtk terpisah) + re-pick hanya efektif
  lewat jalur segmen-audio; P1-10 pembatalan tidak memotong request
  HTTP yang sedang blokir di thread IO.

## Verifikasi (lokal, sebelum push)
- `unzip -t` zip bersih; versionName 1.4.0 + versionCode 14;
  media3Version 1.9.0 + roomVersion 2.6.1 TIDAK BERUBAH.
- `grep "companion object {"` GroqClient.kt = 1;
  `grep -ci llama` GroqClient.kt = 0.
- Tidak ada file .keystore/.jks di zip maupun di source tree.
- Tidak ada Log/println di file yang diubah.
- Keseimbangan kurung {}()[] semua file yang diubah + semua test:
  seimbang (dicek otomatis).
- 39 unit test ada di src/test (5 file). **Catatan: test BELUM
  dijalankan di sandbox ini (tidak ada Android SDK/Gradle lokal) —
  gerbang sebenarnya adalah step testDebugUnitTest di Actions;
  kalau ada test yang gagal di CI, build merah dan harus diperbaiki
  dari log, bukan di-skip.**
- Workflow YAML ter-parse; step unit test berada SEBELUM assemble.
