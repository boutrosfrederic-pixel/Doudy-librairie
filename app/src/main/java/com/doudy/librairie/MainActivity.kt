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

import com.google.mlkit.vision.barcode.common.Barcode
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

               continue
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

    private fun jsonLongValue(
        obj: JSONObject,
        vararg keys: String
    ): Long {

        return parseLong(
            jsonStringValue(
                obj,
                *keys
            )
        )
    }


    // ========================================================
    // OUTILS CSV
    // ========================================================

    private fun escapeCsv(
        value: String
    ): String {

        return if (
            value.contains(";") ||
            value.contains(",") ||
            value.contains("\"") ||
            value.contains("\n") ||
            value.contains("\r")
        ) {

            "\"" +
                    value.replace(
                        "\"",
                        "\"\""
                    ) +
                    "\""

        } else {

            value
        }
    }

    private fun detectCsvDelimiter(
        csv: String
    ): Char {

        val candidates =
            listOf(';', ',', '\t')

        val counts =
            mutableMapOf<Char, Int>()

        candidates.forEach {
            counts[it] = 0
        }

        var inQuotes = false

        val limit =
            minOf(
                csv.length,
                10000
            )

        for (i in 0 until limit) {

            val c = csv[i]

            if (c == '"') {

                if (
                    inQuotes &&
                    i + 1 < limit &&
                    csv[i + 1] == '"'
                ) {

                    continue

                } else {

                    inQuotes = !inQuotes
                }

            } else if (!inQuotes) {

                if (c in candidates) {

                    counts[c] =
                        counts[c]!! + 1
                }
            }
        }

        return counts.maxByOrNull {
            it.value
        }?.key ?: ';'
    }

    private fun parseCsvLines(
        csv: String,
        delimiter: Char
    ): List<List<String>> {

        val rows =
            mutableListOf<List<String>>()

        var currentRow =
            mutableListOf<String>()

        val currentField =
            StringBuilder()

        var inQuotes = false

        var i = 0

        while (i < csv.length) {

            val c = csv[i]

            when {

                c == '"' -> {

                    if (
                        inQuotes &&
                        i + 1 < csv.length &&
                        csv[i + 1] == '"'
                    ) {

                        currentField.append('"')
                        i++

                    } else {

                        inQuotes =
                            !inQuotes
                    }
                }

                c == delimiter &&
                        !inQuotes -> {

                    currentRow.add(
                        currentField.toString()
                    )

                    currentField.clear()
                }

                (c == '\n' || c == '\r') &&
                        !inQuotes -> {

                    currentRow.add(
                        currentField.toString()
                    )

                    currentField.clear()

                    if (
                        currentRow.any {
                            it.isNotBlank()
                        }
                    ) {

                        rows.add(
                            currentRow
                                .toList()
                        )
                    }

                    currentRow =
                        mutableListOf()

                    if (
                        c == '\r' &&
                        i + 1 < csv.length &&
                        csv[i + 1] == '\n'
                    ) {

                        i++
                    }
                }

                else -> {

                    currentField.append(c)
                }
            }

            i++
        }

        if (
            currentField.isNotEmpty() ||
            currentRow.isNotEmpty()
        ) {

            currentRow.add(
                currentField.toString()
            )

            if (
                currentRow.any {
                    it.isNotBlank()
                }
            ) {

                rows.add(
                    currentRow
                )
            }
        }

        return rows
    }


    // ========================================================
    // NORMALISATION
    // ========================================================

    private fun normalizeHeader(
        value: String
    ): String {

        val normalized =
            Normalizer.normalize(
                value
                    .removePrefix("\uFEFF")
                    .trim()
                    .lowercase(Locale.ROOT),
                Normalizer.Form.NFD
            )

        return normalized
            .replace(
                "\\p{M}+".toRegex(),
                ""
            )
            .replace(
                "[^a-z0-9]".toRegex(),
                ""
            )
    }

    private fun firstValue(
        data: Map<String, String>,
        vararg keys: String
    ): String {

        for (key in keys) {

            val normalized =
                normalizeHeader(key)

            val value =
                data[normalized]

            if (!value.isNullOrBlank()) {

                return value.trim()
            }
        }

        return ""
    }

    private fun cleanIsbn(
        isbn: String
    ): String {

        return isbn
            .trim()
            .replace(
                "-",
                ""
            )
            .replace(
                " ",
                ""
            )
            .uppercase(Locale.ROOT)
    }

    private fun parseInt(
        value: String
    ): Int {

        return value
            .trim()
            .replace(",", ".")
            .toDoubleOrNull()
            ?.toInt()
            ?: 0
    }

    private fun parseFloat(
        value: String
    ): Float {

        return value
            .trim()
            .replace(",", ".")
            .toFloatOrNull()
            ?: 0f
    }

    private fun parseLong(
        value: String
    ): Long {

        return value
            .trim()
            .toLongOrNull()
            ?: 0L
    }

    private fun parseBoolean(
        value: String
    ): Boolean {

        return when (
            normalizeHeader(value)
        ) {

            "true",
            "1",
            "yes",
            "oui",
            "vrai",
            "x",
            "prete",
            "emprunte" -> true

            else -> false
        }
    }

    private fun parseStatus(
        value: String
    ): ReadStatus {

        val normalized =
            Normalizer.normalize(
                value
                    .trim()
                    .lowercase(Locale.ROOT),
                Normalizer.Form.NFD
            )
                .replace(
                    "\\p{M}+".toRegex(),
                    ""
                )
                .replace(
                    "[^a-z]".toRegex(),
                    ""
                )

        return when (normalized) {

            "read",
            "lu",
            "lue" ->
                ReadStatus.READ

            "reading",
            "encours",
            "lecture" ->
                ReadStatus.READING

            "wishlist",
            "envie",
            "souhaite",
            "wish" ->
                ReadStatus.WISHLIST

            else ->
                ReadStatus.UNREAD
        }
    }
}


