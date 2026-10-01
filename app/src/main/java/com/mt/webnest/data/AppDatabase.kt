package com.mt.webnest.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppDatabase private constructor(context: Context) : SQLiteOpenHelper(context, "webnest.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE web_apps (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, url TEXT NOT NULL,
            icon BLOB, created_at INTEGER NOT NULL, last_opened_at INTEGER,
            desktop_mode INTEGER NOT NULL DEFAULT 0, user_agent TEXT NOT NULL DEFAULT '',
            third_party_cookies INTEGER NOT NULL DEFAULT 0,
            modified_at INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0,
            position INTEGER NOT NULL DEFAULT 0, theme_color INTEGER)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val columns = db.rawQuery("PRAGMA table_info(web_apps)", null).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))}}
        fun field(name: String, fallback: String) = if (name in columns) name else fallback
        val sequence = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='web_apps'", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        db.execSQL("ALTER TABLE web_apps RENAME TO previous_web_apps")
        onCreate(db)
        val names = "id,name,url,icon,created_at,last_opened_at,desktop_mode,user_agent,third_party_cookies,modified_at,pinned,position,theme_color"
        val values = listOf("id", "name", "url", "icon", "created_at", "last_opened_at", field("desktop_mode", "0"), field("user_agent", "''"), field("third_party_cookies", "0"), field("modified_at", "created_at"), field("pinned", "0"), field("position", "-id"), field("theme_color", "NULL"))
        db.execSQL("INSERT INTO web_apps ($names) SELECT ${values.joinToString(",")} FROM previous_web_apps")
        db.execSQL("DROP TABLE previous_web_apps")
        db.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='web_apps'", arrayOf(sequence))
    }

    suspend fun all(): List<WebApp> = withContext(Dispatchers.IO) { readableDatabase.query("web_apps", null, null, null, null, null, "created_at DESC, id DESC").use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toWebApp())}}}
    suspend fun find(id: Long): WebApp? = withContext(Dispatchers.IO) { readableDatabase.query("web_apps", null, "id = ?", arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) it.toWebApp() else null }}
    suspend fun save(app: WebApp): WebApp = withContext(Dispatchers.IO) {
        val modified = if (app.id == 0L) app.modifiedAt else System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", app.name); put("url", app.url); put("icon", app.icon)
            put("desktop_mode", if (app.desktopMode) 1 else 0)
            put("user_agent", app.userAgent)
            put("third_party_cookies", if (app.thirdPartyCookies) 1 else 0)
            put("modified_at", modified)
            put("theme_color", app.themeColor)
            if (app.id == 0L) {
                put("created_at", app.createdAt); put("pinned", if (app.pinned) 1 else 0)
                writableDatabase.rawQuery("SELECT COALESCE(MIN(position),0)-1 FROM web_apps", null).use { it.moveToFirst(); put("position", it.getLong(0))}
            }
            if (app.id == 0L) if (app.lastOpenedAt == null) putNull("last_opened_at") else put("last_opened_at", app.lastOpenedAt)
        }
        if (app.id == 0L) find(writableDatabase.insertOrThrow("web_apps", null, values))!!
        else {
            check(writableDatabase.update("web_apps", values, "id = ?", arrayOf(app.id.toString())) == 1)
            find(app.id)!!
        }
    }
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { writableDatabase.delete("web_apps", "id = ?", arrayOf(id.toString()))}
    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) { check(writableDatabase.update("web_apps", ContentValues().apply { put("pinned", if (pinned) 1 else 0)}, "id = ?", arrayOf(id.toString())) == 1)}
    suspend fun setPinned(ids: List<Long>, pinned: Boolean) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.update("web_apps", ContentValues().apply { put("pinned", if (pinned) 1 else 0) }, "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    suspend fun reorder(ids: List<Long>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.distinct().forEachIndexed { index, id -> db.update("web_apps", ContentValues().apply { put("position", index.toLong()) }, "id = ?", arrayOf(id.toString()))}
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    suspend fun deleteAll(ids: List<Long>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("web_apps", "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    suspend fun restore(app: WebApp) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", app.id); put("name", app.name); put("url", app.url); put("icon", app.icon)
            put("created_at", app.createdAt); put("modified_at", app.modifiedAt)
            put("last_opened_at", app.lastOpenedAt)
            put("desktop_mode", if (app.desktopMode) 1 else 0); put("user_agent", app.userAgent)
            put("third_party_cookies", if (app.thirdPartyCookies) 1 else 0)
            put("pinned", if (app.pinned) 1 else 0)
            put("position", app.position); put("theme_color", app.themeColor)
        }
        writableDatabase.insertOrThrow("web_apps", null, values)
    }
    suspend fun restoreAll(records: List<WebApp>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            records.forEach { restore(it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    suspend fun markOpened(id: Long) = withContext(Dispatchers.IO) { writableDatabase.update("web_apps", ContentValues().apply { put("last_opened_at", System.currentTimeMillis())}, "id = ?", arrayOf(id.toString()))}
    private fun Cursor.toWebApp() = WebApp(
        getLong(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("name")),
        getString(getColumnIndexOrThrow("url")), getBlob(getColumnIndexOrThrow("icon")),
        getLong(getColumnIndexOrThrow("created_at")),
        getColumnIndexOrThrow("last_opened_at").let { if (isNull(it)) null else getLong(it) },
        getInt(getColumnIndexOrThrow("desktop_mode")) != 0,
        getString(getColumnIndexOrThrow("user_agent")),
        getInt(getColumnIndexOrThrow("third_party_cookies")) != 0,
        getLong(getColumnIndexOrThrow("modified_at")),
        getInt(getColumnIndexOrThrow("pinned")) != 0,
        getLong(getColumnIndexOrThrow("position")),
        getColumnIndexOrThrow("theme_color").let { if (isNull(it)) null else getInt(it) },
    )
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) { instance ?: AppDatabase(context.applicationContext).also { instance = it }}
    }
}