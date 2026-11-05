# ClipKu v1.0.7 — Highlight Clip dari Video Panjang

APK Android (Kotlin native + Jetpack Compose Material3, dark premium).
Package: `com.daftech.clipku` • versionCode 8 / versionName 1.0.7 •
minSdk 24, targetSdk 34, compileSdk 34.

## Scope v1 (locked, gas 2026-11-04 14:30 WIB)

- Tempel link YouTube -> APK menonton dan menganalisa video panjang ->
  menyuguhkan beberapa clip highlight ±1 menit, bisa di-preview.
- Download PER CLIP atas tap user ke Downloads/ClipKu (MP4, rasio asli,
  default 720p). Tidak ada auto-download semua, tidak ada auto-publish.
- Auto highlight ONLY. Tidak ada mode cari topik/keyword.
- Tidak ada edit kreatif: tidak ada transisi, teks, musik, filter,
  reframe vertikal, voiceover, atau thumbnail yang dibakar ke clip.
  Potong di batas highlight (snap ke batas kalimat, hasil 50-70 detik
  masih dihitung ±1 menit) adalah ekstraksi, bukan edit.
- Narasi per clip, on-demand, gaya Nalar (kritis, independen, cerdas,
  antimainstream, mencerdaskan): teks FINAL siap copas ke SuaraKu,
  110-140 kata, plain text tanpa markdown/label/timestamp/emoji/URL/
  arahan panggung. Tombol Salin hanya menyalin blok final itu.
  APK TIDAK generate audio sendiri.

## Mesin

- Stream/metadata: Innertube player API via OkHttp (utama v1).
  NewPipeExtractor v0.26.5 via JitPack adalah sumber stream PERTAMA
  (decipher signature) — lihat BUILD-NOTES.md di folder induk.
  v1.0.5: picker audio SADAR BAHASA di kedua jalur (NewPipe +
  Innertube adaptive) — track ORIGINAL berbahasa video diutamakan,
  track dub tidak pernah menang hanya karena format/bitrate; track
  yang dipilih tampil di diagnostik ("audio track: id, original").
  v1.0.6: pipeline TRANSCRIPT-FIRST + SEGMENT-ONLY — analisa
  memakai caption dulu (sudah begitu sejak v1.0.1) dan audio-only
  (sejak v1.0.3); ekspor clip mencoba dulu jalur segmen (URL fresh +
  Transformer dari URL remote, tanpa download video utuh) dan jatuh
  ke download-penuh v1.0.5 kalau gagal; Whisper memakai segmen
  audio-only dulu. Jalur ekspor tampil di diagnostik
  ("clip N: segment" / "clip N: full-download").
  v1.0.7: scoring VIRALITAS ber-chunk — transkrip dibagi jendela
  12 menit / overlap 90 detik (maks ~8 segmen per call, jadi tidak
  ada lagi satu call raksasa yang kena HTTP 413); kriteria eksplisit
  hook / emotional peak / opinion bomb / revelation / conflict /
  quotable / story peak / practical value; dedupe overlap >50% +
  SATU rerank komparatif atas kandidat final; jendela gagal di-skip,
  bukan menolkan video. Diagnostik: "jendela LLM: X/Y berhasil".
  yt-dlp-android hanya fallback terdokumentasi.
- Scoring: heatmap Most Replayed (best-effort) + transkrip VTT +
  skor LLM Groq + energi audio RMS per detik. Merge puncak dekat,
  buang tumpang tindih, ranking Top 6.
- Potong: Media3 Transformer 1.4.1 (utama), MediaExtractor+MediaMuxer
  (fallback). FFmpeg tidak dibundel di v1 — lihat BUILD-NOTES.md.
- Groq API key: diisi user di Settings, disimpan di SharedPreferences
  privat, dikecualikan dari backup, tidak pernah di-hardcode.

## Build

Build resmi via GitHub Actions (lihat `.github/workflows/clipku-build.yml`):
JDK 17 + Android SDK manual + Gradle 8.7 + `gradle assembleDebug`,
gerbang keystore + gerbang sertifikat APK sebelum upload ke rolling
release `latest`, nama file `ClipKu-v1.0.7.apk`, plus `build.log` ke
release `build-log` (if: always).

Build lokal (opsional): Gradle 8.7+, JDK 17, Android SDK 34, lalu
`gradle assembleDebug`. Tanpa keystore tetap, APK lokal ditandatangani
debug keystore bawaan dan TIDAK bisa timpa-install APK CI.
