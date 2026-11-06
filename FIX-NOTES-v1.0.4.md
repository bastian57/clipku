# ClipKu v1.0.4 — FIX NOTES (REVISI, gas 2026-11-05 00:27 WIB)

## Keadaan yang terbukti (bukan dugaan ulang)

- v1.0.3 hijau dan terpasang. Clip BEKERJA: video KompasTV Merauke
  14 menit memberi transkrip 1261 kalimat via NEWPIPE, audio 880 detik
  ter-decode HTTP 200, Clip 1 01:12–02:13 (60 detik, skor 62) + Clip 2,
  Preview dan Download ada.
- Narasi GAGAL dengan pesan generik "Groq gagal menjawab", DAN scoring
  juga gagal (diagnostik: skor LLM 0 segmen). Jadi jalur chat Groq
  ClipKu gagal sistematis — bukan tombol narasi saja, bukan key kosong
  (diagnostik: Groq key ada), bukan clip-nya.

## Koreksi penting: key-nya key Nalar, BUKAN SuaraKu

Ralat Putra 2026-11-05 00:24 WIB: Groq key di ClipKu SAMA PERSIS dengan
key yang dipakai di app Nalar. Entri sebelumnya yang menyebut SuaraKu
salah dan dikoreksi di sini — jangan ulangi klaim SuaraKu itu.

Key itu JALAN di pipeline Nalar (GitHub Actions, model qwen/qwen3-32b
dengan fallback openai/gpt-oss-120b). Jadi key yang sama terbukti hidup
di satu jalur, tapi gagal di jalur chat ClipKu dari HP. Itu yang
membuat kandidat di bawah ini layak diutamakan.

## Kandidat utama: User-Agent hilang → penolakan Cloudflare 1010

Pelajaran lama Nalar/Phoenix: request ke API Groq yang memakai
User-Agent default pernah kena Cloudflare error 1010, dan begitu
User-Agent diganti yang seperti browser, request lolos.

Code ClipKu sebelum v1.0.4: `GroqClient` TIDAK mengirim header
User-Agent sama sekali (hanya Authorization + Content-Type) — jadi
yang terkirim adalah default OkHttp. Polanya sama persis dengan
pelajaran 1010 itu.

Status kandidat ini: KUAT, TAPI BUKAN TERBUKTI. Bukti yang akan
memastikan atau membantahnya di HP adalah butir error-surfacing di
bawah: kalau setelah v1.0.4 narasi + skor LLM jalan, UA adalah
penyebabnya; kalau masih gagal, pesan error asli yang baru akan
menyebutkan penyebab sebenarnya (401/404/429/timeout).

## Yang diubah v1.0.4 (semua wajib, tidak ada yang ditambah di luar ini)

1. **User-Agent browser (perbaikan utama).**
   - `GroqClient.kt`: header `User-Agent` browser ditambahkan ke
     SEMUA request chat completions (scoring + narasi). Nilainya sama
     persis dengan BROWSER_UA yang sudah dipakai Innertube/AudioEnergy:
     `Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36`.
   - `WhisperClient.kt`: header yang sama ditambahkan ke request audio
     transcriptions, supaya jalur Whisper tidak gagal hanya karena
     User-Agent default.
   - URL Groq, penanganan Authorization, dan sumber key (Settings saja,
     tidak pernah di-hardcode, tidak pernah di-log) TIDAK berubah.
   - Grep membuktikan tidak ada lagi jalur request Groq tanpa
     User-Agent: kedua file di atas adalah satu-satunya jalur request
     ke api.groq.com, dan keduanya sekarang mengirim header itu.

