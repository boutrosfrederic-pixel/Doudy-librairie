package com.doudy.librairie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

// ==========================================
// 1. PALETTE DE COULEURS PERSONNALISÉE
// ==========================================
object AppTheme {
    val BackgroundDark = Color(0xFF12131C)
    val SurfaceDark = Color(0xFF1E1F2C)
    val CardBackground = Color(0xFF2A2C3E)
    val CardBorder = Color(0xFF3A3D54)

    val PrimaryEmerald = Color(0xFF10B981)
    val AccentTeal = Color(0xFF06B6D4)
    val AccentPurple = Color(0xFF8B5CF6)
    val AccentRose = Color(0xFFF43F5E)
    val AccentAmber = Color(0xFFF59E0B)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextTertiary = Color(0xFF64748B)
}

// ==========================================
// 2. MODÈLE DE DONNÉES ENRICHI
// ==========================================
enum class ReadStatus(val label: String) {
    UNREAD("Non lu"),
    READING("En cours"),
    READ("Terminé"),
    ABANDONED("Abandonné")
}

data class Book(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isbn: String,
    val title: String,
    val authors: String,
    val series: String = "",
    val coverUrl: String = "",
    val publisher: String = "",
    val publishedDate: String = "",
    val description: String = "",
    val source: String = "Inconnu",
    val totalPages: Int = 300,
    val currentPage: Int = 0,
    val rating: Float = 0f,
    val status: ReadStatus = ReadStatus.UNREAD,
    val personalNotes: String = "",
    val isBorrowed: Boolean = false,
    val borrowerName: String = ""
)

// ==========================================
// 3. FONCTIONS UTILITAIRES DE SÉRIES
// ==========================================
fun extractSeriesFromTitle(title: String): String {
    if (title.isBlank()) return "Hors série"
    val clean = title.trim()

    val seriesPatterns = listOf(
        Regex("""(?i)^(.*?)\s*[\(\[\-,:\_]?\s*(?:Tome|T\.|T|Volume|Vol\.|Vol|Book|Saison|S)\s*\d+"""),
        Regex("""(?i)^(.*?)\s*[\(\[]\s*T\d+\s*[\)\]]"""),
        Regex("""(?i)^(.*?)\s*:\s*.*"""),
        Regex("""^(.*?)\s+#\d+""")
    )

    for (pattern in seriesPatterns) {
        val match = pattern.find(clean)
        if (match != null) {
            val candidate = match.groupValues[1]
                .replace(Regex("""[,\-:_#(\[\s]+$"""), "")
                .trim()
            if (candidate.length >= 2) return candidate
        }
    }
    return "Hors série"
}

fun extractVolumeNumber(title: String): Int {
    if (title.isBlank()) return 999
    val patterns = listOf(
        Regex("""(?i)(?:Tome|T\.|T|Volume|Vol\.|Vol|Book|Saison|#)\s*(\d+)"""),
        Regex("""(?i)T(\d+)\b"""),
        Regex("""\b(\d+)\b""")
    )
    for (pattern in patterns) {
        val match = pattern.find(title)
        if (match != null) {
            val num = match.groupValues[1].toIntOrNull()
            if (num != null) return num
        }
    }
    return 999
}

// ==========================================
// 4. API DE RECHERCHE
// ==========================================
object BookApiService {
    suspend fun searchBook(isbnOrTitle: String): Book? = withContext(Dispatchers.IO) {
        val cleanQuery = isbnOrTitle.trim()
        val isIsbn = cleanQuery.replace("-", "").all { it.isDigit() } && cleanQuery.length >= 9

        if (isIsbn) {
            val cleanIsbn = cleanQuery.replace("-", "")
            fetchFromOpenLibrary(cleanIsbn)
                ?: fetchFromGoogleBooks("isbn:$cleanIsbn")
                ?: fetchFromBnf(cleanIsbn)
        } else {
            fetchFromGoogleBooks(cleanQuery)
        }
    }

