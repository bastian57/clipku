# ClipKu v1.0.8 — FIX NOTES (semua 7 temuan P0 audit penuh, gas 2026-11-06)

## Keadaan yang terbukti (dari AUDIT-v1.0.7-full.md, 2026-11-06)

Audit baca-seluruh-source v1.0.7 menemukan 28 temuan (P0=7, P1=11,
P2=10) dan verdict "BELUM siap pakai penuh". v1.0.8 ini HANYA
memperbaiki ke-7 P0 — TIDAK ada upgrade #4 (Media3), TIDAK ada #5
(offline STT), TIDAK ada item P1/P2. Urutan pengerjaan mengikuti
saran audit: P0-1 → P0-2 → P0-4 → P0-3 → P0-7 → P0-5 → P0-6.

## Yang diubah v1.0.8 (per P0)

### P0-1 — Transkrip ada tapi clip nol + pesan penolakan bohong
(`analysis/Scoring.kt`, `ui/ClipKuViewModel.kt`)
- `Scoring.selectClips` kini punya jalur **transcript-only**: kalau
  total bobot fusion = 0 (LLM gagal DAN heatmap + audio 0) TAPI
  transkrip ada, clip TETAP dibuat dari sinyal teks transkrip itu
  sendiri — bukan dari tebakan. Aturan eksplisit, didokumentasikan
  di code (`transcriptTextPerSecond`): tiap kalimat men-skor rentang
  detiknya dengan nilai dasar 0.50, +0.20 kalau mengandung '?',
  +0.20 kalau mengandung '!', +0.10 kalau panjangnya 12-40 kata;
  dijepit 0.0-1.0, detik yang tercakup beberapa kalimat mengambil
  nilai tertinggi. Puncak + snap kalimat + Top 6 + jarak 75 detik
  memakai jalur yang sama dengan fusion biasa.
- Clip jalur ini DITANDAI di alasannya: "transkrip saja (tanpa skor
  LLM)" — dan TIDAK PERNAH diklaim sebagai clip ber-skor LLM.
- Pesan penolakan di ViewModel dibangun dari sinyal yang BENAR-BENAR
  nol, mis. "transkrip ada 1103 kalimat, skor LLM gagal (HTTP 413
  ...), audio 0 detik ter-decode, heatmap 0 titik — tidak ada sinyal
  yang cukup untuk membuat clip." Kalau transkrip memang tidak ada,
  pesannya tetap menyebut "transkrip tidak ada".
- Batas jujur: kualitas clip transcript-only BELUM TERBUKTI di HP —
  ini sinyal teks kasar, bukan penilaian semantik LLM.

### P0-2 — Anggaran Groq TPM jebol di video panjang
(`analysis/GroqClient.kt`)
- Jeda **12 detik antar jendela** scoring (`WINDOW_DELAY_MS`,
  sesudah tiap jendela kecuali terakhir). Matematika anggaran:
  9 window x ~2.600 token input ≈ 23.400 token; dengan 8 jeda
  (96 detik) + waktu call sendiri (~5-10 detik x 9), total analisa
  ≈ 2,5-3 menit → laju rata-rata ≈ 23.400 / 180 detik ≈ 7.800
  token/menit, di bawah batas free tier 8.000 token/menit. Jeda ini
  lantai, bukan jaminan: kalau Groq membalas sangat cepat, jeda
  inilah yang menahan laju; kalau Groq lambat, total waktu bertambah
  sendiri. **Analisa video panjang JADI LEBIH LAMBAT karena ini —
  disengaja, bukan bug.**
- Fail-fast 413 di `callChat`: begitu satu model menjawab HTTP 413
  (prompt terlalu besar), loop model BERHENTI — tidak lagi mencoba
  3 model dengan prompt yang sama.
- Backoff 429 di `callChat`: jeda 2 detik sebelum model ke-2, 4
  detik sebelum model ke-3 (`RETRY_429_BASE_MS = 2000`, via
  Thread.sleep — callChat berjalan blocking di Dispatchers.IO).
- Model chain dan temperature 0.4 TIDAK berubah.
- Batas jujur: batas HARIAN 200.000 token tetap bisa kena kalau
  video sangat panjang atau analisa diulang-ulang — itu gagal jujur
  dengan error yang kebaca, bukan clip ngawur.

### P0-4 — Audio ALI: satu DNS gagal membunuh 35 percobaan
(`analysis/AudioEnergy.kt`, `ui/ClipKuViewModel.kt`)
- `AudioEnergy.analyseMultiple` kini mendiversifikasi per host:
  satu URL per host yang berbeda dicoba dulu (giliran pertama),
  baru URL sisa dari host yang sudah dicoba. Semua URL NewPipe
  untuk satu video biasanya menunjuk ke SATU host googlevideo —
  satu host mati tidak lagi membunuh seluruh daftar sebelum host
  lain dicoba.
