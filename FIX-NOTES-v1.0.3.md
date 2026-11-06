# ClipKu v1.0.3 — Fix Notes

## Status
Source only, BUKAN APK, belum terverifikasi build. Actions adalah gerbang
kompilasi. Versi 1.0.3 / versionCode 4. Package `com.daftech.clipku` tidak
berubah. Secret `KEYSTORE_B64` sudah ada — jangan set ulang, jangan bikin
keystore baru.

## Keadaan terbukti (bukan dugaan ulang)
- v1.0.2 hijau dan terpasang. NewPipe JALAN: diagnostik menunjukkan
  `sumber stream: NEWPIPE` dan `audio: URL ada` (sebelumnya URL tidak ada),
  dan track caption muncul.
- Tapi pada DUA video, gagal dengan pola identik:
  - ALI `ezLxfx1qMyE`, 91 menit: track caption 50, transkrip 0 kalimat
    sumber NONE, audio URL ada, 0 detik ter-decode, heatmap 0.
  - SPEAK UP `ToFtmFOuARw` (Abraham Samad), 37 menit: track caption 1,
    transkrip 0 kalimat sumber NONE, audio URL ada, 0 detik ter-decode,
    heatmap 0.
  - Groq key ada di kedua video.

## Dua bug tahap yang diperbaiki di sini
### A. Audio: gagal diam-diam, tanpa jejak tahap
`AudioEnergy` v1.0.2 mengunduh tanpa User-Agent/Referer, memakai file
sementara tanpa ekstensi, dan mengembalikan map kosong pada KEGAGALAN
APAPUN — tidak ada jejak apakah unduhannya 403, filenya halaman error,
atau codec-nya gagal. Dua video berbeda, hasil sama persis = sistematis,
bukan per-video.

### B. Transkrip: cuma satu track yang dicoba
`fetchTranscriptFromTracks` v1.0.2 memilih EXACTLY SATU track (id manual >
id auto > en manual > en auto > pertama) dan mengembalikan kosong kalau
track itu gagal, padahal 49 track lain belum dicoba. Ia juga melempar URL
subtitle NewPipe yang sudah ber-fmt (mis. srv3, bukan VTT) langsung ke
`VttParser`, yang tidak akan menghasilkan kalimat dari payload non-VTT.

## Perbaikan v1.0.3
1. **Diagnostik tahap audio** — `AudioEnergy` kini mengembalikan
   `AudioAnalysisResult` (bukan sekadar map): map per-detik + `httpCode`,
   `downloadedBytes`, `mime`, `error` singkat (tanpa stack trace), dan
   `streamsTried`. Diagnostik UI menampilkan semuanya (lihat contoh di
   bawah). Logcat tidak dipakai sebagai satu-satunya jejak, karena user
   tidak bisa melihat Logcat.
2. **Unduhan audio** — request kini memakai User-Agent browser dan
   Referer `https://www.youtube.com/`. File sementara diberi ekstensi dari
   Content-Type (mp4 -> `.m4a`, webm -> `.webm`, mpeg/mp3 -> `.mp3`,
   ogg/opus -> `.ogg`) sebelum body ditulis, supaya MediaExtractor lebih
   mudah mengenali formatnya. File tetap dihapus di `finally`.
3. **Fallback stream audio berurutan** — `NewPipeStreamExtractor` kini
   mengembalikan `audioUrls` berurutan (mp4 dulu, lalu sisanya, tanpa
   duplikat). `AudioEnergy.analyseMultiple` mencoba satu per satu: stream
   yang decode-nya 0 detik langsung dilanjutkan ke berikutnya, sampai
   satu berhasil atau daftar habis. `audioUrl` tunggal tetap dipertahankan
   sebagai elemen pertama daftar itu.
4. **Transkrip: semua track, dua format per track** —
   `fetchTranscriptFromTracks` kini mengiterasi track berurutan (id manual,
   id auto, en manual, en auto, lalu sisanya sesuai urutan asli, tanpa
   duplikat baseUrl). Per track dicoba (a) VTT lalu (b) json3 dari URL
   track yang sama. Aturan fmt: URL yang sudah `fmt=vtt` dipakai apa
   adanya; URL dengan fmt lain diganti fmt-nya menjadi vtt untuk varian
   VTT (bukan ditumpuk); URL tanpa fmt ditambah `fmt=vtt` / `fmt=json3`.
   Parser json3 dipakai ulang oleh `fetchTimedtext` dan fallback per
   track. Track + format pertama yang menghasilkan kalimat tidak kosong
   langsung menang. Urutan fallback luar TIDAK berubah: track ->
   TIMEDTEXT id/en -> ANDROID.