    private fun fetchFromOpenLibrary(isbn: String): Book? {
        return try {
            val urlStr = "https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data"
            val jsonStr = makeHttpRequest(urlStr) ?: return null
            val rootObj = JSONObject(jsonStr)
            val bookKey = "ISBN:$isbn"
            if (!rootObj.has(bookKey)) return null
            val bookObj = rootObj.getJSONObject(bookKey)

            val title = bookObj.optString("title", "Titre inconnu")
            val authorsArray = bookObj.optJSONArray("authors")
            val authorsList = mutableListOf<String>()
            if (authorsArray != null) {
                for (i in 0 until authorsArray.length()) {
                    authorsList.add(authorsArray.getJSONObject(i).optString("name"))
                }
            }
            val authors = if (authorsList.isNotEmpty()) authorsList.joinToString(", ") else "Auteur inconnu"
            val coverUrl = bookObj.optJSONObject("cover")?.optString("medium", "") ?: ""
            val publishers = bookObj.optJSONArray("publishers")
            val publisher = if (publishers != null && publishers.length() > 0) publishers.getJSONObject(0).optString("name") else ""
            val publishDate = bookObj.optString("publish_date", "")
            val pages = bookObj.optInt("number_of_pages", 300)

            Book(
                isbn = isbn,
                title = title,
                authors = authors,
                series = extractSeriesFromTitle(title),
                coverUrl = coverUrl,
                publisher = publisher,
                publishedDate = publishDate,
                source = "Open Library",
                totalPages = pages
            )
        } catch (e: Exception) { null }
    }

    private fun fetchFromGoogleBooks(query: String): Book? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val urlStr = "https://www.googleapis.com/books/v1/volumes?q=$encoded&maxResults=1"
            val jsonStr = makeHttpRequest(urlStr) ?: return null
            val rootObj = JSONObject(jsonStr)
            val items = rootObj.optJSONArray("items") ?: return null
            if (items.length() == 0) return null

            val volumeInfo = items.getJSONObject(0).getJSONObject("volumeInfo")
            val title = volumeInfo.optString("title", "Titre inconnu")

            val authorsArray = volumeInfo.optJSONArray("authors")
            val authorsList = mutableListOf<String>()
            if (authorsArray != null) {
                for (i in 0 until authorsArray.length()) {
                    authorsList.add(authorsArray.getString(i))
                }
            }
            val authors = if (authorsList.isNotEmpty()) authorsList.joinToString(", ") else "Auteur inconnu"

            val imageLinks = volumeInfo.optJSONObject("imageLinks")
            val rawCover = imageLinks?.optString("thumbnail", "") ?: ""
            val coverUrl = rawCover.replace("http://", "https://")

            val publisher = volumeInfo.optString("publisher", "")
            val publishedDate = volumeInfo.optString("publishedDate", "")
            val description = volumeInfo.optString("description", "")
            val pageCount = volumeInfo.optInt("pageCount", 300)

            val industryIds = volumeInfo.optJSONArray("industryIdentifiers")
            var isbn = ""
            if (industryIds != null) {
                for (i in 0 until industryIds.length()) {
                    val idObj = industryIds.getJSONObject(i)
                    if (idObj.optString("type") == "ISBN_13") {
                        isbn = idObj.optString("identifier")
                        break
                    }
                }
            }

            Book(
                isbn = isbn,
                title = title,
                authors = authors,
                series = extractSeriesFromTitle(title),
                coverUrl = coverUrl,
                publisher = publisher,
                publishedDate = publishedDate,
                description = description,
                source = "Google Books",
                totalPages = pageCount
            )
        } catch (e: Exception) { null }
    }

    private fun fetchFromBnf(isbn: String): Book? {
        return try {
            val urlStr = "https://catalogue.bnf.fr/api/SRU?operation=searchRetrieve&version=1.2&query=bib.isbn%20all%20%22$isbn%22&recordSchema=dublincore"
            val xmlStr = makeHttpRequest(urlStr) ?: return null

            val titleMatch = Regex("""<dc:title>(.*?)</dc:title>""").find(xmlStr)
            val title = titleMatch?.groupValues?.get(1)?.trim() ?: return null

            val creatorMatches = Regex("""<dc:creator>(.*?)</dc:creator>""").findAll(xmlStr)
            val authorsList = creatorMatches.map { it.groupValues[1].trim() }.toList()
            val authors = if (authorsList.isNotEmpty()) authorsList.joinToString(", ") else "Auteur inconnu"

            Book(
                isbn = isbn,
                title = title,
                authors = authors,
                series = extractSeriesFromTitle(title),
                source = "BnF (France)"
            )
        } catch (e: Exception) { null }
    }

    private fun makeHttpRequest(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android BookManager/2.0)")
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) { null } finally {
            conn?.disconnect()
        }
    }
}

