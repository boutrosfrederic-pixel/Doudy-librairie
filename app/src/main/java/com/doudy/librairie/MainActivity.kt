package com.doudy.librairie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import androidx.core.content.ContextCompat
import androidx.room.*
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

private const val GOOGLE_BOOKS_API_KEY = ""

// ==========================================
// 1. BASE DE DONNÉES ROOM
// ==========================================

@Entity
data class Book(
    @PrimaryKey val isbn: String,
    val title: String,
    val authors: String,
    val coverUrl: String = "",
    val description: String = "",
    val status: String = "À lire",
    val series: String = ""
)

@Dao
interface BookDao {
    @Query("SELECT * FROM Book ORDER BY title ASC")
    fun getAllBooks(): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<Book>)

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)
}

@Database(entities = [Book::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

// ==========================================
// 2. ACTIVITÉ PRINCIPALE ET THÈME
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "doudy.db")
            .fallbackToDestructiveMigration()
            .build()

        setContent {
            CustomAppTheme {
                MainScreen(db.bookDao())
            }
        }
    }
}

@Composable
fun CustomAppTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF2B2D42),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEDF2F4),
        onPrimaryContainer = Color(0xFF2B2D42),
        secondary = Color(0xFFD90429),
        surface = Color(0xFFFAFAFA),
        surfaceVariant = Color(0xFFF0F2F5),
        onSurface = Color(0xFF1A1A1A),
        onSurfaceVariant = Color(0xFF5A5A5A)
    )

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