- URL audio Innertube TIDAK LAGI terkubur: ViewModel menyimpan
  `innertubeAudioUrl` SEBELUM merge NewPipe, dan daftar coba =
  URL NewPipe + URL Innertube (tanpa duplikat). Ranking audio
  sadar-bahasa v1.0.5 TIDAK diubah — Innertube hanya ditambahkan
  sebagai kandidat di belakang.
- DNS dibedakan dari kegagalan lain: `UnknownHostException` di
  rantai penyebab → `AudioAnalysisResult.dnsFailure = true` +
  `failedHost`, dan diagnostik menulis "audio DNS: host X gagal
  resolve (DNS)" (bukan pesan exception mentah).
- Satu re-extract + satu retry: kalau hasil pertama gagal karena
  DNS dan audio masih 0 detik, ViewModel melakukan SATU extract
  NewPipe fresh + SATU percobaan ulang dengan URL baru, lalu memakai
  hasil retry. Satu kali saja, bukan loop.
- Batas jujur: kalau DNS HP-nya sendiri yang memblokir host
  googlevideo (mis. Private DNS), re-extract tidak membantu —
  diagnostik akan menunjukkannya ("sudah dicoba ulang 1x" tapi
  tetap DNS).

### P0-3 — Heatmap praktis mati
(`youtube/HeatmapParser.kt`, `youtube/InnertubeClient.kt`,
`analysis/Scoring.kt`)
- `HeatmapParser.parse` kini menerima `durationMs` (Innertube
  `parseVideoInfo` meneruskannya) dan `parseMarkers` membaca format
  ASLI YouTube: array objek `heatMarkers` dengan
  `timeRangeStartMillis` (ms absolut) + `heatMarkerIntensity`.
  Ditangani: objek langsung, terbungkus `heatMarkerRenderer`,
  nilai sebagai angka / string angka / objek `{"value"}` /
  `{"simpleText"}`. Fraksi = startMs / durasi — dulu marker
  disimpan dengan fraksi -1.0 lalu dibuang `parse()`, jadi jalur
  markers SELALU mati. Jalur array angka polos (`parseHeatArray`)
  tetap dipertahankan sebagai fallback.
- `Scoring.heatPerSecond` tail-fill DIBATASI: setelah titik heatmap
  TERAKHIR, nilai meluruh linear dari 0.5x ke 0 dalam
  `TAIL_DECAY_SECONDS = 60` detik, lalu 0. Dulu ekor diisi
  `lastValue * 0.5` SELAMANYA — ekor video akan ter-skor tinggi
  secara palsu begitu heatmap jalan. Celah ANTAR titik tetap diisi
  flat 0.5x seperti dulu.
- Batas jujur: fix ini BELUM TERBUKTI sampai video populer
  menunjukkan "heatmap: N titik" dengan N>0 di diagnostik HP.
  Kalau YouTube mengubah struktur heatmap lagi, parser kembali
  menghasilkan 0 — itu kondisi best-effort, bukan crash.

### P0-7 — "Jendela berhasil" dihitung meski LLM tidak men-skor apa pun
(`analysis/GroqClient.kt`)
- `scoreSegments` kini melempar `EmptyLlmScoreException` (khusus,
  turunan IllegalStateException) kalau jawaban LLM tidak ter-parse
  menjadi satu pun skor — bukan mengembalikan map kosong diam-diam.
- `scoreSegmentsChunked` menghitung jendela BERHASIL hanya kalau
  window itu menghasilkan >=1 skor ter-parse; map kosong / exception
  dihitung GAGAL dengan `lastError` yang menyebutkannya. Diagnostik
  "jendela LLM: X/Y berhasil" tidak lagi bisa membaca X/Y dengan
  0 segmen ter-skor.

### P0-5 — Cache sumber yang terpotong dianggap sah
(`cutting/ClipDownload.kt`)
- `downloadToFile` kini MENGHAPUS file target pada SEMUA kegagalan:
  HTTP non-200, exception di tengah copy (jaringan putus, disk
  penuh), dan sukses-tapi-0-byte. File parsial tidak pernah
  tertinggal untuk dipakai ulang.
- Jalur download-penuh (`downloadClipDetailed` lapis 2 dan
  `cutClipToTemp`) kini memvalidasi cache dengan
  `isValidCachedSource`: panjang >0 DAN MediaExtractor bisa
  membukanya dengan >=1 track. File yang tidak valid diunduh ulang
  dari nol. Validasi ini menggantikan cek `exists() && length() > 0`
  di 6 tempat (progressive / video-only / audio di kedua jalur).
