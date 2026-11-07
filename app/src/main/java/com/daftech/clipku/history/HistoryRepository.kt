package com.daftech.clipku.history

import android.content.Context
import com.daftech.clipku.model.Sentence
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository History Analisa (v1.3.0) — jembatan tipis di atas Room.
 * Semua operasi suspend (dipanggil ViewModel dari Dispatchers.IO).
 * Transkrip disimpan lengkap sebagai JSON supaya analisa ulang bisa
 * skip fetch transkrip; 1.103 kalimat ≈ 100-200 KB per video.
 */
class HistoryRepository(context: Context) {
    private val dao = ClipKuDatabase.get(context).historyDao()

    fun observeAll(): Flow<List<AnalysisHistoryEntity>> = dao.getAll()

    suspend fun getByVideoId(videoId: String): AnalysisHistoryEntity? =
        dao.getByVideoId(videoId)

    suspend fun getClips(videoId: String): List<HistoryClipEntity> =
        dao.getClips(videoId)

    suspend fun saveAnalysis(
        entity: AnalysisHistoryEntity,
        clips: List<HistoryClipEntity>
    ) {
        dao.replaceAnalysis(entity, clips, ClipKuDatabase.MAX_HISTORY_ITEMS)
    }

    suspend fun deleteByVideoId(videoId: String) {
        dao.deleteByVideoId(videoId)
    }

    suspend fun deleteAll() {
        dao.clearAll()
    }

    suspend fun updateClipNarration(
        videoId: String,
        clipIndex: Int,
        text: String,
        source: String
    ) {
        dao.updateClipNarration(videoId, clipIndex, text, source)
    }

    suspend fun updateClipDownload(
        videoId: String,
        clipIndex: Int,
        path: String,
        method: String
    ) {
        dao.updateClipDownload(videoId, clipIndex, path, method)
        dao.refreshDownloadedCount(videoId)
    }

    companion object {
        /** Daftar kalimat -> JSON [{s,e,t}] (defensif: teks apa pun aman). */
        fun sentencesToJson(sentences: List<Sentence>): String {
            val array = JSONArray()
            for (sentence in sentences) {
                array.put(JSONObject().apply {
                    put("s", sentence.startMs)
                    put("e", sentence.endMs)
                    put("t", sentence.text)
                })
            }
            return array.toString()
        }

        /** JSON [{s,e,t}] -> daftar kalimat; rusak = kosong, bukan crash. */
        fun jsonToSentences(json: String): List<Sentence> {
            return try {
                val array = JSONArray(json)
                val out = ArrayList<Sentence>(array.length())
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val start = item.optLong("s", -1L)
                    val end = item.optLong("e", -1L)
                    val text = item.optString("t", "")
                    if (start >= 0 && end >= start && text.isNotBlank()) {
                        out.add(Sentence(start, end, text))
                    }
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
