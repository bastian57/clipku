# ClipKu v1.0.9 — FIX-NOTES (bugfix download)

Tanggal: 2026-11-06. Version 1.0.9 / versionCode 10. Media3 tetap 1.4.1,
NewPipeExtractor tetap 0.26.5, signing/keystore tidak disentuh, scoring /
narasi / Groq / heatmap tidak disentuh.

## Bukti bug (HP Putra, v1.0.8, 2026-11-06 21:13 WIB)

Video Mahfud MD `SRVrqiu_R6U` (iNews, 10 menit): analisa jalan penuh
(6 clip, transkrip 923 kalimat NEWPIPE, audio 606 detik ter-decode HTTP 200,
skor LLM 7 segmen, jendela LLM 1/1, narasi 124 kata jalan, preview jalan).
Tap Download 13 menit setelah analisa (21:00 -> 21:13) gagal dengan banner
merah "Gagal mengunduh audio sumber."

## 2 cacat yang ditemukan di code (ClipDownload.kt)

### Cacat 1 — lapis 2 (download-penuh) pakai URL basi

`downloadClipDetailed()` punya dua lapis. Lapis 1 (segmen, v1.0.6) memakai
URL fresh dari `resolveFreshStreams()` — resolve ulang tepat sebelum ekspor,
karena URL googlevideo umurnya pendek. Tapi lapis 2 (download-penuh)
memakai `info.stream` — URL dari waktu analisa. Kalau lapis 1 gagal karena
apa pun, lapis 2 mengunduh dengan URL yang sudah 13 menit tua dan bisa
kedaluwarsa -> `downloadToFile` gagal -> banner di atas. Proteksi
anti-kedaluwarsa v1.0.6 hanya menutup lapis 1; lapis 2 bocor.

Cacat yang sama ada di `cutClipToTemp()` (jalur Whisper): segmen audio
pakai URL fresh, fallback potong-penuhnya pakai `info.stream`.

### Cacat 2 — downloadToFile tanpa User-Agent/Referer

`downloadToFile()` di ClipDownload mengirim request OkHttp polos tanpa
header apa pun. Bandingkan `AudioEnergy.analyseOne()` (analisa, sukses
HTTP 200 di video yang sama): mengirim UA browser + Referer YouTube.
Ini item P2-7 di audit v1.0.7. Request tanpa header adalah tersangka
kedua untuk penolakan (HTTP 403) dari googlevideo.

### Cacat 3 (bonus, dari pitch) — kegagalan segmen hilang diam-diam

`trySegmentClip` mengembalikan Boolean saja. Kalau lapis 1 gagal, tidak
ada jejak kenapa — dari HP tidak ketahuan segmen mati karena timeout,
403, atau output tidak valid.

## Perubahan (4 poin pitch yang digas)

1. **Lapis 2 pakai URL fresh.** `downloadClipDetailed()` lapis 2 kini
   memakai stream `fresh` (hasil `resolveFreshStreams()` yang sama
   dengan lapis 1) untuk cabang progressive DAN adaptive (video+audio).
   `cutClipToTemp()` resolve fresh SEKALI di awal fungsi dan memakainya
   untuk segmen audio DAN fallback potong-penuh. `resolveFreshStreams`
   sendiri sudah mengembalikan `info.stream` sebagai fallback kalau
   re-extract gagal, jadi `fresh` selalu terisi.
2. **Header browser di downloadToFile.** Setiap request kini mengirim
   `User-Agent: Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36
   Chrome/120.0 Safari/537.36` + `Referer: https://www.youtube.com/`
   — string yang persis sama dengan AudioEnergy. Timeout tidak diubah
   (connect 20 dtk, read 120 dtk).