// ==========================================
// 5. VIEWMODEL & ÉTATS DE L'APPLICATION
// ==========================================
sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val book: Book) : UiState()
    data class Error(val message: String) : UiState()
}

class BookViewModel : ViewModel() {
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        _books.value = listOf(
            Book(isbn = "9782253003854", title = "L'Attaque des Titans - Tome 1", authors = "Hajime Isayama", series = "L'Attaque des Titans", coverUrl = "https://covers.openlibrary.org/b/id/10523456-M.jpg", totalPages = 192, currentPage = 120, rating = 4.5f, status = ReadStatus.READING),
            Book(isbn = "9782253003861", title = "L'Attaque des Titans - Tome 10", authors = "Hajime Isayama", series = "L'Attaque des Titans", coverUrl = "https://covers.openlibrary.org/b/id/10523457-M.jpg", totalPages = 192, currentPage = 0, status = ReadStatus.UNREAD),
            Book(isbn = "9782253003878", title = "L'Attaque des Titans - Tome 2", authors = "Hajime Isayama", series = "L'Attaque des Titans", coverUrl = "https://covers.openlibrary.org/b/id/10523458-M.jpg", totalPages = 192, currentPage = 192, rating = 5.0f, status = ReadStatus.READ),
            Book(isbn = "9782070360024", title = "L'Étranger", authors = "Albert Camus", series = "Hors série", totalPages = 184, currentPage = 184, rating = 4.0f, status = ReadStatus.READ)
        )
    }

    fun searchAndAddBook(isbnOrQuery: String) {
        if (isbnOrQuery.isBlank()) return
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            val foundBook = BookApiService.searchBook(isbnOrQuery)
            if (foundBook != null) {
                _books.value = _books.value + foundBook
                _uiState.value = UiState.Success(foundBook)
            } else {
                _uiState.value = UiState.Error("Aucun livre trouvé pour : $isbnOrQuery")
            }
        }
    }

    fun updateBook(updatedBook: Book) {
        _books.value = _books.value.map { if (it.id == updatedBook.id) updatedBook else it }
    }

    fun removeBook(book: Book) {
        _books.value = _books.value.filter { it.id != book.id }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }

    fun exportToJson(context: Context) {
        try {
            val jsonArray = JSONArray()
            _books.value.forEach { book ->
                val obj = JSONObject().apply {
                    put("isbn", book.isbn)
                    put("title", book.title)
                    put("authors", book.authors)
                    put("series", book.series)
                    put("coverUrl", book.coverUrl)
                    put("publisher", book.publisher)
                }
                jsonArray.put(obj)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, jsonArray.toString(2))
            }
            context.startActivity(Intent.createChooser(intent, "Exporter la bibliothèque"))
        } catch (e: Exception) {
            Toast.makeText(context, "Erreur d'exportation", Toast.LENGTH_SHORT).show()
        }
    }
}

