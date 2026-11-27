package com.pizarra.dev.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Executors

/** Bloque del lienzo. x,y = esquina superior izquierda en coordenadas de mundo (origen = centro inicial). */
@Entity(tableName = "blocks")
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: Int,          // 0 = título, 1 = subtítulo, 2 = nota
    val x: Float,
    val y: Float,
    val w: Int,             // ancho de la tarjeta
    val text: String = "",
    val code: Boolean = false,
    @ColumnInfo(defaultValue = "''") val title: String = ""  // solo usado en notas (tipo 2): título editable de la tarjeta
)

/** Conexión entre dos anclajes. side: 0 arriba, 1 derecha, 2 abajo, 3 izquierda. */
@Entity(
    tableName = "edges",
    foreignKeys = [
        ForeignKey(entity = BlockEntity::class, parentColumns = ["id"], childColumns = ["fromId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BlockEntity::class, parentColumns = ["id"], childColumns = ["toId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("fromId"), Index("toId")]
)
data class EdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromId: Long,
    val fromSide: Int,
    val toId: Long,
    val toSide: Int
)

@Dao
interface BoardDao {
    @Query("SELECT * FROM blocks") fun blocks(): List<BlockEntity>
    @Query("SELECT * FROM edges") fun edges(): List<EdgeEntity>
    @Insert fun insertBlock(b: BlockEntity): Long
    @Insert fun insertEdge(e: EdgeEntity): Long
    @Query("UPDATE blocks SET x = :x, y = :y WHERE id = :id") fun move(id: Long, x: Float, y: Float)
    @Query("UPDATE blocks SET w = :w WHERE id = :id") fun resize(id: Long, w: Int)
    @Query("UPDATE blocks SET text = :t WHERE id = :id") fun setText(id: Long, t: String)
    @Query("UPDATE blocks SET title = :t WHERE id = :id") fun setTitle(id: Long, t: String)
    @Query("UPDATE blocks SET code = :c WHERE id = :id") fun setCode(id: Long, c: Boolean)
    @Query("DELETE FROM blocks WHERE id = :id") fun deleteBlock(id: Long)   // borra sus conexiones en cascada
    @Query("DELETE FROM edges WHERE id = :id") fun deleteEdge(id: Long)
}

@Database(entities = [BlockEntity::class, EdgeEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): BoardDao

    companion object {
        @Volatile private var inst: AppDb? = null

        /** v1 -> v2: añade la columna `title` para el título de las notas. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE blocks ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "pizarra.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build().also { inst = it }
        }
    }
}

/** Acceso a datos en un único hilo de fondo (sin corrutinas). Los callbacks vuelven al hilo principal. */
class Repo(ctx: Context) {
    private val dao = AppDb.get(ctx).dao()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    fun load(done: (List<BlockEntity>, List<EdgeEntity>) -> Unit) {
        io.execute {
            val b = dao.blocks()
            val e = dao.edges()
            ui.post { done(b, e) }
        }
    }

    fun addBlock(b: BlockEntity, done: (Long) -> Unit) {
        io.execute { val id = dao.insertBlock(b); ui.post { done(id) } }
    }

    fun addEdge(e: EdgeEntity, done: (Long) -> Unit) {
        io.execute { val id = dao.insertEdge(e); ui.post { done(id) } }
    }

    fun move(id: Long, x: Float, y: Float) = io.execute { dao.move(id, x, y) }
    fun resize(id: Long, w: Int) = io.execute { dao.resize(id, w) }
    fun text(id: Long, t: String) = io.execute { dao.setText(id, t) }
    fun title(id: Long, t: String) = io.execute { dao.setTitle(id, t) }
    fun code(id: Long, c: Boolean) = io.execute { dao.setCode(id, c) }
    fun delBlock(id: Long) = io.execute { dao.deleteBlock(id) }
    fun delEdge(id: Long) = io.execute { dao.deleteEdge(id) }
}
