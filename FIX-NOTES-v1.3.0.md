# FIX-NOTES — ClipKu v1.3.0 (FINAL)

**Tanggal:** 2026-11-07
**Naik dari:** v1.2.0 (versionCode 12) → **v1.3.0 (versionCode 13)**
**Perintah:** "Gas semua langsung, mau apk final" — semua sisa upgrade (#2, #3, #4, #5) + History Analisa masuk SATU build ini, menggantikan pola satu-versi-per-upgrade.
**Yang TIDAK diubah:** keystore/signing, perilaku #1 (pre-filter Top-12 + scoring paralel 3 model), download/segment HandlerThread v1.1.0, narasi, Whisper.

---

## #2 STRICT JSON (Groq structured output)

**File:** `app/src/main/java/com/daftech/clipku/analysis/GroqClient.kt`

- `scoreSegmentsWithModel` sekarang mencoba **strict dulu**: `response_format: {type: "json_schema", json_schema: {name: "clip_scores", strict: true}}` untuk gpt-oss-120b/20b dan qwen3.8-27b, dengan `reasoning_effort=low` (hanya model gpt-oss — qwen tidak mengenal field itu) dan `max_completion_tokens: 800` (biaya TPM dihitung dari max_tokens yang dideklarasi, bukan yang ter-generate — jadi angka ketat = hemat kuota).
- Kalau server menolak strict mode (error 400 / menyebut `response_format` / `json_schema` / `unsupported` / `strict`), **satu kali retry** dengan body polos lama. Parser lama tetap dipakai untuk mem-parse SEMUA respons (strict atau tidak) — tidak ada parser baru.
- `rerankClips` sama: strict dulu per model (`max_completion_tokens: 600`, skema `clip_ranking`), fallback ke `callChat` lama kalau strict ditolak.
- **Prompt, temperatur, dan isi/format JSON TIDAK diubah** — hanya cara memintanya.

**Yang TIDAK jadi (penyimpangan jujur dari perintah awal):** narasi TIDAK memakai strict JSON. Narasi adalah output teks polos (110–140 kata, tanpa label/markdown) — memaksanya lewat JSON Schema berarti meminta JSON lalu men-strip-nya lagi, tanpa manfaat dan dengan risiko format. Narasi tetap lewat `callChat` polos seperti sebelumnya. Ini keputusan sadar, bukan kelupaan.

**Risiko:** kalau Groq menolak `response_format` untuk qwen3.8-27b (dokumen bilang didukung, tapi perilaku nyata bisa beda), tiap scoring membayar 1 retry — tetap jalan, hanya lebih lambat. Diagnostik tidak membedakan strict vs fallback; kalau scoring terasa lambat, itu kandidat pertama yang dicek.

## #3 TRANSKRIP PARALEL + 1 CALL PLAYER

**File:** `app/src/main/java/com/daftech/clipku/youtube/InnertubeClient.kt`

- **1 call player — sudah dipenuhi sejak v1, tidak perlu diubah:** `fetchVideoInfo` sudah mengambil metadata + captionTracks + heatmap dari SATU respons Innertube `player` klien WEB (`fetchPlayer`). Yang berurutan dulu adalah rantai *transkrip*, bukan rantai metadata — jadi tidak ada merge baru yang ditulis di sini. Player ANDROID tetap hanya dipanggil kalau WEB kekurangan stream (perilaku lama dipertahankan).
- **Transkrip paralel (yang baru):** `fetchTranscriptFromTracks` mencoba track caption dalam **batch paralel maks 4** (maks 12 track total = 3 batch), urutan: id manual → id auto → en manual → en auto → sisanya, deduplikasi per `baseUrl`. Per track: **json3 dulu, VTT fallback**. Track pertama yang menghasilkan kalimat tidak kosong **menang, sisanya di-cancel**; kalau ada yang selesai bersamaan dengan hasil tidak kosong, hasil itu ikut diperiksa (tidak terbuang). Gagal semua di satu batch = lanjut batch berikut.
- Rantai luar tetap ada sebagai fallback: TIMEDTEXT (id, lalu en) → captionTracks ANDROID (juga lewat fetch paralel yang sama). NewPipe tetap fallback stream seperti sebelumnya.
- Diagnostik tetap menampilkan **"track dicoba: N"** — N = jumlah track yang benar-benar **dimulai** (batch yang di-cancel di tengah tetap terhitung karena sudah dimulai).

**Yang TIDAK jadi:** tidak ada perubahan pada cara heatmap diambil (sudah satu call), dan tidak ada pengurangan jumlah call ANDROID di jalur fallback — ANDROID tetap cadangan, bukan dihapus.

## #4 STREAMING AUDIO

**File:** `app/src/main/java/com/daftech/clipku/analysis/AudioEnergy.kt`, `app/src/main/java/com/daftech/clipku/youtube/NewPipeStreamExtractor.kt`, `app/src/main/java/com/daftech/clipku/ui/ClipKuViewModel.kt`

- `analyseOne` sekarang mencoba **streaming dulu**: `MediaExtractor.setDataSource(url, headers)` (User-Agent browser + Referer YouTube) + decode RMS per detik **sambil mengunduh**, dengan batas tunggu **90 detik** (`withTimeoutOrNull`). Berhasil + RMS tidak kosong → dipakai. Gagal/timeout → jatuh ke jalur **download-penuh lama** (tidak pernah menggagalkan analisa).
- Inti decode di-refactor ke `decodeFromExtractor(extractor)` yang dipakai bersama oleh jalur streaming dan jalur file lokal. **Bug yang diperbaiki saat refactor:** tail lama `catch + finally { extractor.release() }` sempat tertinggal di fungsi baru padahal fungsi baru TIDAK boleh me-release (pemiliknya yang me-release) — sudah dihapus; release tepat satu kali di masing-masing pemanggil.
- **Stream opus bitrate terendah untuk analisa** (`NewPipeStreamExtractor.pickAnalysisAudio`): di tier bahasa yang sama dengan audio utama, diutamakan opus, bitrate terendah; null kalau tidak ada kandidat yang lebih rendah. ViewModel meletakkan URL ini **paling depan** daftar analisa (juga di jalur retry DNS). Audio utama bitrate tertinggi tetap dipakai untuk **download clip**, bukan analisa.
- Diagnostik: baris baru **"audio cara: streaming / download-penuh"**; "audio HTTP" menampilkan **"-"** (bukan 0) saat streaming, karena MediaExtractor tidak punya HTTP code. "audio byte" tetap 0 saat streaming (tidak ada file yang di-download).

**Limit jujur:**
- Timeout 90 detik membatasi **waktu tunggu**, bukan panggilan jaringan yang sedang blokir — kalau `setDataSource` nyangkut di dalam, thread IO itu tetap sibuk sampai call-nya kembali, lalu jalur download-penuh jalan di thread lain. Dampak: satu thread terbuang sementara, bukan analisa menggantung.
- **Apakah streaming benar-benar menghindari download penuh, BELUM TERBUKTI** (tergantung container/moov — laporan riset §1.3 bilang sama). Yang bisa diukur di HP: **waktu** analisa audio vs v1.2.0, bukan byte (streaming tidak mencatat byte). Kalau waktu tidak membaik, streaming = no-op yang aman, bukan regresi.
- Codec yang gagal di tengah decode (`decodeFromExtractor`) tidak di-stop/release eksplisit — sama seperti perilaku lama, bukan regresi, tapi tetap kebocoran kecil yang dicatat di sini.

## #5 MEDIA3 1.11.1 (perubahan paling berisiko di build ini)

**File:** `app/build.gradle`, `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/clipku-build.yml`

- media3 **1.4.1 → 1.11.1** (stabil 26 Okt 2026).
- compileSdk **34 → 35**, targetSdk **34 → 35**.
- AGP **8.5.2 → 8.7.3** (8.5.2 mentok SDK 34). Kotlin **tetap 1.9.25**. Gradle wrapper **8.7 → 8.9** (minimum AGP 8.7.3). JDK workflow **tetap 17** (Temurin).
- Workflow: SDK platforms `android-34 → android-35`, build-tools `34.0.0 → 35.0.0`, langkah install Gradle `8.7 → 8.9`. **SigningConfigs + gerbang sertifikat workflow TIDAK diubah.**
- **Logika Transformer/SegmentExport TIDAK diubah sama sekali** — tidak ada InAppMp4Muxer eksplisit, tidak ada flag trim eksperimental. Kalau build Actions gagal di API Transformer, itu kandidat pertama; tapi tidak ada yang diubah "berjaga-jaga".
- **Tidak ada perubahan permission.**

**Risiko (jujur):** ini perubahan paling berisiko di build ini. compileSdk 35 + AGP baru + media3 yang lompat 7 minor bisa gagal di tiga tempat yang tidak bisa dicek dari VM ini (tidak ada Java/Gradle lokal): (1) kompilasi — API media3 yang berubah nama/dihapus; (2) runtime — perilaku Transformer 1.11.1 di segmen; (3) workflow — versi tool yang diminta. Kalau Actions merah, log langkah 7 ("Build debug APK") yang memutuskan: error API media3 → patch SegmentExport seperlunya; error Gradle/JDK → sesuaikan versi tool. **Jangan Re-run membabi buta** — merah karena kode butuh fix commit, merah karena infra boleh Re-run (pelajaran v1.0.5).

## HISTORY ANALISA (Room 2.8.5)

**File baru:**
- `app/src/main/java/com/daftech/clipku/history/HistoryDatabase.kt` — entity, DAO, database
- `app/src/main/java/com/daftech/clipku/history/HistoryRepository.kt` — JSON transkrip + CRUD
- `app/src/main/java/com/daftech/clipku/ui/HistoryScreen.kt` — layar daftar

**File diubah:** `app/src/main/java/com/daftech/clipku/ui/ClipKuViewModel.kt`, `.../ui/MainScreen.kt`, `.../MainActivity.kt`, `app/build.gradle` (Room runtime + ktx + kapt compiler).

- Database **`clipku-history.db`, versi 1**, 2 tabel: `analysis_history` (videoId PK, judul, channel, durasi, thumbnailUrl, analyzedAt, clipCount, transcriptSentenceCount, audioTrackDescription, heatmapPointCount, diagnosticSummary, transcriptJson, llmWindowSucceeded/Total, llmSegmentCount, downloadedCount) + `history_clips` (auto id, FK videoId **CASCADE delete**, clipIndex, start/end, score, title, reason, transcriptText, narrationText, narrationSource, downloadedPath, downloadMethod).
- **TIDAK ADA `fallbackToDestructiveMigration`** — DB baru versi 1, dan perubahan skema berikutnya WAJIB Migration eksplisit. Data History user tidak boleh hilang saat update.
- Disimpan **lengkap**: judul, clip + skor + alasan, transkrip penuh sebagai JSON `[{s,e,t}]` (~100–200 KB/video), diagnostik, narasi kalau sudah dibuat. **TIDAK disimpan: stream URL** (kedaluwarsa) dan Groq key (tetap di SharedPreferences privat). Thumbnail hanya URL.
- Layar History dari tombol **"History"** di MainScreen (di samping Settings): daftar terbaru dulu, tap = **buka ulang hasil TANPA analisa ulang dan TANPA network** (harus jalan mode pesawat — dibuktikan dari Room). Preview/Download tetap butuh network karena stream di-resolve fresh di belakang (`refreshStreamsForHistory`); kalau refresh gagal, hasil tetap tampil dan Preview/Download gagal jujur saat ditekan.
- Hapus per item (konfirmasi dialog) + hapus semua (konfirmasi dialog). Hapus analisa **TIDAK menghapus file clip** di Download/ClipKu.
- **Maks 100 item + eviksi LRU** otomatis (yang terlama terhapus saat item ke-101 masuk), di transaksi yang sama dengan penyimpanan.
- **Analisa ulang video yang sama = menimpa** (upsert by videoId, bukan duplikat). Saat link ditempel dan videoId sudah ada di History, muncul **banner "sudah dianalisa"** dengan tombol "Buka Hasil Lama" / "Abaikan" — tekan Analisa tetap jalan dan menimpa.
- **Save-back:** narasi yang dibuat per clip langsung disimpan ke entri History (buka ulang tidak generate ulang = hemat kuota Groq); download yang berhasil mencatat path + metode (badge "N tersimpan" di daftar). Path tersimpan yang file-nya sudah dihapus dari luar app **tidak lagi diklaim "Tersimpan"** (dicek `File.exists()` saat restore).
- KAPT dipakai untuk Room compiler (Kotlin tetap 1.9.25 — KSP2 berisiko di versi ini, sesuai catatan laporan riset).

**Limit jujur:** reopen History di mode pesawat menampilkan hasil + narasi, tapi **tidak bisa Preview/Download** (butuh stream fresh) — itu by design, bukan bug. Badge "tersimpan" dihitung dari path yang tercatat; kalau file dipindah manual, badge bisa basi sampai clip dibuka ulang.

---

## Verifikasi (lokal, sebelum push)

- `unzip -t` zip sumber: bersih.
- `versionName '1.3.0'`, `versionCode 13`, `compileSdk 35`, `targetSdk 35`, media3 `1.11.1`, Room `2.8.5` di `app/build.gradle`: ada.
- `companion object {` di GroqClient.kt = **1**; `grep -ci llama` di GroqClient.kt = **0** (Llama & Compound tidak dipakai sebagai model aktif).
- Room: Entity/Dao/Database ada, versi DB = **1**, tanpa `fallbackToDestructiveMigration`.
- Tidak ada file `.keystore`/`.jks` di source maupun di zip.
- Tidak ada `Log.`/`println(` di file yang diubah/ditambah.
- Keseimbangan kurung `{`/`}` semua file yang diubah: seimbang (cek kasar via grep — **bukan pengganti kompilasi**).
- **Kompilasi TIDAK bisa dicek lokal** (tidak ada Java/Gradle di VM ini) — gerbang kompilasi adalah GitHub Actions, seperti biasa. Yang paling mungkin merah pertama kali: media3 1.11.1 (lihat risiko #5 di atas).

## Yang harus diukur di HP (bukan dari build ini)

- **Kecepatan:** waktu analisa video 10 menit dan 91 menit vs v1.2.0 (estimasi riset: 10 mnt ≈ 15–25 dtk, 91 mnt ≈ 60–90 dtk — **estimasi, bukan fakta**). Stopwatch HP yang memutuskan.
- **Streaming audio:** "audio cara: streaming" + waktu analisa audio vs "download-penuh".
- **Segment:** GoDocs clip 1 harus `segment` + selesai hitungan detik–1–2 menit; Mahfud download langsung + ulang jeda 15 menit.
- **History:** analisa → tutup app → mode pesawat → buka History → hasil tampil lengkap + narasi tersimpan; analisa ulang link sama → menimpa, bukan duplikat; hapus item + hapus semua.
- **Media3 1.11.1:** Preview + Download clip tetap jalan normal (ini uji regresi terpenting build ini).
