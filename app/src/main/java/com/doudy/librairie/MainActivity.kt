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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    val AccentPurple = Color(0xFF8B5CF6) // Couleur du badge de volume
    val AccentRose = Color(0xFFF43F5E)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextTertiary = Color(0xFF64748B)
}

// ==========================================
// 2. MODÈLE DE DONNÉES
// ==========================================
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
    val source: String = "Inconnu"
)

// ==========================================
// 3. FONCTIONS UTILITAIRES DE SÉRIES & STRIP
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
// 4. API DE RECHERCHE DÉCOUPLÉE
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

            Book(
                isbn = isbn,
                title = title,
                authors = authors,
                series = extractSeriesFromTitle(title),
                coverUrl = coverUrl,
                publisher = publisher,
                publishedDate = publishDate,
                source = "Open Library"
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
                source = "Google Books"
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
            Book(isbn = "9782253003854", title = "L'Attaque des Titans - Tome 1", authors = "Hajime Isayama", series = "L'Attaque des Titans", coverUrl = "https://covers.openlibrary.org/b/id/10523456-M.jpg"),
            Book(isbn = "9782253003861", title = "L'Attaque des Titans - Tome 10", authors = "Hajime Isayama", series = "L'Attaque des Titans", coverUrl = "https://covers.openlibrary.org/b/id/10523457-M.jpg"),
            Book(isbn = "9782253003878", title = "L'Attaque des Titans - Tome 2", authors = "Hajime Isayama", series = "L'Attaque des Titans", coverUrl = "https://covers.openlibrary.org/b/id/10523458-M.jpg"),
            Book(isbn = "9782070360024", title = "L'Étranger", authors = "Albert Camus", series = "Hors série")
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
// 6. ACTIVITÉ PRINCIPALE ET ÉCRAN
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
    var inputQuery by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedNavIndex by remember { mutableIntStateOf(0) }

    val tabs = listOf("Livres", "Étagères", "Tags", "Souhaits")
    val keyboardController = LocalSoftwareKeyboardController.current

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) showScanner = true
        else Toast.makeText(context, "Permission caméra requise pour scanner", Toast.LENGTH_SHORT).show()
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

                // Onglets Supérieurs (Livres, Étagères...)
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
                    onClick = {
                        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                            showScanner = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
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
            // Barre d'Ajout Manuelle
            Card(
                colors = CardDefaults.cardColors(containerColor = AppTheme.SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                            showScanner = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan", tint = AppTheme.PrimaryEmerald)
                    }

                    OutlinedTextField(
                        value = inputQuery,
                        onValueChange = { inputQuery = it },
                        placeholder = { Text("ISBN ou Titre...", color = AppTheme.TextTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = AppTheme.TextPrimary
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            viewModel.searchAndAddBook(inputQuery)
                            inputQuery = ""
                            keyboardController?.hide()
                        })
                    )

                    Button(
                        onClick = {
                            viewModel.searchAndAddBook(inputQuery)
                            inputQuery = ""
                            keyboardController?.hide()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.PrimaryEmerald),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Ajouter", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

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

            // Boutons de Filtre et Recherche
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
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
                    contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
                ) {
                    groupedBooks.forEach { (seriesName, seriesBooks) ->
                        // En-tête de série occupant les 3 colonnes
                        item(span = { GridItemSpan(3) }) {
                            SeriesGridHeader(seriesName = seriesName, count = seriesBooks.size)
                        }

                        // Éléments de la grille
                        items(seriesBooks, key = { it.id }) { book ->
                            BookGridItem(book = book, onDelete = { viewModel.removeBook(book) })
                        }
                    }
                }
            }
        }
    }

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
                            showScanner = false
                            viewModel.searchAndAddBook(barcode)
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
// 7. MODULE SCANNER CAMERA ML KIT
// ==========================================
@Composable
fun CameraBarcodeScanner(onBarcodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isScanned by remember { mutableStateOf(false) }

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
                        if (!isScanned) {
                            isScanned = true
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

// ==========================================
// 8. COMPOSANTS DE LA GRILLE & VIGNETTES
// ==========================================
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
fun BookGridItem(book: Book, onDelete: () -> Unit) {
    val volumeNum = remember(book.title) { extractVolumeNumber(book.title) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* Ouvre la fiche détail plus tard */ }
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

            // Badge du numéro de volume (Superposé en bas à gauche)
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

        // Titre sous la couverture
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
