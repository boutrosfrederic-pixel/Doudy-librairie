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
import androidx.compose.material.icons.filled.*
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
data class Book(@PrimaryKey val isbn: String, val title: String, val authors: String, val coverUrl: String = "", val status: String = "À lire", val description: String = "")

@Dao
interface BookDao {
    @Query("SELECT * FROM books") fun getAllBooks(): Flow<List<Book>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBook(book: Book)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBooks(books: List<Book>)
    @Delete suspend fun deleteBook(book: Book)
}

@Database(entities = [Book::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "doudy_librairie_db").fallbackToDestructiveMigration().build()
                INSTANCE = instance; instance
            }
        }
    }
}

private val LibraryWarmBackground = Color(0xFFFAF6F0)
private val LibraryPrimary = Color(0xFF6B3E2E)
private val LibrarySecondary = Color(0xFF8C5A47)
private val LibraryAccent = Color(0xFFD97736)
private val LibraryCardBg = Color(0xFFFFFFFF)

@Composable fun DoudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = LibraryPrimary, secondary = LibrarySecondary, tertiary = LibraryAccent, background = LibraryWarmBackground, surface = LibraryCardBg), content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        setContent { DoudyTheme { MainScreen(db.bookDao()) } }
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
    var showScanner by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var initialIsbnForDialog by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { exportDataToJson(context, booksState, it) } }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { exportDataToCsv(context, booksState, it) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { scope.launch { val c = importDataFromUri(context, bookDao, it); Toast.makeText(context, "$c importés", Toast.LENGTH_SHORT).show() } } }

    val countAll = booksState.size
    val countALire = booksState.count { it.status == "À lire" }
    val countEnCours = booksState.count { it.status == "En cours" }
    val countLu = booksState.count { it.status == "Lu" }
    val categories = listOf("Tous ($countAll)", "À lire ($countALire)", "En cours ($countEnCours)", "Lu ($countLu)")

    val filteredBooks = remember(booksState, selectedTab, searchQuery) {
        val tab = when (selectedTab) { 1 -> booksState.filter { it.status == "À lire" }; 2 -> booksState.filter { it.status == "En cours" }; 3 -> booksState.filter { it.status == "Lu" }; else -> booksState }
        if (searchQuery.isBlank()) tab else { val q = searchQuery.lowercase(); tab.filter { it.title.lowercase().contains(q) || it.authors.lowercase().contains(q) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Doudy Librairie", fontWeight = FontWeight.Bold, color = Color.White) },
                actions = {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Exporter JSON") }, onClick = { showMenu = false; exportJsonLauncher.launch("doudy.json") })
                        DropdownMenuItem(text = { Text("Exporter CSV") }, onClick = { showMenu = false; exportCsvLauncher.launch("doudy.csv") })
                        DropdownMenuItem(text = { Text("Importer") }, onClick = { showMenu = false; importLauncher.launch(arrayOf("*/*")) })
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = LibraryPrimary))
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(onClick = { initialIsbnForDialog = ""; showManualDialog = true }, containerColor = LibrarySecondary, contentColor = Color.White, modifier = Modifier.padding(bottom = 8.dp)) { Icon(Icons.Default.Edit, contentDescription = "Manuel") }
                ExtendedFloatingActionButton(onClick = { showScanner = true }, containerColor = LibraryAccent, contentColor = Color.White, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Scanner un livre", fontWeight = FontWeight.Bold) })
            }
        }, containerColor = LibraryWarmBackground
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Rechercher...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp))
            TabRow(selectedTabIndex = selectedTab, containerColor = LibraryWarmBackground, contentColor = LibraryPrimary) { categories.forEachIndexed { i, t -> Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(t, fontSize = 12.sp) }) } }
            Box(Modifier.fillMaxSize()) {
                if (isLoading) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = LibraryAccent) } }
                else if (showScanner) {
                    CameraScannerView(onIsbnScanned = { raw ->
                        showScanner = false; val clean = raw.filter { it.isDigit() }; isLoading = true
                        scope.launch {
                            val book = fetchBookDetails(clean); isLoading = false
                            if (book != null) { bookDao.insertBook(book) }
                            else { initialIsbnForDialog = clean; showManualDialog = true }
                        }
                    }, onClose = { showScanner = false })
                } else {
                    BookList(books = filteredBooks, searchQuery = searchQuery, onBookClick = { selectedBookForDetail = it }, onStatusChange = { b, s -> scope.launch { bookDao.insertBook(b.copy(status = s)) } }, onDelete = { b -> scope.launch { bookDao.deleteBook(b) } })
                }
                if (showManualDialog) {
                    ManualAddDialog(initialIsbn = initialIsbnForDialog, onDismiss = { showManualDialog = false }, onAdd = { isbn, title, author, desc, status ->
                        showManualDialog = false
                        scope.launch {
                            val fIsbn = if (isbn.isBlank()) System.currentTimeMillis().toString() else isbn
                            val fTitle = if (title.isBlank()) "Livre $fIsbn" else title
                            val fAuthor = if (author.isBlank()) "Inconnu" else author
                            val fCover = "https://covers.openlibrary.org/b/isbn/$fIsbn-M.jpg"
                            bookDao.insertBook(Book(fIsbn, fTitle, fAuthor, fCover, status, desc))
                        }
                    })
                }
                selectedBookForDetail?.let { b -> BookDetailDialog(book = b, onDismiss = { selectedBookForDetail = null }, onSave = { u -> selectedBookForDetail = null; scope.launch { bookDao.insertBook(u) } }, onDelete = { selectedBookForDetail = null; scope.launch { bookDao.deleteBook(b) } }) }
            }
        }
    }
}

@Composable
fun BookList(books: List<Book>, searchQuery: String, onBookClick: (Book) -> Unit, onStatusChange: (Book, String) -> Unit, onDelete: (Book) -> Unit) {
    if (books.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Aucun livre") } }
    else { LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(books) { b -> BookCard(book = b, onClick = { onBookClick(b) }, onStatusChange = { s -> onStatusChange(b, s) }, onDelete = { onDelete(b) }) } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCard(book: Book, onClick: () -> Unit, onStatusChange: (String) -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(4.dp), modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = book.coverUrl, contentDescription = null, modifier = Modifier.size(70.dp, 105.dp).background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.Bold, color = LibraryPrimary, maxLines = 2)
                Text(book.authors, style = MaterialTheme.typography.bodySmall, color = LibrarySecondary)
                Spacer(Modifier.height(8.dp))
                Box {
                    AssistChip(onClick = { expanded = true }, label = { Text(book.status, fontSize = 11.sp) })
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("À lire", "En cours", "Lu").forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { onStatusChange(s); expanded = false }) }
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailDialog(book: Book, onDismiss: () -> Unit, onSave: (Book) -> Unit, onDelete: () -> Unit) {
    var title by remember { mutableStateOf(book.title) }
    var authors by remember { mutableStateOf(book.authors) }
    var desc by remember { mutableStateOf(book.description) }
    var status by remember { mutableStateOf(book.status) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Fiche livre") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (book.coverUrl.isNotEmpty()) { AsyncImage(model = book.coverUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Crop) }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = authors, onValueChange = { authors = it }, label = { Text("Auteur") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Résumé") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("À lire", "En cours", "Lu").forEach { s -> FilterChip(selected = status == s, onClick = { status = s }, label = { Text(s) }) } }
            }
        },
        confirmButton = { Button(onClick = { onSave(book.copy(title = title, authors = authors, description = desc, status = status)) }) { Text("Sauver") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("F
