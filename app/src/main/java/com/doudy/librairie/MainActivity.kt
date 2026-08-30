package com.doudy.librairie

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.net.URL

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

    @Delete
    suspend fun deleteBook(book: Book)
}

@Database(entities = [Book::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

// ==========================================
// 2. ACTIVITÉ PRINCIPALE
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "doudy.db")
            .fallbackToDestructiveMigration()
            .build()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6750A4),
                    surfaceVariant = Color(0xFFF3EDF7)
                )
            ) {
                MainScreen(db.bookDao())
            }
        }
    }
}

// ==========================================
// 3. ÉCRAN PRINCIPAL DE L'APPLICATION
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

    // Amélioration : Groupement par série et tri par numéro de tome
    val groupedBooks = remember(books, searchQuery) {
        val filtered = if (searchQuery.isBlank()) books
        else books.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.authors.contains(searchQuery, ignoreCase = true) ||
            it.series.contains(searchQuery, ignoreCase = true) ||
            it.isbn.contains(searchQuery)
        }
        
        filtered.groupBy { 
            if (it.series.isNotBlank()) it.series else extractSeriesFromTitle(it.title) 
        }.mapValues { (_, seriesList) ->
            seriesList.sortedBy { extractVolumeNumber(it.title) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val count = importBooksFromCsv(context, uri, dao)
                Toast.makeText(context, "$count livre(s) importé(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val success = exportBooksToCsv(context, uri, books)
                Toast.makeText(context, if (success) "Exportation réussie" else "Erreur d'exportation", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Doudy Librairie", fontWeight = FontWeight.Bold)
                            Text("${books.size} livre(s)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("📥 Importer un fichier CSV") },
                                    onClick = {
                                        showMenu = false
                                        importLauncher.launch("*/*")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("📤 Exporter ma bibliothèque") },
                                    onClick = {
                                        showMenu = false
                                        if (books.isNotEmpty()) exportLauncher.launch("ma_bibliotheque.csv")
                                        else Toast.makeText(context, "Bibliothèque vide", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text("Scanner un livre") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            showScan = true
                        } else {
                            activity.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
                        }
                    }
                )
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Rechercher un titre, série, auteur...") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (groupedBooks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucun livre trouvé", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        groupedBooks.forEach { (seriesName, seriesBooks) ->
                            item {
                                SeriesSection(
                                    seriesName = seriesName,
                                    books = seriesBooks,
                                    onDeleteBook = { book -> scope.launch { dao.deleteBook(book) } }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Caméra Overlay Plein écran
        if (showScan) {
            CameraView(
                onScanned = { isbn ->
                    val clean = isbn.filter { it.isDigit() || it == 'X' || it == 'x' }
                    if (clean.length >= 10) {
                        scope.launch {
                            val book = fetchBookInfo(clean)
                            dao.insertBook(book)
                            Toast.makeText(context, "Ajouté : ${book.title}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onClose = { showScan = false }
            )
        }
    }
}

// ==========================================
// 4. AFFICHAGE DES SÉRIES ET DES CARTES
// ==========================================

@Composable
fun SeriesSection(seriesName: String, books: List<Book>, onDeleteBook: (Book) -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    val isStandalone = seriesName == "Hors série" || seriesName.isBlank()

    Column(Modifier.fillMaxWidth()) {
        if (!isStandalone) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(seriesName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("${books.size} tome(s)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        AnimatedVisibility(visible = expanded || isStandalone) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                books.forEach { book ->
                    BookItemCard(book = book, onDelete = { onDeleteBook(book) })
                }
            }
        }
    }
}

@Composable
fun BookItemCard(book: Book, onDelete: () -> Unit) {
    var isImageError by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {

            // Zone Couverture / Pochette de secours
            Box(
                modifier = Modifier
                    .size(60.dp, 90.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (book.coverUrl.isNotBlank() && !isImageError) {
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
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text(
                            text = book.title.take(1).uppercase(),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 22.sp
                        )
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 8.sp,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Surface(
                    color = if (book.status == "Lu") Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = book.status,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (book.status == "Lu") Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.authors,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "ISBN: ${book.isbn}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = Color.Gray)
            }
        }
    }
}

// ==========================================
// 5. PARSER, EXPORT ET EXTRACTION DE SÉRIES
// ==========================================

fun extractSeriesFromTitle(title: String): String {
    val patterns = listOf(
        Regex("""(?i)^(.*?)\s*[:\-_,]\s*(?:Volume|Vol|Tome|T)\s*\d+"""),
        Regex("""(?i)^(.*?)\s*[:\-_,]\s*.*"""),
        Regex("""(?i)^(.*?)\s+\d+$""")
    )
    for (pattern in patterns) {
        val match = pattern.find(title)
        if (match != null) {
            val seriesCandidate = match.groupValues[1].trim()
            if (seriesCandidate.length > 2) return seriesCandidate
        }
    }
    return "Hors série"
}

fun extractVolumeNumber(title: String): Int {
    val match = Regex("""(?i)(?:Volume|Vol|Tome|T)\s*(\d+)""").find(title)
        ?: Regex("""\b(\d+)\b""").find(title)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 999
}

suspend fun importBooksFromCsv(context: android.content.Context, uri: Uri, dao: BookDao): Int = withContext(Dispatchers.IO) {
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
                            val title = if (titleIdx != -1 && titleIdx < tokens.size && tokens[titleIdx].isNotBlank()) tokens[titleIdx] else "Livre $cleanIsbn"
                            val authors = if (authorsIdx != -1 && authorsIdx < tokens.size && tokens[authorsIdx].isNotBlank()) tokens[authorsIdx] else "Auteur inconnu"
                            val explicitSeries = if (seriesIdx != -1 && seriesIdx < tokens.size) tokens[seriesIdx] else ""
                            
                            val coverUrl = if (coverIdx != -1 && coverIdx < tokens.size && tokens[coverIdx].isNotBlank()) {
                                tokens[coverIdx]
                            } else {
                                ""
                            }

                            importedBooks.add(
                                Book(
                                    isbn = cleanIsbn,
                                    title = title,
                                    authors = authors,
                                    coverUrl = coverUrl,
                                    series = if (explicitSeries.isNotBlank()) explicitSeries else extractSeriesFromTitle(title)
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

suspend fun exportBooksToCsv(context: android.content.Context, uri: Uri, books: List<Book>): Boolean = withContext(Dispatchers.IO) {
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
// 6. RECHERCHE EN LIGNE MULTI-APIS
// ==========================================

suspend fun fetchBookInfo(isbn: String): Book = withContext(Dispatchers.IO) {
    var title = ""
    var authors = ""
    var cover = ""
    var series = ""

    // 1. Google Books API
    try {
        val url = URL("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn")
        val conn = url.openConnection().apply {
            connectTimeout = 4000
            readTimeout = 4000
        }
        val jsonStr = conn.getInputStream().bufferedReader().use { it.readText() }
        val json = JSONObject(jsonStr)

        if (json.optInt("totalItems", 0) > 0) {
            val info = json.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo")
            title = info.optString("title", "")

            if (info.has("authors")) {
                val arr = info.getJSONArray("authors")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                authors = list.joinToString(", ")
            }

            if (info.has("imageLinks")) {
                val images = info.getJSONObject("imageLinks")
                cover = images.optString("thumbnail", "")
                    .ifEmpty { images.optString("smallThumbnail", "") }
                    .replace("http:", "https:")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. OpenLibrary API en secours
    try {
        val url = URL("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&jscmd=data&format=json")
        val conn = url.openConnection().apply {
            connectTimeout = 4000
            readTimeout = 4000
        }
        val jsonStr = conn.getInputStream().bufferedReader().use { it.readText() }
        val json = JSONObject(jsonStr)

        if (json.has("ISBN:$isbn")) {
            val bookObj = json.getJSONObject("ISBN:$isbn")

            if (title.isBlank()) {
                title = bookObj.optString("title", "")
            }

            if (authors.isBlank() && bookObj.has("authors")) {
                val authorsArr = bookObj.getJSONArray("authors")
                val list = mutableListOf<String>()
                for (i in 0 until authorsArr.length()) {
                    list.add(authorsArr.getJSONObject(i).optString("name"))
                }
                authors = list.joinToString(", ")
            }

            if (cover.isBlank() && bookObj.has("cover")) {
                val coverObj = bookObj.getJSONObject("cover")
                cover = coverObj.optString("large", "")
                    .ifEmpty { coverObj.optString("medium", "") }
                    .ifEmpty { coverObj.optString("small", "") }
                    .replace("http:", "https:")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    if (title.isBlank()) title = "Livre $isbn"
    
    // Auto-détection de secours si l'auteur n'est pas fourni par l'API
    if (authors.isBlank() || authors == "Auteur non renseigné") {
        authors = when {
            title.contains("Lore Olympus", ignoreCase = true) -> "Rachel Smythe"
            else -> "Auteur non renseigné"
        }
    }

    series = extractSeriesFromTitle(title)

    Book(
        isbn = isbn,
        title = title,
        authors = authors,
        coverUrl = cover,
        series = series
    )
}

// ==========================================
// 7. SCANNER D'BARRES EN PLEIN ÉCRAN
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