3. **Error download membawa alasan aslinya.** `downloadToFile` kini
   mengembalikan `DownloadOutcome(ok, reason)`; reason diklasifikasi:
   `HTTP <code>` (mis. 403), `host X gagal resolve (DNS)` untuk
   UnknownHostException (dicari di rantai cause, seperti AudioEnergy),
   `timeout` untuk SocketTimeoutException, `0 byte terunduh`, atau
   pesan exception dipangkas 80 karakter. Pesan lama dipertahankan
   sebagai awalan — mis. `Gagal mengunduh audio sumber. (HTTP 403)` —
   bukan pesan generik lagi. URL lengkap dan Groq key TIDAK PERNAH masuk
   pesan error. ViewModel juga menuliskan alasan gagal download ke
   diagnostik (` • download clip N gagal: ...`).
4. **Alasan gagal segmen dicatat.** `SegmentExport` punya varian baru
   `trySegmentClipDetailed` / `trySegmentAudioDetailed` yang
   mengembalikan `Pair<Boolean, String?>` (alasan singkat; URL di
   pesan exception diganti `<url>` supaya tidak bocor ke diagnostik).
   `trySegmentClip` / `trySegmentAudio` lama dipertahankan sebagai
   wrapper (`.first`). Timeout 180 detik dibedakan eksplisit
   ("timeout 180 detik"). `DownloadResult` mendapat field baru
   `segmentFailureReason` (default null, kompatibel); ViewModel
   menampilkannya di diagnostik kalau akhirnya jatuh ke full-download:
   ` • clip N: full-download (segmen gagal: ...)`. Format diagnostik
   tetap satu paragraf dipisah " • ", tidak ada format lain yang diubah.

## File yang diubah (4)

- `app/build.gradle` — versionCode 9 -> 10, versionName 1.0.8 -> 1.0.9.
- `app/src/main/java/com/daftech/clipku/cutting/ClipDownload.kt` —
  BROWSER_UA + Referer, DownloadOutcome + klasifikasi alasan,
  lapis 2 + cutClipToTemp pakai `fresh`, DownloadResult.segmentFailureReason.
- `app/src/main/java/com/daftech/clipku/cutting/SegmentExport.kt` —
  trySegmentClipDetailed / trySegmentAudioDetailed + shortReason.
- `app/src/main/java/com/daftech/clipku/ui/ClipKuViewModel.kt` —
  requestDownload menampilkan alasan segmen + alasan download gagal
  di diagnostik; error banner tetap memakai pesan exception.

## Limit jujur

- Alasan segmen tetap singkat (maks ~120 karakter, pesan ExportException
  dipangkas) — cukup untuk membedakan timeout / 403 / output tidak
  valid, bukan stack trace lengkap.
- URL fresh tetap bisa kedaluwarsa kalau jeda antara tap Download dan
  selesainya download sangat lama — re-extract dilakukan SEKALI per tap,
  bukan per menit. Kalau download-penuh video panjang memakan waktu
  lebih lama dari umur URL fresh, kegagalan masih mungkin; bedanya
  sekarang alasan itu terlihat di diagnostik (HTTP 403), bukan banner
  generik.
- Kalau lapis 1 segmen gagal karena sebab yang sama dengan lapis 2
  (mis. host googlevideo mati total), kedua lapis tetap gagal — v1.0.9
  memperbaiki URL basi + header, bukan ketersediaan host.
- `downloadToFile` tetap satu percobaan per URL (tanpa retry) — retry
  berulang di level ini sengaja tidak ditambahkan di versi ini.

## Acceptance HP (masih harus dites Putra)

Video Mahfud MD `SRVrqiu_R6U` yang sama:
1. Analisa, lalu tap Download LANGSUNG (tanpa jeda) — harus tersimpan,
   laporkan jalur ("segment" / "full-download") + waktunya dalam detik.
2. Analisa ulang, tunggu ±15 menit, lalu tap Download — harus tersimpan
   juga (ini tes kedaluwarsa yang dulu gagal); kalau gagal, diagnostik
   kini harus menunjukkan alasan aslinya (mis. HTTP 403 / DNS /
   timeout), bukan banner generik.
