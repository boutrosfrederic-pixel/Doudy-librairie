package com.doudy.librairie

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

@Entity
data class Book(@PrimaryKey val isbn: String, val title: String, val author: String = "", val dateAdded: Long = System.currentTimeMillis())

@Dao
interface BookDao {
    @Query("SELECT * FROM Book ORDER BY dateAdded DESC")
    suspend fun getAll(): List<Book>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book)
    @Delete
    suspend fun delete(book: Book)
}

@Database(entities = [Book::class], version = 1)
abstract class AppDatabase : RoomDatabase() { abstract fun bookDao(): BookDao }

class BookAdapter(var books: List<Book>, val onDelete: (Book)->Unit) : RecyclerView.Adapter<BookAdapter.VH>() {
    class VH(val tv: TextView): RecyclerView.ViewHolder(tv)
    override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int): VH {
        val tv = TextView(p.context).apply {
            setPadding(32,40,32,40); textSize = 17f
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        }
        return VH(tv)
    }
    override fun getItemCount() = books.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val b = books[pos]
        val txt = if(b.author.isNotEmpty()) "${b.title}\n${b.author}\nISBN: ${b.isbn}" else "${b.title}\nISBN: ${b.isbn}"
        h.tv.text = "📚 $txt"
        h.tv.setOnLongClickListener { onDelete(b); true }
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var db: AppDatabase
    private lateinit var adapter: BookAdapter
    private lateinit var titleView: TextView

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents!= null) {
            val isbn = result.contents
            Toast.makeText(this, "ISBN: $isbn", Toast.LENGTH_SHORT).show()
            fetchAndSave(isbn)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleView = findViewById(R.id.title)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "doudy-db-v9-1").build()
        adapter = BookAdapter(emptyList()) { book ->
            AlertDialog.Builder(this).setTitle("Supprimer?").setMessage(book.title)
              .setPositiveButton("Oui") { _, _ -> lifecycleScope.launch { db.bookDao().delete(book); loadBooks() } }
              .setNegativeButton("Non", null).show()
        }

        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            barcodeLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.EAN_13, ScanOptions.EAN_8, ScanOptions.UPC_A)
                setPrompt("Scanne")
                setBeepEnabled(true)
                setOrientationLocked(false)
            })
        }
        loadBooks()
    }

    private fun loadBooks() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { db.bookDao().getAll() }
            adapter.books = list
            adapter.notifyDataSetChanged()
            titleView.text = if(list.isEmpty()) "Aucun livre - Scanne!" else "Ma Librairie - ${list.size} livres (long clic = suppr)"
        }
    }

    private fun fetchAndSave(isbn: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            var title = "Livre $isbn"
            var author = ""
            try {
                val jsonStr = URL("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn").readText()
                val json = JSONObject(jsonStr)
                if (json.optInt("totalItems",0) > 0) {
                    val volumeInfo = json.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo")
                    title = volumeInfo.optString("title", title)
                    val authors = volumeInfo.optJSONArray("authors")
                    if (authors!=null && authors.length()>0) author = authors.getString(0)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Hors ligne, ajout ISBN seul", Toast.LENGTH_SHORT).show() }
            }
            db.bookDao().insert(Book(isbn = isbn, title = title, author = author))
            withContext(Dispatchers.Main) { loadBooks() }
        }
    }
}
