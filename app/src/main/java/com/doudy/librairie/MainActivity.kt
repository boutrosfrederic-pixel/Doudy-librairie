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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.*
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// ==========================================
// 1. THÈME ET COULEURS (Dark Mode Premium)
// ==========================================
object AppTheme {
    val BackgroundDark = Color(0xFF121212)
    val SurfaceDark = Color(0xFF1E1E1E)
    val CardBackground = Color(0xFF252525)
    val CardBorder = Color(0xFF333333)

    val PrimaryEmerald = Color(0xFF10B981)
    val AccentPurple = Color(0xFF8B5CF6)
    val AccentTeal = Color(0xFF14B8A6)
    val AccentAmber = Color(0xFFF59E0B)
    val AccentRose = Color(0xFFEF4444)

    val TextPrimary = Color(0xFFEEEEEE)
    val TextSecondary = Color(0xFFA0A0A0)
    val TextTertiary = Color(0xFF666666)
}

// ==========================================
// 2. MODÈLES DE DONNÉES & STATISTIQUES
// ==========================================
enum class ReadStatus(val label: String) {
    READ("Lu"),
    READING("En cours"),
    UNREAD("À lire"),
    WISHLIST("Envie")
}

data class Book(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isbn: String = "",
    val title: String,
    val authors: String,
    val series: String = "",
    val coverUrl: String = "",
    val publisher: String = "",
    val publishedDate: String = "",
    val description: String = "",
    val source: String = "Manuel",
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val rating: Float = 0f,
    val status: ReadStatus = ReadStatus.UNREAD,
    val personalNotes: String = "",
    val isBorrowed: Boolean = false,
    val borrowerName: String = "",
    val addedTimestamp: Long = System.currentTimeMillis()
)

data class ReadingGoal(
    val targetBooks: Int = 12,
    val year: Int = 2026
)

data class ReadingStats(
    val totalBooks: Int,
    val booksRead: Int,
    val totalPagesRead: Int,
    val topAuthor: String,
    val averageRating: Float,
    val wishlistCount: Int,
    val borrowedCount: Int
)

// ==========================================
// 3. ROOM DATABASE (ENTITÉ ET DAO)
// ==========================================
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val isbn: String,
    val title: String,
    val authors: String,
    val series: String,
    val coverUrl: String,
    val publisher: String,
    val publishedDate: String,
    val description: String,
    val source: String,
    val totalPages: Int,
    val currentPage: Int,
    val rating: Float,
    val status: String,
    val personalNotes: String,
    val isBorrowed: Boolean,
    val borrowerName: String,
    val addedTimestamp: Long
)

fun Book.toEntity(): BookEntity = BookEntity(
    id = id, isbn = isbn, title = title, authors = authors, series = series,
    coverUrl = coverUrl, publisher = publisher, publishedDate = publishedDate,
    description = description, source = source, totalPages = totalPages,
    currentPage = currentPage, rating = rating, status = status.name,
    personalNotes = personalNotes, isBorrowed = isBorrowed, borrowerName = borrowerName,
    addedTimestamp = addedTimestamp
)

fun BookEntity.toDomain(): Book = Book(
    id = id, isbn = isbn, title = title, authors = authors, series = series,
    coverUrl = coverUrl, publisher = publisher, publishedDate = publishedDate,
    description = description, source = source, totalPages = totalPages,
    currentPage = currentPage, rating = rating,
    status = try { ReadStatus.valueOf(status) } catch (e: Exception) { ReadStatus.UNREAD },
    personalNotes = personalNotes, isBorrowed = isBorrowed, borrowerName = borrowerName,
    addedTimestamp = addedTimestamp
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY addedTimestamp DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Delete
    suspend fun deleteBook(book: BookEntity)
}

