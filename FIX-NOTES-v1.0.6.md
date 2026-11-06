# ClipKu v1.0.6 — FIX NOTES (upgrade #2, gas 2026-11-05)

## Keadaan yang terbukti (bukan dugaan ulang)

- v1.0.5 hijau dan terverifikasi 2026-11-05 17:04 WIB (run 41999250794,
  APK 21.436.295 byte). Acceptance HP v1.0.5 — Rocky harus
  "audio track: id, original" + clip Indonesia, Liputan6 tetap
  Indonesia — masih berjalan saat v1.0.6 ini dibangun (gas nomor 2
  sudah diberikan Putra sebelum acceptance itu ditutup).
- Keluhan yang dijawab upgrade ini: download clip 1 menit terasa
  LAMA karena ClipDownload v1.0.5 mengunduh video sumber FULL ke
  cache dulu, baru memotong 60 detik. Untuk video panjang, clip
  pertama = waktu download video utuh.
- Riset engine 2026-11-05 (report.md bagian C + F upgrade #2):
  pisahkan download ANALISA dari download CLIP; analisa pakai
  audio-only; tiap clip hanya ambil segmen [start-2 dtk, end+2 dtk]
  lewat Range / DASH-segment + stream-copy mux; fallback
  download-penuh selalu ada. Peringatan riset yang DIIKUTI:
  (a) Transformer menerima input DASH/HLS dan hanya mengambil
  segmen yang di-clip = TIDAK TERVERIFIKASI — jangan klaim itu;
  (b) Range cap ~1 MiB di sebagian URL googlevideo, URL SABR
  kedaluwarsa, PO-token 403 — semua dimitigasi fallback, bukan
  janji; (c) FFmpeg dihindari kalau Transformer belum terbukti
  kurang — belum terbukti, jadi TIDAK ditambah.

## Yang SUDAH benar sebelum v1.0.6 (dicatat jujur, bukan diklaim baru)

- **Transcript-first SUDAH jalan sejak v1.0.1:** analisa memakai
  caption (WEB -> TIMEDTEXT -> ANDROID, lalu NewPipe) DULU untuk
  kandidat highlight; Whisper HANYA per clip untuk narasi kalau
  clip tidak punya transkrip. v1.0.6 tidak mengubah urutan itu —
  hanya menambah label "analisa: caption / audio-saja" di
  diagnostik supaya sumbernya terlihat.
- **Audio-only untuk analisa SUDAH jalan sejak v1.0.3:**
  AudioEnergy mengunduh HANYA stream audio (bukan video) untuk
  RMS per detik. Yang masih penuh adalah durasi audionya (RMS
  butuh seluruh audio untuk scoring) — itu kebutuhan scoring,
  bukan pemborosan; v1.0.6 tidak mengubahnya.

## Yang diubah v1.0.6 (hanya upgrade #2 — TIDAK ada #3–#5)

1. **Jalur segmen untuk ekspor clip — baru** (`cutting/SegmentExport.kt`).
   - URL stream FRESH di-resolve tepat sebelum ekspor (NewPipe
     extract ulang, metadata saja — URL googlevideo bisa
     kedaluwarsa / SABR / PO-token; ranking audio sadar-bahasa
     v1.0.5 dipakai apa adanya, TIDAK diubah).
   - Transformer dari URL remote + ClippingConfiguration
     [start, end] — tumpukan DataSource yang sama dengan ExoPlayer
     preview, yang SUDAH terbukti di HP bisa memutar rentang tengah
     tanpa download utuh (baca moov, seek via HTTP Range ke sampel
     clip). Progressive = satu item; adaptive = Composition dua
     sequence (video-only + audio, keduanya di-clip ke rentang
     yang sama).
   - Output DIVALIDASI dengan MediaExtractor (track video ada +
     durasi 0.4x–1.6x dari yang diminta); gagal / tidak valid =
     file dihapus, hasil false — clip rusak tidak pernah sampai
     ke user.
   - PENYIMPANGAN JUJUR dari mekanisme harfiah di riset: yang
     diimplementasikan BUKAN fetch Range byte-per-byte + remux
     MP4 tulisan tangan. Merakit ulang moov (stco/stsz/stts/stss)
     sendiri adalah ratusan baris rawan salah dan TIDAK BISA
     dites di sandbox ini (tanpa JDK/Gradle). Yang dipakai adalah
     Transformer streaming-trim — muxing-nya tetap code Media3
     yang sudah terbukti di v1.0.3; yang baru hanya INPUT-nya
     (URL remote, bukan file lokal penuh). Berapa byte persisnya
     yang ditransfer TIDAK bisa diukur dari code (Transformer
     tidak mengekspos counter byte) — bukti nyatanya adalah WAKTU
     download di HP pada acceptance, bukan angka dari sini.
   - Batas potong mengikuti keyframe terdekat: hasil boleh meleset
     sampai ~2 detik dari batas yang diminta. Batas yang diterima
     di scope ini; UI tidak mengklaim potongan akurat-frame.

2. **Download clip dua lapis** (`cutting/ClipDownload.kt`).
   - Lapis 1 (baru): jalur segmen di atas, timeout 180 detik.
   - Lapis 2 (v1.0.5, TIDAK DIUBAH): download-penuh ke cache +
     CuttingEngine, dipakai pada KEGAGALAN APA PUN di lapis 1
     (timeout, 403, URL kedaluwarsa, output tidak valid).
   - `downloadClipDetailed()` baru mengembalikan DownloadResult
     (path + method "segment" / "full-download"); `downloadClip()`
     lama dipertahankan sebagai wrapper (mengembalikan path).

3. **Whisper audio-only segmen dulu** (`cutting/ClipDownload.kt`,
   `cutClipToTemp`).
   - Whisper hanya butuh AUDIO clip ~60 detik: dicoba dulu
     `SegmentExport.trySegmentAudio` (URL audio fresh + Transformer
     dari URL, tanpa video utuh), timeout 120 detik. Gagal = jatuh
     ke potong-penuh dari sumber cache seperti dulu. File temp
     tetap .mp4 (MP4 berisi AAC); WhisperClient mengirimnya sebagai
     clip.mp4 dan Groq membaca isi, bukan ekstensi.

4. **Diagnostik** (`ui/ClipKuViewModel.kt`).
   - Analisa: "analisa: caption" kalau transkrip ada,
     "analisa: audio-saja" kalau tidak ada caption.
   - Per download clip: " • clip N: segment" atau
     " • clip N: full-download" ditambahkan ke diagnostik, dan
     status tersimpan menyebut metodenya ("Clip N tersimpan:
     ... (segment)"). Semua isi diagnostik v1.0.5 dipertahankan.

5. **Versi + dokumen.**
   - `app/build.gradle`: versionCode 6 → 7, versionName 1.0.5 → 1.0.6.
     Workflow sudah menurunkan nama APK dari versionName, jadi aset
     baru HARUS `ClipKu-v1.0.6.apk`.
   - `README.md`: versi/dokumen saja (1.0.6, pipeline segment-only).

## File yang diubah (satu baris per file)

- `cutting/SegmentExport.kt` — BARU: Transformer dari URL remote
  + clipping, validasi output via MediaExtractor, tanpa lempar
  exception ke pemanggil.
- `cutting/ClipDownload.kt` — resolveFreshStreams, downloadClipDetailed
  (segmen dulu + fallback penuh), cutClipToTemp (segmen audio dulu),
  DownloadResult; jalur download-penuh v1.0.5 TIDAK DIUBAH isinya.
- `ui/ClipKuViewModel.kt` — requestDownload memakai
  downloadClipDetailed + mencatat method di diagnostik/status;
  segmen "analisa: ..." di diagnostik analisa.
- `app/build.gradle` — version 1.0.6 / versionCode 7.
- `README.md` — versi/dokumen saja (1.0.6, segment-only).

Tidak diubah: ranking picker audio sadar-bahasa v1.0.5, bobot /
kriteria scoring, Top-6, jarak 75 detik, snap kalimat, aliran
Whisper per-clip + model whisper-large-v3-turbo, aturan teks
narasi, tombol Salin, kontras/UI selain tambahan diagnostik,
prioritas stream (NewPipe dulu, Innertube fallback), dan Media3
(tetap 1.4.1 — upgrade Media3 itu upgrade #4, nanti). FFmpeg
TIDAK ditambah. STT offline TIDAK ditambah (upgrade #5, nanti).

## Cek yang benar-benar dijalankan (jujur)

- Brace/paren/bracket seimbang (selisih 0) di file Kotlin yang
  diubah/ditambah + build.gradle.
- Grep: versionCode 7 / versionName 1.0.6 di build.gradle;
  `trySegmentClip` / `trySegmentAudio` / `validateClip` ada di
  SegmentExport; `downloadClipDetailed` + `resolveFreshStreams` ada
  di ClipDownload; `clip ${clip.index}: ` + `analisa: ` ada di
  ViewModel; `media3Version = '1.4.1'` tidak berubah; jalur
  download-penuh (downloadToFile + cutProgressive + cutAdaptive)
  masih ada utuh di ClipDownload.
- Tidak ada secret Putra di code yang diubah (tidak ada key `gsk_`,
  keystore, base64); tidak ada placeholder TODO/FIXME di code yang
  diubah.
- **Kompilasi Gradle penuh TIDAK bisa dijalankan di sandbox ini
  (tidak ada JDK/Gradle) — TIDAK diklaim hijau.** Actions adalah
  gerbang kompilasi. Risiko tertinggi, eksplisit: (a) Composition
  dua sequence (video + audio remote) di Transformer 1.4.1 untuk
  jalur adaptive — kalau API/perilakunya berbeda dari yang
  diasumsikan, kompilasi atau runtime jalur segmen adaptive gagal
  dan jatuh ke fallback (clip tetap jadi, hanya lambat); (b)
  Transformer dari URL remote — kalau Transformer 1.4.1 menolak
  input remote, SEMUA percobaan segmen gagal cepat dan jatuh ke
  fallback; (c) seberapa banyak byte yang benar-benar ditransfer
  jalur segmen hanya terbukti dari waktu download di HP, bukan
  dari code — acceptance WAJIB mengukur waktu, bukan cuma melihat
  label "segment" di diagnostik.

## Batasan yang tersisa (bukan janji)

- Kalau jalur segmen gagal (Range cap, 403 tengah jalan, URL
  kedaluwarsa, SABR, PO-token, Transformer menolak URL remote),
  download tetap jalan lewat download-penuh — lambat seperti
  v1.0.5, tapi clip tetap jadi. Diagnostik akan menunjukkan
  "full-download" — itu BUKAN kegagalan, itu fallback bekerja.
- Transformer dari URL bisa tetap mengunduh lebih dari segmen
  (mis. kalau extractor tidak bisa seek di format itu) — tanpa
  counter byte, yang bisa diukur hanya waktu. Kalau waktu download
  clip dari video panjang TIDAK membaik padahal labelnya
  "segment", laporkan angkanya — itu data untuk iterasi berikutnya,
  bukan bukti jalur segmen "bohong".
- Batas potong ±2 detik (keyframe snap) tetap berlaku di kedua
  jalur, seperti v1.0.5.
- Analisa (RMS audio) tetap mengunduh audio UTUH — itu kebutuhan
  scoring, bukan bagian dari upgrade ini.
- Acceptance v1.0.5 (Rocky "id, original") TIDAK digantikan oleh
  v1.0.6 — v1.0.6 dibangun di atas source v1.0.5 yang sama, jadi
  tes Rocky + Liputan6 tetap berlaku dan harus diulang di APK
  v1.0.6.

## Blok Termux untuk Putra (commit baru, BUKAN Re-run)

Secret KEYSTORE_B64 untuk bastian57/clipku SUDAH ada — jangan di-set
ulang, jangan bikin keystore baru.

```bash
ZIP=$(ls /sdcard/Download/ClipKu-v1.0.6_source_20261105.zip ~/storage/downloads/ClipKu-v1.0.6_source_20261105.zip 2>/dev/null | head -n 1); test -s "$ZIP" || { echo "ZIP TIDAK KETEMU di Download"; exit 1; }; command -v unzip >/dev/null || pkg install -y unzip; cp "$ZIP" $PREFIX/tmp/clipku106.zip; unzip -o $PREFIX/tmp/clipku106.zip -d ~/clipku; grep -q "1.0.6" ~/clipku/source/app/build.gradle && echo "SOURCE 1.0.6 OK" || { echo "VERSI BUKAN 1.0.6"; exit 1; }; cd ~/clipku/source && git add -A && git commit -m "ClipKu v1.0.6: transcript-first + segment-only pipeline" && git push origin main
```

## Acceptance setelah hijau + verifikasi (seperti biasa)

Pasang timpa v1.0.5 tanpa uninstall (sertifikat sama, Groq key
Nalar tidak hilang). Tes utama — WAKTU download, bukan cuma label:
- Video PANJANG (mis. video 91 menit / ALI, atau video Rocky):
  analisa ulang → diagnostik "analisa: caption" (kalau ada
  transkrip) dan "audio track: id, original" (Rocky).
- Download SATU clip dari video panjang itu → catat WAKTUNYA dan
  diagnostik harus terbaca "clip N: segment". Bandingkan dengan
  v1.0.5 (download video utuh dulu): harus terasa JAUH lebih
  cepat. Kalau labelnya "segment" tapi waktunya sama lambatnya
  dengan v1.0.5, LAPORKAN ANGKA WAKTUNYA — itu data penting.
- Hasil clip: putar — video + audio lengkap, durasi ±2 detik dari
  yang diminta, audio bahasa Indonesia (Rocky).
- Kalau diagnostik terbaca "clip N: full-download" tapi clip
  tetap jadi dan benar, itu fallback bekerja — laporkan juga,
  supaya tahu jalur segmen gagal di video itu.
- Tes kontrol Liputan6 (Y_2DRQz35-0, 8 menit) → clip tetap
  Indonesia, narasi + skor LLM tetap seperti v1.0.5.
- Buat Narasi di clip tanpa transkrip (Whisper) → tetap jalan;
  itu sekarang lewat segmen audio-only dulu.