// ============================================================
// 7. FACTORY
// ============================================================

class BookViewModelFactory(
    private val bookDao: BookDao
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(
        modelClass: Class<T>
    ): T {

        if (
            modelClass.isAssignableFrom(
                BookViewModel::class.java
            )
        ) {

            @Suppress("UNCHECKED_CAST")

            return BookViewModel(
                bookDao
            ) as T
        }

        throw IllegalArgumentException(
            "Unknown ViewModel class"
        )
    }
}


// ============================================================
// 8. ACTIVITY
// ============================================================

class MainActivity :
    ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        val database =
            AppDatabase.getDatabase(
                this
            )

        val factory =
            BookViewModelFactory(
                database.bookDao()
            )

        val viewModel =
            ViewModelProvider(
                this,
                factory
            )[BookViewModel::class.java]

        setContent {

            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        background =
                            AppTheme.BackgroundDark,
                        surface =
                            AppTheme.SurfaceDark,
                        primary =
                            AppTheme.PrimaryEmerald
                    )
            ) {

                Surface(
                    modifier =
                        Modifier.fillMaxSize(),
                    color =
                        AppTheme.BackgroundDark
                ) {

                    MainScreen(
                        viewModel
                    )
                }
            }
        }
    }
}


// ============================================================
// 9. ÉCRAN PRINCIPAL
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: BookViewModel
) {

    val context =
        LocalContext.current

    val books by
            viewModel.books.collectAsState()

    val stats by
            viewModel.readingStats.collectAsState()

    val uiState by
            viewModel.uiState.collectAsState()

    var selectedStatusTab
            by remember {
                mutableStateOf<ReadStatus?>(null)
            }

    var searchQuery
            by remember {
                mutableStateOf("")
            }

    var isGridView
            by remember {
                mutableStateOf(true)
            }

    var selectedBook
            by remember {
                mutableStateOf<Book?>(null)
            }

    var showManualAdd
            by remember {
                mutableStateOf(false)
            }

    var showScanner
            by remember {
                mutableStateOf(false)
            }

    var showStats
            by remember {
                mutableStateOf(false)
            }


    // ========================================================
    // EXPORT JSON
    // ========================================================

    val exportJsonLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/json"
            )
        ) { uri ->

            uri ?: return@rememberLauncherForActivityResult

            try {

                context.contentResolver
                    .openOutputStream(uri)
                    ?.use { output ->

                        output.write(
                            viewModel
                                .getExportJsonString()
                                .toByteArray(
                                    StandardCharsets.UTF_8
                                )
                        )
                    }

                Toast.makeText(
                    context,
                    "Export JSON terminé !",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {

                Toast.makeText(
                    context,
                    "Erreur export JSON",
                    Toast.LENGTH_LONG
                ).show()
            }
        }


    // ========================================================
    // EXPORT CSV
    // ========================================================

    val exportCsvLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(
                "text/csv"
            )
        ) { uri ->

            uri ?: return@rememberLauncherForActivityResult

            try {

                context.contentResolver
                    .openOutputStream(uri)
                    ?.use { output ->

                        output.write(
                            viewModel
                                .getExportCsvString()
                                .toByteArray(
                                    StandardCharsets.UTF_8
                                )
                        )
                    }

                Toast.makeText(
                    context,
                    "Export CSV terminé !",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {

                Toast.makeText(
                    context,
                    "Erreur export CSV",
                    Toast.LENGTH_LONG
                ).show()
            }
        }


    // ========================================================
    // IMPORT
    // ========================================================

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            uri ?: return@rememberLauncherForActivityResult

            try {

                val content =
                    context.contentResolver
                        .openInputStream(uri)
                        ?.use { input ->

                            input
                                .bufferedReader(
                                    StandardCharsets.UTF_8
                                )
                                .use {
                                    it.readText()
                                }
                        }

                if (content.isNullOrBlank()) {

                    Toast.makeText(
                        context,
                        "Fichier vide",
                        Toast.LENGTH_LONG
                    ).show()

                    return@rememberLauncherForActivityResult
                }

                val fileName =
                    getFileName(
                        context,
                        uri
                    )

                viewModel.importFromString(
                    content,
                    fileName
                ) { result, message ->

                    Toast.makeText(
                        context,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                Log.e(
                    "IMPORT",
                    "Erreur lecture fichier",
                    e
                )

                Toast.makeText(
                    context,
                    "Impossible de lire le fichier",
                    Toast.LENGTH_LONG
                ).show()
            }
        }


    // ========================================================
    // CAMÉRA
    // ========================================================

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                showScanner = true

            } else {

                Toast.makeText(
                    context,
                    "Permission caméra requise",
                    Toast.LENGTH_LONG
                ).show()
            }
        }


    // ========================================================
    // FILTRE
    // ========================================================

    val filteredBooks =
        remember(
            books,
            selectedStatusTab,
            searchQuery
        ) {

            books.filter { book ->

                val statusOk =
                    selectedStatusTab == null ||
                            book.status ==
                            selectedStatusTab

                val searchOk =
                    searchQuery.isBlank() ||
                            book.title.contains(
                                searchQuery,
                                true
                            ) ||
                            book.authors.contains(
                                searchQuery,
                                true
                            ) ||
                            book.series.contains(
                                searchQuery,
                                true
                            ) ||
                            book.isbn.contains(
                                searchQuery,
                                true
                            )

                statusOk && searchOk
            }
        }


    // ========================================================
    // TOAST API
    // ========================================================

    LaunchedEffect(uiState) {

        when (val state = uiState) {

            is UiState.Success -> {

                Toast.makeText(
                    context,
                    "Ajouté : ${state.book.title}",
                    Toast.LENGTH_SHORT
                ).show()

                viewModel.resetState()
            }

            is UiState.Error -> {

                Toast.makeText(
                    context,
                    state.message,
                    Toast.LENGTH_LONG
                ).show()

                viewModel.resetState()
            }

            else -> {}
        }
    }


    // ========================================================
    // UI
    // ========================================================

    Scaffold(

        topBar = {

            Column(
                Modifier.background(
                    AppTheme.BackgroundDark
                )
            ) {

                TopAppBar(

                    title = {

                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Text(
                                "Bibliothèque",
                                fontWeight =
                                    FontWeight.Bold,
                                fontSize = 22.sp,
                                color =
                                    AppTheme.TextPrimary
                            )

                            Spacer(
                                Modifier.width(8.dp)
                            )

                            Text(
                                "(${books.size})",
                                color =
                                    AppTheme.TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    },

                    colors =
                        TopAppBarDefaults
                            .topAppBarColors(
                                containerColor =
                                    AppTheme.BackgroundDark
                            ),

                    actions = {

                        IconButton(
                            onClick = {
                                showStats = true
                            }
                        ) {

                            Icon(
                                Icons.Default.BarChart,
                                "Statistiques",
                                tint =
                                    AppTheme.AccentAmber
                            )
                        }

                        IconButton(
                            onClick = {
                                isGridView =
                                    !isGridView
                            }
                        ) {

                            Icon(
                                if (isGridView)
                                    Icons.Default.List
                                else
                                    Icons.Default.GridView,
                                "Changer la vue"
                            )
                        }

                        IconButton(
                            onClick = {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/csv",
                                        "text/plain",
                                        "*/*"
                                    )
                                )
                            }
                        ) {

                            Icon(
                                Icons.Outlined.FileUpload,
                                "Importer",
                                tint =
                                    AppTheme.AccentTeal
                            )
                        }

                        IconButton(
                            onClick = {
                                exportJsonLauncher.launch(
                                    "ma_bibliotheque.json"
                                )
                            }
                        ) {

                            Icon(
                                Icons.Outlined.FileDownload,
                                "Exporter JSON",
                                tint =
                                    AppTheme.PrimaryEmerald
                            )
                        }

                        IconButton(
                            onClick = {
                                exportCsvLauncher.launch(
                                    "ma_bibliotheque.csv"
                                )
                            }
                        ) {

                            Icon(
                                Icons.Default.TableView,
                                "Exporter CSV",
                                tint =
                                    AppTheme.AccentPurple
                            )
                        }
                    }
                )


                OutlinedTextField(

                    value = searchQuery,

                    onValueChange = {
                        searchQuery = it
                    },

                    placeholder = {
                        Text(
                            "Rechercher titre, auteur, ISBN...",
                            color =
                                AppTheme.TextTertiary
                        )
                    },

                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            null
                        )
                    },

                    trailingIcon = {

                        if (
                            searchQuery.isNotEmpty()
                        ) {

                            IconButton(
                                onClick = {
                                    searchQuery = ""
                                }
                            ) {

                                Icon(
                                    Icons.Default.Clear,
                                    null
                                )
                            }
                        }
                    },

                    singleLine = true,

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 16.dp,
                                vertical = 4.dp
                            ),

                    shape =
                        RoundedCornerShape(12.dp),

                    colors =
                        OutlinedTextFieldDefaults
                            .colors(
                                focusedBorderColor =
                                    AppTheme.PrimaryEmerald,
                                unfocusedBorderColor =
                                    AppTheme.CardBorder,
                                focusedContainerColor =
                                    AppTheme.SurfaceDark,
                                unfocusedContainerColor =
                                    AppTheme.SurfaceDark,
                                focusedTextColor =
                                    AppTheme.TextPrimary,
                                unfocusedTextColor =
                                    AppTheme.TextPrimary
                            )
                )


                ScrollableTabRow(

                    selectedTabIndex =
                        if (
                            selectedStatusTab == null
                        ) {
                            0
                        } else {
                            selectedStatusTab!!
                                .ordinal + 1
                        },

                    edgePadding = 16.dp,

                    containerColor =
                        AppTheme.BackgroundDark,

                    divider = {}
                ) {

                    Tab(
                        selected =
                            selectedStatusTab == null,
                        onClick = {
                            selectedStatusTab = null
                        },
                        text = {
                            Text(
                                "Tous (${books.size})"
                            )
                        }
                    )

                    ReadStatus.values()
                        .forEach { status ->

                            val count =
                                books.count {
                                    it.status == status
                                }

                            Tab(
                                selected =
                                    selectedStatusTab ==
                                            status,
                                onClick = {
                                    selectedStatusTab =
                                        status
                                },
                                text = {
                                    Text(
                                        "${status.label} ($count)"
                                    )
                                }
                            )
                        }
                }
            }
        },


        floatingActionButton = {

            Column(
                horizontalAlignment =
                    Alignment.End
            ) {

                SmallFloatingActionButton(

                    onClick = {
                        showManualAdd = true
                    },

                    containerColor =
                        AppTheme.SurfaceDark,

                    contentColor =
                        AppTheme.AccentPurple,

                    modifier =
                        Modifier.padding(
                            bottom = 8.dp
                        )
                ) {

                    Icon(
                        Icons.Default.Edit,
                        "Ajout manuel"
                    )
                }

                FloatingActionButton(

                    onClick = {

                        val permission =
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            )

                        if (
                            permission ==
                            PackageManager.PERMISSION_GRANTED
                        ) {

                            showScanner = true

                        } else {

                            cameraPermissionLauncher.launch(
                                Manifest.permission.CAMERA
                            )
                        }
                    },

                    containerColor =
                        AppTheme.PrimaryEmerald
                ) {

                    Icon(
                        Icons.Default.QrCodeScanner,
                        "Scanner ISBN"
                    )
                }
            }
        }

    ) { padding ->

        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            if (
                uiState is UiState.Loading
            ) {

                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color =
                        AppTheme.PrimaryEmerald
                )
            }

            if (
                filteredBooks.isEmpty()
            ) {

                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        if (
                            searchQuery.isNotBlank()
                        ) {
                            "Aucun livre trouvé"
                        } else {
                            "Aucun livre dans cette catégorie"
                        },
                        color =
                            AppTheme.TextTertiary
                    )
                }

            } else if (isGridView) {

                LazyVerticalGrid(

                    columns =
                        GridCells.Fixed(3),

                    contentPadding =
                        PaddingValues(12.dp),

                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp),

                    verticalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {

                    items(
                        filteredBooks,
                        key = { it.id }
                    ) { book ->

                        BookGridItem(
                            book
                        ) {
                            selectedBook =
                                book
                        }
                    }
                }

            } else {

                LazyColumn(

                    contentPadding =
                        PaddingValues(12.dp),

                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    items(
                        filteredBooks,
                        key = { it.id }
                    ) { book ->

                        BookListItem(
                            book
                        ) {

                            selectedBook =
                                book
                        }
                    }
                }
            }
        }
    }


    // ========================================================
    // DIALOGUES
    // ========================================================

    if (showScanner) {

        BarcodeScannerDialog(

            onDismiss = {
                showScanner = false
            },

            onBarcodeScanned = { isbn ->

                showScanner = false

                viewModel.searchAndAddBook(
                    isbn
                )
            }
        )
    }

    if (showManualAdd) {

        ManualAddBookDialog(

            onDismiss = {
                showManualAdd = false
            },

            onAdd = {

                viewModel.addBookDirectly(it)

                showManualAdd = false
            }
        )
    }

    if (showStats) {

        StatsDialog(
            stats = stats,
            onDismiss = {
                showStats = false
            }
        )
    }

    selectedBook?.let { book ->

        BookDetailDialog(

            book = book,

            onDismiss = {
                selectedBook = null
            },

            onUpdate = {

                viewModel.updateBook(it)

                selectedBook = it
            },

            onDelete = {

                viewModel.removeBook(book)

                selectedBook = null
            }
        )
    }
}