@Database(entities = [BookEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "library_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// 4. SERVICE API (Google Books + OpenLibrary)
// ==========================================
object BookApiService {
    suspend fun searchBook(query: String): Book? = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().replace("-", "")
        
        val googleResult = searchGoogleBooks(cleanQuery)
        if (googleResult != null) return@withContext googleResult

        searchOpenLibrary(cleanQuery)
    }

    private fun searchGoogleBooks(query: String): Book? {
        return try {
            val urlString = "https://www.googleapis.com/books/v1/volumes?q=$query"
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                if (root.optInt("totalItems", 0) > 0) {
                    val item = root.getJSONArray("items").getJSONObject(0)
                    val info = item.getJSONObject("volumeInfo")

                    val title = info.optString("title", "Titre inconnu")
                    val authors = if (info.has("authors")) {
                        val arr = info.getJSONArray("authors")
                        (0 until arr.length()).joinToString(", ") { arr.getString(it) }
                    } else "Auteur inconnu"

                    val publisher = info.optString("publisher", "")
                    val publishedDate = info.optString("publishedDate", "")
                    val description = info.optString("description", "")
                    val pageCount = info.optInt("pageCount", 0)

                    var cover = ""
                    if (info.has("imageLinks")) {
                        val images = info.getJSONObject("imageLinks")
                        cover = images.optString("thumbnail", "").replace("http://", "https://")
                    }

                    Book(
                        isbn = query,
                        title = title,
                        authors = authors,
                        coverUrl = cover,
                        publisher = publisher,
                        publishedDate = publishedDate,
                        description = description,
                        source = "Google Books",
                        totalPages = pageCount
                    )
                } else null
            } else null
        } catch (e: Exception) {
            Log.e("BookApiService", "Google Books Error", e)
            null
        }
    }

    private fun searchOpenLibrary(query: String): Book? {
        return try {
            val urlString = if (query.all { it.isDigit() }) {
                "https://openlibrary.org/api/books?bibkeys=ISBN:$query&format=json&jscmd=data"
            } else {
                "https://openlibrary.org/search.json?q=${query.replace(" ", "+")}"
            }

            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)

                if (query.all { it.isDigit() }) {
                    val key = "ISBN:$query"
                    if (root.has(key)) {
                        val data = root.getJSONObject(key)
                        val title = data.optString("title", "Titre inconnu")
                        val authors = if (data.has("authors")) {
                            val arr = data.getJSONArray("authors")
                            (0 until arr.length()).joinToString(", ") { arr.getJSONObject(it).getString("name") }
                        } else "Auteur inconnu"

                        var cover = ""
                        if (data.has("cover")) {
                            cover = data.getJSONObject("cover").optString("large", "")
                        }

                        val pages = data.optInt("number_of_pages", 0)

                        Book(
                            isbn = query,
                            title = title,
                            authors = authors,
                            coverUrl = cover,
                            source = "OpenLibrary",
                            totalPages = pages
                        )
                    } else null
                } else {
                    val docs = root.optJSONArray("docs")
                    if (docs != null && docs.length() > 0) {
                        val doc = docs.getJSONObject(0)
                        val title = doc.optString("title", "Titre inconnu")
                        val authors = if (doc.has("author_name")) {
                            val arr = doc.getJSONArray("author_name")
                            (0 until arr.length()).joinToString(", ") { arr.getString(it) }
                        } else "Auteur inconnu"

                        val coverId = doc.optInt("cover_i", 0)
                        val cover = if (coverId > 0) "https://covers.openlibrary.org/b/id/$coverId-L.jpg" else ""

                        Book(
                            isbn = query,
                            title = title,
                            authors = authors,
                            coverUrl = cover,
                            source = "OpenLibrary"
                        )
                    } else null
                }
            } else null
        } catch (e: Exception) {
            Log.e("BookApiService", "OpenLibrary Error", e)
            null
        }
    }
}

// ==========================================
// 5. VIEWMODEL & ÉTATS UI
// ==========================================
sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val book: Book) : UiState()
    data class Error(val message: String) : UiState()
}

