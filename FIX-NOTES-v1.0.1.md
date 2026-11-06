# ClipKu v1.0.1 — FIX NOTES

**Status: SOURCE ONLY, diperbarui di tempat. APK BELUM SIAP.**
Tidak ada klaim APK sampai GitHub Actions hijau DAN gerbang keystore +
gerbang sertifikat lolos DAN release `latest` terverifikasi.
Jangan install apa pun dari lampiran chat — pengiriman APK hanya lewat
link release GitHub langsung, dengan ukuran byte yang dicocokkan.

Gas: 2026-11-04 16:06 WIB, untuk daftar fix ini saja.
Package tidak berubah: `com.daftech.clipku`.
Versi: **1.0.1, versionCode 2** (dari 1.0.0 / 1).
Workflow sudah menurunkan nama APK dari `versionName` di
`app/build.gradle` (`ClipKu-v${V}.apk`), jadi aset baru wajib bernama
`ClipKu-v1.0.1.apk` — tidak ada nama yang di-hardcode bertentangan.

## Bug yang dikonfirmasi (bukan dugaan baru)

Video uji: https://youtu.be/kU6ZTFmnNNY (Susno Duadji / Listyo / Suyudi).
v1.0.0: judul terambil, tapi Analisa gagal dengan
"Tidak ada sinyal highlight yang cukup" — heatmap, transkrip, dan audio
semua nol di Scoring, sehingga totalWeight = 0.
Putra mengonfirmasi: (1) CC/subtitle ADA di YouTube, (2) Groq key SUDAH
diisi di Settings, (3) durasi > 30 menit. Jadi ini bug ekstraksi, bukan
video tanpa sinyal: transkrip saja + Groq seharusnya sudah menghasilkan
clip, walau heatmap/audio nol.
Bug UI terpisah di screenshot yang sama: teks utama hitam di background
gelap (judul ClipKu, deskripsi, judul video hampir tak terbaca).

## Akar masalah

1. **Transkrip (utama).** Innertube WEB v1.0.0 hanya membaca
   `captionTracks` dari respons player WEB. Untuk video ini jalur itu
   mengembalikan nol track / nol kalimat, sehingga segmen transkrip
   kosong, skor LLM diskip, dan total bobot jadi nol. Tidak ada
   fallback dan tidak ada log per-sinyal, sehingga kegagalannya tampil
   sebagai satu pesan generik.
2. **Audio (sekunder).** Code v1.0.0 hanya membaca field `url` dari
   format streaming. Banyak format WEB hanya membawa `signatureCipher`,
   sehingga `audioUrl` bisa null padahal videonya bersuara, dan
   `AudioEnergy` langsung mengembalikan map kosong.
3. **Kontras.** `MainActivity.setContent` memakai `ClipKuTheme` tanpa
   `Surface`, sehingga Text Compose default ke hitam, padahal tema
   ClipKu gelap (background 0xFF0B1220). Ini pelajaran tema gelap
   LoopLive yang terulang.

## Daftar fix (semua sudah diterapkan di `source/`)

1. **Kontras — `app/src/main/java/com/daftech/clipku/MainActivity.kt`.**
   Seluruh konten dibungkus
   `androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background)`.
   Surface menetapkan LocalContentColor ke onBackground (putih),
   sehingga semua Text di dalamnya terbaca. Surface saja sudah cukup;
   tidak ada Text yang diubah warnanya satu per satu di fix ini.
2. **Diagnostik — `ui/ClipKuViewModel.kt` + `ui/MainScreen.kt`.**
   State baru `diagnostic: String` di ViewModel, ditampilkan di
   MainScreen di bawah status/error, teks biasa yang terbaca.
   Setelah setiap percobaan analisa (sukses MAUPUN gagal), diagnostik
   menampilkan angka nyata: judul terambil, durasi (menit), jumlah
   track caption, jumlah kalimat transkrip + sumbernya
   (WEB / TIMEDTEXT / ANDROID / NONE), jumlah titik heatmap,
   audio URL ada/tidak + jumlah detik ter-decode, Groq key ada/tidak,
   jumlah segmen skor LLM, dan daftar sinyal yang nol.
   Saat metadata gagal total, diagnostik menyebut kegagalan itu dan
   bahwa sinyal-sinyal lain tidak diketahui — bukan angka palsu.