// ==========================================
// 6. ACTIVITÉ PRINCIPALE
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = AppTheme.BackgroundDark,
                    surface = AppTheme.SurfaceDark,
                    primary = AppTheme.PrimaryEmerald,
                    onPrimary = Color.Black,
                    onBackground = AppTheme.TextPrimary,
                    onSurface = AppTheme.TextPrimary
                )
            ) {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BookViewModel = viewModel()) {
    val context = LocalContext.current
    val books by viewModel.books.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var isBatchMode by remember { mutableStateOf(false) }
    var showAddBottomSheet by remember { mutableStateOf(false) }
    var selectedBookForDetail by remember { mutableStateOf<Book?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedNavIndex by remember { mutableIntStateOf(0) }

    val tabs = listOf("Livres", "Étagères", "Tags", "Souhaits")

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) showScanner = true
        else Toast.makeText(context, "Permission caméra requise", Toast.LENGTH_SHORT).show()
    }

    val groupedBooks = remember(books, searchQuery) {
        val filtered = if (searchQuery.isBlank()) books
        else books.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.authors.contains(searchQuery, ignoreCase = true) ||
            it.series.contains(searchQuery, ignoreCase = true) ||
            it.isbn.contains(searchQuery)
        }

        filtered.groupBy { book ->
            val s = if (book.series.isNotBlank() && book.series != "Hors série") {
                book.series
            } else {
                extractSeriesFromTitle(book.title)
            }
            if (s.isBlank()) "Hors série" else s
        }.mapValues { (_, seriesList) ->
            seriesList.sortedWith(
                compareBy<Book> { extractVolumeNumber(it.title) }
                    .thenBy { it.title }
            )
        }.toSortedMap { a, b ->
            when {
                a == "Hors série" -> 1
                b == "Hors série" -> -1
                else -> a.compareTo(b, ignoreCase = true)
            }
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
                                text = "(${books.size} livres)",
                                fontSize = 14.sp,
                                color = AppTheme.TextSecondary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.BackgroundDark),
                    actions = {
                        IconButton(onClick = { viewModel.exportToJson(context) }) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = "Exporter", tint = AppTheme.PrimaryEmerald)
                        }
                    }
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = AppTheme.BackgroundDark,
                    contentColor = AppTheme.PrimaryEmerald,
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 14.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == index) AppTheme.PrimaryEmerald else AppTheme.TextSecondary
                                )
                            }
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = AppTheme.SurfaceDark,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedNavIndex == 0,
                    onClick = { selectedNavIndex = 0 },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = "Bibliothèque") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppTheme.PrimaryEmerald,
                        indicatorColor = AppTheme.CardBackground
                    )
                )
                NavigationBarItem(
                    selected = selectedNavIndex == 1,
                    onClick = { selectedNavIndex = 1 },
                    icon = { Icon(Icons.Default.Explore, contentDescription = "Découvrir") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppTheme.PrimaryEmerald,
                        indicatorColor = AppTheme.CardBackground
                    )
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { showAddBottomSheet = true },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(AppTheme.PrimaryEmerald),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Ajouter", tint = Color.Black)
                        }
                    }
                )
                NavigationBarItem(
                    selected = selectedNavIndex == 2,
                    onClick = { selectedNavIndex = 2 },
                    icon = { Icon(Icons.Default.People, contentDescription = "Communauté") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppTheme.PrimaryEmerald,
                        indicatorColor = AppTheme.CardBackground
                    )
                )
                NavigationBarItem(
                    selected = selectedNavIndex == 3,
                    onClick = { selectedNavIndex = 3 },
                    icon = { Icon(Icons.Default.MoreHoriz, contentDescription = "Menu") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppTheme.PrimaryEmerald,
                        indicatorColor = AppTheme.CardBackground
                    )
                )
            }
        },
        containerColor = AppTheme.BackgroundDark
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp)
        ) {
            AnimatedVisibility(visible = uiState !is UiState.Idle) {
                when (uiState) {
                    is UiState.Loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = AppTheme.PrimaryEmerald, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Recherche en cours...", color = AppTheme.TextSecondary, fontSize = 13.sp)
                        }
                    }
                    is UiState.Error -> {
                        val msg = (uiState as UiState.Error).message
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AppTheme.AccentRose.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(msg, color = AppTheme.TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                    is UiState.Success -> {
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(2000)
                            viewModel.resetState()
                        }
                        Text("✔ Livre ajouté !", color = AppTheme.PrimaryEmerald, modifier = Modifier.padding(4.dp), fontSize = 12.sp)
                    }
                    else -> {}
                }
            }

            // Barre de Filtre & Recherche
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AppTheme.TextSecondary, modifier = Modifier.size(18.dp)) },
                    placeholder = { Text("Recherche...", color = AppTheme.TextTertiary, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.PrimaryEmerald,
                        unfocusedBorderColor = AppTheme.CardBorder,
                        focusedTextColor = AppTheme.TextPrimary
                    ),
                    singleLine = true
                )

                Surface(
                    color = AppTheme.SurfaceDark,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.CardBorder),
                    modifier = Modifier
                        .height(46.dp)
                        .clickable { }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FilterList, contentDescription = null, tint = AppTheme.TextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Filtre", color = AppTheme.TextSecondary, fontSize = 13.sp)
                    }
                }
            }

            // Grille de Livres (3 colonnes)
            if (groupedBooks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.AutoStories, contentDescription = null, modifier = Modifier.size(56.dp), tint = AppTheme.TextTertiary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Aucun livre dans la collection", color = AppTheme.TextSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)
                ) {
                    groupedBooks.forEach { (seriesName, seriesBooks) ->
                        item(span = { GridItemSpan(3) }) {
                            SeriesGridHeader(seriesName = seriesName, count = seriesBooks.size)
                        }

                        items(seriesBooks, key = { it.id }) { book ->
                            BookGridItem(
                                book = book,
                                onClick = { selectedBookForDetail = book }
                            )
                        }
                    }
                }
            }
        }
    }

    // Bottom Sheet de Fiche Détaillée
    if (selectedBookForDetail != null) {
        BookDetailSheet(
            book = selectedBookForDetail!!,
            onDismiss = { selectedBookForDetail = null },
            onUpdate = { updated ->
                viewModel.updateBook(updated)
                selectedBookForDetail = updated
            },
            onDelete = {
                viewModel.removeBook(selectedBookForDetail!!)
                selectedBookForDetail = null
            }
        )
    }

    // Modal Bottom Sheet (Menu d'Ajout)
    if (showAddBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddBottomSheet = false },
            containerColor = AppTheme.SurfaceDark,
            scrimColor = Color.Black.copy(alpha = 0.6f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, top = 8.dp)
            ) {
                AddOptionItem(
                    icon = Icons.Default.QrCodeScanner,
                    title = "Scanner le livre ISBN",
                    onClick = {
                        showAddBottomSheet = false
                        isBatchMode = false
                        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) showScanner = true
                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                )
                AddOptionItem(
                    icon = Icons.Default.DocumentScanner,
                    title = "Scanner plusieurs livres",
                    onClick = {
                        showAddBottomSheet = false
                        isBatchMode = true
                        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) showScanner = true
                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                )
                AddOptionItem(
                    icon = Icons.Default.Search,
                    title = "Rechercher de nouveaux livres",
                    onClick = { showAddBottomSheet = false }
                )
                AddOptionItem(
                    icon = Icons.Default.Edit,
                    title = "Ajouter un nouveau livre manuellement",
                    onClick = { showAddBottomSheet = false }
                )
                AddOptionItem(
                    icon = Icons.Default.EmojiEvents,
                    title = "Ajouter un nouvel objectif de lecture",
                    onClick = { showAddBottomSheet = false }
                )
            }
        }
    }

    // Modal Scanner Caméra
    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraBarcodeScanner(
                        onBarcodeScanned = { barcode ->
                            if (!isBatchMode) showScanner = false
                            viewModel.searchAndAddBook(barcode)
                            Toast.makeText(context, "Scan : $barcode", Toast.LENGTH_SHORT).show()
                        }
                    )
                    IconButton(
                        onClick = { showScanner = false },
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
}

