package com.doudy.librairie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import org.json.JSONArray
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
    val status: String = "À lire",
    val description: String = ""
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    fun getAllBooks(): Flow<List<Book>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<Book>)
    @Delete
    suspend fun deleteBook(book: Book)
}

@Database(entities = [Book::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "doudy_librairie_db").fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

private val LibraryWarmBackground = Color(0xFFFAF6F0)
private val LibraryPrimary = Color(0xFF6B3E2E)
private val LibrarySecondary = Color(0xFF8C5A47)
private val LibraryAccent = Color(0xFFD97736)
private val LibraryCardBg = Color(0xFFFFFFFF)

@Composable
fun DoudyTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(primary = LibraryPrimary, secondary = LibrarySecondary, tertiary = LibraryAccent, background = LibraryWarmBackground, surface = LibraryCardBg)
    MaterialTheme(colorScheme = colorScheme, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        val bookDao = db.bookDao()
        setContent { DoudyTheme { MainScreen(bookDao) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(bookDao: BookDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val booksState by bookDao.getAllBooks().collectAsState(initial = emptyList())
    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var selectedBookForDetail by remember { mutableStateOf<Book?>(null) }

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { exportDataToJson(context, booksState, it) } }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { exportDataToCsv(context, booksState, it) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { scope.launch { val c = importDataFromUri(context, bookDao, it); Toast.makeText(context, "$c livre(s) importé(s)", Toast.LENGTH_SHORT).show() } } }

    val countAll = booksState.size
    val countALire = remember(booksState) { booksState.count { it.status == "À lire" } }
    val countEnCours = remember(booksState) { booksState.count { it.status == "En cours" } }
    val countLu = remember(booksState) { booksState.count { it.status == "Lu" } }
    val categories = listOf("Tous ($countAll)", "À lire ($countALire)", "En cours ($countEnCours)", "Lu ($countLu)")

    val filteredBooks = remember(booksState, selectedTab, searchQuery) {
        val tabFiltered = when (selectedTab) { 1 -> booksState.filter { it.status == "À lire" }; 2 -> booksState.filter { it.status == "En cours" }; 3 -> booksState.filter { it.status == "Lu" }; else -> booksState }
        if (searchQuery.isBlank()) tabFiltered else { val q = searchQuery.trim().lowercase(); tabFiltered.filter { it.title.lowercase().contains(q) || it.authors.lowercase().contains(q) || it.description.lowercase().contains(q) || it.isbn.contains(q) } }
    }

    var showScanner by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var initialIsbnForDialog by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("📖 Doudy Librairie", fontWeight = FontWeight.Bold, color = Color.White) },
                actions = {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Exporter en JSON") }, onClick = { showMenu = false; exportJsonLauncher.launch("bibliotheque_doudy.json") })
                        DropdownMenuItem(text = { Text("Exporter en CSV") }, onClick = { showMenu = false; exportCsvLauncher.launch("bibliotheque_doudy.csv") })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Importer (CSV ou JSON)") }, onClick = { showMenu = false; importLauncher.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "*/*")) })
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = LibraryPrimary))
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(onClick = { initialIsbnForDialog = ""; showManualDialog = true }, containerColor = LibrarySecondary, contentColor = Color.White, modifier = Modifier.padding(bottom = 8.dp)) { Icon(Icons.Default.Edit, contentDescription = "Ajout manuel") }
                ExtendedFloatingActionButton(onClick = { showScanner = true }, containerColor = LibraryAccent, contentColor = Color.White, shape = RoundedCornerShape(16.dp), icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("Scanner un livre", fontWeight = FontWeight.Bold) })
            }
        }, containerColor = LibraryWarmBackground
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp), placeholder = { Text("Rechercher...") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Recherche", tint = LibraryPrimary) }, trailingIcon = { if (searchQuery.isNotEmpty()) { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Effacer", tint = Color.Gray) } } }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LibraryAccent, unfocusedBorderColor = LibrarySecondary.copy(alpha = 0.5f), focusedContainerColor = LibraryCardBg, unfocusedContainerColor = LibraryCardBg))
            TabRow(selectedTabIndex = selectedTab, containerColor = LibraryWarmBackground, contentColor = LibraryPrimary) { categories.forEachIndexed { index, title -> Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(text = title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp) }) } }
            Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading) { Box(Modifier.fillMaxSize(), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = LibraryAccent); Spacer(Modifier.height(12.dp)); Text("Recherche du livre...", color = LibraryPrimary) } } }
                else if (showScanner) {
                    CameraScannerView(onIsbnScanned = { rawIsbn ->
                        showScanner = false; val cleanIsbn = rawIsbn.filter { it.isDigit() }; isLoading = true
                        scope.launch { val book = fetchBookDetails(cleanIsbn); isLoading = false; if (book != null) { bookDao.insertBook(book); withContext(Dispatchers.Main) { Toast.makeText(context, "📚 Ajouté : ${book.title}", Toast.LENGTH_SHORT).show() } } else { withContext(Dispatchers.Main) { Toast.makeText(context, "Introuvable. Saisie manuelle.", Toast.LENGTH_LONG).show(); initialIsbnForDialog = cleanIsbn; showManualDialog = true } } }
                    }, onClose = { showScanner = false })
                } else {
                    BookList(books = filteredBooks, searchQuery = searchQuery, onBookClick = { book -> selectedBookForDetail = book }, onStatusChange = { book, newStatus -> scope.launch { bookDao.insertBook(book.copy(status = newStatus)) } }, onDelete = { book -> scope.launch { bookDao.deleteBook(book) } })
                }
                if (showManualDialog) { ManualAddDialog(initialIsbn = initialIsbnForDialog, onDismiss = { showManualDialog = false }, onAdd = { isbn, title, author, description, status -> showManualDialog = false; scope.launch { val finalIsbn = if (isbn.isBlank()) System.currentTimeMillis().toString() else isbn; val finalTitle = if (title.isBlank()) "Livre $finalIsbn" else title; val finalAuthor = if (author.isBlank()) "Auteur inconnu" else author; bookDao.insertBook(Book(isbn = finalIsbn, title = finalTitle, authors = finalAuthor, description = description, status = status, coverUrl = if(finalIsbn.length>10) "https://covers.openlibrary.org/b/isbn/$finalIsbn-L.jpg" else "")) } }) }
                selectedBookForDetail?.let { book -> BookDetailDialog(book = book, onDismiss = { selectedBookForDetail = null }, onSave = { updatedBook -> selectedBookForDetail = null; scope.launch { bookDao.insertBook(updatedBook) } }, onDelete = { selectedBookForDetail = null; scope.launch { bookDao.deleteBook(book) } }) }
            }
        }
    }
}

@Composable
fun BookList(books: List<Book>, searchQuery: String, onBookClick: (Book) -> Unit, onStatusChange: (Book, String) -> Unit, onDelete: (Book) -> Unit) {
    if (books.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if (searchQuery.isNotEmpty()) "🔍" else "📚", fontSize = 64.sp); Spacer(Modifier.height(16.dp)); Text(text = if (searchQuery.isNotEmpty()) "Aucun résultat pour \"$searchQuery\"" else "Aucun livre", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LibraryPrimary) } } }
    else { androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(books) { book -> BookCard(book = book, onClick = { onBookClick(book) }, onStatusChange = { newStatus -> onStatusChange(book, newStatus) }, onDelete = { onDelete(book) }) } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCard(book: Book, onClick: () -> Unit, onStatusChange: (String) -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val statuses = listOf("À lire", "En cours", "Lu")
    val (chipBg, chipText) = when (book.status) { "Lu" -> Color(0xFFE8F5E9) to Color(0xFF2E7D32); "En cours" -> Color(0xFFFFF3E0) to Color(0xFFE65100); else -> Color(0xFFE3F2FD) to Color(0xFF1565C0) }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(4.dp), modifier = Modifier.fillMaxWidth().clickable {