5. **Diagnostik diperluas** — semua field lama dipertahankan, ditambah:
   `audio HTTP`, `audio byte`, `audio mime`, `stream dicoba`,
   `audio error` (hanya kalau ada), dan `track dicoba` pada transkrip.

### Contoh baris diagnostik baru (angka ilustrasi, formatnya yang dijamin)
```
Diagnostik: ... • transkrip: 0 kalimat (sumber: NONE, track dicoba: 50) •
... • audio: URL ada, 0 detik ter-decode • sumber stream: NEWPIPE •
audio HTTP: 200 • audio byte: 12345678 • audio mime: audio/mp4 •
stream dicoba: 3 • audio error: decode 0 sampel • Groq key: ada • ...
```
Bila audio berhasil, `audio error` tidak tampil dan detik ter-decode > 0.

## Yang TIDAK berubah
Scoring (bobot, Top-6, gap 75 detik, snap kalimat), narasi 110-140 kata
plain text, Salin hanya blok final, Whisper hanya per clip, kontras,
prioritas stream (NewPipe dulu, lalu Innertube WEB/TIMEDTEXT/ANDROID).
Tidak ada mesin baru / yt-dlp, cari topik, edit kreatif, auto-download,
auto-publish, atau generate suara di APK.

## File yang berubah (satu baris per file)
- `analysis/AudioEnergy.kt` — result type + UA/Referer + ekstensi file +
  fallback stream berurutan + error tahap decode.
- `youtube/NewPipeStreamExtractor.kt` — `audioUrls` berurutan (mp4 dulu).
- `youtube/InnertubeClient.kt` — iterasi semua track, VTT lalu json3 per
  track, aturan fmt, `lastTracksTried`, parser json3 dipakai ulang.
- `ui/ClipKuViewModel.kt` — pakai `analyseMultiple`, bawa daftar audioUrl
  NewPipe, diagnostik diperluas.
- `app/build.gradle` — version 1.0.3 / versionCode 4.
- `README.md` — versi/dokumen disamakan ke 1.0.3 (dokumen saja).

## Keterbatasan yang tersisa (jujur)
- Kalau SEMUA stream audio decode-nya 0, diagnostik baru akan menunjukkan
  HTTP/byte/mime/error-nya — itu sinyal untuk langkah berikutnya (jalur
  decoder berbeda), bukan tebakan baru. Jangan menambah tebakan format
  tanpa data itu.
- Kalau SEMUA track gagal, diagnostik akan menunjukkan `track dicoba`
  > 0 dengan sumber NONE — itu sinyal bahwa masalahnya ada di format/
  otorisasi subtitle, bukan di jumlah track.
- Full Gradle compile TIDAK bisa jalan di sandbox (tidak ada JDK/Gradle).
  Risiko kompil tertinggi: perubahan result type `AudioEnergy` (semua
  pemanggil harus konsisten — sudah dicek: satu-satunya pemanggil adalah
  ViewModel, kini memakai `analyseMultiple`) dan penanganan fmt URL
  transkrip (tidak boleh merusak track Innertube yang sudah jalan di
  v1.0.1 — aturan fmt lama dipertahankan untuk track tanpa fmt).

## Blok Termux untuk Putra (dari ~/clipku/source, commit BARU, bukan Re-run)
```bash
ZIP=$(ls /sdcard/Download/ClipKu-v1.0.3_source_20261104.zip ~/storage/downloads/ClipKu-v1.0.3_source_20261104.zip 2>/dev/null | head -n 1); test -s "$ZIP" || { echo "ZIP TIDAK KETEMU di Download"; exit 1; }; command -v unzip >/dev/null || pkg install -y unzip; cp "$ZIP" $PREFIX/tmp/clipku103.zip; unzip -o $PREFIX/tmp/clipku103.zip -d ~/clipku; grep -q "1.0.3" ~/clipku/source/app/build.gradle && echo "SOURCE 1.0.3 OK" || { echo "VERSI BUKAN 1.0.3"; exit 1; }; cd ~/clipku/source && git add -A && git commit -m "ClipKu v1.0.3: audio/transcript stage diagnostics + fallbacks" && git push origin main
```

## Acceptance setelah hijau + verifikasi
Install timpa v1.0.2 tanpa uninstall. Uji ALI `ezLxfx1qMyE` dan SPEAK UP
`ToFtmFOuARw`: baca diagnostik baru — audio HTTP/byte/mime/error dan
track dicoba harus terisi (bukan cuma "0 detik"). Clip harus muncul bila
salah satu sinyal (audio atau transkrip) berhasil; bila masih 0 semua,
diagnostik baru itulah data untuk fix berikutnya.