class BookViewModel(private val bookDao: BookDao) : ViewModel() {

    val books: StateFlow<List<Book>> = bookDao.getAllBooks()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val readingStats: StateFlow<ReadingStats> = books.map { list ->
        val readBooks = list.filter { it.status == ReadStatus.READ }
        val pages = readBooks.sumOf { it.totalPages }
        val topAuth = list.groupBy { it.authors }
            .maxByOrNull { it.value.size }?.key ?: "Aucun"
        val avgRating = if (readBooks.isNotEmpty()) {
            readBooks.map { it.rating }.filter { it > 0 }.average().toFloat()
        } else 0f

        ReadingStats(
            totalBooks = list.size,
            booksRead = readBooks.size,
            totalPagesRead = pages,
            topAuthor = topAuth,
            averageRating = if (avgRating.isNaN()) 0f else avgRating,
            wishlistCount = list.count { it.status == ReadStatus.WISHLIST },
            borrowedCount = list.count { it.isBorrowed }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingStats(0, 0, 0, "-", 0f, 0, 0))

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun searchAndAddBook(isbnOrQuery: String) {
        if (isbnOrQuery.isBlank()) return
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            val foundBook = BookApiService.searchBook(isbnOrQuery)
            if (foundBook != null) {
                bookDao.insertBook(foundBook.toEntity())
                _uiState.value = UiState.Success(foundBook)
            } else {
                _uiState.value = UiState.Error("Aucun livre trouvé pour : $isbnOrQuery")
            }
        }
    }

    fun addBookDirectly(book: Book) {
        viewModelScope.launch {
            bookDao.insertBook(book.toEntity())
        }
    }

    fun updateBook(updatedBook: Book) {
        viewModelScope.launch {
            bookDao.updateBook(updatedBook.toEntity())
        }
    }

    fun removeBook(book: Book) {
        viewModelScope.launch {
            bookDao.deleteBook(book.toEntity())
        }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }

    fun getExportJsonString(): String {
        val jsonArray = JSONArray()
        books.value.forEach { book ->
            val obj = JSONObject().apply {
                put("id", book.id)
                put("isbn", book.isbn)
                put("title", book.title)
                put("authors", book.authors)
                put("series", book.series)
                put("coverUrl", book.coverUrl)
                put("publisher", book.publisher)
                put("publishedDate", book.publishedDate)
                put("description", book.description)
                put("source", book.source)
                put("totalPages", book.totalPages)
                put("currentPage", book.currentPage)
                put("rating", book.rating)
                put("status", book.status.name)
                put("personalNotes", book.personalNotes)
                put("isBorrowed", book.isBorrowed)
                put("borrowerName", book.borrowerName)
                put("addedTimestamp", book.addedTimestamp)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    fun importFromJsonString(jsonString: String, onComplete: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray(jsonString)
                var count = 0
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val book = Book(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        isbn = obj.optString("isbn", ""),
                        title = obj.optString("title", "Titre inconnu"),
                        authors = obj.optString("authors", "Auteur inconnu"),
                        series = obj.optString("series", ""),
                        coverUrl = obj.optString("coverUrl", ""),
                        publisher = obj.optString("publisher", ""),
                        publishedDate = obj.optString("publishedDate", ""),
                        description = obj.optString("description", ""),
                        source = obj.optString("source", "Import JSON"),
                        totalPages = obj.optInt("totalPages", 300),
                        currentPage = obj.optInt("currentPage", 0),
                        rating = obj.optDouble("rating", 0.0).toFloat(),
                        status = try {
                            ReadStatus.valueOf(obj.optString("status", ReadStatus.UNREAD.name))
                        } catch (e: Exception) { ReadStatus.UNREAD },
                        personalNotes = obj.optString("personalNotes", ""),
                        isBorrowed = obj.optBoolean("isBorrowed", false),
                        borrowerName = obj.optString("borrowerName", ""),
                        addedTimestamp = obj.optLong("addedTimestamp", System.currentTimeMillis())
                    )
                    bookDao.insertBook(book.toEntity())
                    count++
                }
                withContext(Dispatchers.Main) {
                    onComplete(count)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete(-1)
                }
            }
        }
    }
}

