package com.doudy.librairie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.room.*
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val isbn: String,
    val title: String,
    val authors: String,
    val coverUrl: String = ""
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
            val inst = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "doudy_db").fallbackToDestructiveMigration().build()
            INSTANCE = inst
            return inst
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getDatabase(this).bookDao()
        setContent {
            MaterialTheme {
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
    var showScan by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Doudy Librairie") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showScan = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Scanner") })
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(books) { book ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = book.coverUrl, contentDescription = null, modifier = Modifier.size(60.dp, 90.dp).background(Color.LightGray), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(book.title, fontWeight = FontWeight.Bold)
                                Text(book.authors)
                                Text(book.isbn)
                            }
                            IconButton(onClick = { scope.launch { dao.deleteBook(book) } }) {
                                Icon(Icons.Default.Delete, null)
                            }
                        }
                    }
                }
            }
            if (showScan) {
                CameraView(onScanned = { isbn ->
                    showScan = false
                    val clean = isbn.filter { it.isDigit() }
                    val cover = "https://covers.openlibrary.org/b/isbn/" + clean + "-M.jpg"
                    val book = Book(clean, "Livre " + clean, "Auteur", cover)
                    scope.launch {
                        dao.insertBook(book)
                        Toast.makeText(context, "Ajoute " + clean, Toast.LENGTH_SHORT).show()
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
    LaunchedEffect(Unit) {
        if (hasPerm.not()) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
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
                                    val type = bc.valueType
                                    if (type == Barcode.TYPE_ISBN) {
                                        val raw = bc.rawValue
                                        if (raw != null) {
                                            onScanned(raw)
                                            break
                                        }
                                    }
                                    if (type == Barcode.TYPE_PRODUCT) {
                                        val raw = bc.rawValue
                                        if (raw != null) {
                                            onScanned(raw)
                                            break
                                        }
                                    }
                                }
                            }.addOnCompleteListener { proxy.close() }
                        } else {
                            proxy.close()
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
                pv
            }, modifier = Modifier.fillMaxSize())
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            Icon(Icons.Default.Clear, null, tint = Color.White)
        }
    }
}
