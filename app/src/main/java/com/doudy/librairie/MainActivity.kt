package com.doudy.librairie

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import coil.load
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Entity data class Book(@PrimaryKey val isbn: String, val title: String, val author: String="", val category: String="Roman", val cover: String="", val dateAdded: Long=System.currentTimeMillis())
@Dao interface BookDao{ @Query("SELECT * FROM Book ORDER BY dateAdded DESC") suspend fun getAll(): List<Book>; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insert(b: Book); @Delete suspend fun delete(b: Book) }
@Database(entities=[Book::class], version=1, exportSchema=false) abstract class AppDatabase: RoomDatabase(){ abstract fun bookDao(): BookDao }

class MainActivity: AppCompatActivity(){
    private lateinit var db: AppDatabase
    private lateinit var adapter: BookAdapter
    private var allBooks: List<Book> = emptyList()
    private var isMultiScan = true
    private var currentFilter = "Tous"

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()){ if(it!=null) importCsvFile(it) }
    private val scanLauncher = registerForActivityResult(ScanContract()){ r-> if(r.contents!=null){ Toast.makeText(this,"Scanné: ${r.contents}",Toast.LENGTH_SHORT).show(); fetchAndSave(r.contents){ if(isMultiScan){ lifecycleScope.launch{ delay(1200); launchScan() } } } } }

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "doudy-v16").fallbackToDestructiveMigration().build()
        adapter = BookAdapter(emptyList())
        findViewById<RecyclerView>(R.id.recycler).layoutManager=LinearLayoutManager(this)
        findViewById<RecyclerView>(R.id.recycler).adapter=adapter

        findViewById<View>(R.id.btnScan).setOnClickListener{ launchScan() }
        findViewById<View>(R.id.btnExport)?.setOnClickListener{ exportCsv() }
        findViewById<View>(R.id.btnImport)?.setOnClickListener{ importLauncher.launch("text/*") }
        findViewById<View>(R.id.btnTheme)?.setOnClickListener{ Toast.makeText(this,"Thèmes bientôt!",Toast.LENGTH_SHORT).show() }
        findViewById<Switch>(R.id.switchRafale)?.setOnCheckedChangeListener{ _, c-> isMultiScan=c }
        findViewById<EditText>(R.id.search).addTextChangedListener{ applyFilters(it.toString()) }
        findViewById<Spinner>(R.id.spinnerCat)?.let{ sp ->
            sp.adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Tous","Roman","Manga","BD","Cuisine","Jeunesse","Autre"))
            sp.onItemSelectedListener=object: AdapterView.OnItemSelectedListener{
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long){ currentFilter=p?.getItemAtPosition(pos).toString(); applyFilters(findViewById<EditText>(R.id.search).text.toString()) }
                override fun onNothingSelected(p: AdapterView<*>?){}
            }
        }
        load()
    }

    fun launchScan(){ val o=ScanOptions().apply{ setDesiredBarcodeFormats(ScanOptions.EAN_13); setPrompt("RAFALE ${allBooks.size} livres"); setBeepEnabled(true); setOrientationLocked(true); setCaptureActivity(MyCaptureActivity::class.java) }; scanLauncher.launch(o) }
    private fun applyFilters(q: String){ var list=allBooks; if(currentFilter!="Tous") list=list.filter{ it.category==currentFilter }; if(q.isNotEmpty()){ val s=q.lowercase(); list=list.filter{ it.title.lowercase().contains(s) || it.author.lowercase().contains(s) } }; adapter.books=list; adapter.notifyDataSetChanged(); findViewById<TextView>(R.id.title).text="📚 ${list.size} livres" }
    private fun load(){ lifecycleScope.launch{ allBooks=withContext(Dispatchers.IO){ db.bookDao().getAll() }; applyFilters(findViewById<EditText>(R.id.search).text.toString()) } }
    fun deleteBook(b: Book){ AlertDialog.Builder(this).setTitle("Supprimer ${b.title}?").setPositiveButton("Oui"){_,_-> lifecycleScope.launch{ withContext(Dispatchers.IO){ db.bookDao().delete(b) }; load() }}.setNegativeButton("Non",null).show() }
    fun editBook(b: Book){ val lay=LinearLayout(this).apply{ orientation=1; setPadding(32,16,32,16) }; val et1=EditText(this).apply{ setText(b.title) }; val et2=EditText(this).apply{ setText(b.author) }; lay.addView(et1); lay.addView(et2); AlertDialog.Builder(this).setTitle("Editer").setView(lay).setPositiveButton("Sauver"){_,_-> lifecycleScope.launch(Dispatchers.IO){ db.bookDao().insert(b.copy(title=et1.text.toString(), author=et2.text.toString())); withContext(Dispatchers.Main){ load() } }}.show() }

    private fun httpGet(u: String): String? = try{ (URL(u).openConnection() as HttpURLConnection).apply{ setRequestProperty("User-Agent","Mozilla/5.0"); connectTimeout=15000; readTimeout=15000 }.inputStream.bufferedReader().readText() }catch(_:Exception){ null }

    // FONCTION CORRIGEE - COUVERTURES + TITRES
    private fun fetchAndSave(isbn: String, onDone:()->Unit={}){
        lifecycleScope.launch(Dispatchers.IO){
            var title=""; var author=""; var cover=""; var found=false
            try{
                val gb = httpGet("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn")
                if(gb!=null){
                    val root = JSONObject(gb)
                    if(root.optInt("totalItems",0) > 0){
                        val item = root.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo")
                        title = item.optString("title","")
                        author = item.optJSONArray("authors")?.optString(0) ?: ""
                        val img = item.optJSONObject("imageLinks")
                        cover = img?.optString("thumbnail","") ?: ""
                        cover = cover.replace("http://","https://")
                        if(title.isNotEmpty()) found = true
                    }
                }
            }catch(_:Exception){}
            if(!found){
                try{
                    val b1 = httpGet("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data")
                    if(b1!=null){
                        val j=JSONObject(b1)
                        if(j.has("ISBN:$isbn")){
                            val d=j.getJSONObject("ISBN:$isbn")
                            title=d.optString("title","")
                            author=d.optJSONArray("authors")?.optJSONObject(0)?.optString("name","")?:""
                            cover="https://covers.openlibrary.org/b/isbn/$isbn-L.jpg"
                            if(title.isNotEmpty()) found=true
                        }
                    }
                }catch(_:Exception){}
            }
            if(cover.isEmpty()) cover="https://covers.openlibrary.org/b/isbn/$isbn-L.jpg"
            if(title.isEmpty()) title="Livre $isbn"
            val book=Book(isbn, title, author, cover=cover)
            db.bookDao().insert(book)
            withContext(D
