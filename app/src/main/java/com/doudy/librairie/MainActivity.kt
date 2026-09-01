package com.doudy.librairie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Star

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

import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONArray
import org.json.JSONObject

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors


// ============================================================
// 1. THÈME
// ============================================================

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


// ============================================================
// 2. MODÈLES
// ============================================================

enum class ReadStatus(val label: String) {
    READ("Lu"),
    READING("En cours"),
    UNREAD("À lire"),
    WISHLIST("Envie")
}

data class Book(
    val id: String = UUID.randomUUID().toString(),
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

data class ImportResult(
    val imported: Int,
    val duplicates: Int,
    val errors: Int
)


// ============================================================
// 3. ROOM
// ============================================================

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

fun Book.toEntity(): BookEntity {
    return BookEntity(
        id = id,
        isbn = isbn,
        title = title,
        authors = authors,
        series = series,
        coverUrl = coverUrl,
        publisher = publisher,
        publishedDate = publishedDate,
        description = description,
        source = source,
        totalPages = totalPages,
        currentPage = currentPage,
        rating = rating,
        status = status.name,
        personalNotes = personalNotes,
        isBorrowed = isBorrowed,
        borrowerName = borrowerName,
        addedTimestamp = addedTimestamp
    )
}

fun BookEntity.toDomain(): Book {
    return Book(
        id = id,
        isbn = isbn,
        title = title,
        authors = authors,
        series = series,
        coverUrl = coverUrl,
        publisher = publisher,
        publishedDate = publishedDate,
        description = description,
        source = source,
        totalPages = totalPages,
        currentPage = currentPage,
        rating = rating,
        status = try {
            ReadStatus.valueOf(status)
        } catch (_: Exception) {
            ReadStatus.UNREAD
        },
        personalNotes = personalNotes,
        isBorrowed = isBorrowed,
        borrowerName = borrowerName,
        addedTimestamp = addedTimestamp
    )
}

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

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE isbn = :isbn LIMIT 1")
    suspend fun findByIsbn(isbn: String): BookEntity?
}

@Database(
    entities = [BookEntity::class],
    version = 1,
    exportSchema = false
)
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


// ============================================================
// 4. API GOOGLE BOOKS / OPENLIBRARY
// ============================================================

object BookApiService {

    suspend fun searchBook(query: String): Book? =
        withContext(Dispatchers.IO) {

            val cleanQuery = query
                .trim()
                .replace("-", "")

            val googleResult = searchGoogleBooks(cleanQuery)

            if (googleResult != null) {
                return@withContext googleResult
            }

            searchOpenLibrary(cleanQuery)
        }