3. **Rantai fallback transkrip — `youtube/InnertubeClient.kt`.**
   Fungsi baru `fetchTranscriptWithFallback(info): TranscriptResult`
   (kalimat + label sumber), dipakai ViewModel menggantikan panggilan
   `fetchTranscript` tunggal. Urutan, pertama yang berhasil menang,
   tiap langkah yang gagal/kosong tidak melempar:
   - **WEB:** captionTracks dari `fetchVideoInfo`, format VTT
     (jalur lama, kini dengan header User-Agent browser di request
     VTT — v1.0.0 tidak mengirim UA di request ini).
   - **TIMEDTEXT:** GET
     `https://www.youtube.com/api/timedtext?v={videoId}&lang=id&fmt=json3`,
     lalu `lang=en` kalau id kosong. Format json3 diparse dari
     `events[].tStartMs / dDurationMs / segs[].utf8`, tag HTML dibuang.
     HTTP non-200 diperlakukan sebagai kosong, bukan error.
   - **ANDROID:** captionTracks dari player ANDROID, format VTT.
   Fungsi lama `fetchTranscript(info)` dipertahankan sebagai wrapper
   (hanya kalimatnya) supaya tidak ada pemanggil lama yang patah.
4. **Fallback stream/audio — `youtube/InnertubeClient.kt` (request yang sama).**
   `fetchVideoInfo` kini: kalau stream WEB kekurangan `audioUrl`
   atau `progressiveUrl`, ia mengambil player ANDROID
   (clientName `ANDROID`, clientVersion `19.47.53` — lini klien
   Android 19.x yang dipakai luas sepanjang 2025; kalau YouTube
   menolak versi ini, fallback mengembalikan kosong dan hasil WEB
   tetap dipakai) dan mengisi field stream yang kosong dari ANDROID,
   begitu juga captionTracks / heatPoints kalau WEB kosong.
   Hasil ANDROID di-cache di instance (`cachedAndroidInfo`) supaya
   fallback transkrip tidak mengulang request player yang sama.
   **signatureCipher TIDAK di-decipher di fix ini** (butuh decipher
   JS player, di luar scope v1.0.1) — lihat keterbatasan di bawah.
5. **Versi — `app/build.gradle`.**
   `versionCode 1 -> 2`, `versionName '1.0.0' -> '1.0.1'`.
   Tetap: AGP 8.5.2, Kotlin 1.9.25, compileSdk/targetSdk 34, minSdk 24,
   media3 pin 1.4.1. Workflow, pola signing workspace, dan gerbang
   keras TIDAK diubah: fingerprint keystore wajib
   `0A:A5:2A:F9:10:17:42:AE:5D:5A:13:80:F7:F2:E8:A4:F1:F5:88:03:80:6F:B6:09:43:72:D3:3C:78:10:30:20`
   dan sertifikat APK wajib
   `0aa52af9101742ae5d5a1380f7f2e8a4f1f58803806fb6094372d33c78103020`,
   kalau tidak cocok build dibatalkan sebelum upload.

## File yang diubah (satu baris alasan per file)

- `app/src/main/java/com/daftech/clipku/MainActivity.kt` — Surface
  background agar teks putih di tema gelap.
- `app/src/main/java/com/daftech/clipku/youtube/InnertubeClient.kt` —
  fallback transkrip WEB → TIMEDTEXT → ANDROID + fallback stream
  ANDROID + UA di request VTT + hasil `TranscriptResult`.
- `app/src/main/java/com/daftech/clipku/ui/ClipKuViewModel.kt` —
  state `diagnostic`, pakai `fetchTranscriptWithFallback`, susun
  diagnostik angka nyata di sukses dan gagal.
- `app/src/main/java/com/daftech/clipku/ui/MainScreen.kt` —
  tampilkan `diagnostic` di bawah status/error.
- `app/build.gradle` — version 1.0.0/1 → 1.0.1/2.
- File lain TIDAK diubah: Scoring, Narration, AudioEnergy, CuttingEngine,
  workflow, dan aturan narasi (110–140 kata, plain final text,
  salin hanya blok final) tidak berubah.

## Tidak ada scope creep

Tidak ditambahkan: cari topik/keyword, edit kreatif (transisi, teks,
musik, filter, reframe, voiceover, thumbnail bakar), auto-download
semua, auto-publish, atau generate suara di dalam APK.
Aturan scoring (Top-6, jarak minimal 75 detik, snap ke batas kalimat
50–70 detik) dan aturan narasi tidak berubah.

## Cek yang BENAR-BENAR dijalankan (VM ini)