// ==========================================
// 3. ÉCRAN PRINCIPAL
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(dao: BookDao) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    val books by dao.getAllBooks().collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var showScan by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val readCount = remember(books) { books.count { it.status == "Lu" } }
    val unreadCount = remember(books) { books.count { it.status == "À lire" } }

    val groupedBooks = remember(books, searchQuery) {
        val filtered = if (searchQuery.isBlank()) books
        else books.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.authors.contains(searchQuery, ignoreCase = true) ||
            it.series.contains(searchQuery, ignoreCase = true) ||
            it.isbn.contains(searchQuery)
        }

        filtered.groupBy { 
            val s = if (it.series.isNotBlank() && it.series != "Hors série") it.series else extractSeriesFromTitle(it.title)
            if (s.isBlank()) "Hors série" else s
        }.mapValues { (_, seriesList) ->
            seriesList.sortedBy { extractVolumeNumber(it.title) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val count = importBooksFromCsv(context, it, dao)
                Toast.makeText(context, "$count livre(s) importé(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val success = exportBooksToCsv(context, it, books)
                Toast.makeText(context, if (success) "Exportation réussie" else "Erreur d'exportation", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            "Ma Bibliothèque",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 26.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Doudy Collection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Importer un CSV", fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    importLauncher.launch("*/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter ma liste", fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    if (books.isNotEmpty()) exportLauncher.launch("bibliotheque.csv")
                                    else Toast.makeText(context, "Bibliothèque vide", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Scanner", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        showScan = true
                    } else {
                        activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                elevation = FloatingActionButtonDefaults.elevation(8.dp)
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {

                // Cartes de statistiques rapide
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard("Total", books.size.toString(), Icons.Outlined.Book, Modifier.weight(1f), Color(0xFF4A90E2))
                    StatCard("Lus", readCount.toString(), Icons.Outlined.CheckCircle, Modifier.weight(1f), Color(0xFF2E7D32))
                    StatCard("À lire", unreadCount.toString(), Icons.Outlined.HourglassEmpty, Modifier.weight(1f), Color(0xFFE65100))
                }

                // Barre de recherche
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Rechercher un titre, auteur, série...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Color.Gray) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Effacer")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )

                if (groupedBooks.isEmpty()) {
                    EmptyStateView(isSearching = searchQuery.isNotEmpty())
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        groupedBooks.forEach { (seriesName, seriesBooks) ->
                            item(key = seriesName) {
                                ModernSeriesSection(
                                    seriesName = seriesName,
                                    books = seriesBooks,
                                    onDeleteBook = { book ->
                                        scope.launch(Dispatchers.IO) { dao.deleteBook(book) }
                                    },
                                    onToggleStatus = { book ->
                                        scope.launch(Dispatchers.IO) {
                                            val newStatus = if (book.status == "Lu") "À lire" else "Lu"
                                            dao.updateBook(book.copy(status = newStatus))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (showScan) {
                CameraView(
                    onScanned = { isbn ->
                        val clean = isbn.filter { it.isDigit() || it == 'X' || it == 'x' }
                        if (clean.length >= 10) {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val book = fetchBookInfo(clean)
                                        dao.insertBook(book)
                                        book
                                    }
                                }.onSuccess { book ->
                                    Toast.makeText(context, "Ajouté : ${book.title}", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(context, "Erreur lors de l'ajout", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onClose = { showScan = false }
                )
            }
        }
    }
}

// ==========================================
// 4. COMPOSANTS DASHBOARD & D'AFFICHAGE
// ==========================================

@Composable
fun StatCard(label: String, count: String, icon: ImageVector, modifier: Modifier = Modifier, color: Color) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(count, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun EmptyStateView(isSearching: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = if (isSearching) Icons.Filled.SearchOff else Icons.Outlined.Book,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isSearching) "Aucun résultat trouvé" else "Votre bibliothèque est vide",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isSearching) "Essayez avec d'autres mots-clés." else "Scannez votre premier livre pour commencer votre collection.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ModernSeriesSection(
    seriesName: String,
    books: List<Book>,
    onDeleteBook: (Book) -> Unit,
    onToggleStatus: (Book) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val isStandalone = seriesName == "Hors série" || seriesName.isBlank()

    Column(Modifier.fillMaxWidth()) {
        if (!isStandalone) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = seriesName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${books.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.Gray
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        AnimatedVisibility(
            visible = expanded || isStandalone,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                books.forEach { book ->
                    ModernBookCard(
                        book = book,
                        onDelete = { onDeleteBook(book) },
                        onToggleStatus = { onToggleStatus(book) }
                    )
                }
            }
        }
    }
}

@Composable
fun ModernBookCard(
    book: Book,
    onDelete: () -> Unit,
    onToggleStatus: () -> Unit
) {
    var isImageError by remember { mutableStateOf(false) }
    var showBookMenu by remember { mutableStateOf(false) }
    val isFallbackCover = book.coverUrl.isBlank() || isImageError || book.coverUrl.contains("blank")

    val isRead = book.status == "Lu"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Couverture du livre
            Box(
                modifier = Modifier
                    .size(68.dp, 100.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .shadow(4.dp, RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF3A3D52), Color(0xFF1E2029))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!isFallbackCover) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onError = { isImageError = true }
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(6.dp)
                    ) {
                        Text(
                            text = book.title.take(1).uppercase(),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 24.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 8.sp,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            // Infos du livre
            Column(Modifier.weight(1f)) {
                // Badge Statut
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isRead) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    modifier = Modifier.clickable { onToggleStatus() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isRead) Color(0xFF2E7D32) else Color(0xFFE65100))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = book.status,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isRead) Color(0xFF2E7D32) else Color(0xFFE65100)
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = book.authors,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "ISBN ${book.isbn}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray,
                    fontSize = 10.sp
                )
            }

            // Options sur le livre
            Box {
                IconButton(onClick = { showBookMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Options livre",
                        tint = Color.Gray
                    )
                }

                DropdownMenu(
                    expanded = showBookMenu,
                    onDismissRequest = { showBookMenu = false },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isRead) "Marquer comme 'À lire'" else "Marquer comme 'Lu'") },
                        leadingIcon = {
                            Icon(
                                if (isRead) Icons.Outlined.HourglassEmpty else Icons.Outlined.CheckCircle,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showBookMenu = false
                            onToggleStatus()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Supprimer", color = MaterialTheme.colorScheme.secondary) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        },
                        onClick = {
                            showBookMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 5. PARSER, SÉRIES ET IMPORT/EXPORT CSV
// ==========================================

fun extractSeriesFromTitle(title: String): String {
    val cleanTitle = title.replace(Regex("""(?i)^Livre\s+\d+"""), "").trim()
    if (cleanTitle.isBlank()) return "Hors série"

    val patterns = listOf(
        Regex("""(?i)^(.*?)\s*[:\-_,]\s*(?:Volume|Vol|Tome|T)\s*\d+"""),
        Regex("""(?i)^(.*?)\s+Tome\s+\d+"""),
        Regex("""(?i)^(.*?)\s*[:\-_,]\s*.*"""),
        Regex("""(?i)^(.*?)\s+\d+$""")
    )
    for (pattern in patterns) {
        val match = pattern.find(cleanTitle)
        if (match != null) {
            val candidate = match.groupValues[1].trim()
            if (candidate.length > 2) return candidate
        }
    }
    return "Hors série"
}

fun extractVolumeNumber(title: String): Int {
    val match = Regex("""(?i)(?:Volume|Vol|Tome|T)\s*(\d+)""").find(title)
        ?: Regex("""\b(\d+)\b""").find(title)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 999
}

suspend fun importBooksFromCsv(context: Context, uri: Uri, dao: BookDao): Int = withContext(Dispatchers.IO) {
    val importedBooks = mutableListOf<Book>()
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val headerLine = reader.readLine() ?: return@withContext 0
                val delimiter = if (headerLine.contains(";")) ";" else ","
                val headers = parseCsvLine(headerLine, delimiter).map { it.lowercase().trim() }

                val isbnIdx = headers.indexOfFirst { it == "isbn" }
                val titleIdx = headers.indexOfFirst { it == "title" || it == "titre" }
                val authorsIdx = headers.indexOfFirst { it == "authors" || it == "auteurs" || it == "author" }
                val coverIdx = headers.indexOfFirst { it == "coverurl" || it == "imageurl" }
                val seriesIdx = headers.indexOfFirst { it == "series" || it == "série" }

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) continue
                    val tokens = parseCsvLine(line!!, delimiter)

                    if (isbnIdx != -1 && isbnIdx < tokens.size) {
                        val cleanIsbn = tokens[isbnIdx].filter { it.isDigit() || it == 'X' || it == 'x' }

                        if (cleanIsbn.length >= 10) {
                            val fetched = fetchBookInfo(cleanIsbn)
                            val title = if (titleIdx != -1 && titleIdx < tokens.size && tokens[titleIdx].isNotBlank()) tokens[titleIdx] else fetched.title
                            val authors = if (authorsIdx != -1 && authorsIdx < tokens.size && tokens[authorsIdx].isNotBlank()) tokens[authorsIdx] else fetched.authors
                            val cover = if (coverIdx != -1 && coverIdx < tokens.size && tokens[coverIdx].isNotBlank()) tokens[coverIdx] else fetched.coverUrl
                            val series = if (seriesIdx != -1 && seriesIdx < tokens.size && tokens[seriesIdx].isNotBlank()) tokens[seriesIdx] else fetched.series

                            importedBooks.add(
                                Book(
                                    isbn = cleanIsbn,
                                    title = title,
                                    authors = authors,
                                    coverUrl = cover,
                                    series = if (series.isBlank()) extractSeriesFromTitle(title) else series
                                )
                            )
                        }
                    }
                }
            }
        }
        if (importedBooks.isNotEmpty()) dao.insertBooks(importedBooks)
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return@withContext importedBooks.size
}

private fun parseCsvLine(line: String, delimiter: String): List<String> {
    val result = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    val delimChar = delimiter.first()

    for (ch in line.toCharArray()) {
        if (ch == '"') {
            inQuotes = !inQuotes
        } else if (ch == delimChar && !inQuotes) {
            result.add(cur.toString().trim().removeSurrounding("\""))
            cur.clear()
        } else {
            cur.append(ch)
        }
    }
    result.add(cur.toString().trim().removeSurrounding("\""))
    return result
}

suspend fun exportBooksToCsv(context: Context, uri: Uri, books: List<Book>): Boolean = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            val builder = StringBuilder("ISBN,Title,Authors,Series,CoverUrl,Status\n")
            for (b in books) {
                builder.append("\"${b.isbn}\",\"${b.title.replace("\"", "\"\"")}\",\"${b.authors.replace("\"", "\"\"")}\",\"${b.series.replace("\"", "\"\"")}\",\"${b.coverUrl}\",\"${b.status}\"\n")
            }
            outputStream.write(builder.toString().toByteArray())
        }
        true
    } catch (e: Exception) {
        false
    }
}

