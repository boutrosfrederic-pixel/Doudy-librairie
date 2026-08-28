package com.doudy.librairie

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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

class BookAdapter(var books: List<Book>, val onDelete: (Book)->Unit, val onEdit: (Book)->Unit) : RecyclerView.Adapter<BookAdapter.VH>() {
    class VH(val tv: TextView): RecyclerView.ViewHolder(tv)
    override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int): VH {
        val tv = TextView(p.context).apply {
            setPadding(36,40,36,40); textSize=16f
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        }
        return VH(tv)
    }
    override fun getItemCount()=books.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val b=books[pos]
        h.tv.text = "📚 ${b.title}\n${if(b.author.isNotEmpty()) b.author+"\n" else ""}ISBN: ${b.isbn}"
        h.tv.setOnLongClickListener { onDelete(b); true }
        h.tv.setOnClickListener { onEdit(b) }
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var db: AppDatabase
    private lateinit var adapter: BookAdapter
    private lateinit var titleView: TextView

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if(result.contents!=null) fetchAndSave(result.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        titleView=findViewById(R.id.title)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "doudy-v10").build()

        adapter = BookAdapter(emptyList(),
            onDelete = { book ->
                AlertDialog.Builder(this).setTitle("Supprimer ${book.title}?")
               .setPositiveButton("Oui"){_,_-> lifecycleScope.launch{ db.bookDao().delete(book); loadBooks()} }
               .setNegativeButton("Non",null).show()
            },
            onEdit = { book -> showEditDialog(book) }
        )

        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager=LinearLayoutManager(this@MainActivity)
            adapter=this@MainActivity.adapter
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            barcodeLauncher.launch(ScanOptions().apply{
                setDesiredBarcodeFormats(ScanOptions.EAN_13, ScanOptions.EAN_8)
                setPrompt("Scanne")
                setBeepEnabled(true)
                setOrientationLocked(false)
            })
        }
        loadBooks()
    }

    private fun loadBooks(){
        lifecycleScope.launch{
            val list = withContext(Dispatchers.IO){ db.bookDao().getAll() }
            adapter.books=list
            adapter.notifyDataSetChanged()
            titleView.text = if(list.isEmpty()) "Aucun livre" else "Ma Librairie - ${list.size} livres (clic=éditer, long=suppr)"
        }
    }

    private fun showEditDialog(book: Book){
        val edit = EditText(this).apply{ setText(book.title) }
        AlertDialog.Builder(this).setTitle("Titre du livre").setView(edit)
       .setPositiveButton("Sauver"){_,_->
            val newTitle = edit.text.toString()
            lifecycleScope.launch(Dispatchers.IO){
                db.bookDao().insert(book.copy(title=newTitle))
                withContext(Dispatchers.Main){ loadBooks() }
            }
        }.show()
    }

    private fun fetchAndSave(isbn: String){
        lifecycleScope.launch(Dispatchers.IO){
            var title="Livre $isbn"
            var author=""
            var found=false

            // 1. Google Books
            try{
                val js = URL("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn").readText()
                val json=JSONObject(js)
                if(json.optInt("totalItems",0)>0){
                    val vi=json.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo")
                    title=vi.optString("title",title)
                    val a=vi.optJSONArray("authors")
                    if(a!=null && a.length()>0) author=a.getString(0)
                    found=true
                }
            }catch(_: Exception){}

            // 2. OpenLibrary fallback pour les 979
            if(!found){
                try{
                    val js2 = URL("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data").readText()
                    val json2=JSONObject(js2)
                    if(json2.has("ISBN:$isbn")){
                        val data=json2.getJSONObject("ISBN:$isbn")
                        title=data.optString("title",title)
                        val authors=data.optJSONArray("authors")
                        if(authors!=null && authors.length()>0) author=authors.getJSONObject(0).optString("name","")
                        found=true
                    }
                }catch(_: Exception){}
            }

            val book=Book(isbn=isbn, title=title, author=author)
            db.bookDao().insert(book)
            withContext(Dispatchers.Main){
                loadBooks()
                if(!found){
                    Toast.makeText(this@MainActivity,"Titre non trouvé, clique dessus pour l'éditer",Toast.LENGTH_LONG).show()
                    showEditDialog(book)
                } else {
                    Toast.makeText(this@MainActivity,"Ajouté: $title",Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
