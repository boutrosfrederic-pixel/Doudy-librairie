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
    private val scanLauncher = registerForActivityResult(ScanContract()){ r-> if(r.contents!=null){ fetchAndSave(r.contents){ if(isMultiScan){ lifecycleScope.launch{ delay(1200); launchScan() } } } } }

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
        findViewById<EditText>(R.id.search).addTextChangedListener{ applyFilters(it.toString()) }
        load()
    }

    fun launchScan(){ val o=ScanOptions().apply{ setDesiredBarcodeFormats(ScanOptions.EAN_13); setPrompt("RAFALE ${allBooks.size}"); setBeepEnabled(true); setOrientationLocked(true); setCaptureActivity(MyCaptureActivity::class.java) }; scanLauncher.launch(o) }
    private fun applyFilters(q: String){ var list=allBooks; if(q.isNotEmpty()){ val s=q.lowercase(); list=list.filter{ it.title.lowercase().contains(s) || it.author.lowercase().contains(s) } }; adapter.books=list; adapter.notifyDataSetChanged(); findViewById<TextView>(R.id.title).text="📚 ${list.size} livres" }
    private fun load(){ lifecycleScope.launch{ allBooks=withContext(Dispatchers.IO){ db.bookDao().getAll() }; applyFilters("") } }
    fun deleteBook(b: Book){ AlertDialog.Builder(this).setTitle("Supprimer ${b.title}?").setPositiveButton("Oui"){_,_-> lifecycleScope.launch{ withContext(Dispatchers.IO){ db.bookDao().delete(b) }; load() }}.setNegativeButton("Non",null).show() }
    fun editBook(b: Book){ val lay=LinearLayout(this).apply{ orientation=1; setPadding(32,16,32,16) }; val et1=EditText(this).apply{ setText(b.title) }; val et2=EditText(this).apply{ setText(b.author) }; lay.addView(et1); lay.addView(et2); AlertDialog.Builder(this).setTitle("Editer").setView(lay).setPositiveButton("Sauver"){_,_-> lifecycleScope.launch(Dispatchers.IO){ db.bookDao().insert(b.copy(title=et1.text.toString(), author=et2.text.toString())); withContext(Dispatchers.Main){ load() } }}.show() }
    private fun httpGet(u: String): String?{ return try{ val c=URL(u).openConnection() as HttpURLConnection; c.setRequestProperty("User-Agent","Mozilla/5.0"); c.connectTimeout=15000; c.readTimeout=15000; c.inputStream.bufferedReader().readText() }catch(e:Exception){ null } }

    private fun fetchAndSave(isbn: String, onDone:()->Unit={}){
        lifecycleScope.launch(Dispatchers.IO){
            var title=""; var author=""; var cover=""; var found=false
            try{
                val gb=httpGet("https://www.googleapis.com/books/v1/volumes?q=isbn:"+isbn)
                if(gb!=null){
                    val root=JSONObject(gb)
                    if(root.optInt("totalItems",0)>0){
                        val volume=root.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo")
                        title=volume.optString("title")
                        if(volume.has("authors")){ author=volume.getJSONArray("authors").optString(0) }
                        if(volume.has("imageLinks")){
                            val img=volume.getJSONObject("imageLinks")
                            cover=img.optString("thumbnail")
                            cover=cover.replace("http:","https:")
                        }
                        if(title.isNotEmpty()) found=true
                    }
                }
            }catch(e:Exception){}
            if(!found){
                try{
                    val b1=httpGet("https://openlibrary.org/api/books?bibkeys=ISBN:"+isbn+"&format=json&jscmd=data")
                    if(b1!=null){
                        val j=JSONObject(b1)
                        val key="ISBN:"+isbn
                        if(j.has(key)){
                            val d=j.getJSONObject(key)
                            title=d.optString("title")
                            if(d.has("authors")){ author=d.getJSONArray("authors").getJSONObject(0).optString("name") }
                            cover="https://covers.openlibrary.org/b/isbn/"+isbn+"-L.jpg"
                            if(title.isNotEmpty()) found=true
                        }
                    }
                }catch(e:Exception){}
            }
            if(cover.isEmpty()){ cover="https://covers.openlibrary.org/b/isbn/"+isbn+"-L.jpg" }
            if(title.isEmpty()){ title="Livre "+isbn }
            val book=Book(isbn, title, author, cover=cover)
            db.bookDao().insert(book)
            withContext(Dispatchers.Main){ load(); Toast.makeText(this@MainActivity,"Ajouté: "+title,Toast.LENGTH_SHORT).show(); onDone() }
        }
    }

    private fun importCsvFile(uri: Uri){ lifecycleScope.launch(Dispatchers.IO){ try{ val t=contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?: return@launch; var c=0; t.lines().drop(1).forEach{ if(it.isNotBlank()){ val p=it.split(","); if(p.size>=2){ db.bookDao().insert(Book(p[0].trim().replace("\"",""), p[1].trim().replace("\"",""))); c++ } } }; withContext(Dispatchers.Main){ load() } }catch(e:Exception){} } }
    private fun exportCsv(){ lifecycleScope.launch(Dispatchers.IO){ val csv=buildString{ appendLine("ISBN,Titre,Auteur"); allBooks.forEach{ appendLine(it.isbn+","+it.title+","+it.author) } }; try{ val file=File(getExternalFilesDir(null),"doudy.csv"); file.writeText(csv); withContext(Dispatchers.Main){ Toast.makeText(this@MainActivity,"Exporté",Toast.LENGTH_LONG).show() } }catch(e:Exception){} } }
}

class BookAdapter(var books: List<Book>): RecyclerView.Adapter<BookAdapter.VH>(){
    class VH(val view: View, val img: ImageView, val t1: TextView, val t2: TextView): RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH {
        val card=androidx.cardview.widget.CardView(p.context).apply{ radius=24f; cardElevation=8f; setCardBackgroundColor(0xFF2C2823.toInt()); layoutParams=RecyclerView.LayoutParams(-1,-2).apply{ setMargins(24,12,24,12) }; setContentPadding(16,16,16,16) }
        val row=LinearLayout(p.context).apply{ orientation=LinearLayout.HORIZONTAL }
        val img=ImageView(p.context).apply{ layoutParams=LinearLayout.LayoutParams(120,180).apply{ setMargins(0,0,20,0) }; scaleType=ImageView.ScaleType.CENTER_CROP }
        val col=LinearLayout(p.context).apply{ orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,-2,1f) }
        val t1=TextView(p.context).apply{ textSize=16f; setTextColor(0xFFE8D5B5.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) }
        val t2=TextView(p.context).apply{ textSize=13f; setTextColor(0xFF8C7A65.toInt()) }
        col.addView(t1); col.addView(t2); row.addView(img); row.addView(col); card.addView(row)
        return VH(card,img,t1,t2)
    }
    override fun getItemCount()=books.size
    override fun onBindViewHolder(h: VH, pos: Int){
        val b=books[pos]; h.t1.text=b.title; h.t2.text=b.author+" - "+b.isbn
        if(b.cover.isNotEmpty()){ h.img.load(b.cover) } else { h.img.setImageResource(android.R.drawable.ic_menu_gallery) }
        h.view.setOnClickListener{ (h.view.context as MainActivity).editBook(b) }
        h.view.setOnLongClickListener{ (h.view.context as MainActivity).deleteBook(b); true }
    }
}
