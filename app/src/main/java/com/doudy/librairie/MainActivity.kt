package com.doudy.librairie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.room.*
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val isbn: String,
    val title: String,
    val authors: String,
    val coverUrl: String = "",
    val status: String = "A lire"
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    fun getAllBooks(): Flow<List<Book>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book)
    @Delete
    suspend fun deleteBook(book: Book)
}

@Database(entities = [Book::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "doudy_db"
            ).fallbackToDestructiveMigration().build()
            INSTANCE = instance
            return instance
        }
    }
}

private val Bg = Color(0xFFFAF6F0)
private val Primary = Color(0xFF6B3E2E)
private val Accent = Color(0xFFD97736)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getDatabase(this).bookDao()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Primary, background = Bg)) {
                MainScreen(dao)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(dao: BookDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by dao.getAllBooks().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var showScan by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val filtered = if (query.isBlank()) books else books.filter { it.title.lowercase().contains(query.lowercase()) || it.authors.lowercase().contains(query.lowercase()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Doudy Librairie", color = Color.White) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Primary)) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showScan = true }, containerColor = Accent, contentColor = Color.White, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Scanner") })
        },
        containerColor = Bg
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Rechercher") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, null) } }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                if (loading) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() } }
                else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(filtered) { book ->
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().clickable {}) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(model = book.coverUrl, contentDescription = null, modifier = Modifier.size(60.dp, 90.dp).background(Color.LightGray, RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(book.title, fontWeight = FontWeight.Bold, maxLines = 2)
                                        Text(book.authors, fontSize = 12.sp, color = Color.Gray)
                                        Text(book.isbn, fontSize = 10.sp, color = Color.Gray)
                                    }
                                    IconButton(onClick = { scope.launch { dao.deleteBook(book) } }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                                }
                            }
                        }
                    }
                }
            }
            if (showScan) {
                CameraView(onScanned = { isbn ->
                    showScan = false
                    loading = true
                    scope.launch {
                        val b = fetchBook(isbn)
                        loading = false
                        if (b != null) {
                            dao.insertBook(b)
                            Toast.makeText(context, "Ajoute " + b.title, Toast.LENGTH_SHORT).show()
                        }
                    }
                }, onClose = { showScan = false })
            }
        }
    }
}

@Composable
fun CameraView(onScanned: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hasPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    LaunchedEffect(Unit) { if (!hasPerm) launcher.launch(Manifest.permission.CAMERA) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPerm) {
            AndroidView(factory = { ctx ->
                val pv = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(pv.surfaceProvider)
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                        val media = proxy.image
                        if (media != null) {
                            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            scanner.process(input).addOnSuccessListener { list ->
                                for (bc in list) {
                                    if (bc.valueType == Barcode.TYPE_ISBN || bc.valueType == Barcode.TYPE_PRODUCT) {
                                        bc.rawValue?.let { onScanned(it) }
                                        break
                                    }
                                }
                            }.addOnCompleteListener { proxy.close() }
                        } else proxy.close()
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
                pv
            }, modifier = Modifier.fillMaxSize())
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Icon(Icons.Default.Clear, null, tint = Color.White) }
    }
}

suspend fun fetchBook(isbnRaw: String): Book? = withContext(Dispatchers.IO) {
    val isbn = isbnRaw.filter { it.isDigit() }
    if (isbn.isEmpty()) return@withContext null
    var title = ""
    var authors = "Inconnu"
    var cover = ""
    try {
        val url = URL("https://www.googleapis.com/books/v1/volumes?q=isbn:" + isbn)
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (conn.responseCode == 200) {
            val txt = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(txt)
            if (json.optInt("totalItems", 0) > 0) {
                val info = json.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo")
                title = info.optString("title")
                val arr = info.optJSONArray("authors")
                if (arr != null && arr.length() > 0) { authors = arr.getString(0) }
                if (info
