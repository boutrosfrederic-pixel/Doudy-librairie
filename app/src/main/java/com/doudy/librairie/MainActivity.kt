package com.doudy.librairie

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.room.*
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

@Entity
data class Book(
    @PrimaryKey val isbn: String,
    val title: String,
    val authors: String,
    val coverUrl: String
)

@Dao
interface BookDao {
    @Query("SELECT * FROM Book ORDER BY isbn DESC")
    fun getAllBooks(): Flow<List<Book>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book)
    @Delete
    suspend fun deleteBook(book: Book)
}

@Database(entities = [Book::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "doudy.db").build()
        setContent { MaterialTheme { MainScreen(db.bookDao()) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(dao: BookDao) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    val books by dao.getAllBooks().collectAsState(initial = emptyList())
    var showScan by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(books) {
        val toFix = books.filter { it.title.startsWith("Livre ") }
        for (old in toFix) {
            val real = fetchBookInfo(old.isbn)
            if (real.title != old.title) {
                dao.insertBook(real)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Doudy Librairie") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Scanner") },
                icon = { Icon(Icons.Filled.Add, null) },
                onClick = {
                    hasCameraPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasCameraPermission) {
                        showScan = true
                    } else {
                        activity.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
                        Toast.makeText(context, "Autorise la camera puis reessaie", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aucun livre. Appuie sur Scanner", color = Color.Gray)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(books) { book ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = book.coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(60.dp, 90.dp).background(Color(0xFFE0E0E0)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                    Text(book.authors, style = MaterialTheme.typography.bodySmall)
                                    Text(book.isbn, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                                IconButton(onClick = { scope.launch { dao.deleteBook(book) } }) {
                                    Icon(Icons.Filled.Delete, null)
                                }
                            }
                        }
                    }
                }
            }
            if (showScan) {
                CameraView(
                    onScanned = { isbn ->
                        showScan = false
                        val clean = isbn.filter { it.isDigit() }
                        if (clean.length >= 10) {
                            scope.launch {
                                val book = fetchBookInfo(clean)
                                dao.insertBook(book)
                                Toast.makeText(context, "Ajoute: " + book.title, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onClose = { showScan = false }
                )
            }
        }
    }
}

suspend fun fetchBookInfo(isbn: String): Book = withContext(Dispatchers.IO) {
    var title = "Livre $isbn"
    var authors = "Auteur inconnu"
    var cover = "https://covers.openlibrary.org/b/isbn/" + isbn + "-M.jpg"
    try {
        val jsonStr = URL("https://www.googleapis.com/books/v1/volumes?q=isbn:" + isbn).readText()
        val json = JSONObject(jsonStr)
        if (json.optInt("totalItems", 0) > 0) {
            val info = json.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo")
            title = info.optString("title", title)
            if (info.has("authors")) {
                val arr = info.getJSONArray("authors")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                authors = list.joinToString(", ")
            }
            if (info.has("imageLinks")) {
                val thumb = info.getJSONObject("imageLinks").optString("thumbnail", cover)
                cover = thumb.replace("http:", "https:")
            }
        }
    } catch (e: Exception) {
    }
    Book(isbn, title, authors, cover)
}

@Composable
fun CameraView(onScanned: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanned by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                        val mediaImage = proxy.image
                        if (mediaImage != null && !scanned) {
                            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    for (b in barcodes) {
                                        b.rawValue?.let { v ->
                                            if (v.length >= 10 && !scanned) {
                                                scanned = true
                                                onScanned(v)
                                            }
                                        }
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        } else {
                            proxy.close()
                        }
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Erreur: " + e.message, Toast.LENGTH_LONG).show()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
        Button(onClick = onClose, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)) {
            Text("Fermer")
        }
    }
}