class BookViewModelFactory(private val bookDao: BookDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BookViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BookViewModel(bookDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ==========================================
// 6. MAIN ACTIVITY & ENTRY POINT
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getDatabase(this)
        val viewModelFactory = BookViewModelFactory(database.bookDao())
        val viewModel = ViewModelProvider(this, viewModelFactory)[BookViewModel::class.java]

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = AppTheme.BackgroundDark,
                    surface = AppTheme.SurfaceDark,
                    primary = AppTheme.PrimaryEmerald
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppTheme.BackgroundDark
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}

// ==========================================
// 7. INTERFACE GRAPHIQUE PRINCIPALE
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BookViewModel) {
    val context = LocalContext.current
    val books by viewModel.books.collectAsState()
    val stats by viewModel.readingStats.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var selectedStatusTab by remember { mutableStateOf<ReadStatus?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isGridView by remember { mutableStateOf(true) }

    var selectedBookForDetail by remember { mutableStateOf<Book?>(null) }
    var showManualAddDialog by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { destinationUri ->
            try {
                context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    outputStream.write(viewModel.getExportJsonString().toByteArray())
                }
                Toast.makeText(context, "Bibliothèque exportée !", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur lors de l'exportation", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            try {
                val jsonString = context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                }
                if (!jsonString.isNullOrBlank()) {
                    viewModel.importFromJsonString(jsonString) { count ->
                        if (count >= 0) {
                            Toast.makeText(context, "$count livre(s) importé(s) !", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Fichier JSON invalide", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur lors de l'importation", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showCameraScanner = true
        } else {
            Toast.makeText(context, "Permission caméra requise pour le scan", Toast.LENGTH_SHORT).show()
        }
    }

    val filteredBooks = remember(books, selectedStatusTab, searchQuery) {
        books.filter { book ->
            val matchesStatus = selectedStatusTab == null || book.status == selectedStatusTab
            val matchesSearch = searchQuery.isBlank() ||
                    book.title.contains(searchQuery, ignoreCase = true) ||
                    book.authors.contains(searchQuery, ignoreCase = true) ||
                    book.series.contains(searchQuery, ignoreCase = true) ||
                    book.isbn.contains(searchQuery, ignoreCase = true)
            matchesStatus && matchesSearch
        }
    }

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is UiState.Success -> {
                Toast.makeText(context, "Ajouté : ${state.book.title}", Toast.LENGTH_SHORT).show()
                viewModel.resetState()
            }
            is UiState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                viewModel.resetState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(AppTheme.BackgroundDark)) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Bibliothèque",
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp,
                                color = AppTheme.TextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "(${books.size})",
                                fontSize = 14.sp,
                                color = AppTheme.TextSecondary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.BackgroundDark),
                    actions = {
                        IconButton(onClick = { showStatsDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.BarChart,
                                contentDescription = "Statistiques",
                                tint = AppTheme.AccentAmber
                            )
                        }
                        IconButton(onClick = { isGridView = !isGridView }) {
                            Icon(
                                imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                                contentDescription = "Changer la vue",
                                tint = AppTheme.TextPrimary
                            )
                        }
                        IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                            Icon(
                                imageVector = Icons.Outlined.FileUpload,
                                contentDescription = "Importer JSON",
                                tint = AppTheme.AccentTeal
                            )
                        }
                        IconButton(onClick = { exportLauncher.launch("ma_bibliotheque.json") }) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = "Exporter JSON",
                                tint = AppTheme.PrimaryEmerald
                            )
                        }
                    }
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Rechercher titre, auteur, tome...", color = AppTheme.TextTertiary) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AppTheme.TextSecondary) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = null, tint = AppTheme.TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.PrimaryEmerald,
                        unfocusedBorderColor = AppTheme.CardBorder,
                        focusedContainerColor = AppTheme.SurfaceDark,
                        unfocusedContainerColor = AppTheme.SurfaceDark,
                        focusedTextColor = AppTheme.TextPrimary,
                        unfocusedTextColor = AppTheme.TextPrimary
                    )
                )

                ScrollableTabRow(
                    selectedTabIndex = if (selectedStatusTab == null) 0 else selectedStatusTab!!.ordinal + 1,
                    edgePadding = 16.dp,
                    containerColor = AppTheme.BackgroundDark,
                    contentColor = AppTheme.PrimaryEmerald,
                    divider = {}
                ) {
                    Tab(
                        selected = selectedStatusTab == null,
                        onClick = { selectedStatusTab = null },
                        text = { Text("Tous (${books.size})", fontSize = 13.sp) }
                    )
                    ReadStatus.values().forEach { status ->
                        val count = books.count { it.status == status }
                        Tab(
                            selected = selectedStatusTab == status,
                            onClick = { selectedStatusTab = status },
                            text = { Text("${status.label} ($count)", fontSize = 13.sp) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { showManualAddDialog = true },
                    containerColor = AppTheme.SurfaceDark,
                    contentColor = AppTheme.AccentPurple,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Ajout manuel")
                }
                FloatingActionButton(
                    onClick = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            showCameraScanner = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    containerColor = AppTheme.PrimaryEmerald,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scanner un code-barres")
                }
            }
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState is UiState.Loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = AppTheme.PrimaryEmerald
                )
            }

            if (filteredBooks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "Aucun livre trouvé" else "Aucun livre dans cette catégorie",
                        color = AppTheme.TextTertiary,
                        fontSize = 16.sp
                    )
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredBooks, key = { it.id }) { book ->
                        BookGridItem(book = book, onClick = { selectedBookForDetail = book })
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredBooks, key = { it.id }) { book ->
                        BookListItem(book = book, onClick = { selectedBookForDetail = book })
                    }
                }
            }
        }
    }

    if (showCameraScanner) {
        BarcodeScannerDialog(
            onDismiss = { showCameraScanner = false },
            onBarcodeScanned = { isbn ->
                showCameraScanner = false
                viewModel.searchAndAddBook(isbn)
            }
        )
    }

    if (showManualAddDialog) {
        ManualAddBookDialog(
            onDismiss = { showManualAddDialog = false },
            onAdd = { newBook ->
                viewModel.addBookDirectly(newBook)
                showManualAddDialog = false
            }
        )
    }

    if (showStatsDialog) {
        StatsDialog(
            stats = stats,
            onDismiss = { showStatsDialog = false }
        )
    }

    selectedBookForDetail?.let { book ->
        BookDetailDialog(
            book = book,
            onDismiss = { selectedBookForDetail = null },
            onUpdate = { updatedBook ->
                viewModel.updateBook(updatedBook)
                selectedBookForDetail = updatedBook
            },
            onDelete = {
                viewModel.removeBook(book)
                selectedBookForDetail = null
            }
        )
    }
}

