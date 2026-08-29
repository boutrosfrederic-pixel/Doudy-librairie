package com.doudy.librairie

import android.content.Context
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    @Query("SELECT * FROM Book WHERE isbn = :isbn LIMIT 1")
    suspend fun getByIsbn(isbn: String): Book?
}

@Database(entities = [Book::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "doudy.db").build()
        setContent { MaterialTheme { MainScreen(db.bookDao()) } }
    }
    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(dao: BookDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by dao.getAllBooks().collectAsState(initial = emptyList())
    var showScan by remember { mutableStateOf(false) }

    // Auto-corrige les 3 livres "Livre 978..." déjà scannés
    LaunchedEffect(books) {
        books.filter { it.title.startsWith("Livre ") }.forEach { old ->
            val real = fetchBookInfo(old.isbn)
            if (real.title != old.title) dao.insertBook(real)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Doudy Librairie") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Scanner") },
                icon = { Icon(Icons.Filled.Add, null) },
                onClick = { showScan = true }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(books) { book ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = book.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(60.dp, 90.dp).background(Color.LightGray),
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
            if (showScan) {
                CameraView(
                    onScanned = { isbn ->
                        showScan = false
                        val clean = isbn.filter { it.isDigit() }
                        scope.launch {
                            val book = fetchBookInfo(clean)
                            dao.insertBook(book)
                            Toast.makeText(context, "Ajouté: ${book.title}", Toast.LENGTH_SHORT).show()
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
    var cover = "https://covers.openlibrary.org/b/isbn/$isbn-M.jpg"
    try {
        val jsonStr = URL("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn").readText()
        val json = JSONObject(jsonStr)
        if (json.optInt("totalItems", 0) > 0) {
            val info = json.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo")
            title = info.optString("title", title)
            if (info.has("authors")) {
                val arr = info.getJSONArray("authors")
                authors = (0 until arr.length()).joinToString(", ") { arr.getString(it) }
            }
            if (info.has("imageLinks")) {
                cover = info.getJSONObject("imageLinks").optString("thumbnail", cover).replace("http://","https://")
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
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
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                        if (!scanned) {
                            val mediaImage = proxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                                scanner.process(image).addOnSuccessListener { barcodes ->
                                    for (b in barcodes) {
                                        b.rawValue?.let {
                                            if (it.length >= 8) { scanned = true; onScanned(it) }
                                        }
                                    }
                                }.addOnCompleteListener { proxy.close() }
                            } else proxy.close()
                        } else proxy.close()
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (e: Exception) { e.printStackTrace() }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
        Button(onClick = onClose, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)) { Text("Fermer") }
    }
}