2. **Error asli Groq ditampilkan, bukan ditelan.**
   - Dulu: `postOnce` mengembalikan null untuk semua kegagalan
     (non-200, konten kosong, exception) dan `callChat` melempar pesan
     generik yang sama — tidak mungkin membedakan 401/404/429/400/
     timeout.
   - Sekarang: `postOnce` mencatat kegagalan terakhir (`lastError`):
     HTTP + pesan/kode error Groq dari body
     (`{"error": {"message": "...", "code": "..."}}`, dipotong),
     atau "jawaban kosong", atau "jaringan/timeout: ...".
     `callChat` melempar:
     `Groq gagal menjawab (<detail>). Periksa Groq API key di Settings
     dan koneksi internet.`
     Contoh yang akan terlihat di HP:
     - `Groq gagal menjawab (HTTP 404 model_not_found: ...). ...`
     - `Groq gagal menjawab (HTTP 429 rate_limit_exceeded: ...). ...`
     - `Groq gagal menjawab (HTTP 401 invalid_api_key: ...). ...`
     - `Groq gagal menjawab (jaringan/timeout: ...). ...`
     Detail TIDAK PERNAH memuat API key (hanya HTTP + pesan Groq).
   - Penyaluran ke UI (tanpa mengubah signature pemanggil):
     - Narasi: `ViewModel.generateNarrationFor` sudah memakai
       `e.message` sebagai `narrationError`, jadi pesan detail itu
       langsung tampil di bawah tombol Buat Narasi — tidak perlu
       perubahan di jalur itu.
     - Scoring: `ViewModel.startAnalysis` sekarang menyimpan
       `llmError` dari exception scoring dan diagnostik menampilkan
       `skor LLM: 0 segmen (gagal: <detail>)` bila scoring gagal,
       bukan lagi sekadar "0 segmen" tanpa alasan.
     - Gagal scoring tetap TIDAK memblokir clip; gagal narasi tetap
       TIDAK memblokir download (perilaku lama dipertahankan).

3. **Fallback model ketiga.**
   - Rantai chat menjadi, berurutan, pertama yang berhasil menang:
     `openai/gpt-oss-120b` → `openai/gpt-oss-20b` →
     `qwen/qwen3.8-27b`.
   - Model qwen adalah model default Phoenix/Nalar yang terbukti jalan
     dengan key yang sama, dan terdaftar di antara model Groq yang
     masih menjawab per September 2026.
   - Prompt system/user, temperature (0.4), format JSON scoring,
     aturan narasi 110–140 kata, dan Salin-hanya-blok-final TIDAK
     berubah. Signature `scoreSegments` / `generateNarration` /
     `callChat` / `postOnce` TIDAK berubah (hanya isi callChat/postOnce
     yang berubah), jadi tidak ada pemanggil yang perlu diubah
     signature-nya — risiko regresi kompilasi di sini ditekan sekecil
     mungkin (lihat "Risiko tertinggi").

4. **Versi + signing.**
   - `app/build.gradle`: versionCode 4 → 5, versionName 1.0.3 → 1.0.4.
     Workflow sudah menurunkan nama APK dari versionName, jadi aset
     baru HARUS `ClipKu-v1.0.4.apk`.
   - AGP 8.5.2, Kotlin 1.9.25, compileSdk/targetSdk 34, minSdk 24,
     media3 pin 1.4.1, NewPipeExtractor v0.26.4 via JitPack: tidak berubah.
   - Signing + gerbang fingerprint keystore tetap dan sertifikat APK
     tidak berubah (lihat build.gradle / workflow v1.0.1).

## File yang diubah (satu baris per file)

- `analysis/GroqClient.kt` — User-Agent browser, error asli (lastError),
  rantai model + qwen/qwen3.8-27b, komentar pelajaran 1010.
- `groq/WhisperClient.kt` — User-Agent browser, pesan error Groq di
  exception Whisper (HTTP + pesan, tanpa key).
- `ui/ClipKuViewModel.kt` — llmError dari exception scoring masuk
  diagnostik (`skor LLM: N segmen (gagal: <detail>)` bila gagal).
- `app/build.gradle` — version 1.0.4 / versionCode 5.
- `README.md` — versi/dokumen saja (1.0.4).

Tidak diubah: bobot scoring, Top-6, jarak 75 detik, snap kalimat,
ekstraksi transkrip/audio, prioritas stream, kontras, aliran Whisper
per-clip, aturan teks narasi, dan tombol Salin.

## Cek yang benar-benar dijalankan (jujur)

- Brace/paren/bracket seimbang (selisih 0) di 3 file Kotlin yang diubah.
- XML di res/ well-formed (tidak ada XML yang diubah).
- Groovy def-only di build files; tidak ada nama fungsi ViewModel pola
  setter-clash.
- Tidak ada secret Putra di code yang diubah (tidak ada key `gsk_`,
  keystore, base64); tidak ada placeholder TODO/FIXME di code yang diubah.