- Gradle TIDAK bisa jalan di sandbox ini (tidak ada JDK/Gradle,
  sesuai skill build APK) — jadi TIDAK ADA klaim "kompilasi lolos".
  Kompilasi penuh adalah gerbang Actions. Area risiko tertinggi
  tetap Media3 Transformer (tidak diubah di fix ini, kecuali tidak
  disentuh sama sekali).
- Yang sudah dijalankan dan lolos:
  - Brace balance: 4 file Kotlin yang diubah + `app/build.gradle`,
    semua seimbang; parens juga seimbang.
  - XML well-formed: semua file XML di `app/src/main/res` lolos parser
    (tidak ada XML yang diubah di fix ini, dicek ulang tetap).
  - Groovy: tidak ada `val` di `build.gradle` / `app/build.gradle` /
    `settings.gradle` (hanya `def`).
  - Tidak ada fungsi pola setter di ViewModel yang diubah
    (`updateX` / `saveX` / `clearX` saja, sesuai pelajaran SuaraKu).
  - Tidak ada secret Putra di source yang diubah: tidak ada Groq key
    (hanya referensi ke key di Settings), tidak ada keystore/base64.
    Satu-satunya kunci di `InnertubeClient.kt` adalah kunci web publik
    YouTube yang sudah ada sejak v1.0.0 dan terdokumentasi sebagai
    bukan secret.
  - Tidak ada placeholder TODO di code yang diubah.
  - Workflow: nama APK diturunkan dari `versionName`
    (`ClipKu-v${V}.apk`), sehingga 1.0.1 → `ClipKu-v1.0.1.apk`;
    gerbang fingerprint + sertifikat terverifikasi masih ada di file.

## Keterbatasan yang tersisa (bukan bug yang disembunyikan)

1. Kalau ANDROID juga hanya mengembalikan format cipher-only,
   `audioUrl` tetap bisa null — audio tetap nol, TAPI transkrip
   (kalau fallback berhasil) sudah cukup membuka clip lewat LLM.
   Decipher signatureCipher dan NewPipeExtractor / yt-dlp tetap
   menjadi fallback masa depan yang terdokumentasi, bukan bagian
   fix ini.
2. Timedtext direct bisa mengembalikan 403 untuk caption auto di
   beberapa video — diperlakukan sebagai kosong dan rantai lanjut
   ke ANDROID, bukan error.
3. Heatmap tetap best-effort dan bisa kosong di video baru/sepi;
   itu tidak lagi menggagalkan analisa selama transkrip ada.
4. Versi klien ANDROID 19.47.53 adalah best-effort: kalau YouTube
   menolaknya, fallback itu kosong dan sumber sebelumnya tetap dipakai.
5. Diagnostik menampilkan angka dari jalur yang benar-benar jalan;
   kalau semua jalur transkrip kosong, ia akan menyebut
   "transkrip: 0 kalimat (sumber: NONE)" — itu sinyal untuk fix
   berikutnya, bukan untuk mengarang clip.

## Blok Termux Putra (SATU blok, dari ~/clipku/source)

JANGAN Re-run commit lama — Re-run mengulang commit v1.0.0 yang sama
dan akan merah lagi. Yang dibutuhkan adalah commit BARU + push.
Jalankan dari folder source yang sudah berisi fix ini
(ambil dulu source v1.0.1 yang sudah diperbarui ke HP, atau terapkan
file-file yang diubah ke `~/clipku/source` yang ada, lalu):

```bash
cd ~/clipku/source && git add -A && git commit -m "ClipKu v1.0.1: diagnostic + transcript fallback + contrast" && git push origin main
```

Setelah push, Actions jalan otomatis dengan commit baru. Secret
`KEYSTORE_B64` untuk repo `bastian57/clipku` sudah diset di langkah
sebelumnya — jangan set ulang, jangan bikin keystore baru.
Pantau run baru: gerbang keystore, kompilasi, gerbang sertifikat,
lalu release `latest` harus memuat `ClipKu-v1.0.1.apk`.
Acceptance HP setelah hijau + verifikasi: install → Settings Groq key
(sudah ada) → tempel link video yang sama
(https://youtu.be/kU6ZTFmnNNY) → Analisa → baca blok Diagnostik
(sumber transkrip harus bukan NONE, kalimat > 0) → clip muncul →
Preview → Download per clip → Buat Narasi → Salin → SuaraKu → CapCut.