// ==========================================
// 7. COMPOSANT FICHE DÉTAILLÉE DU LIVRE
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailSheet(
    book: Book,
    onDismiss: () -> Unit,
    onUpdate: (Book) -> Unit,
    onDelete: () -> Unit
) {
    var detailTab by remember { mutableIntStateOf(0) } // 0: Notes, 1: Info
    val volumeNum = remember(book.title) { extractVolumeNumber(book.title) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.BackgroundDark,
        scrimColor = Color.Black.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // En-tête : Couverture et Infos Principales
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(160.dp)
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
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = AppTheme.TextTertiary,
                            modifier = Modifier.size(40.dp).align(Alignment.Center)
                        )
                    }

                    if (volumeNum != 999) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(AppTheme.AccentPurple, RoundedCornerShape(topEnd = 6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("$volumeNum", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = book.authors,
                        fontSize = 14.sp,
                        color = AppTheme.TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Rating étoiles
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(5) { index ->
                            val isStarFilled = index < book.rating.toInt()
                            Icon(
                                imageVector = if (isStarFilled) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = null,
                                tint = if (isStarFilled) AppTheme.AccentAmber else AppTheme.TextTertiary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { onUpdate(book.copy(rating = (index + 1).toFloat())) }
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (book.rating > 0) "${book.rating}" else "Noter",
                            fontSize = 12.sp,
                            color = AppTheme.TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Badge de Série
                    Surface(
                        color = AppTheme.SurfaceDark,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.CardBorder)
                    ) {
                        Text(
                            text = book.series.ifBlank { "Hors série" },
                            fontSize = 11.sp,
                            color = AppTheme.AccentTeal,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Onglets de la Fiche Détaillée
            TabRow(
                selectedTabIndex = detailTab,
                containerColor = AppTheme.SurfaceDark,
                contentColor = AppTheme.PrimaryEmerald,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
            ) {
                Tab(
                    selected = detailTab == 0,
                    onClick = { detailTab = 0 },
                    text = { Text("Notes & Suivi", fontSize = 13.sp) }
                )
                Tab(
                    selected = detailTab == 1,
                    onClick = { detailTab = 1 },
                    text = { Text("Informations", fontSize = 13.sp) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (detailTab == 0) {
                // CONTENU ONGLET 1 : SUIVI DE LECTURE & PROGRESSION
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppTheme.SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Progression", fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary)
                            Text(
                                "${book.currentPage} / ${book.totalPages} pages",
                                fontSize = 13.sp,
                                color = AppTheme.PrimaryEmerald,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = book.currentPage.toFloat(),
                            onValueChange = { newPage ->
                                val newStatus = when {
                                    newPage.toInt() == 0 -> ReadStatus.UNREAD
                                    newPage.toInt() >= book.totalPages -> ReadStatus.READ
                                    else -> ReadStatus.READING
                                }
                                onUpdate(book.copy(currentPage = newPage.toInt(), status = newStatus))
                            },
                            valueRange = 0f..book.totalPages.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = AppTheme.PrimaryEmerald,
                                activeTrackColor = AppTheme.PrimaryEmerald,
                                inactiveTrackColor = AppTheme.CardBorder
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Chips de Statut
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReadStatus.values().forEach { status ->
                                FilterChip(
                                    selected = book.status == status,
                                    onClick = {
                                        val page = if (status == ReadStatus.READ) book.totalPages else book.currentPage
                                        onUpdate(book.copy(status = status, currentPage = page))
                                    },
                                    label = { Text(status.label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AppTheme.PrimaryEmerald,
                                        selectedLabelColor = Color.Black
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Zone de Notes personnelles
                OutlinedTextField(
                    value = book.personalNotes,
                    onValueChange = { onUpdate(book.copy(personalNotes = it)) },
                    placeholder = { Text("Ajouter des remarques ou des citations...", color = AppTheme.TextTertiary, fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.PrimaryEmerald,
                        unfocusedBorderColor = AppTheme.CardBorder,
                        focusedTextColor = AppTheme.TextPrimary
                    )
                )
            } else {
                // CONTENU ONGLET 2 : INFORMATIONS COMPLÈTES
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppTheme.SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        InfoRow(label = "ISBN", value = book.isbn.ifBlank { "N/A" })
                        InfoRow(label = "Éditeur", value = book.publisher.ifBlank { "Inconnu" })
                        InfoRow(label = "Publication", value = book.publishedDate.ifBlank { "Inconnue" })
                        InfoRow(label = "Source", value = book.source)
                        InfoRow(label = "Total Pages", value = "${book.totalPages}")
                        InfoRow(
                            label = "Statut Prêt",
                            value = if (book.isBorrowed) "Prêté à ${book.borrowerName}" else "Non prêté"
                        )
                    }
                }

                if (book.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Résumé", fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(book.description, fontSize = 12.sp, color = AppTheme.TextSecondary, textAlign = TextAlign.Justify)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Actions : Supprimer
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentRose),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentRose),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Supprimer ce livre")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppTheme.TextTertiary, fontSize = 13.sp)
        Text(value, color = AppTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ==========================================
// 8. AUTRES COMPOSANTS (OPTIONS & CAMERA)
// ==========================================
@Composable
fun AddOptionItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTheme.TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.TextPrimary
        )
    }
}

@Composable
fun CameraBarcodeScanner(onBarcodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastScannedTime by remember { mutableLongStateOf(0L) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val barcodeScanner = BarcodeScanning.getClient()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    processImageProxy(barcodeScanner, imageProxy) { barcode ->
                        val now = System.currentTimeMillis()
                        if (now - lastScannedTime > 2000) {
                            lastScannedTime = now
                            onBarcodeScanned(barcode)
                        }
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onSuccess: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue
                    if (rawValue != null && (barcode.format == Barcode.FORMAT_EAN_13 || barcode.format == Barcode.FORMAT_EAN_8)) {
                        onSuccess(rawValue)
                        break
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

@Composable
fun SeriesGridHeader(seriesName: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (seriesName == "Hors série") AppTheme.TextTertiary else AppTheme.PrimaryEmerald)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = seriesName,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = AppTheme.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count vol.",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.AccentTeal
        )
    }
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