// ============================================================
// 10. LISTE
// ============================================================

@Composable
fun BookListItem(
    book: Book,
    onClick: () -> Unit
) {

    val statusColor =
        when (book.status) {

            ReadStatus.READ ->
                AppTheme.PrimaryEmerald

            ReadStatus.READING ->
                AppTheme.AccentAmber

            ReadStatus.UNREAD ->
                AppTheme.AccentPurple

            ReadStatus.WISHLIST ->
                AppTheme.AccentTeal
        }

    Card(
        Modifier
            .fillMaxWidth()
            .clickable {
                onClick()
            },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    AppTheme.CardBackground
            ),
        shape =
            RoundedCornerShape(12.dp)
    ) {

        Row(
            Modifier.padding(10.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Box(
                Modifier
                    .size(50.dp, 75.dp)
                    .clip(
                        RoundedCornerShape(6.dp)
                    )
                    .background(
                        AppTheme.SurfaceDark
                    )
            ) {

                if (
                    book.coverUrl.isNotBlank()
                ) {

                    AsyncImage(
                        model =
                            book.coverUrl,
                        contentDescription =
                            book.title,
                        modifier =
                            Modifier.fillMaxSize(),
                        contentScale =
                            ContentScale.Crop
                    )

                } else {

                    Icon(
                        Icons.Default.MenuBook,
                        null,
                        tint =
                            AppTheme.TextTertiary,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(15.dp)
                    )
                }
            }

            Spacer(
                Modifier.width(12.dp)
            )

            Column(
                Modifier.weight(1f)
            ) {

                Text(
                    book.title,
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        AppTheme.TextPrimary,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis
                )

                Text(
                    book.authors,
                    color =
                        AppTheme.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis
                )

                if (
                    book.series.isNotBlank()
                ) {

                    Text(
                        book.series,
                        color =
                            AppTheme.AccentPurple,
                        fontSize = 12.sp
                    )
                }

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Box(
                        Modifier
                            .background(
                                statusColor.copy(
                                    alpha = 0.2f
                                ),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(
                                horizontal = 6.dp,
                                vertical = 2.dp
                            )
                    ) {

                        Text(
                            book.status.label,
                            color =
                                statusColor,
                            fontSize = 10.sp,
                            fontWeight =
                                FontWeight.Bold
                        )
                    }

                    if (
                        book.isBorrowed
                    ) {

                        Spacer(
                            Modifier.width(6.dp)
                        )

                        Text(
                            "Prêté : ${book.borrowerName}",
                            color =
                                AppTheme.AccentRose,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (
                book.rating > 0
            ) {

                Icon(
                    Icons.Default.Star,
                    null,
                    tint =
                        AppTheme.AccentAmber,
                    modifier =
                        Modifier.size(17.dp)
                )
            }
        }
    }
}


// ============================================================
// 11. GRILLE
// ============================================================

fun extractVolumeNumber(
    title: String
): Int {

    val regex =
        Regex(
            """(?i)(?:tome|t|vol|volume|\bT|\bV)\s*[:.-]?\s*(\d+)"""
        )

    return regex
        .find(title)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 999
}

@Composable
fun BookGridItem(
    book: Book,
    onClick: () -> Unit
) {

    val volume =
        remember(book.title) {
            extractVolumeNumber(
                book.title
            )
        }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                onClick()
            }
    ) {

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(
                    RoundedCornerShape(8.dp)
                )
                .background(
                    AppTheme.CardBackground
                )
                .border(
                    1.dp,
                    AppTheme.CardBorder,
                    RoundedCornerShape(8.dp)
                )
        ) {

            if (
                book.coverUrl.isNotBlank()
            ) {

                AsyncImage(
                    model =
                        book.coverUrl,
                    contentDescription =
                        book.title,
                    modifier =
                        Modifier.fillMaxSize(),
                    contentScale =
                        ContentScale.Crop
                )

            } else {

                Icon(
                    Icons.Default.MenuBook,
                    null,
                    tint =
                        AppTheme.TextTertiary,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(35.dp)
                )
            }

            if (
                volume != 999
            ) {

                Box(
                    Modifier
                        .align(
                            Alignment.BottomStart
                        )
                        .background(
                            AppTheme.AccentPurple,
                            RoundedCornerShape(
                                topEnd = 6.dp,
                                bottomStart = 8.dp
                            )
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp
                        )
                ) {

                    Text(
                        "$volume",
                        color = Color.White,
                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }

        Text(
            book.title,
            color =
                AppTheme.TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow =
                TextOverflow.Ellipsis
        )
    }
}


// ============================================================
// 12. STATISTIQUES
// ============================================================

@Composable
fun StatsDialog(
    stats: ReadingStats,
    goal: ReadingGoal = ReadingGoal(),
    onDismiss: () -> Unit
) {

    val progress =
        if (goal.targetBooks > 0) {

            (
                stats.booksRead.toFloat() /
                        goal.targetBooks.toFloat()
                ).coerceIn(0f, 1f)

        } else {
            0f
        }

    Dialog(
        onDismissRequest =
            onDismiss
    ) {

        Card(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        AppTheme.SurfaceDark
                ),
            shape =
                RoundedCornerShape(20.dp)
        ) {

            Column(
                Modifier
                    .padding(20.dp)
                    .verticalScroll(
                        rememberScrollState()
                    )
            ) {

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {

                    Text(
                        "Statistiques ${goal.year}",
                        fontWeight =
                            FontWeight.Bold,
                        fontSize = 20.sp,
                        color =
                            AppTheme.TextPrimary
                    )

                    IconButton(
                        onClick =
                            onDismiss
                    ) {

                        Icon(
                            Icons.Default.Close,
                            "Fermer"
                        )
                    }
                }

                Text(
                    "${stats.booksRead} / ${goal.targetBooks} livres",
                    color =
                        AppTheme.AccentTeal,
                    fontWeight =
                        FontWeight.Bold
                )

                LinearProgressIndicator(
                    progress = {
                        progress
                    },
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical = 10.dp
                        ),
                    color =
                        AppTheme.PrimaryEmerald
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {

                    StatCard(
                        Modifier.weight(1f),
                        "Pages lues",
                        stats.totalPagesRead.toString(),
                        Icons.Default.MenuBook,
                        AppTheme.AccentPurple
                    )

                    StatCard(
                        Modifier.weight(1f),
                        "Note moyenne",
                        if (
                            stats.averageRating > 0
                        ) {
                            "★ ${
                                String.format(
                                    Locale.FRANCE,
                                    "%.1f",
                                    stats.averageRating
                                )
                            }"
                        } else {
                            "-"
                        },
                        Icons.Default.Star,
                        AppTheme.AccentAmber
                    )
                }

                Spacer(
                    Modifier.height(10.dp)
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {

                    StatCard(
                        Modifier.weight(1f),
                        "Livres prêtés",
                        stats.borrowedCount.toString(),
                        Icons.Default.Outbox,
                        AppTheme.AccentRose
                    )

                    StatCard(
                        Modifier.weight(1f),
                        "Envies",
                        stats.wishlistCount.toString(),
                        Icons.Default.Bookmark,
                        AppTheme.AccentTeal
                    )
                }

                Spacer(
                    Modifier.height(15.dp)
                )

                Text(
                    "Auteur le plus présent",
                    color =
                        AppTheme.TextSecondary
                )

                Text(
                    stats.topAuthor,
                    color =
                        AppTheme.TextPrimary,
                    fontWeight =
                        FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun StatCard(
    modifier: Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    tint: Color
) {

    Card(
        modifier,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    AppTheme.CardBackground
            )
    ) {

        Column(
            Modifier.padding(12.dp)
        ) {

            Icon(
                icon,
                null,
                tint = tint
            )

            Text(
                value,
                fontSize = 18.sp,
                fontWeight =
                    FontWeight.Bold,
                color =
                    AppTheme.TextPrimary
            )

            Text(
                title,
                fontSize = 11.sp,
                color =
                    AppTheme.TextSecondary
            )
        }
    }
}


// ============================================================
// 13. DÉTAIL DU LIVRE
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailDialog(
    book: Book,
    onDismiss: () -> Unit,
    onUpdate: (Book) -> Unit,
    onDelete: () -> Unit
) {

    var currentPage by
            remember {
                mutableStateOf(
                    book.currentPage.toString()
                )
            }

    var totalPages by
            remember {
                mutableStateOf(
                    book.totalPages.toString()
                )
            }

    var notes by
            remember {
                mutableStateOf(
                    book.personalNotes
                )
            }

    var rating by
            remember {
                mutableStateOf(
                    book.rating
                )
            }

    var status by
            remember {
                mutableStateOf(
                    book.status
                )
            }

    var borrowed by
            remember {
                mutableStateOf(
                    book.isBorrowed
                )
            }

    var borrower by
            remember {
                mutableStateOf(
                    book.borrowerName
                )
            }

    Dialog(
        onDismissRequest =
            onDismiss
    ) {

        Card(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        AppTheme.SurfaceDark
                )
        ) {

            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(
                        rememberScrollState()
                    )
            ) {

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {

                    Text(
                        "Détails du livre",
                        fontWeight =
                            FontWeight.Bold,
                        fontSize = 19.sp,
                        color =
                            AppTheme.TextPrimary
                    )

                    IconButton(
                        onClick =
                            onDelete
                    ) {

                        Icon(
                            Icons.Default.Delete,
                            "Supprimer",
                            tint =
                                AppTheme.AccentRose
                        )
                    }
                }

                Text(
                    book.title,
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        AppTheme.TextPrimary
                )

                Text(
                    book.authors,
                    color =
                        AppTheme.TextSecondary
                )

                if (
                    book.isbn.isNotBlank()
                ) {

                    Text(
                        "ISBN : ${book.isbn}",
                        color =
                            AppTheme.TextTertiary,
                        fontSize = 12.sp
                    )
                }

                Spacer(
                    Modifier.height(15.dp)
                )

                Text(
                    "Statut",
                    color =
                        AppTheme.TextSecondary
                )

                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(4.dp)
                ) {

                    ReadStatus.values()
                        .forEach { item ->

                            FilterChip(
                                selected =
                                    status == item,
                                onClick = {
                                    status = item
                                },
                                label = {
                                    Text(
                                        item.label,
                                        fontSize = 10.sp
                                    )
                                }
                            )
                        }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    OutlinedTextField(
                        currentPage,
                        {
                            currentPage = it
                        },
                        label = {
                            Text("Page actuelle")
                        },
                        modifier =
                            Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        totalPages,
                        {
                            totalPages = it
                        },
                        label = {
                            Text("Total pages")
                        },
                        modifier =
                            Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Text(
                    "Note : ${rating.toInt()}/5",
                    color =
                        AppTheme.TextSecondary
                )

                Row {

                    (1..5).forEach { star ->

                        IconButton(
                            onClick = {
                                rating =
                                    star.toFloat()
                            }
                        ) {

                            Icon(
                                if (
                                    star <= rating
                                ) {
                                    Icons.Default.Star
                                } else {
                                    Icons.Outlined.Star
                                },
                                null,
                                tint =
                                    if (
                                        star <= rating
                                    ) {
                                        AppTheme.AccentAmber
                                    } else {
                                        AppTheme.TextTertiary
                                    }
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Checkbox(
                        checked = borrowed,
                        onCheckedChange = {
                            borrowed = it
                        }
                    )

                    Text(
                        "Livre prêté",
                        color =
                            AppTheme.TextPrimary
                    )
                }

                if (borrowed) {

                    OutlinedTextField(
                        borrower,
                        {
                            borrower = it
                        },
                        label = {
                            Text("Emprunteur")
                        },
                        modifier =
                            Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    notes,
                    {
                        notes = it
                    },
                    label = {
                        Text("Notes personnelles")
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    maxLines = 4
                )

                Spacer(
                    Modifier.height(15.dp)
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.End
                ) {

                    TextButton(
                        onClick =
                            onDismiss
                    ) {

                        Text("Annuler")
                    }

                    Button(
                        onClick = {

                            onUpdate(
                                book.copy(
                                    currentPage =
                                        currentPage
                                            .toIntOrNull()
                                            ?: 0,

                                    totalPages =
                                        totalPages
                                            .toIntOrNull()
                                            ?: 0,

                                    rating =
                                        rating.coerceIn(
                                            0f,
                                            5f
                                        ),

                                    status =
                                        status,

                                    personalNotes =
                                        notes,

                                    isBorrowed =
                                        borrowed,

                                    borrowerName =
                                        if (borrowed) {
                                            borrower
                                        } else {
                                            ""
                                        }
                                )
                            )

                            onDismiss()
                        },

                        colors =
                            ButtonDefaults
                                .buttonColors(
                                    containerColor =
                                        AppTheme.PrimaryEmerald
                                )
                    ) {

                        Text("Enregistrer")
                    }
                }
            }
        }
    }
}


// ============================================================
// 14. AJOUT MANUEL
// ============================================================

@Composable
fun ManualAddBookDialog(
    onDismiss: () -> Unit,
    onAdd: (Book) -> Unit
) {

    var title by
            remember {
                mutableStateOf("")
            }

    var author by
            remember {
                mutableStateOf("")
            }

    var series by
            remember {
                mutableStateOf("")
            }

    var pages by
            remember {
                mutableStateOf("")
            }

    Dialog(
        onDismissRequest =
            onDismiss
    ) {

        Card(
            Modifier.padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        AppTheme.SurfaceDark
                )
        ) {

            Column(
                Modifier.padding(16.dp)
            ) {

                Text(
                    "Ajouter un livre",
                    fontWeight =
                        FontWeight.Bold,
                    fontSize = 18.sp,
                    color =
                        AppTheme.TextPrimary
                )

                OutlinedTextField(
                    title,
                    {
                        title = it
                    },
                    label = {
                        Text("Titre *")
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    author,
                    {
                        author = it
                    },
                    label = {
                        Text("Auteur *")
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    series,
                    {
                        series = it
                    },
                    label = {
                        Text("Série / Tome")
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    pages,
                    {
                        pages = it
                    },
                    label = {
                        Text("Nombre de pages")
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                )

                Spacer(
                    Modifier.height(15.dp)
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.End
                ) {

                    TextButton(
                        onClick =
                            onDismiss
                    ) {

                        Text("Annuler")
                    }

                    Button(
                        onClick = {

                            if (
                                title.isNotBlank() &&
                                author.isNotBlank()
                            ) {

                                onAdd(
                                    Book(
                                        title = title,
                                        authors = author,
                                        series = series,
                                        totalPages =
                                            pages.toIntOrNull()
                                                ?: 0
                                    )
                                )
                            }
                        }
                    ) {

                        Text("Ajouter")
                    }
                }
            }
        }
    }
}


// ============================================================
// 15. SCANNER ISBN
// ============================================================

@Composable
fun BarcodeScannerDialog(
    onDismiss: () -> Unit,
    onBarcodeScanned: (String) -> Unit
) {

    val lifecycleOwner =
        LocalLifecycleOwner.current

    Dialog(
        onDismissRequest =
            onDismiss
    ) {

        Card(
            Modifier
                .fillMaxWidth()
                .height(400.dp),
            shape =
                RoundedCornerShape(16.dp)
        ) {

            Box(
                Modifier.fillMaxSize()
            ) {

                AndroidView(

                    factory = { ctx ->

                        val previewView =
                            PreviewView(ctx)

                        val executor =
                            Executors
                                .newSingleThreadExecutor()

                        val providerFuture =
                            ProcessCameraProvider
                                .getInstance(ctx)

                        providerFuture
                            .addListener({

                                val provider =
                                    providerFuture.get()

                                val preview =
                                    Preview.Builder()
                                        .build()

                                preview.setSurfaceProvider(
                                    previewView
                                        .surfaceProvider
                                )

                                val scanner =
                                    BarcodeScanning
                                        .getClient(
                                            BarcodeScannerOptions
                                                .Builder()
                                                .setBarcodeFormats(
                                                    Barcode.FORMAT_EAN_13,
                                                    Barcode.FORMAT_EAN_8
                                                )
                                                .build()
                                        )

                                val analysis =
                                    ImageAnalysis
                                        .Builder()
                                        .setBackpressureStrategy(
                                            ImageAnalysis
                                                .STRATEGY_KEEP_ONLY_LATEST
                                        )
                                        .build()

                                analysis.setAnalyzer(
                                    executor
                                ) { imageProxy ->

                                    processImageProxy(
                                        scanner,
                                        imageProxy,
                                        onBarcodeScanned
                                    )
                                }

                                try {

                                    provider
                                        .unbindAll()

                                    provider
                                        .bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector
                                                .DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                        )

                                } catch (e: Exception) {

                                    Log.e(
                                        "Scanner",
                                        "Erreur caméra",
                                        e
                                    )
                                }

                            }, ContextCompat
                                .getMainExecutor(ctx))

                        previewView
                    },

                    modifier =
                        Modifier.fillMaxSize()
                )

                IconButton(
                    onClick =
                        onDismiss,
                    modifier =
                        Modifier
                            .align(
                                Alignment.TopEnd
                            )
                            .padding(8.dp)
                ) {

                    Icon(
                        Icons.Default.Close,
                        "Fermer",
                        tint = Color.White
                    )
                }
            }
        }
    }
}


// ============================================================
// 16. TRAITEMENT CODE-BARRES
// ============================================================

@androidx.annotation.OptIn(
    androidx.camera.core.ExperimentalGetImage::class
)
private fun processImageProxy(
    scanner: BarcodeScanner,
    imageProxy: ImageProxy,
    onBarcodeScanned: (String) -> Unit
) {

    val mediaImage =
        imageProxy.image

    if (mediaImage == null) {

        imageProxy.close()

        return
    }

    val image =
        InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

    scanner.process(image)

        .addOnSuccessListener { barcodes ->

            barcodes.forEach { barcode ->

                barcode.rawValue?.let {
                    onBarcodeScanned(it)
                }
            }
        }

        .addOnCompleteListener {

            imageProxy.close()
        }
}


// ============================================================
// 17. NOM DU FICHIER
// ============================================================

private fun getFileName(
    context: Context,
    uri: Uri
): String {

    var result: String? = null

    context.contentResolver
        .query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME
            ),
            null,
            null,
            null
        )
        ?.use { cursor ->

            if (cursor.moveToFirst()) {

                val index =
                    cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                    )

                if (index >= 0) {

                    result =
                        cursor.getString(index)
                }
            }
        }

    return result ?: ""
}