    private fun searchGoogleBooks(query: String): Book? {

        return try {

            val encodedQuery =
                URLEncoder.encode(query, "UTF-8")

            val urlString =
                "https://www.googleapis.com/books/v1/volumes?q=$encodedQuery"

            val connection =
                URL(urlString).openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode != 200) {
                return null
            }

            val jsonStr =
                connection.inputStream.bufferedReader().use {
                    it.readText()
                }

            val root = JSONObject(jsonStr)

            if (root.optInt("totalItems", 0) <= 0) {
                return null
            }

            val item =
                root.getJSONArray("items").getJSONObject(0)

            val info =
                item.getJSONObject("volumeInfo")

            val title =
                info.optString("title", "Titre inconnu")

            val authors =
                if (info.has("authors")) {

                    val arr = info.getJSONArray("authors")

                    (0 until arr.length())
                        .joinToString(", ") {
                            arr.getString(it)
                        }

                } else {
                    "Auteur inconnu"
                }

            var cover = ""

            if (info.has("imageLinks")) {

                cover = info
                    .getJSONObject("imageLinks")
                    .optString("thumbnail", "")
                    .replace("http://", "https://")
            }

            Book(
                isbn = query,
                title = title,
                authors = authors,
                coverUrl = cover,
                publisher = info.optString("publisher", ""),
                publishedDate = info.optString("publishedDate", ""),
                description = info.optString("description", ""),
                source = "Google Books",
                totalPages = info.optInt("pageCount", 0)
            )

        } catch (e: Exception) {

            Log.e(
                "BookApiService",
                "Google Books Error",
                e
            )

            null
        }
    }

    private fun searchOpenLibrary(query: String): Book? {

        return try {

            val urlString = if (
                query.all { it.isDigit() }
            ) {

                "https://openlibrary.org/api/books" +
                        "?bibkeys=ISBN:$query" +
                        "&format=json" +
                        "&jscmd=data"

            } else {

                val encoded =
                    URLEncoder.encode(query, "UTF-8")

                "https://openlibrary.org/search.json?q=$encoded"
            }

            val connection =
                URL(urlString).openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode != 200) {
                return null
            }

            val jsonStr =
                connection.inputStream.bufferedReader().use {
                    it.readText()
                }

            val root = JSONObject(jsonStr)

            if (query.all { it.isDigit() }) {

                val key = "ISBN:$query"

                if (!root.has(key)) {
                    return null
                }

                val data = root.getJSONObject(key)

                val authors =
                    if (data.has("authors")) {

                        val arr =
                            data.getJSONArray("authors")

                        (0 until arr.length())
                            .joinToString(", ") {
                                arr.getJSONObject(it)
                                    .optString("name")
                            }

                    } else {
                        "Auteur inconnu"
                    }

                var cover = ""

                if (data.has("cover")) {

                    cover =
                        data.getJSONObject("cover")
                            .optString("large", "")
                }

                Book(
                    isbn = query,
                    title = data.optString(
                        "title",
                        "Titre inconnu"
                    ),
                    authors = authors,
                    coverUrl = cover,
                    source = "OpenLibrary",
                    totalPages = data.optInt(
                        "number_of_pages",
                        0
                    )
                )

            } else {

                val docs =
                    root.optJSONArray("docs")

                if (docs == null || docs.length() == 0) {
                    return null
                }

                val doc =
                    docs.getJSONObject(0)

                val authors =
                    if (doc.has("author_name")) {

                        val arr =
                            doc.getJSONArray("author_name")

                        (0 until arr.length())
                            .joinToString(", ") {
                                arr.getString(it)
                            }

                    } else {
                        "Auteur inconnu"
                    }

                val coverId =
                    doc.optInt("cover_i", 0)

                val cover =
                    if (coverId > 0) {
                        "https://covers.openlibrary.org/b/id/$coverId-L.jpg"
                    } else {
                        ""
                    }

                Book(
                    isbn = query,
                    title = doc.optString(
                        "title",
                        "Titre inconnu"
                    ),
                    authors = authors,
                    coverUrl = cover,
                    source = "OpenLibrary"
                )
            }

        } catch (e: Exception) {

            Log.e(
                "BookApiService",
                "OpenLibrary Error",
                e
            )

            null
        }
    }
}


// ============================================================
// 5. ÉTAT UI
// ============================================================

sealed class UiState {

    object Idle : UiState()

    object Loading : UiState()

    data class Success(
        val book: Book
    ) : UiState()

    data class Error(
        val message: String
    ) : UiState()
}


// ============================================================
// 6. VIEWMODEL
// ============================================================