- Batas jujur: validasi hanya membuktikan file bisa dibuka dan
  punya track — file yang terpotong di tengah tapi header-nya utuh
  masih bisa lolos; itu trade-off yang diterima dibanding tidak
  ada validasi sama sekali.

### P0-6 — MediaStore bisa "berhasil" padahal file kosong
(`cutting/ClipDownload.kt` `saveToDownloads`)
- Sukses HANYA setelah byte benar-benar tertulis: `openOutputStream`
  dicek null, dan jumlah byte yang tersalin dicek >0. Kalau stream
  null ATAU 0 byte tertulis, entri MediaStore kosong itu DIHAPUS
  (`contentResolver.delete(uri)`), `cutFile` TIDAK dihapus, dan alur
  jatuh ke fallback folder file eksternal app — sukses TIDAK
  dikembalikan untuk file kosong. Dulu code mengembalikan path
  "Download/ClipKu/…" sebagai sukses walau filenya kosong/tidak ada.

## File yang diubah (satu baris per file)

- `analysis/Scoring.kt` — fallback transcript-only
  (`transcriptTextPerSecond`, aturan 0.50/+0.20 '?'/+0.20 '!'/+0.10
  12-40 kata), alasan "transkrip saja (tanpa skor LLM)",
  `TAIL_DECAY_SECONDS = 60` untuk tail heatmap.
- `analysis/GroqClient.kt` — `WINDOW_DELAY_MS = 12_000` antar
  jendela, fail-fast 413 + backoff 429 di `callChat`
  (`RETRY_429_BASE_MS = 2_000`), `EmptyLlmScoreException`,
  jendela berhasil hanya kalau >=1 skor ter-parse. SATU companion
  object saja (cek: `grep -c "companion object {"` = 1) — bug
  v1.0.7-fix1 TIDAK terulang.
- `youtube/HeatmapParser.kt` — `parse(root, durationMs)`,
  `parseMarkers` membaca objek heatMarkers asli (langsung /
  heatMarkerRenderer / angka / string / objek value), fraksi dari
  durasi; helper `readMillis` / `readDouble`.
- `youtube/InnertubeClient.kt` — `HeatmapParser.parse(root,
  durationMs)` (satu baris, call site).
- `analysis/AudioEnergy.kt` — `AudioAnalysisResult.dnsFailure` +
  `failedHost`, `diversifyByHost` (satu URL per host dulu),
  deteksi `UnknownHostException` di rantai penyebab.
- `ui/ClipKuViewModel.kt` — `innertubeAudioUrl` disimpan sebelum
  merge NewPipe dan masuk daftar coba; 1x re-extract + retry saat
  DNS gagal; teks "audio DNS: host X gagal resolve (DNS)" di
  diagnostik; pesan penolakan dibangun dari sinyal yang
  benar-benar nol.
- `cutting/ClipDownload.kt` — `downloadToFile` hapus target di
  semua kegagalan; `isValidCachedSource` (MediaExtractor >=1
  track) di 6 titik cache; `saveToDownloads` hanya sukses setelah
  byte >0 tertulis (hapus entri kosong, jangan hapus cutFile).
- `app/build.gradle` — version 1.0.8 / versionCode 9.
- `README.md` — versi/dokumen saja (1.0.8, 7 P0).

