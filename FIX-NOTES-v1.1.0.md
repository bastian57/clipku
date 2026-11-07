# ClipKu v1.1.0 — Fix jalur segmen: Transformer wrong thread

## Bug yang terbukti di HP (v1.0.9, GoDocs fRQL6LEjwFg, WiFi)
- Download clip 1 DAN clip 2 tersimpan, tapi keduanya `full-download` dengan
  diagnostik `segmen gagal: Transformer is accessed on the wrong thread.`
- Download clip 1 >10 menit — karena selalu mengunduh video utuh dulu.
- Kesimpulan: **jalur segmen TIDAK PERNAH jalan sejak v1.0.6.** v1.0.9 hanya
  membuat kegagalan ini kelihatan di diagnostik, bukan memperbaikinya.

## Akar (SegmentExport.kt, transformRemote)
- Media3 Transformer 1.4.1 menangkap `Looper.myLooper()` saat konstruksi dan
  menolak akses dari thread lain (`checkApplicationLooper`).
- `transformRemote` dipanggil dari coroutine `Dispatchers.IO` — thread pool
  yang ganti-ganti thread antar suspension — jadi `start()`/callback bisa
  jatuh di thread berbeda → `IllegalStateException: Transformer is accessed
  on the wrong thread.` di setiap percobaan segmen.
- `suspendCancellableCoroutine` + `withTimeoutOrNull` tidak menjamin thread
  yang sama; yang menjamin adalah Looper thread khusus.

## Perubahan (1 file + versi)
- `cutting/SegmentExport.kt` — `transformRemote` sekarang memakai SATU
  `HandlerThread("ClipKuTransformer")` per export: konstruksi Transformer,
  `start()`, `cancel()`, dan callback listener `onCompleted`/`onError`
  semuanya di-post ke `Handler` thread itu. `resume()` continuation dari
  thread mana saja aman — yang dilarang hanya akses Transformer-nya.
  Thread di-quit (`quitSafely`) tepat sekali lewat `finish()` berpenjaga
  `AtomicBoolean`, termasuk saat coroutine dibatalkan (timeout 180/120
  detik di ClipDownload): `invokeOnCancellation` mem-post `cancel()` lalu
  `finish()` kedua, jadi thread tidak bocor walau cancel tidak memicu
  callback `onError`.
- `app/build.gradle` — versionCode 10 → 11, versionName 1.0.9 → 1.1.0.
- TIDAK disentuh: scoring/narasi/Groq/heatmap, `downloadToFile`,
  `ClipDownload` (timeout, fallback, validasi output), manifest/permission,
  media3 1.4.1, NPE 0.26.5, keystore/signing. API publik SegmentExport
  tidak berubah (`trySegmentClip`/`trySegmentAudio` + varian Detailed).

## Limit jujur
- HandlerThread hanya memperbaiki crash thread — BUKAN kecepatan server.
  Kalau segmen jalan tapi tetap lambat karena host googlevideo lemot,
  itu masalah lain yang baru kelihatan setelah fix ini.
- Kecepatan segmen di HP tetap harus diukur (acceptance), bukan diklaim
  dari code: clip 1 GoDocs harus tertulis `segment` dan selesai dalam
  hitungan detik–1-2 menit, bukan >10 menit.

## Acceptance HP
1. Video GoDocs fRQL6LEjwFg: clip 1 harus `segment`, ukur detiknya.
2. Video Mahfud SRVrqiu_R6U: Download langsung + ulang jeda 15 menit.
3. Kalau masih `full-download`, kirim alasan segmen dari diagnostik.