- Grep: satu-satunya jalur request ke api.groq.com adalah GroqClient
  (chat) dan WhisperClient (transcriptions), dan keduanya sekarang
  mengirim header User-Agent.
- Pemanggil GroqClient dicek: `scoreSegments` dan `generateNarration`
  dipakai ViewModel dengan signature yang sama seperti sebelumnya;
  `postOnce`/`callChat` hanya dipakai internal GroqClient.
- **Kompilasi Gradle penuh TIDAK bisa dijalankan di sandbox ini
  (tidak ada JDK/Gradle) — TIDAK diklaim hijau.** Actions adalah
  gerbang kompilasi.
- Risiko tertinggi, eksplisit: perubahan isi `callChat`/`postOnce`
  di GroqClient. Ditekan dengan mempertahankan signature semua fungsi
  publik dan hanya menambah field privat `lastError` + satu konstanta
  model; tetapi kalau ada pemanggil yang terlewat, regresinya akan
  terlihat di Actions (kompilasi) atau di HP (error narasi/scoring
  baru). Parameter `model` di `postOnce` kini hanya diteruskan
  (model sudah tertulis di body JSON) — itu warning Kotlin saja,
  bukan error kompilasi, sama seperti pola sebelumnya.

## Batasan yang tersisa (bukan janji)

- UA adalah kandidat kuat, bukan jaminan. Kalau error asli yang baru
  ternyata HTTP 401, key itu sendiri harus dicek ulang di konsol Groq
  (misalnya key dirotasi/dicabut) walau pernah jalan di Nalar — Nalar
  jalan di GitHub Actions, ClipKu jalan dari HP, dan status key bisa
  berubah antar waktu.
- Kalau errornya HTTP 429 (rate limit), solusinya menunggu dan coba
  lagi, bukan ganti key atau model — fallback model ketiga tidak
  membantu bila limitnya per akun, bukan per model.
- Kalau errornya HTTP 404 model_not_found untuk gpt-oss, rantai akan
  jatuh ke qwen — dan diagnostik tetap akan mencatat kegagalan model
  pertama lewat pesan terakhir hanya bila semua model gagal; bila
  qwen berhasil, narasi/scoring jalan dan tidak ada error yang tampil
  (perilaku fallback yang benar).
- Timeout jaringan HP (sinyal lemah) tetap bisa gagal dengan pesan
  "jaringan/timeout" — itu data, bukan bug baru.

## Blok Termux untuk Putra (commit baru, BUKAN Re-run)

Secret KEYSTORE_B64 untuk bastian57/clipku SUDAH ada — jangan di-set
ulang, jangan bikin keystore baru.

```bash
ZIP=$(ls /sdcard/Download/ClipKu-v1.0.4_source_20261105.zip ~/storage/downloads/ClipKu-v1.0.4_source_20261105.zip 2>/dev/null | head -n 1); test -s "$ZIP" || { echo "ZIP TIDAK KETEMU di Download"; exit 1; }; command -v unzip >/dev/null || pkg install -y unzip; cp "$ZIP" $PREFIX/tmp/clipku104.zip; unzip -o $PREFIX/tmp/clipku104.zip -d ~/clipku; grep -q "1.0.4" ~/clipku/source/app/build.gradle && echo "SOURCE 1.0.4 OK" || { echo "VERSI BUKAN 1.0.4"; exit 1; }; cd ~/clipku/source && git add -A && git commit -m "ClipKu v1.0.4: Groq UA + error surfacing + qwen fallback" && git push origin main
```

## Acceptance setelah hijau + verifikasi (seperti biasa)

Pasang timpa v1.0.3 tanpa uninstall (sertifikat sama). Di video yang
sudah berhasil clip-nya (KompasTV Merauke 14 menit):
- Tap Buat Narasi → narasi harus muncul (bila UA adalah penyebabnya),
  atau error barunya harus menyebutkan HTTP + pesan Groq yang jelas.
- Diagnostik analisa ulang: bila scoring masih gagal, harus terbaca
  `skor LLM: 0 segmen (gagal: HTTP ...)`, bukan sekadar 0 segmen.
- Clip, Preview, Download, dan Salin-hanya-blok-final tetap seperti
  v1.0.3.
