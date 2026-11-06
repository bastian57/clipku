# ClipKu v1.0.5 — FIX NOTES (upgrade #1, gas 2026-11-05)

## Keadaan yang terbukti (bukan dugaan ulang)

- v1.0.4 hijau dan terverifikasi, narasi + skor LLM JALAN dengan key
  Nalar (laporan Putra 2026-11-05 09:29 WIB: "Narasi udah keluar.
  Clip udah beres.").
- Bug baru dari video Rocky Gerung: clip hasil download terdengar
  berbahasa INGGRIS, padahal video asli berbahasa Indonesia full.
  Tes pemisah Putra 2026-11-05 14:49 WIB: download clip video
  Liputan6 (Y_2DRQz35-0) TETAP bahasa Indonesia — jadi bug ini
  SPESIFIK video yang punya track audio ganda, bukan sistematis.
- Akar (dari code, bukan tebakan): pemilihan audio di v1.0.4 buta
  bahasa di KEDUA jalur — NewPipe "mp4 dulu, sisanya belakangan",
  Innertube adaptive "bitrate tertinggi". Kalau video punya track
  auto-dub Inggris (format mp4 / bitrate lebih tinggi), track dub
  itu yang menang dan kepotong ke clip, walau transkripnya Indonesia.
  Video satu track (Liputan6) lolos karena tidak ada track lain
  yang bisa menang.
- Riset engine 2026-11-05 (report.md bagian B, upgrade #1):
  NewPipeExtractor v0.26.5 sudah mengekspos semua yang dibutuhkan —
  `AudioStream.getAudioLocale()`, `getAudioTrackType()` (ORIGINAL /
  DUBBED / DESCRIPTIVE / SECONDARY), `getAudioTrackName()`,
  `getAudioTrackId()`, plus `StreamExtractor.getLanguageInfo()`.
  v0.26.5 terverifikasi 2026-11-05: release GitHub
  TeamNewPipe/NewPipeExtractor tag v0.26.5 + javadoc v0.26.5
  (AudioStream + AudioTrackType + StreamExtractor.getLanguageInfo).

## Yang diubah v1.0.5 (hanya upgrade #1 — TIDAK ada #2–#5)

1. **NewPipeExtractor v0.26.4 → v0.26.5** (`app/build.gradle`).
   Media3 TETAP pin 1.4.1 (upgrade Media3 itu upgrade #4, nanti),
   AGP 8.5.2, Kotlin 1.9.25, compileSdk/targetSdk 34, minSdk 24
   tidak berubah. Signing + gerbang fingerprint keystore tetap dan
   sertifikat APK tidak berubah.

2. **Picker audio sadar-bahasa — jalur NewPipe**
   (`youtube/NewPipeStreamExtractor.kt`).
   - `pickAudioUrls` (mp4 dulu) DIGANTI `pickAudioStreams` dengan
     ranking, tingkat (angka kecil menang):
     0 = ORIGINAL + bahasa cocok dengan bahasa video;
     1 = bahasa cocok (tipe apa pun);
     2 = ORIGINAL (bahasa lain / bahasa tidak diketahui);
     3 = nama track mengandung "original";
     4 = bukan DUBBED dan bukan DESCRIPTIVE;
     5 = sisanya (DUBBED / DESCRIPTIVE).
     Di dalam satu tingkat: mp4 dulu, lalu bitrate tertinggi
     (fallback lama — dipertahankan, bukan dihapus).
   - Bahasa video dihitung berurutan: hint ViewModel (bahasa caption
     Innertube) → caption NewPipe berbahasa "id" → getLanguageInfo()
     → caption pertama → default "id". Kode Java lama "in"
     dinormalisasi ke "id".
   - Semua getter NPE dibungkus try/catch: null di salah satunya =
     jatuh ke tingkat berikutnya, bukan crash. Kalau SEMUA info
     bahasa null, ranking jatuh ke mp4/bitrate seperti v1.0.4.
   - `NewPipeStreamResult` mendapat field baru
     `audioTrackDescription` (mis. "id, original"), dan daftar
     `audioUrls` sekarang diurutkan ranking yang sama — jadi
     `AudioEnergy.analyseMultiple` (yang mencoba stream berurutan)
     juga mulai dari track asli, bukan dari track dub.

3. **Picker audio sadar-bahasa — jalur Innertube adaptive**
   (`youtube/InnertubeClient.kt`).
   - `pickAdaptiveAudio` (bitrate tertinggi buta) DIGANTI ranking
     yang sama polanya, memakai `audioTrack` tiap format adaptive:
     `audioIsDefault` + `displayName` (mis. "Bahasa Indonesia" /
     "English", dipetakan ke kode "id"/"en"/"jv"/"su"/"ms"):
     0 = default + bahasa cocok; 1 = bahasa cocok; 2 = default;
     3 = displayName mengandung "original"; 4 = sisanya.
     Di dalam satu tingkat: bitrate tertinggi (fallback lama).
   - JUJUR soal tipe: Innertube TIDAK memberi flag ORIGINAL/DUBBED
     eksplisit seperti NewPipe, hanya `audioIsDefault`. Jadi
     deskripsinya ditulis "default"/"non-default" (mis.
     "id, default (Bahasa Indonesia)"), BUKAN diklaim "original".
   - `parseVideoInfo` sekarang membaca caption DULU (sebelum memilih
     audio) supaya bahasa preferensi diketahui; bahasa preferensi =
     "id" kalau ada caption id, kalau tidak bahasa caption pertama,
     kalau tidak ada caption default "id".
   - Merge WEB+ANDROID: deskripsi track mengikuti SUMBER audioUrl
     yang menang (WEB kalau WEB punya audio, kalau tidak ANDROID).

4. **Deskripsi track dibawa + ditampilkan di diagnostik**
   (`model/Models.kt`, `ui/ClipKuViewModel.kt`).
   - `VideoStream` mendapat field baru `audioTrackDescription`
     (default `""`, jadi semua konstruksi lama tetap kompilasi).
   - ViewModel menghitung hint bahasa dari caption Innertube
     ("id" kalau ada caption id, kalau tidak bahasa caption
     pertama, null kalau tidak ada caption) dan meneruskannya ke
     `NewPipeStreamExtractor.extract(videoId, preferredLanguage)`.
     Merge NewPipe: deskripsi mengikuti sumber audioUrl yang menang.
   - Diagnostik analisa mendapat segmen baru:
     `audio track: id, original` (atau `en, dubbed`, atau
     `id, default (Bahasa Indonesia)` dari jalur Innertube, atau
     `-` kalau tidak ada info track). Semua isi diagnostik v1.0.4
     dipertahankan (sumber stream, audio HTTP/byte/mime, transkrip,
     skor LLM + error asli).

5. **Versi + dokumen.**
   - `app/build.gradle`: versionCode 5 → 6, versionName 1.0.4 → 1.0.5.
     Workflow sudah menurunkan nama APK dari versionName, jadi aset
     baru HARUS `ClipKu-v1.0.5.apk`.
   - `README.md`: versi/dokumen saja (1.0.5, NPE v0.26.5, picker
     sadar-bahasa).

## File yang diubah (satu baris per file)

- `youtube/NewPipeStreamExtractor.kt` — ranking audio sadar-bahasa
  (getAudioLocale/getAudioTrackType/getAudioTrackName/getLanguageInfo),
  `preferredLanguage` + `normaliseLang`, `describeAudio`,
  `audioTrackDescription` di hasil.
- `youtube/InnertubeClient.kt` — ranking audio adaptive
  (audioTrack.audioIsDefault + displayName), caption dibaca sebelum
  audio dipilih, deskripsi track di merge WEB+ANDROID.
- `model/Models.kt` — field `VideoStream.audioTrackDescription`
  (default "").
- `ui/ClipKuViewModel.kt` — hint bahasa ke NewPipe, deskripsi track
  di merge, segmen "audio track: ..." di diagnostik.
- `app/build.gradle` — version 1.0.5 / versionCode 6, NPE v0.26.5.
- `README.md` — versi/dokumen saja (1.0.5).

Tidak diubah: pemilihan stream VIDEO, bobot scoring, Top-6, jarak
75 detik, snap kalimat, ekstraksi transkrip/audio, prioritas stream
(NewPipe dulu, Innertube fallback), kontras, aliran Whisper
per-clip, aturan teks narasi, tombol Salin, aliran Download/potong,
dan Media3 (tetap 1.4.1).

## Cek yang benar-benar dijalankan (jujur)

- Brace/paren/bracket seimbang (selisih 0) di 4 file Kotlin yang
  diubah + build.gradle.
- Tidak ada referensi tersisa ke `pickAudioUrls` (fungsi lama sudah
  diganti) dan tidak ada duplikat deklarasi `captions`/`heat` di
  InnertubeClient setelah caption dipindah ke atas parseVideoInfo.
- Grep: `getAudioLocale` + `getAudioTrackType` ada di picker NewPipe;
  `audioTrackDescription` ada di Models, InnertubeClient, ViewModel,
  dan diagnostik; versionCode 6 / versionName 1.0.5 di build.gradle.
- Tidak ada secret Putra di code yang diubah (tidak ada key `gsk_`,
  keystore, base64); tidak ada placeholder TODO/FIXME di code yang
  diubah.
- **Kompilasi Gradle penuh TIDAK bisa dijalankan di sandbox ini
  (tidak ada JDK/Gradle) — TIDAK diklaim hijau.** Actions adalah
  gerbang kompilasi. Risiko tertinggi, eksplisit: (a) JitPack
  membangun tag NPE v0.26.5 — kalau JitPack gagal, build merah di
  dependency resolution, bukan di code ClipKu; (b) getter NPE
  `languageInfo`/`audioLocale` — semuanya dibungkus try/catch, jadi
  kegagalan di sana hanya menurunkan ranking ke fallback, bukan
  crash; tapi kalau API-nya ternyata berbeda dari javadoc v0.26.5
  yang dibaca, kompilasi akan merah di file NewPipeStreamExtractor.

## Batasan yang tersisa (bukan janji)

- Kalau SEMUA info bahasa null di sebuah video (locale null, tipe
  null, tidak ada caption, displayName kosong), picker jatuh ke
  mp4/bitrate seperti v1.0.4 — track dub BISA menang lagi di video
  seperti itu. Diagnostik akan menunjukkannya ("audio track: ?, unknown"
  atau "-"), jadi kegagalannya terlihat, bukan diam-diam.
- Cross-check Whisper TIDAK termasuk di v1.0.5 (sengaja — itu
  follow-up dari riset, bukan bagian gas ini): kalau track yang
  salah tetap kepilih, Whisper akan mentranskrip bahasa yang salah
  itu; deteksi bahasa Whisper di 30 detik pertama + re-pick sekali
  adalah pekerjaan lanjutan, bukan janji v1.0.5.
- Picker ini memilih track untuk ANALISA (AudioEnergy + Whisper)
  dan untuk DOWNLOAD adaptive. Kalau video punya progressiveUrl,
  ClipDownload tetap memakai progressive (audio gabungan asli) —
  itu perilaku lama dan sudah benar.
- Download clip 1 menit yang masih lambat (download sumber full
  dulu) TIDAK diperbaiki di v1.0.5 — itu upgrade #2 (pipeline
  segment-only), urutan berikutnya setelah v1.0.5 terverifikasi.

## Blok Termux untuk Putra (commit baru, BUKAN Re-run)

Secret KEYSTORE_B64 untuk bastian57/clipku SUDAH ada — jangan di-set
ulang, jangan bikin keystore baru.

```bash
ZIP=$(ls /sdcard/Download/ClipKu-v1.0.5_source_20261105.zip ~/storage/downloads/ClipKu-v1.0.5_source_20261105.zip 2>/dev/null | head -n 1); test -s "$ZIP" || { echo "ZIP TIDAK KETEMU di Download"; exit 1; }; command -v unzip >/dev/null || pkg install -y unzip; cp "$ZIP" $PREFIX/tmp/clipku105.zip; unzip -o $PREFIX/tmp/clipku105.zip -d ~/clipku; grep -q "1.0.5" ~/clipku/source/app/build.gradle && echo "SOURCE 1.0.5 OK" || { echo "VERSI BUKAN 1.0.5"; exit 1; }; cd ~/clipku/source && git add -A && git commit -m "ClipKu v1.0.5: language-aware audio picker (NPE 0.26.5)" && git push origin main
```

## Acceptance setelah hijau + verifikasi (seperti biasa)

Pasang timpa v1.0.4 tanpa uninstall (sertifikat sama, Groq key
Nalar tidak hilang). Tes utama di video Rocky Gerung yang kemarin
jadi Inggris:
- Analisa ulang → diagnostik harus terbaca `audio track: id, original`
  (atau setidaknya `id, ...` — bukan `en, dubbed`).
- Download clip → audio hasil download harus bahasa Indonesia.
- Tes kontrol di video Liputan6 (Y_2DRQz35-0) → diagnostik tetap
  `id, ...` dan download tetap Indonesia (tidak regresi).
- Clip, Preview, narasi, dan skor LLM tetap seperti v1.0.4.