Tidak diubah: ranking picker audio sadar-bahasa (v1.0.5),
segment-only export + fallback download-penuh (v1.0.6), aturan
narasi (110-140 kata, plain text, Salin hanya blok final), model
Whisper, Media3 (tetap 1.4.1 — upgrade #4 TIDAK di sini), keystore/
signing, kontras/UI selain teks diagnostik dan pesan penolakan,
dan scope terkunci (tanpa edit kreatif, tanpa auto-download,
tanpa auto-publish, tanpa TTS di app). Tidak ada Silero VAD,
FFmpeg, offline STT, atau unit test di versi ini (P2-9 di luar
scope, sesuai tugas).

## Cek yang benar-benar dijalankan (jujur)

- Grep: versionCode 9 / versionName '1.0.8' di build.gradle;
  `transcriptTextPerSecond` + "transkrip saja (tanpa skor LLM)" di
  Scoring; `WINDOW_DELAY_MS` + `EmptyLlmScoreException` +
  "HTTP 413" fail-fast di GroqClient; `parseMarkers` + `readMillis`
  di HeatmapParser; `diversifyByHost` + `dnsFailure` di AudioEnergy;
  `isValidCachedSource` + `bytesWritten` di ClipDownload;
  `innertubeAudioUrl` + "audio DNS:" di ViewModel.
- `grep -c "companion object {"` di GroqClient.kt = **1**.
- Brace/paren/bracket seimbang (selisih 0) di semua file Kotlin
  yang diubah + build.gradle.
- Tidak ada secret Putra di code yang diubah (tidak ada key `gsk_`,
  keystore, base64); tidak ada TODO/FIXME di code yang diubah.
- **Kompilasi Gradle penuh TIDAK bisa dijalankan di sandbox ini
  (tidak ada JDK/Gradle) — TIDAK diklaim hijau.** Actions adalah
  gerbang kompilasi. Risiko tertinggi, eksplisit: (a) restruktur
  `selectClips` (jalur transcript-only berbagi kode puncak/snap
  dengan fusion — kalau ada salah rujuk variabel, kompilasi merah
  di Scoring.kt); (b) `Thread.sleep` di `callChat` (blocking, di
  Dispatchers.IO — benar secara API, tapi kalau dipanggil dari
  main thread di masa depan akan ANR; saat ini semua pemanggil
  lewat withContext IO); (c) `MediaExtractor.setDataSource` di
  `isValidCachedSource` untuk file audio tanpa ekstensi (file
  `source-<id>-audio` tanpa ekstensi — MediaExtractor memakai
  sniffing konten, seharusnya jalan, tapi kalau gagal, cache audio
  akan diunduh ulang tiap kali: lambat, bukan crash).

## Batasan yang tersisa (bukan janji)

- Transcript-only: kualitas clip dari sinyal '?'/'!'/panjang
  kalimat BELUM TERBUKTI di HP — bisa jadi clip-nya terasa acak
  dibanding clip ber-skor LLM. Labelnya jujur supaya user bisa
  membedakan.
- Pacing Groq: analisa video 91 menit kini ~2,5-3 menit HANYA
  untuk scoring (belum termasuk transkrip + audio + download).
  Itu harga anggaran free tier.
- Heatmap: belum terbukti sampai ada video populer dengan N>0.
- Audio DNS: re-extract 1x tidak menyembuhkan DNS HP yang
  memblokir googlevideo.
- Cache validation: file terpotong tapi header utuh masih bisa
  lolos (lihat P0-5).
- P1/P2 (preview tanpa audio, cache tak terbatas, Whisper
  cross-check bahasa, tombol batal, workflow curl -f, dsb.)
  TIDAK disentuh di v1.0.8 — itu pekerjaan berikutnya, bukan janji
  versi ini.

## Blok Termux untuk Putra (commit baru, BUKAN Re-run)

Secret KEYSTORE_B64 untuk bastian57/clipku SUDAH ada — jangan di-set
ulang, jangan bikin keystore baru.

```bash
ZIP=$(ls /sdcard/Download/ClipKu-v1.0.8_source_20261106.zip ~/storage/downloads/ClipKu-v1.0.8_source_20261106.zip 2>/dev/null | head -n 1); test -s "$ZIP" || { echo "ZIP TIDAK KETEMU di Download"; exit 1; }; command -v unzip >/dev/null || pkg install -y unzip; cp "$ZIP" $PREFIX/tmp/clipku108.zip; unzip -o $PREFIX/tmp/clipku108.zip -d ~/clipku; grep -q "1.0.8" ~/clipku/source/app/build.gradle && echo "SOURCE 1.0.8 OK" || { echo "VERSI BUKAN 1.0.8"; exit 1; }; cd ~/clipku/source && git add -A && git commit -m "ClipKu v1.0.8: fix all 7 P0 audit findings" && git push origin main
```

## Acceptance setelah hijau + verifikasi (seperti biasa)

Pasang timpa v1.0.7 tanpa uninstall (sertifikat sama, Groq key
Nalar tidak hilang). Tes utama:
- ALI 91 menit (`ezLxfx1qMyE`): analisa ulang → clip HARUS ketemu
  (jalur transcript-only kalau LLM masih gagal, alasan clip
  menyebut "transkrip saja (tanpa skor LLM)"); ATAU kalau LLM
  jalan, clip ber-skor LLM seperti biasa. Yang TIDAK BOLEH lagi:
  kartu merah "tidak punya transkrip" padahal diagnostik menulis
  transkrip 1103 kalimat. Diagnostik "jendela LLM: X/Y berhasil"
  harus konsisten dengan "skor LLM: N segmen" (X>0 kalau N>0).
- Video populer (apa pun): diagnostik "heatmap: N titik" — kalau
  N>0 pertama kalinya, heatmap fix terbukti.
- Rocky: diagnostik "audio track: id, original", clip Indonesia.
- Liputan6 (`Y_2DRQz35-0`): tetap Indonesia, narasi tetap jalan,
  tidak regresi.
- Download clip: waktu terukur (segment vs full-download) +
  file hasil BISA DIPUTAR dan tidak kosong (P0-6).
