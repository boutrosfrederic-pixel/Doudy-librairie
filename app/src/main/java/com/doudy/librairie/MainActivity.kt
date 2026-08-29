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
}

@Database(entities = [Book::class], version = 1)
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

    // Auto-fix les 3 anciens "Livre 978..."
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
                onClick = {
                    hasCameraPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasCameraPermission) showScan = true
                    else {
                        activity.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
                        Toast.makeText(context, "Autorise la caméra puis réessaie", Toast.LENGTH_SHORT).show()
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
                                Async
