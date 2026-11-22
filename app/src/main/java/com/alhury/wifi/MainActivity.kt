package com.alhury.wifi

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class LocalStore(context: Context) : SQLiteOpenHelper(context, "alhury.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY, kind TEXT, payload TEXT, created_at INTEGER, synced INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE distributors(id TEXT PRIMARY KEY, name TEXT, phone TEXT, active INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE categories(id TEXT PRIMARY KEY, name TEXT, face_value REAL, sale_price REAL, active INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE stock(id TEXT PRIMARY KEY, distributor_id TEXT, category_id TEXT, quantity INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE points(id TEXT PRIMARY KEY, distributor_id TEXT, name TEXT, phone TEXT)")
        db.execSQL("CREATE TABLE sales(id TEXT PRIMARY KEY, distributor_id TEXT, point_id TEXT, category_id TEXT, quantity INTEGER, unit_price REAL, total REAL, paid REAL, created_at INTEGER)")
        db.execSQL("CREATE TABLE ledger(id TEXT PRIMARY KEY, distributor_id TEXT, point_id TEXT, kind TEXT, amount REAL, note TEXT, created_at INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun addEvent(kind: String, payload: JSONObject) {
        writableDatabase.execSQL(
            "INSERT INTO events(id,kind,payload,created_at,synced) VALUES(?,?,?,?,0)",
            arrayOf(UUID.randomUUID().toString(), kind, payload.toString(), System.currentTimeMillis())
        )
    }
    fun pending(): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT id,kind,payload,created_at FROM events WHERE synced=0 ORDER BY created_at", null).use { c ->
            while (c.moveToNext()) out.put(JSONObject()
                .put("id", c.getString(0)).put("type", c.getString(1))
                .put("payload", JSONObject(c.getString(2))).put("createdAt", c.getLong(3)))
        }
        return out
    }
    fun markSynced(events: JSONArray) {
        for (i in 0 until events.length()) {
            writableDatabase.execSQL("UPDATE events SET synced=1 WHERE id=?", arrayOf(events.getJSONObject(i).getString("id")))
        }
    }
    fun pendingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE synced=0", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }
}

class SheetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("apps_script_url", "").orEmpty()
        if (endpoint.isBlank()) return@withContext Result.success()
        val store = LocalStore(applicationContext)
        val events = store.pending()
        if (events.length() == 0) return@withContext Result.success()
        try {
            val body = JSONObject().put("action", "sync")
                .put("deviceId", android.provider.Settings.Secure.getString(
                    applicationContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID))
                .put("events", events)
            val request = Request.Builder().url(endpoint)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.retry()
                val result = JSONObject(response.body?.string().orEmpty())
                if (result.optBoolean("ok")) {
                    store.markSynced(events)
                    Result.success()
                } else Result.retry()
            }
        } catch (_: Exception) { Result.retry() }
    }
}

fun queueSync(context: Context) {
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val periodic = PeriodicWorkRequestBuilder<SheetSyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "alhury-google-sheets-sync", ExistingPeriodicWorkPolicy.UPDATE, periodic)
    val immediate = OneTimeWorkRequestBuilder<SheetSyncWorker>().setConstraints(constraints).build()
    WorkManager.getInstance(context).enqueue(immediate)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queueSync(this)
        setContent { AlhuryHome(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlhuryHome(context: Context) {
    val store = remember { LocalStore(context) }
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var role by remember { mutableStateOf(prefs.getString("role", "المدير") ?: "المدير") }
    var page by remember { mutableStateOf("الرئيسية") }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(prefs.getString("apps_script_url", "").orEmpty()) }
    var refresh by remember { mutableIntStateOf(0) }
    val waiting = remember(refresh) { store.pendingCount() }
    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("الحوري | حسابات كروت الواي فاي") }) }) { padding ->
            Column(Modifier.padding(padding).padding(14.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(role == "المدير", { role = "المدير"; prefs.edit().putString("role", role).apply() }, { Text("المدير") })
                    FilterChip(role == "موزع", { role = "موزع"; prefs.edit().putString("role", role).apply() }, { Text("موزع") })
                    Spacer(Modifier.weight(1f))
                    Text("معلّق: $waiting")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("الرئيسية", "عملية", "السجل", "الإعدادات").forEach {
                        TextButton(onClick = { page = it }) { Text(it) }
                    }
                }
                when (page) {
                    "الرئيسية" -> {
                        Text(if (role == "المدير") "لوحة المدير" else "لوحة الموزع", style = MaterialTheme.typography.headlineSmall)
                        Text("قاعدة بيانات محلية؛ لا يتطلب تسجيل العمليات اتصالًا بالإنترنت.")
                        Card { Column(Modifier.padding(16.dp)) {
                            Text("عمليات تنتظر المزامنة")
                            Text("$waiting", style = MaterialTheme.typography.headlineLarge)
                        } }
                        Text("المدير: الموزعون والفئات والمخازن والتقارير.")
                        Text("الموزع: نقاط البيع والمبيعات والتحصيل والمصروفات.")
                    }
                    "عملية" -> {
                        var kind by remember { mutableStateOf("sale") }
                        Text("تسجيل عملية", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(title, { title = it }, label = { Text("اسم الموزع أو نقطة البيع أو البيان") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ / سعر الوحدة") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(quantity, { quantity = it }, label = { Text("الكمية") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(note, { note = it }, label = { Text("ملاحظات") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf("sale" to "بيع", "collection" to "تحصيل", "expense" to "مصروف", "distributor" to "موزع", "category" to "فئة", "point" to "نقطة بيع", "stock" to "توريد").forEach { (v, label) ->
                                FilterChip(kind == v, { kind = v }, { Text(label) })
                            }
                        }
                        Button(onClick = {
                            val payload = JSONObject().put("role", role).put("title", title.trim())
                                .put("amount", amount.toDoubleOrNull() ?: 0.0)
                                .put("quantity", quantity.toIntOrNull() ?: 1)
                                .put("note", note).put("occurredAt", System.currentTimeMillis())
                            store.addEvent(kind, payload)
                            refresh++
                            title = ""; amount = ""; quantity = "1"; note = ""
                            queueSync(context); page = "الرئيسية"
                        }, enabled = title.isNotBlank()) { Text("حفظ محليًا") }
                    }
                    "السجل" -> {
                        Text("السجل المحلي", style = MaterialTheme.typography.headlineSmall)
                        Text("العمليات محفوظة على الهاتف وتنتظر إرسالها إلى Google Sheets.")
                        Button(onClick = { refresh++; queueSync(context) }) { Text("محاولة المزامنة الآن") }
                        Text("عدد العمليات غير المتزامنة: $waiting")
                    }
                    else -> {
                        Text("إعداد Google Sheets", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("رابط Apps Script Web App") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            prefs.edit().putString("apps_script_url", endpoint.trim()).apply()
                            queueSync(context)
                        }) { Text("حفظ الرابط") }
                        Text("لا تضع كلمة مرور Google داخل التطبيق. يجب نشر Apps Script وإعداد صلاحيات الخادم.")
                        Text("تنبيه: اختيار الدور هنا تجريبي وليس نظام صلاحيات آمنًا للإنتاج.")
                    }
                }
            }
        }
    }
}