class BookViewModel(
    private val bookDao: BookDao
) : ViewModel() {

    val books: StateFlow<List<Book>> =
        bookDao.getAllBooks()
            .map { list ->
                list.map {
                    it.toDomain()
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    val readingStats:
            StateFlow<ReadingStats> =
        books.map { list ->

            val readBooks =
                list.filter {
                    it.status == ReadStatus.READ
                }

            val pages =
                readBooks.sumOf {
                    it.totalPages
                }

            val topAuthor =
                list.groupBy {
                    it.authors
                }.maxByOrNull {
                    it.value.size
                }?.key ?: "Aucun"

            val avgRating =
                readBooks
                    .map { it.rating }
                    .filter { it > 0 }
                    .average()
                    .toFloat()

            ReadingStats(
                totalBooks = list.size,
                booksRead = readBooks.size,
                totalPagesRead = pages,
                topAuthor = topAuthor,
                averageRating =
                    if (avgRating.isNaN()) {
                        0f
                    } else {
                        avgRating
                    },
                wishlistCount =
                    list.count {
                        it.status == ReadStatus.WISHLIST
                    },
                borrowedCount =
                    list.count {
                        it.isBorrowed
                    }
            )

        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ReadingStats(
                0,
                0,
                0,
                "-",
                0f,
                0,
                0
            )
        )

    private val _uiState =
        MutableStateFlow<UiState>(UiState.Idle)

    val uiState =
        _uiState.asStateFlow()


    // --------------------------------------------------------
    // AJOUT / MODIFICATION
    // --------------------------------------------------------

    fun searchAndAddBook(
        isbnOrQuery: String
    ) {

        if (isbnOrQuery.isBlank()) return

        viewModelScope.launch {

            _uiState.value =
                UiState.Loading

            val foundBook =
                BookApiService.searchBook(
                    isbnOrQuery
                )

            if (foundBook != null) {

                bookDao.insertBook(
                    foundBook.toEntity()
                )

                _uiState.value =
                    UiState.Success(foundBook)

            } else {

                _uiState.value =
                    UiState.Error(
                        "Aucun livre trouvé pour : $isbnOrQuery"
                    )
            }
        }
    }

    fun addBookDirectly(book: Book) {

        viewModelScope.launch {

            bookDao.insertBook(
                book.toEntity()
            )
        }
    }

    fun updateBook(book: Book) {

        viewModelScope.launch {

            bookDao.updateBook(
                book.toEntity()
            )
        }
    }

    fun removeBook(book: Book) {

        viewModelScope.launch {

            bookDao.deleteBook(
                book.toEntity()
            )
        }
    }

    fun resetState() {

        _uiState.value =
            UiState.Idle
    }


    // ========================================================
    // EXPORT JSON
    // ========================================================

    fun getExportJsonString(): String {

        val jsonArray =
            JSONArray()

        books.value.forEach { book ->

            val obj =
                JSONObject().apply {

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


    // ========================================================
    // EXPORT CSV
    // ========================================================

    fun getExportCsvString(): String {

        val builder =
            StringBuilder()

        // BOM UTF-8 pour Excel
        builder.append('\uFEFF')

        val headers =
            listOf(
                "id",
                "isbn",
                "title",
                "authors",
                "series",
                "coverUrl",
                "publisher",
                "publishedDate",
                "description",
                "source",
                "totalPages",
                "currentPage",
                "rating",
                "status",
                "personalNotes",
                "isBorrowed",
                "borrowerName",
                "addedTimestamp"
            )

        builder.append(
            headers.joinToString(";")
        )

        builder.append("\n")

        books.value.forEach { book ->

            val values =
                listOf(
                    book.id,
                    book.isbn,
                    book.title,
                    book.authors,
                    book.series,
                    book.coverUrl,
                    book.publisher,
                    book.publishedDate,
                    book.description,
                    book.source,
                    book.totalPages.toString(),
                    book.currentPage.toString(),
                    book.rating.toString(),
                    book.status.name,
                    book.personalNotes,
                    book.isBorrowed.toString(),
                    book.borrowerName,
                    book.addedTimestamp.toString()
                )

            builder.append(
                values.joinToString(";") {
                    escapeCsv(it)
                }
            )

            builder.append("\n")
        }

        return builder.toString()
    }


    // ========================================================
    // IMPORT AUTOMATIQUE
    // ========================================================

    fun importFromString(
        content: String,
        fileName: String = "",
        onComplete: (ImportResult, String) -> Unit
    ) {

        viewModelScope.launch(Dispatchers.IO) {

            try {

                var data =
                    content
                        .removePrefix("\uFEFF")
                        .trim()

                if (data.isBlank()) {

                    withContext(Dispatchers.Main) {

                        onComplete(
                            ImportResult(0, 0, 1),
                            "Fichier vide"
                        )
                    }

                    return@launch
                }

                val lowerName =
                    fileName.lowercase(Locale.ROOT)

                val isJson =
                    lowerName.endsWith(".json") ||
                            data.startsWith("[") ||
                            data.startsWith("{")

                val result =
                    if (isJson) {

                        importJson(data)

                    } else {

                        importCsv(data)
                    }

                val message =
                    buildString {

                        append(
                            "${result.imported} livre(s) importé(s)"
                        )

                        if (result.duplicates > 0) {

                            append(
                                "\n${result.duplicates} doublon(s) ignoré(s)"
                            )
                        }

                        if (result.errors > 0) {

                            append(
                                "\n${result.errors} ligne(s) ignorée(s)"
                            )
                        }
                    }

                withContext(Dispatchers.Main) {

                    onComplete(
                        result,
                        message
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    "IMPORT",
                    "Erreur import",
                    e
                )

                withContext(Dispatchers.Main) {

                    onComplete(
                        ImportResult(0, 0, 1),
                        "Erreur : ${e.message}"
                    )
                }
            }
        }
    }


    // ========================================================
    // IMPORT JSON
    // ========================================================

    private suspend fun importJson(
        jsonString: String
    ): ImportResult {

        val array =
            try {

                if (jsonString.trimStart().startsWith("[")) {

                    JSONArray(jsonString)

                } else {

                    val root =
                        JSONObject(jsonString)

                    val possibleArrays =
                        listOf(
                            "books",
                            "library",
                            "items",
                            "data"
                        )

                    var found: JSONArray? = null

                    for (key in possibleArrays) {

                        if (root.has(key)) {

                            found =
                                root.optJSONArray(key)

                            if (found != null) {
                                break
                            }
                        }
                    }

                    found ?: JSONArray().apply {
                        put(root)
                    }
                }

            } catch (e: Exception) {

                throw IllegalArgumentException(
                    "JSON invalide",
                    e
                )
            }

        var imported = 0
        var duplicates = 0
        var errors = 0

        for (i in 0 until array.length()) {

            try {

                val obj =
                    array.getJSONObject(i)

                val book =
                    Book(
                        id = jsonStringValue(
                            obj,
                            "id"
                        ).ifBlank {
                            UUID.randomUUID().toString()
                        },

                        isbn = cleanIsbn(
                            jsonStringValue(
                                obj,
                                "isbn",
                                "ISBN"
                            )
                        ),

                        title =
                            jsonStringValue(
                                obj,
                                "title",
                                "titre",
                                "name",
                                "nom"
                            ).ifBlank {
                                "Titre inconnu"
                            },

                        authors =
                            jsonStringValue(
                                obj,
                                "authors",
                                "author",
                                "auteur",
                                "auteurs"
                            ).ifBlank {
                                "Auteur inconnu"
                            },

                        series =
                            jsonStringValue(
                                obj,
                                "series",
                                "serie",
                                "série"
                            ),

                        coverUrl =
                            jsonStringValue(
                                obj,
                                "coverUrl",
                                "cover",
                                "image",
                                "imageUrl"
                            ),

                        publisher =
                            jsonStringValue(
                                obj,
                                "publisher",
                                "éditeur",
                                "editeur"
                            ),

                        publishedDate =
                            jsonStringValue(
                                obj,
                                "publishedDate",
                                "publicationDate",
                                "date"
                            ),

                        description =
                            jsonStringValue(
                                obj,
                                "description",
                                "resume",
                                "résumé"
                            ),

                        source =
                            jsonStringValue(
                                obj,
                                "source"
                            ).ifBlank {
                                "Import JSON"
                            },

                        totalPages =
                            jsonIntValue(
                                obj,
                                "totalPages",
                                "pages",
                                "pageCount"
                            ),

                        currentPage =
                            jsonIntValue(
                                obj,
                                "currentPage",
                                "pageActuelle"
                            ),

                        rating =
                            jsonFloatValue(
                                obj,
                                "rating",
                                "note",
                                "stars"
                            ).coerceIn(0f, 5f),

                        status =
                            parseStatus(
                                jsonStringValue(
                                    obj,
                                    "status",
                                    "statut"
                                )
                            ),

                        personalNotes =
                            jsonStringValue(
                                obj,
                                "personalNotes",
                                "notes",
                                "notesPersonnelles"
                            ),

                        isBorrowed =
                            parseBoolean(
                                jsonStringValue(
                                    obj,
                                    "isBorrowed",
                                    "borrowed",
                                    "prete",
                                    "prête"
                                )
                            ),

                        borrowerName =
                            jsonStringValue(
                                obj,
                                "borrowerName",
                                "borrower",
                                "emprunteur"
                            ),

                        addedTimestamp =
                            jsonLongValue(
                                obj,
                                "addedTimestamp",
                                "timestamp"
                            ).takeIf {
                                it > 0
                            } ?: System.currentTimeMillis()
                    )

                when (
                    saveImportedBook(book)
                ) {

                    ImportAction.INSERTED -> imported++

                    ImportAction.DUPLICATE -> duplicates++

                    ImportAction.ERROR -> errors++
                }

            } catch (e: Exception) {

                errors++

                Log.e(
                    "IMPORT_JSON",
                    "Erreur ligne $i",
                    e
                )
            }
        }

        return ImportResult(
            imported,
            duplicates,
            errors
        )
    }


    // ========================================================
    // IMPORT CSV
    // ========================================================

    private suspend fun importCsv(
        csvString: String
    ): ImportResult {

        val delimiter =
            detectCsvDelimiter(csvString)

        val rows =
            parseCsvLines(
                csvString,
                delimiter
            )

        if (rows.isEmpty()) {

            return ImportResult(
                0,
                0,
                1
            )
        }

        val headers =
            rows.first()
                .map {
                    normalizeHeader(it)
                }

        if (headers.isEmpty()) {

            return ImportResult(
                0,
                0,
                1
            )
        }

        var imported = 0
        var duplicates = 0
        var errors = 0

        for (rowIndex in 1 until rows.size) {

            val row =
                rows[rowIndex]

            if (
                row.all {
                    it.isBlank()
                }
            ) {
                continue
            }

            try {

                val data =
                    mutableMapOf<String, String>()

                headers.forEachIndexed { index, header ->

                    if (header.isNotBlank()) {

                        data[header] =
                            row.getOrNull(index)
                                ?.trim()
                                ?: ""
                    }
                }

                val title =
                    firstValue(
                        data,
                        "title",
                        "titre",
                        "booktitle",
                        "name",
                        "nom"
                    )

                if (title.isBlank()) {

                    errors++

                    return@for
                }

                val book =
                    Book(

                        id =
                            firstValue(
                                data,
                                "id"
                            ).ifBlank {
                                UUID.randomUUID().toString()
                            },

                        isbn =
                            cleanIsbn(
                                firstValue(
                                    data,
                                    "isbn",
                                    "isbn10",
                                    "isbn13"
                                )
                            ),

                        title =
                            title,

                        authors =
                            firstValue(
                                data,
                                "authors",
                                "author",
                                "auteur",
                                "auteurs"
                            ).ifBlank {
                                "Auteur inconnu"
                            },

                        series =
                            firstValue(
                                data,
                                "series",
                                "serie",
                                "collection"
                            ),

                        coverUrl =
                            firstValue(
                                data,
                                "coverurl",
                                "cover",
                                "image",
                                "imageurl"
                            ),

                        publisher =
                            firstValue(
                                data,
                                "publisher",
                                "editeur",
                                "éditeur"
                            ),

                        publishedDate =
                            firstValue(
                                data,
                                "publisheddate",
                                "publicationdate",
                                "date",
                                "published"
                            ),

                        description =
                            firstValue(
                                data,
                                "description",
                                "resume"
                            ),

                        source =
                            firstValue(
                                data,
                                "source"
                            ).ifBlank {
                                "Import CSV"
                            },

                        totalPages =
                            parseInt(
                                firstValue(
                                    data,
                                    "totalpages",
                                    "pages",
                                    "pagecount",
                                    "nombredepages",
                                    "nbpages"
                                )
                            ),

                        currentPage =
                            parseInt(
                                firstValue(
                                    data,
                                    "currentpage",
                                    "pageactuelle"
                                )
                            ),

                        rating =
                            parseFloat(
                                firstValue(
                                    data,
                                    "rating",
                                    "note",
                                    "stars",
                                    "evaluation"
                                )
                            ).coerceIn(0f, 5f),

                        status =
                            parseStatus(
                                firstValue(
                                    data,
                                    "status",
                                    "statut",
                                    "readstatus"
                                )
                            ),

                        personalNotes =
                            firstValue(
                                data,
                                "personalnotes",
                                "notes",
                                "notespersonnelles"
                            ),

                        isBorrowed =
                            parseBoolean(
                                firstValue(
                                    data,
                                    "isborrowed",
                                    "borrowed",
                                    "prete",
                                    "emprunte"
                                )
                            ),

                        borrowerName =
                            firstValue(
                                data,
                                "borrowername",
                                "borrower",
                                "emprunteur",
                                "nomemprunteur"
                            ),

                        addedTimestamp =
                            parseLong(
                                firstValue(
                                    data,
                                    "addedtimestamp",
                                    "timestamp"
                                )
                            ).takeIf {
                                it > 0
                            } ?: System.currentTimeMillis()
                    )

                when (
                    saveImportedBook(book)
                ) {

                    ImportAction.INSERTED -> imported++

                    ImportAction.DUPLICATE -> duplicates++

                    ImportAction.ERROR -> errors++
                }

            } catch (e: Exception) {

                errors++

                Log.e(
                    "IMPORT_CSV",
                    "Erreur ligne $rowIndex",
                    e
                )
            }
        }

        return ImportResult(
            imported,
            duplicates,
            errors
        )
    }


    // ========================================================
    // DOUBLONS
    // ========================================================

    private enum class ImportAction {
        INSERTED,
        DUPLICATE,
        ERROR
    }

    private suspend fun saveImportedBook(
        book: Book
    ): ImportAction {

        return try {

            val existingById =
                bookDao.findById(book.id)

            if (existingById != null) {

                // Même ID = on met à jour
                bookDao.insertBook(
                    book.toEntity()
                )

                return ImportAction.INSERTED
            }

            if (book.isbn.isNotBlank()) {

                val existingByIsbn =
                    bookDao.findByIsbn(book.isbn)

                if (existingByIsbn != null) {

                    return ImportAction.DUPLICATE
                }
            }

            bookDao.insertBook(
                book.toEntity()
            )

            ImportAction.INSERTED

        } catch (e: Exception) {

            Log.e(
                "IMPORT",
                "Erreur sauvegarde",
                e
            )

            ImportAction.ERROR
        }
    }


    // ========================================================
    // OUTILS JSON
    // ========================================================

    private fun jsonStringValue(
        obj: JSONObject,
        vararg keys: String
    ): String {

        for (key in keys) {

            if (
                obj.has(key) &&
                !obj.isNull(key)
            ) {

                val value =
                    obj.opt(key)

                if (value != null) {

                    return value.toString()
                }
            }
        }

        return ""
    }

    private fun jsonIntValue(
        obj: JSONObject,
        vararg keys: String
    ): Int {

        return parseInt(
            jsonStringValue(
                obj,
                *keys
            )
        )
    }

    private fun jsonFloatValue(
        obj: JSONObject,
        vararg keys: String
    ): Float {

        return parseFloat(
            jsonStringValue(
                obj,
                *keys
            )
        )
    }

    private fun json
