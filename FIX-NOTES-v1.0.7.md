# ClipKu v1.0.7 — FIX NOTES (upgrade #3, gas 2026-11-05)

## FIX1 — 2026-11-05 23:42 WIB (merah pertama, commit c7e0187)

Build merah di `:app:compileDebugKotlin`, BUKAN di Gradle/infra:
- `GroqClient.kt:46:15` + `:92:5` — "Conflicting declarations: public companion object" / "Only one companion object is allowed per class"
- `GroqClient.kt:231:16` — "Unresolved reference: MAX_CHARS_PER_CALL"

Akar: v1.0.7 nambah `companion object` KEDUA di GroqClient (buat WINDOW_MS / WINDOW_OVERLAP_MS / MAX_CHARS_PER_CALL), padahal class itu sudah punya satu companion object sejak v1.0.4 (model + URL + BROWSER_UA). Kotlin cuma boleh satu companion object per class, jadi konstanta di companion kedua tidak ter-resolve.

Fix (satu file, GroqClient.kt saja, versi tetap 1.0.7 / versionCode 8):
- Tiga konstanta chunking dipindah ke companion object PERTAMA (yang sudah ada).
- Companion object kedua DIHAPUS seluruhnya.
- Verifikasi lokal: tinggal satu deklarasi `companion object {` (baris 46), `MAX_CHARS_PER_CALL` terdefinisi sekali, brace/paren/bracket seimbang 0.
- Zip fix1: `ClipKu-v1.0.7-fix1_source_20261105.zip` = 84.752 byte, 80 entries, testzip bersih, layout sama dengan zip v1.0.6 (hanya nama fix-notes yang beda).
- Kompilasi penuh tetap belum bisa di sandbox (tanpa JDK/Gradle) — Actions tetap gerbangnya. Kalau merah lagi, kirim baris `e:`-nya lagi.


## Keadaan yang terbukti (bukan dugaan ulang)

- v1.0.6 hijau dan terverifikasi 2026-11-05 21:46 WIB (run 42040874526,
  APK 21.444.759 byte). Acceptance HP v1.0.6 — download clip dari
  video panjang harus jauh lebih cepat (ukur WAKTU) + Rocky
  "audio track: id, original" — masih berjalan saat v1.0.7 ini
  dibangun (gas nomor 3 diberikan sebelum acceptance itu ditutup).
