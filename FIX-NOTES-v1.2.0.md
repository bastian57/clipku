# ClipKu v1.2.0 — Upgrade #1 riset super-cepat: pre-filter + scoring paralel 3 model

## Yang berubah (3 file + versi)
- `analysis/Scoring.kt`
  - Konstanta baru `TOP_N_LLM = 12`.
  - `sentenceTextScore(text)` — aturan kalimat v1.0.8 P0-1 diekstrak jadi satu
    fungsi (0.50 dasar, +0.20 '?', +0.20 '!', +0.10 12-40 kata, clamp 0-1),
    dipakai `transcriptTextPerSecond` (refactor tanpa ubah perilaku) DAN
    pre-filter.
  - `prefilterSegmentsForLlm(segments, sentences)` — skor cepat tiap segmen
    dari transkrip saja (nilai kalimat TERKUAT di dalam segmen), ambil Top-12
    untuk di-skor LLM. Diversifikasi waktu: tiap sepertiga video dijamin
    minimal 1 segmen (ganti segmen terlemah yang sepertiganya masih
    terwakili). Segmen di luar Top-12 TIDAK dibuang — tetap masuk
    `selectClips` (tanpa skor LLM) dan tetap bisa jadi clip lewat fusion
    heatmap/audio atau jalur transcript-only.
- `analysis/GroqClient.kt`
  - `scoreSegmentsChunked` dieksekusi PARALEL: jendela (8 segmen, langkah 7
    — sama seperti v1.0.7) dibagikan round-robin ke 3 model Groq gratis
    (`openai/gpt-oss-120b`, `openai/gpt-oss-20b`, `qwen/qwen3.8-27b`) via
    `async`/`awaitAll`. Limit TPM Groq berlaku PER MODEL, jadi paralel
    lintas model tidak melanggar batas 8.000 TPM per model.
  - Pacing antar call ke model YANG SAMA dipertahankan: jendela ke model
    yang sama di-stagger `WINDOW_DELAY_MS` (12 detik) — jendela 0/1/2 mulai
    langsung, 3/4/5 setelah 12 detik, dst. Backoff 429 + fail-fast 413
    dipertahankan (413 tidak di-retry: prompt yang sama gagal di semua model).
  - Satu jendela gagal TIDAK menolkan video: gagal di model yang ditugaskan
    → coba SEKALI model lain; kalau itu pun gagal, jendela dicatat gagal
    (diagnostik "jendela LLM: X/Y" tetap jujur, P0-7 dipertahankan).
  - `postOnce` di-refactor: `postOnceDetailed` tanpa shared state (aman dari
    coroutine paralel — `lastError` lama adalah var bersama yang akan
    balapan); `postOnce` lama jadi wrapper untuk `callChat` (jalur berurutan:
    narasi, rerank, `scoreSegments`). `scoreSegmentsWithModel` (satu jendela,
    satu model) + `buildScoringPrompts` (prompt diekstrak dari `scoreSegments`
    — prompt, temperatur 0.4, dan format JSON TIDAK berubah).
  - `ChunkedScoreResult` mendapat field baru `modelWins: Map<String, Int>`
    (default kosong — konstruktor lama tetap kompatibel).
- `ui/ClipKuViewModel.kt` — pre-filter dipanggil sebelum `scoreSegmentsChunked`
  (input LLM = Top-12 segmen; `selectClips` tetap menerima SEMUA segmen).
  Diagnostik mendapat dua segmen baru: `pre-filter: X segmen → Top 12` dan
  `LLM paralel: 120b:A, 20b:B, qwen:C`. `jendela LLM: X/Y berhasil`, rerank,
  dedupe, dan bobot fusion TIDAK diubah.
- `app/build.gradle` — versionCode 11 → 12, versionName 1.1.0 → 1.2.0.

## Model yang dipakai paralel + kenapa
- 120b (utama, paling akurat), 20b (~2× lebih cepat dari 120b menurut klaim
  Groq), qwen3.8-27b (model default Phoenix/Nalar). Ketiganya sudah ada di
  rantai fallback code sejak v1.0.4 — v1.2.0 hanya mengubah pemakaian dari
  berurutan (fallback) menjadi paralel (round-robin).
- TIDAK dipakai: Llama (mati di free tier 16 Agu 2026) dan Groq Compound
  (di-decommission 21 Sep 2026) — sesuai riset 2026-11-07.

## TIDAK disentuh
Download/segment (`ClipDownload`, `SegmentExport`), narasi, heatmap, Whisper,
prompt LLM, temperatur, format JSON scoring, struktur Clip/segmen, UI selain
dua segmen diagnostik baru, manifest/permission, media3 1.4.1, NPE 0.26.5,
keystore/signing.

## Limit jujur
- Kecepatan di HP tetap harus diukur — paralel lintas model mempercepat
  scoring, tapi total token/hari tetap 200k (free tier) dan satu video 91
  menit yang tadinya 9 jendela kini jadi maks 2 jendela (Top-12 segmen →
  12 segmen = 2 jendela: 8 + 5), jadi penghematan datang dari DUA arah
  (pre-filter + paralel).
- Pre-filter bisa melewatkan segmen yang hanya kelihatan bagus oleh LLM
  (momen tenang tanpa '?'/'!' dan tanpa kata kunci mencolok). Mitigasi:
  Top-12 yang longgar + diversifikasi per sepertiga video — bukan jaminan.
  A/B test kualitas Top 6 (bandingkan judul + timestamp vs v1.1.0) adalah
  bukti yang dibutuhkan di HP.
- Retry satu jendela ke model lain bisa mendorong model itu mendekati batas
  TPM-nya sendiri (3 jendela terjadwal + retry); kalau 429, jendela dicatat
  gagal dan segmennya tetap ter-cover jalur transcript-only.
- Kualitas skor 20b/qwen untuk Bahasa Indonesia vs 120b belum teruji —
  diagnostik "LLM paralel" menunjukkan model mana yang menang per jendela,
  supaya regresi kualitas bisa dilacak.

## Acceptance HP
1. Video ALI 91 menit: total waktu analisa + "jendela LLM: X/Y berhasil" +
   "pre-filter: 61 segmen → Top 12" + "LLM paralel: ..." — target total
   <90 detik (protokol riset §6.3), Top 6 sama/lebih baik dari v1.1.0.
2. Video GoDocs 9 menit (7 segmen, di bawah Top-12): pre-filter tidak
   memotong apa pun, hasil sama seperti v1.1.0.
3. Video Mahfud 10 menit: clip + narasi tetap jalan seperti v1.1.0.