// ==========================================
// 6. MOTEUR SECURISE MULTI-SOURCES METADATAS
// ==========================================

suspend fun fetchBookInfo(isbn: String): Book = withContext(Dispatchers.IO) {
    var title = ""
    var authors = ""
    var cover = ""

    // 1. GOOGLE BOOKS API
    try {
        val keyParam = if (GOOGLE_BOOKS_API_KEY.isNotBlank()) "&key=$GOOGLE_BOOKS_API_KEY" else ""
        val url = URL("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn$keyParam")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            connectTimeout = 4000
            readTimeout = 4000
        }
        if (conn.responseCode == 200) {
            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonStr)

            if (json.optInt("totalItems", 0) > 0) {
                val items = json.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    val info = items.getJSONObject(0).optJSONObject("volumeInfo")
                    if (info != null) {
                        title = info.optString("title", "")

                        if (info.has("authors")) {
                            val arr = info.optJSONArray("authors")
                            if (arr != null) {
                                val list = mutableListOf<String>()
                                for (i in 0 until arr.length()) list.add(arr.getString(i))
                                authors = list.joinToString(", ")
                            }
                        }

                        val images = info.optJSONObject("imageLinks")
                        if (images != null) {
                            cover = images.optString("thumbnail", "")
                                .ifEmpty { images.optString("smallThumbnail", "") }
                                .replace("http:", "https:")
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. BNF (Bibliothèque Nationale de France)
    if (title.isBlank()) {
        try {
            val url = URL("https://catalogue.bnf.fr/api/SRU?operation=searchRetrieve&version=1.2&query=bib.isbn%20adj%20%22$isbn%22&recordSchema=dublincore")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 4000
                readTimeout = 4000
            }

            if (conn.responseCode == 200) {
                val xmlStr = conn.inputStream.bufferedReader().use { it.readText() }
                val titleMatcher = Pattern.compile("<dc:title>(.*?)</dc:title>").matcher(xmlStr)
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1)?.replace(Regex("""\s*/\s*.*"""), "")?.trim() ?: ""
                }

                val creatorMatcher = Pattern.compile("<dc:creator>(.*?)</dc:creator>").matcher(xmlStr)
                val authorsList = mutableListOf<String>()
                while (creatorMatcher.find()) {
                    creatorMatcher.group(1)?.let { authorsList.add(it.trim()) }
                }
                if (authorsList.isNotEmpty()) {
                    authors = authorsList.joinToString(", ")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 3. OPEN LIBRARY
    if (title.isBlank() || authors.isBlank() || cover.isBlank()) {
        try {
            val url = URL("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&jscmd=data&format=json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)

                if (json.has("ISBN:$isbn")) {
                    val bookObj = json.optJSONObject("ISBN:$isbn")
                    if (bookObj != null) {
                        if (title.isBlank()) title = bookObj.optString("title", "")

                        if (authors.isBlank() && bookObj.has("authors")) {
                            val authorsArr = bookObj.optJSONArray("authors")
                            if (authorsArr != null) {
                                val list = mutableListOf<String>()
                                for (i in 0 until authorsArr.length()) {
                                    val a = authorsArr.optJSONObject(i)
                                    if (a != null) list.add(a.optString("name"))
                                }
                                authors = list.joinToString(", ")
                            }
                        }

                        val coverObj = bookObj.optJSONObject("cover")
                        if (cover.isBlank() && coverObj != null) {
                            cover = coverObj.optString("large", "")
                                .ifEmpty { coverObj.optString("medium", "") }
                                .ifEmpty { coverObj.optString("small", "") }
                                .replace("http:", "https:")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 4. SCRAPING SÉCURITÉ WEB
    if (title.isBlank() || cover.isBlank()) {
        try {
            val url = URL("https://www.leslibraires.fr/livre/$isbn")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode == 200) {
                val html = conn.inputStream.bufferedReader().use { it.readText() }

                if (title.isBlank()) {
                    val titleMatcher = Pattern.compile("<h1 class=\"title\">\\s*(.*?)\\s*</h1>").matcher(html)
                    if (titleMatcher.find()) title = titleMatcher.group(1)?.trim() ?: ""
                }

                if (authors.isBlank()) {
                    val authorMatcher = Pattern.compile("<p class=\"author\">\\s*De\\s*<a[^>]*>\\s*(.*?)\\s*</a>").matcher(html)
                    if (authorMatcher.find()) authors = authorMatcher.group(1)?.trim() ?: ""
                }

                if (cover.isBlank()) {
                    val imgMatcher = Pattern.compile("id=\"main-image\" src=\"(.*?)\"").matcher(html)
                    if (imgMatcher.find()) cover = imgMatcher.group(1) ?: ""
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    if (title.isBlank()) title = "Livre $isbn"
    if (authors.isBlank()) authors = "Auteur non renseigné"

    val series = extractSeriesFromTitle(title)

    Book(
        isbn = isbn,
        title = title,
        authors = authors,
        coverUrl = cover,
        series = series
    )
}

// ==========================================
// 7. CAMERA SCANNER ML KIT
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraView(onScanned: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var isContinuousMode by remember { mutableStateOf(false) }
    var isProcessingScan by remember { mutableStateOf(false) }
    var lastScannedCode by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                        val mediaImage = proxy.image
                        if (mediaImage != null && !isProcessingScan) {
                            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    for (b in barcodes) {
                                        val rawVal = b.rawValue ?: continue
                                        val cleanVal = rawVal.filter { it.isDigit() || it == 'X' || it == 'x' }

                                        if (cleanVal.length >= 10 && !isProcessingScan) {
                                            if (isContinuousMode && cleanVal == lastScannedCode) continue

                                            isProcessingScan = true
                                            lastScannedCode = cleanVal
                                            onScanned(cleanVal)

                                            if (!isContinuousMode) {
                                                onClose()
                                            } else {
                                                scope.launch {
                                                    delay(2000)
                                                    isProcessingScan = false
                                                }
                                            }
                                            break
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
                        Toast.makeText(ctx, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isContinuousMode) "Mode : Scan Continu" else "Mode : Scan Unique",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Fermer")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = !isContinuousMode,
                    onClick = { isContinuousMode = false; lastScannedCode = "" },
                    label = { Text("Unique (1 livre)") }
                )
                FilterChip(
                    selected = isContinuousMode,
                    onClick = { isContinuousMode = true; lastScannedCode = "" },
                    label = { Text("En continu (Plusieurs)") }
                )
            }
        }
    }
}
