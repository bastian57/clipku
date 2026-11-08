# FIX-NOTES — ClipKu v1.4.0 fix1 (crash launch, 2026-11-08)

Versi TETAP 1.4.0 / versionCode 14 (preseden v1.3.0 fix1/fix2: fix sebelum
acceptance tidak naik versi — APK akan ter-install sebagai reinstall versi
yang sama, sertifikat sama, data + History + kunci Groq aman).

## Diagnosis (TERBUKTI dari logcat, bukan dugaan)

Logcat via rish/Shizuku (`logcat -d -b crash`, file
`/sdcard/Download/clipku-crash.txt`, 2069 baris) menunjukkan crash yang
sama BERULANG di setiap launch (11:45:07, 11:45:10, 11:45:25, 11:48:25,
11:48:30, 11:49:42, 11:49:45, 11:49:47 — 8x):

```
java.lang.IllegalStateException: Reading a state that was created after the
snapshot was taken or in a snapshot that has not yet been applied
    at androidx.compose.runtime.snapshots.SnapshotKt.readError(Snapshot.kt:1929)
    at androidx.compose.runtime.SnapshotMutableStateImpl.setValue(SnapshotState.kt:308)
    at com.daftech.clipku.ui.ClipKuViewModel.setCacheInfo(ClipKuViewModel.kt:1477)
    at com.daftech.clipku.ui.ClipKuViewModel$refreshCacheInfo$1.invokeSuspend(ClipKuViewModel.kt:357)
    ... FATAL EXCEPTION: DefaultDispatcher-worker-2 (thread IO)
```

Akar: `refreshCacheInfo()` (baru di v1.4.0, P1-3) jalan di
`viewModelScope.launch(Dispatchers.IO)` dan MENULIS Compose
`mutableStateOf` (`cacheInfo`) langsung dari thread IO. Compose melarang
tulis state dari thread non-Main saat snapshot sedang di-apply — dan
`init` ViewModel memanggil `refreshCacheInfo()`, jadi crash-nya
deterministik di SETIAP buka pertama habis update. BUKAN Room (skema DB
identik v1.3.0), BUKAN data lama, BUKAN keystore.

"Lihat ringkasan" di dialog crash MIUI tidak bisa dipakai (malah nutup
app tanpa keterangan) — logcat via rish yang akhirnya memberi jawaban.

## Perbaikan (1 file: ClipKuViewModel.kt, 4 titik)

Aturan yang ditegakkan: **hitung boleh di IO, tulis Compose state WAJIB
di Main.**

1. `refreshCacheInfo()` — string cache dihitung di IO, assignment
   `cacheInfo = text` dipindah ke `withContext(Dispatchers.Main)`
   (termasuk cabang catch → `""`).
2. `clearSourceCache()` — pola yang sama: `status` ditulis di Main.
   (Bug laten yang sama: tadinya bakal crash saat user tap "Bersihkan
   Cache" di Settings.)
3. `onProgress` di `generateNarrationFor` (2 tempat: potong audio biasa +
   re-pick P1-4) — callback `cutClipToTemp` dipanggil dari dalam
   `withContext(Dispatchers.IO)`, jadi `updateClip` (tulis state `clips`)
   di dalamnya dibungkus `viewModelScope.launch { ... }` (Main).
   Bug laten yang sama: tadinya bisa crash di tengah pembuatan narasi.

## Audit pasca-fix (semua titik tulis state diperiksa)

- `viewModelScope.launch(Dispatchers.IO)` lain (init cleanup temp
  Whisper, updateClipDownload, updateClipNarration, saveHistory,
  deleteHistoryItem, clearHistory): TIDAK menulis state — aman.
- Coroutine analisa (411), download (912), narasi (1023), openHistory
  (1291), collect History (69): `viewModelScope.launch` tanpa dispatcher
  = Main — tulis state aman.
- `withContext(Dispatchers.IO)` lain (918 download, 1051/1097 potong
  audio): hanya me-return value, kecuali 2 callback onProgress yang
  sudah diperbaiki di atas.
- `cancelAnalysis/cancelDownload/cancelNarration`: dipanggil dari UI
  (Main) — aman.

## Yang TIDAK berubah

- versionName 1.4.0 / versionCode 14; media3 1.9.0; Room 2.6.1;
  Kotlin 1.9.25; compileSdk/targetSdk 35; AGP 8.7.3; Gradle 8.9.
- Tidak ada perubahan skema DB, workflow, dependensi, atau test.
- 39 unit test v1.4.0 tidak menyentuh jalur ini (snapshot threading
  Compose tidak bisa diuji di JVM unit test) — gerbangnya tetap tes HP.

## Acceptance (tes HP)

1. Buka ClipKu habis update — HARUS langsung kebuka, tanpa dialog
   "terus berhenti".
2. Settings → teks "Cache sumber: … MB (maks 500 MB…)" tampil.
3. Settings → "Bersihkan Cache" → status sukses, app TIDAK crash.
4. Buat narasi satu clip → tahap "memotong/mengunduh audio clip…"
   tampil, narasi selesai tanpa crash.
