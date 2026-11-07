package com.daftech.clipku.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * History Analisa (v1.3.0) — Room 2.8.5, database versi 1.
 *
 * Satu baris per video yang pernah dianalisa + satu baris per clip.
 * Disimpan: hasil analisa LENGKAP (judul, clip, skor, alasan, transkrip,
 * narasi) supaya buka ulang TIDAK butuh network sama sekali.
 * TIDAK disimpan: stream URL googlevideo (kedaluwarsa — re-extract
 * fresh saat Preview/Download, jalur itu sudah ada sejak v1.0.6) dan
 * Groq key (tetap di SharedPreferences privat).
 * TIDAK ADA fallbackToDestructiveMigration: ini DB baru versi 1, dan
 * perubahan skema berikutnya WAJIB Migration eksplisit — data History
 * user tidak boleh hilang saat update.
 */
@Entity(tableName = "analysis_history")
data class AnalysisHistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelName: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    /** Epoch ms — urutan daftar + eviksi LRU. */
    val analyzedAt: Long,
    val clipCount: Int,
    val transcriptSentenceCount: Int,
    val audioTrackDescription: String,
    val heatmapPointCount: Int,
    /** Teks diagnostik terakhir (untuk debug + ditampilkan saat restore). */
    val diagnosticSummary: String,
    /** SELURUH transkrip + timing sebagai JSON [{s,e,t}]. */
    val transcriptJson: String,
    val llmWindowSucceeded: Int,
    val llmWindowTotal: Int,
    val llmSegmentCount: Int,
    /** Jumlah clip yang sudah di-download (badge di daftar). */
    val downloadedCount: Int = 0
)

@Entity(
    tableName = "history_clips",
    foreignKeys = [ForeignKey(
        entity = AnalysisHistoryEntity::class,
        parentColumns = ["videoId"],
        childColumns = ["videoId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("videoId")]
)
data class HistoryClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val clipIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val title: String,
    val reason: String,
    val transcriptText: String,
    /** Narasi final kalau sudah dibuat (hemat token Groq — tidak generate ulang). */
    val narrationText: String? = null,
    val narrationSource: String? = null,
    val downloadedPath: String? = null,
    val downloadMethod: String? = null
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM analysis_history ORDER BY analyzedAt DESC")
    fun getAll(): Flow<List<AnalysisHistoryEntity>>

    @Query("SELECT * FROM analysis_history WHERE videoId = :videoId LIMIT 1")
    suspend fun getByVideoId(videoId: String): AnalysisHistoryEntity?

    @Query("SELECT * FROM history_clips WHERE videoId = :videoId ORDER BY clipIndex ASC")
    suspend fun getClips(videoId: String): List<HistoryClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysis(entity: AnalysisHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClips(clips: List<HistoryClipEntity>)

    @Query("DELETE FROM history_clips WHERE videoId = :videoId")
    suspend fun deleteClipsFor(videoId: String)

    @Query("DELETE FROM analysis_history WHERE videoId = :videoId")
    suspend fun deleteByVideoId(videoId: String)

    @Query("DELETE FROM analysis_history")
    suspend fun deleteAll()

    @Query("DELETE FROM history_clips")
    suspend fun deleteAllClips()

    @Query("SELECT COUNT(*) FROM analysis_history")
    suspend fun count(): Int

    /**
     * Eviksi LRU: hanya N terbaru yang dipertahankan (maks 100 item).
     * Hapus analisa TIDAK menghapus file clip di Download/ClipKu
     * (file itu milik user di storage publik).
     */
    @Query(
        "DELETE FROM analysis_history WHERE videoId NOT IN " +
            "(SELECT videoId FROM analysis_history " +
            "ORDER BY analyzedAt DESC LIMIT :keep)"
    )
    suspend fun deleteOldestExcept(keep: Int)

    @Query(
        "UPDATE history_clips SET narrationText = :text, " +
            "narrationSource = :source WHERE videoId = :videoId " +
            "AND clipIndex = :clipIndex"
    )
    suspend fun updateClipNarration(
        videoId: String,
        clipIndex: Int,
        text: String,
        source: String
    )

    @Query(
        "UPDATE history_clips SET downloadedPath = :path, " +
            "downloadMethod = :method WHERE videoId = :videoId " +
            "AND clipIndex = :clipIndex"
    )
    suspend fun updateClipDownload(
        videoId: String,
        clipIndex: Int,
        path: String,
        method: String
    )

    @Query(
        "UPDATE analysis_history SET downloadedCount = " +
            "(SELECT COUNT(*) FROM history_clips WHERE videoId = :videoId " +
            "AND downloadedPath IS NOT NULL) WHERE videoId = :videoId"
    )
    suspend fun refreshDownloadedCount(videoId: String)

    /**
     * Simpan / timpa satu analisa + clip-clipnya secara atomik.
     * Analisa ulang video yang sama MENIMPA baris lama (upsert by
     * videoId), bukan duplikat. Eviksi LRU jalan di transaksi yang sama.
     */
    @Transaction
    suspend fun replaceAnalysis(
        entity: AnalysisHistoryEntity,
        clips: List<HistoryClipEntity>,
        maxItems: Int
    ) {
        deleteClipsFor(entity.videoId)
        insertAnalysis(entity)
        insertClips(clips)
        deleteOldestExcept(maxItems)
    }

    @Transaction
    suspend fun clearAll() {
        deleteAllClips()
        deleteAll()
    }
}

@Database(
    entities = [AnalysisHistoryEntity::class, HistoryClipEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ClipKuDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        const val MAX_HISTORY_ITEMS = 100

        @Volatile
        private var INSTANCE: ClipKuDatabase? = null

        fun get(context: Context): ClipKuDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipKuDatabase::class.java,
                    "clipku-history.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