// ==========================================
// 8. ÉLÉMENTS DE LISTE ET DE GRILLE
// ==========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListItem(book: Book, onClick: () -> Unit) {
    val statusColor = when (book.status) {
        ReadStatus.READ -> AppTheme.PrimaryEmerald
        ReadStatus.READING -> AppTheme.AccentAmber
        ReadStatus.UNREAD -> AppTheme.AccentPurple
        ReadStatus.WISHLIST -> AppTheme.AccentTeal
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = AppTheme.CardBackground)
    ) {
        Row(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 50.dp, height = 75.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppTheme.SurfaceDark)
            ) {
                if (book.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = AppTheme.TextTertiary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AppTheme.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = book.authors,
                    fontSize = 13.sp,
                    color = AppTheme.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (book.series.isNotBlank()) {
                    Text(
                        text = book.series,
                        fontSize = 12.sp,
                        color = AppTheme.AccentPurple,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(statusColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = book.status.label,
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (book.isBorrowed) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(AppTheme.AccentRose.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Prêté: ${book.borrowerName}",
                                color = AppTheme.AccentRose,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (book.rating > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = AppTheme.AccentAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${book.rating}",
                        fontSize = 12.sp,
                        color = AppTheme.TextSecondary,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
}

fun extractVolumeNumber(title: String): Int {
    val regex = Regex("""(?i)(?:tome|t|vol|volume|\bT|\bV)\s*[:.-]?\s*(\d+)""")
    val match = regex.find(title)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 999
}

@Composable
fun BookGridItem(book: Book, onClick: () -> Unit) {
    val volumeNum = remember(book.title) { extractVolumeNumber(book.title) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.CardBackground)
                .border(1.dp, AppTheme.CardBorder, RoundedCornerShape(8.dp))
        ) {
            if (book.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = book.coverUrl,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = AppTheme.TextTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            if (volumeNum != 999) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(
                            color = AppTheme.AccentPurple,
                            shape = RoundedCornerShape(topEnd = 6.dp, bottomStart = 8.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$volumeNum",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = book.title,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = AppTheme.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==========================================
// 9. DIALOGS & STATISTIQUES (PRIORITÉ 6)
// ==========================================
@Composable
fun StatsDialog(
    stats: ReadingStats,
    goal: ReadingGoal = ReadingGoal(12, 2026),
    onDismiss: () -> Unit
) {
    val progress = if (goal.targetBooks > 0) {
        (stats.booksRead.toFloat() / goal.targetBooks.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.SurfaceDark)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Statistiques ${goal.year}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = AppTheme.TextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer", tint = AppTheme.TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.CardBackground),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Objectif annuel", fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text("${stats.booksRead} / ${goal.targetBooks} livres", color = AppTheme.AccentTeal, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AppTheme.PrimaryEmerald,
                            trackColor = AppTheme.CardBorder
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = "Pages lues",
                        value = "${stats.totalPagesRead}",
                        icon = Icons.Default.MenuBook,
                        tint = AppTheme.AccentPurple
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = "Note moyenne",
                        value = if (stats.averageRating > 0) "★ ${String.format("%.1f", stats.averageRating)}" else "-",
                        icon = Icons.Default.Star,
                        tint = AppTheme.AccentAmber
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = "Livres prêtés",
                        value = "${stats.borrowedCount}",
                        icon = Icons.Default.Outbox,
                        tint = AppTheme.AccentRose
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = "Envies (Wishlist)",
                        value = "${stats.wishlistCount}",
                        icon = Icons.Default.Bookmark,
                        tint = AppTheme.AccentTeal
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.CardBackground),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = AppTheme.PrimaryEmerald)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Auteur le plus présent", fontSize = 12.sp, color = AppTheme.TextSecondary)
                            Text(stats.topAuthor, fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    tint: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AppTheme.CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary)
            Text(title, fontSize = 11.sp, color = AppTheme.TextSecondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailDialog(
    book: Book,
    onDismiss: () -> Unit,
    onUpdate: (Book) -> Unit,
    onDelete: () -> Unit
) {
    var currentPage by remember { mutableStateOf(book.currentPage.toString()) }
    var totalPages by remember { mutableStateOf(book.totalPages.toString()) }
    var notes by remember { mutableStateOf(book.personalNotes) }
    var rating by remember { mutableStateOf(book.rating) }
    var status by remember { mutableStateOf(book.status) }
    var isBorrowed by remember { mutableStateOf(book.isBorrowed) }
    var borrowerName by remember { mutableStateOf(book.borrowerName) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.SurfaceDark)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Détails du Livre",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AppTheme.TextPrimary
                    )
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = AppTheme.AccentRose)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row {
                    if (book.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = book.coverUrl,
                            contentDescription = book.title,
                            modifier = Modifier
                                .size(width = 80.dp, height = 120.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Column {
                        Text(text = book.title, fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary)
                        Text(text = book.authors, fontSize = 14.sp, color = AppTheme.TextSecondary)
                        if (book.isbn.isNotBlank()) {
                            Text(text = "ISBN: ${book.isbn}", fontSize = 12.sp, color = AppTheme.TextTertiary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Statut de lecture", fontSize = 14.sp, color = AppTheme.TextSecondary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ReadStatus.values().forEach { st ->
                        FilterChip(
                            selected = status == st,
                            onClick = { status = st },
                            label = { Text(st.label, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currentPage,
                        onValueChange = { currentPage = it },
                        label = { Text("Page actuelle") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = totalPages,
                        onValueChange = { totalPages = it },
                        label = { Text("Total pages") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text("Note (${rating}/5)", fontSize = 14.sp, color = AppTheme.TextSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    (1..5).forEach { star ->
                        IconButton(onClick = { rating = star.toFloat() }) {
                            Icon(
                                imageVector = if (star <= rating) Icons.Default.Star else Icons.Outlined.Star,
                                contentDescription = null,
                                tint = if (star <= rating) AppTheme.AccentAmber else AppTheme.TextTertiary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isBorrowed,
                        onCheckedChange = { isBorrowed = it }
                    )
                    Text("Livre prêté", color = AppTheme.TextPrimary)
                }

                if (isBorrowed) {
                    OutlinedTextField(
                        value = borrowerName,
                        onValueChange = { borrowerName = it },
                        label = { Text("Nom de l'emprunteur") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes personnelles") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Annuler", color = AppTheme.TextSecondary)
                    }
                    Button(
                        onClick = {
                            val updated = book.copy(
                                currentPage = currentPage.toIntOrNull() ?: 0,
                                totalPages = totalPages.toIntOrNull() ?: 0,
                                personalNotes = notes,
                                rating = rating,
                                status = status,
                                isBorrowed = isBorrowed,
                                borrowerName = if (isBorrowed) borrowerName else ""
                            )
                            onUpdate(updated)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.PrimaryEmerald)
                    ) {
                        Text("Enregistrer")
                    }
                }
            }
        }
    }
}

@Composable
fun ManualAddBookDialog(onDismiss: () -> Unit, onAdd: (Book) -> Unit) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var series by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.SurfaceDark)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Ajouter un livre manuellement", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppTheme.TextPrimary)
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titre *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Auteur *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = series, onValueChange = { series = it }, label = { Text("Série / Tome") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = pages, onValueChange = { pages = it }, label = { Text("Nombre de pages") }, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Annuler", color = AppTheme.TextSecondary) }
                    Button(
                        onClick = {
                            if (title.isNotBlank() && author.isNotBlank()) {
                                onAdd(
                                    Book(
                                        title = title,
                                        authors = author,
                                        series = series,
                                        totalPages = pages.toIntOrNull() ?: 0,
                                        source = "Manuel"
                                    )
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.PrimaryEmerald)
                    ) {
                        Text("Ajouter")
                    }
                }
            }
        }
    }
}

@Composable
fun BarcodeScannerDialog(onDismiss: () -> Unit, onBarcodeScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val executor = Executors.newSingleThreadExecutor()
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val scanner = BarcodeScanning.getClient(
                                BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
                                    .build()
                            )

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                processImageProxy(scanner, imageProxy, onBarcodeScanned)
                            }

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e("Scanner", "Erreur binding caméra", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onBarcodeScanned: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let { value ->
                        onBarcodeScanned(value)
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