- Bukti yang memicu upgrade ini, dari HP Putra 2026-11-05 23:15 WIB,
  video ALI `ezLxfx1qMyE` (91 menit, Deen Stories TV), v1.0.6:
  transkrip ADA (1103 kalimat, sumber NEWPIPE), tapi skor LLM
  **0 segmen (gagal: Groq gagal menjawab (HTTP 413
  rate_limit_exceeded: Request too large for model
  `qwen/qwen3.8-27b` … on input tokens per minute)**. Heatmap 0,
  audio 0 detik ter-decode (audio error: Unable to resolve host
  googlevideo — jejak audio terpisah, bukan penyebab 413), dan
  aplikasi menolak jujur membuat clip — benar, tapi video
  bertranskrip 1103 kalimat itu seharusnya BISA dinilai.
- Akar (dari code, bukan tebakan): `GroqClient.scoreSegments`
  mengirim SEMUA segmen dalam SATU call — ~61 segmen 90 detik x
  maks 1200 karakter ≈ 73.000 karakter ≈ 18.000+ token input dalam
  satu request. Model qwen3.8-27b menolaknya di batas input-token
  per menit. Bukan key-nya (key ada, narasi jalan di v1.0.4), bukan
  modelnya mati — request-nya yang terlalu besar.
- Riset engine 2026-11-05 (report.md bagian A + F upgrade #3):
  AI-Youtube-Shorts-Generator (>30 mnt → chunk 20 mnt / overlap
  60 dtk) dan SupoClip (>15 mnt → jendela 12 mnt / overlap 2 mnt,
  3 konkuren, dedupe + SATU rerank komparatif) — keduanya menilai
  per jendela dengan framework viralitas eksplisit, bukan satu
  call raksasa.

## Yang diubah v1.0.7 (hanya upgrade #3 — TIDAK ada #4–#5)

1. **Scoring ber-chunk** (`analysis/GroqClient.kt`,
   `scoreSegmentsChunked` baru).
   - Ukuran yang DIPILIH dan didokumentasikan: jendela **12 menit,
     overlap 90 detik**. Diimplementasikan sebagai maks **8 segmen
     per jendela, maju 7 segmen** (segmen berdurasi ~90 detik dari
     `Scoring.buildSegments`, jadi 8 segmen ≈ 12 menit dan 1 segmen
     overlap ≈ 90 detik). Satu call = maks ~8 x 1200 karakter ≈
     9.600 karakter ≈ 2.400 token + prompt — jauh di bawah batas
     input-token per menit yang membunuh call ALI. Batas keras
     tambahan: teks per call dipotong di 12.000 karakter
     (`MAX_CHARS_PER_CALL`).
   - Video 91 menit (ALI) = ~9 jendela + 1 rerank = ~10 call kecil,
     bukan 1 call raksasa. Video pendek (<12 menit) tetap 1 jendela,
     perilaku sama seperti dulu (hanya prompt-nya yang baru).
   - **Isolasi gagal per jendela:** tiap jendela di-try/catch
     sendiri. Jendela yang gagal (413/429/timeout/parse) di-skip
     dan dicatat — skor dari jendela lain tetap dipakai. Satu
     jendela gagal TIDAK PERNAH lagi menolkan seluruh video.
   - Dedupe di level segmen: segmen yang masuk dua jendela (overlap)
     dinilai dua kali, skor TERTINGGI yang disimpan.
   - Model chain dan temperature 0.4 TIDAK berubah
     (gpt-oss-120b → gpt-oss-20b → qwen3.8-27b).

2. **Framework viralitas** (`GroqClient.scoreSegments` — prompt
   diganti, format JSON sama).
   - Kriteria eksplisit (desain dipinjam dari
     AI-Youtube-Shorts-Generator `highlights.py` / SupoClip;
     prompt ditulis sendiri untuk ClipKu, BUKAN copy code mereka):
     hook, emotional peak, opinion bomb, revelation, conflict,
     quotable line, story peak, practical value.
   - Segmen tanpa satu pun kriteria = skor rendah (<40). Judul =
     hook sentence segmen itu (maks 8 kata), alasan maks 12 kata
     dan menyebut kriteria yang paling menonjol. Format balasan
     tetap `{"scores":[{"id":0,"score":0,"title":"...","reason":"..."}]}`.
   - Aturan clip TIDAK berubah: Top 6, jarak minimal 75 detik,
     snap ke batas kalimat, 50-70 detik diterima sebagai ±1 menit.

3. **Dedupe + rerank** (`analysis/Scoring.kt` `dedupeClips` baru,
   `GroqClient.rerankClips` baru, `ui/ClipKuViewModel.kt`).
   - Dedupe kandidat: dua clip yang tumpang tindih >50% dari
     durasi clip yang lebih pendek, yang skor fused-nya lebih
     rendah dibuang. Jujur dicatat: dalam praktik ini jarang
     terpicu karena MIN_PEAK_GAP 75 dtk sudah memisahkan puncak —
     pass ini adalah jaminan, bukan sumber clip baru.
   - Rerank komparatif: SATU call LLM atas kandidat final (maks
     6, tiap kandidat maks 1200 karakter transkrip) yang mengurutkan
     dari paling layak dulu. Rerank HANYA mengurutkan ulang dan
     me-reindex 1..N — waktu, judul, alasan, dan transkrip clip
     tidak diubah. Rerank gagal (null / exception) = urutan semula
     dipakai, analisa tidak gagal.
   - Bobot fusi TIDAK berubah: heatmap 0.45 / LLM 0.35 / audio
     0.20, didistribusikan ulang kalau satu sinyal kosong. Skor LLM
     tetap masuk lewat bobot yang sama, bukan menggantikan fusi.

4. **Diagnostik** (`ui/ClipKuViewModel.kt`).
   - Baru: `jendela LLM: X/Y berhasil` (mis. "jendela LLM: 9/9
     berhasil" atau "jendela LLM: 7/9 berhasil" + error terakhir).
   - Semua isi diagnostik v1.0.6 dipertahankan (sumber stream,
     audio track, audio HTTP/byte/mime, transkrip, analisa, skor
     LLM + error asli).

5. **Versi + dokumen.**
   - `app/build.gradle`: versionCode 7 → 8, versionName 1.0.6 → 1.0.7.
     Workflow sudah menurunkan nama APK dari versionName, jadi aset
     baru HARUS `ClipKu-v1.0.7.apk`.
   - `README.md`: versi/dokumen saja (1.0.7, scoring viralitas ber-chunk).

## File yang diubah (satu baris per file)

- `analysis/GroqClient.kt` — `scoreSegmentsChunked` + `ChunkedScoreResult`
  + `rerankClips`/`parseRanking` + `RerankInput`/`RerankResult` baru;
  prompt scoring diganti framework viralitas; konstanta
  `WINDOW_MS`/`WINDOW_OVERLAP_MS`/`MAX_CHARS_PER_CALL`.
- `analysis/Scoring.kt` — `dedupeClips` baru (overlap >50%).
- `ui/ClipKuViewModel.kt` — pakai `scoreSegmentsChunked` (bukan
  `scoreSegments` langsung), jejak jendela di diagnostik, dedupe +
  rerank komparatif atas kandidat final (gagal = urutan semula).
- `app/build.gradle` — version 1.0.7 / versionCode 8.
- `README.md` — versi/dokumen saja (1.0.7).

Tidak diubah: picker audio sadar-bahasa (v1.0.5), ekspor segment-only
+ fallback download-penuh (v1.0.6), bobot scoring, aturan narasi
(110-140 kata, plain text, Salin hanya blok final), model Whisper,
Media3 (tetap 1.4.1 — upgrade itu #4, nanti), keystore/signing,
kontras, dan scope (tanpa edit kreatif / auto-download /
auto-publish). Silero VAD, FFmpeg, dan STT offline TIDAK ditambah
di versi ini.

## Cek yang benar-benar dijalankan (jujur)

- Brace/paren/bracket seimbang (selisih 0) di 3 file Kotlin yang
  diubah + build.gradle.
- Grep: `scoreSegmentsChunked` + `rerankClips` + `dedupeClips` ada;
  `jendela LLM:` ada di diagnostik ViewModel; versionCode 8 /
  versionName 1.0.7 di build.gradle; `media3Version = '1.4.1'`.
- Tidak ada secret Putra di code yang diubah (tidak ada key `gsk_`,
  keystore, base64); tidak ada placeholder TODO/FIXME di code yang
  diubah.
- **Kompilasi Gradle penuh TIDAK bisa dijalankan di sandbox ini
  (tidak ada JDK/Gradle) — TIDAK diklaim hijau.** Actions adalah
  gerbang kompilasi. Risiko tertinggi, eksplisit: (a) logika jendela
  (8 segmen / langkah 7) — kalau ada off-by-one, segmen terakhir
  bisa tidak tercakup atau jendela terakhir kosong; fungsi
  berhenti setelah segmen terakhir tercakup, dan jendela kosong
  tidak pernah dibuat karena loop berhenti di `fromIndex <
  segments.size`; (b) rerank memakai `clip.index` sebagai id
  kandidat — index di-reassign setelah rerank, dan dedupe kedua
  dijalankan atas hasil rerank untuk menjaga invariannya.

## Batasan yang tersisa (bukan janji)

- Anggaran Groq free tier TETAP mengikat: chunking MEMPERBANYAK
  call (video 91 menit ≈ 10 call, bukan 1). Tiap call kecil
  (~2.400 token) aman dari 413 TPM, tapi total harian 200k token
  tetap bisa habis di video yang sangat panjang / analisa
  berulang — kalau habis, scoring gagal jujur dengan error asli
  yang tampil di diagnostik, bukan clip karangan.
- Jendela gagal (mis. 429 rate limit di tengah) hanya di-skip,
  TIDAK di-retry otomatis di versi ini — skor dari jendela itu
  hilang, clip di rentang itu hanya bisa muncul dari heatmap/audio.
- Snapping tepi clip ke batas bicara (Silero VAD) TIDAK ada di
  v1.0.7 — tepi clip tetap snap ke batas kalimat seperti dulu.
- Audio error di tes ALI ("Unable to resolve host googlevideo")
  TIDAK disentuh versi ini — itu jalur audio, bukan scoring.
  Kalau di v1.0.7 audio tetap 0 detik di video yang sama, itu
  masalah terpisah untuk dibedah dari diagnostiknya, bukan dari
  skor LLM.

## Blok Termux untuk Putra (commit baru, BUKAN Re-run)

Secret KEYSTORE_B64 untuk bastian57/clipku SUDAH ada — jangan di-set
ulang, jangan bikin keystore baru.

```bash
ZIP=$(ls /sdcard/Download/ClipKu-v1.0.7_source_20261105.zip ~/storage/downloads/ClipKu-v1.0.7_source_20261105.zip 2>/dev/null | head -n 1); test -s "$ZIP" || { echo "ZIP TIDAK KETEMU di Download"; exit 1; }; command -v unzip >/dev/null || pkg install -y unzip; cp "$ZIP" $PREFIX/tmp/clipku107.zip; unzip -o $PREFIX/tmp/clipku107.zip -d ~/clipku; grep -q "1.0.7" ~/clipku/source/app/build.gradle && echo "SOURCE 1.0.7 OK" || { echo "VERSI BUKAN 1.0.7"; exit 1; }; cd ~/clipku/source && git add -A && git commit -m "ClipKu v1.0.7: virality-framework LLM scoring with chunking, rerank & dedupe" && git push origin main
```

## Acceptance setelah hijau + verifikasi (seperti biasa)

Pasang timpa v1.0.6 tanpa uninstall (sertifikat sama, Groq key
Nalar tidak hilang). Tes utama di video ALI yang kemarin 413
(`https://youtu.be/ezLxfx1qMyE`, 91 menit):
- Analisa ulang → diagnostik harus terbaca `jendela LLM: 9/9
  berhasil` (atau X/Y dengan X>0) dan `skor LLM: N segmen` dengan
  N>0 — bukan lagi "0 segmen (gagal: HTTP 413...)".
- Clip HARUS ketemu (minimal 1, target 6) dengan alasan yang
  menyebut kriteria viralitas (hook / emosi / pengakuan /
  konflik / kutipan), bukan alasan generik.
- Tes kontrol di video Liputan6 (Y_2DRQz35-0, 8 menit, 1 jendela)
  → clip tetap ketemu, narasi tetap jalan, tidak regresi.
- Acceptance v1.0.6 yang masih berjalan (download clip video
  panjang diukur WAKTU-nya; Rocky "audio track: id, original")
  tetap berlaku dan dilaporkan bersama hasil ALI ini.
