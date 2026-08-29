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
import java.net.HttpURLConnection
import java.net.URL

@Entity
data class Book(
    @PrimaryKey val isbn: String,
    val title: String,
    val author: String="",
    val category: String="Roman",
    val cover: String="",
    val dateAdded: Long=System.currentTimeMillis()
)

@Dao
interface BookDao{
    @Query("SELECT * FROM Book ORDER BY dateAdded DESC")
    suspend fun getAll(): List<Book>
    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun insert(b: Book)
    @Delete
    suspend fun delete(b: Book)
}

@Database(entities=[Book::class], version=1, exportSchema = false)
abstract class AppDatabase: RoomDatabase(){
    abstract fun bookDao(): BookDao
}

class MainActivity: AppCompatActivity(){
    private lateinit var db: AppDatabase
    private lateinit var adapter: BookAdapter
    private var allBooks: List<Book> = emptyList()
    private var isMultiScan = true
    private var currentFilter = "Tous"

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()){ uri ->
        if(uri!=null) importCsvFile(uri)
    }

    private val scanLauncher = registerForActivityResult(ScanContract()){ result ->
        if(result.contents!=null){
            Toast.makeText(this,"Scanné: ${result.contents}",Toast.LENGTH_SHORT).show()
            fetchAndSave(result.contents){
                if(isMultiScan){
                    lifecycleScope.launch{
                        delay(1200)
                        launchScan()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "doudy-v14-final").fallbackToDestructiveMigration().build()
        adapter = BookAdapter(emptyList())
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<Button>(R.id.btnScan).setOnClickListener{ launchScan() }
        findViewById<Button>(R.id.btnExport).setOnClickListener{ exportCsv() }
        findViewById<Button>(R.id.btnImport).setOnClickListener{ importLauncher.launch("text/*") }
        findViewById<Switch>(R.id.switchRafale).setOnCheckedChangeListener{ _, c -> isMultiScan=c }
        findViewById<EditText>(R.id.search).addTextChangedListener{ text -> applyFilters(text.toString()) }

        val spinnerCat = findViewById<Spinner>(R.id.spinnerCat)
        spinnerCat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Tous","Roman","Manga","BD","Cuisine","Jeunesse","Autre"))
        spinnerCat.onItemSelectedListener = object: AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long){
                currentFilter = p?.getItemAtPosition(pos).toString()
                applyFilters(findViewById<EditText>(R.id.search).text.toString())
            }
            override fun onNothingSelected(p: AdapterView<*>?){}
        }
        load()
    }

    fun launchScan(){
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.EAN_13)
        options.setPrompt("RAFALE ON - ${allBooks.size} livres")
        options.setBeepEnabled(true)
        options.setOrientationLocked(true)
        options.setCaptureActivity(MyCaptureActivity::class.java)
        scanLauncher.launch(options)
    }

    private fun applyFilters(query: String){
        var list = allBooks
        if(currentFilter!="Tous") list = list.filter{ it.category==currentFilter }
        if(query.isNotEmpty()){
            val s = query.lowercase()
            list = list.filter{ it.title.lowercase().contains(s) || it.author.lowercase().contains(s) }
        }
        adapter.books = list
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.title).text = "📚 ${list.size} livres"
    }

    private fun load(){
        lifecycleScope.launch{
            allBooks = withContext(Dispatchers.IO){ db.bookDao().getAll() }
            applyFilters(findViewById<EditText>(R.id.search).text.toString())
        }
    }

    fun deleteBook(b: Book){
        AlertDialog.Builder(this).setTitle("Supprimer ${b.title}?").setPositiveButton("Oui"){_,_->
            lifecycleScope.launch{ withContext(Dispatchers.IO){ db.bookDao().delete(b) }; load() }
        }.setNegativeButton("Non",null).show()
    }

    fun editBook(b: Book){
        val lay = LinearLayout(this).apply{ orientation=1; setPadding(32,16,32,16) }
        val et1 = EditText(this).apply{ setText(b.title) }
        val et2 = EditText(this).apply{ setText(b.author) }
        lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle("Editer").setView(lay).setPositiveButton("Sauver"){_,_->
            lifecycleScope.launch(Dispatchers.IO){
                db.bookDao().insert(b.copy(title=et1.text.toString(), author=et2.text.toString()))
                withContext(Dispatchers.Main){ load() }
            }
        }.show()
    }

    private fun httpGet(urlStr: String): String? = try{
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent","Mozilla/5.0")
        conn.connectTimeout=15000
        conn.readTimeout=15000
        conn.inputStream.bufferedReader().readText()
    }catch(_:Exception){ null }

    private fun fetchAndSave(isbn: String, onDone:()->Unit={}){
        lifecycleScope.launch(Dispatchers.IO){
            var title=""; var author=""; var cover=""; var found=false
            val body1 = httpGet("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data")
            if(body1!=null){
                try{
                    val j=JSONObject(body1)
                    if(j.has("ISBN:$isbn")){
                        val d=j.getJSONObject("ISBN:$isbn")
                        title=d.optString("title","")
                        author=d.optJSONArray("authors")?.optJSONObject(0)?.optString("name","")?:""
                        cover="https://covers.openlibrary.org/b/isbn/$isbn-M.jpg"
                        if(title.isNotEmpty()) found=true
                    }
                }catch(_:Exception){}
            }
            if(!found){
                val body2 = httpGet("https://openlibrary.org/isbn/$isbn.json")
                if(body2!=null){
                    try{
                        val j=JSONObject(body2)
                        title=j.optString("title","")
                        if(title.isNotEmpty()){
                            found=true
                            cover="https://covers.openlibrary.org/b/isbn/$isbn-M.jpg"
                        }
                    }catch(_:Exception){}
                }
            }
            if(!found) title="Livre $isbn"
            val book=Book(isbn, title, author, cover)
            db.bookDao().insert(book)
            withContext(Dispatchers.Main){
                load()
                Toast.makeText(this@MainActivity,if(found) "Trouvé: $title" else "Ajouté",Toast.LENGTH_SHORT).show()
                onDone()
            }
        }
    }

    private fun importCsvFile(uri: Uri){
        lifecycleScope.launch(Dispatchers.IO){
            try{
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?: return@launch
                var c=0
                text.lines().drop(1).forEach{ line ->
                    if(line.isNotBlank()){
                        val p=line.split(",")
                        if(p.size>=2){
                            db.bookDao().insert(Book(p[0].trim().replace("\"",""), p[1].trim().replace("\"","")))
                            c++
                        }
                    }
                }
                withContext(Dispatchers.Main){
                    load()
                    Toast.makeText(this@MainActivity,"$c importés",Toast.LENGTH_LONG).show()
                }
            }catch(_:Exception){}
        }
    }

    private fun exportCsv(){
        lifecycleScope.launch(Dispatchers.IO){
            val csv = buildString{
                appendLine("ISBN,Titre,Auteur")
                allBooks.forEach{ appendLine("${it.isbn},\"${it.title}\",\"${it.author}\"") }
            }
            try{
                val file = java.io.File(getExternalFilesDir(null),"doudy.csv")
                file.writeText(csv)
                withContext(Dispatchers.Main){
                    Toast.makeText(this@MainActivity,"Exporté: ${file.absolutePath}",Toast.LENGTH_LONG).show()
                }
            }catch(_:Exception){}
        }
    }
}

class BookAdapter(var books: List<Book>): RecyclerView.Adapter<BookAdapter.VH>(){
    class VH(val view: View, val img: ImageView, val t1: TextView, val t2: TextView, val cat: TextView): RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = androidx.cardview.widget.CardView(parent.context).apply{
            radius=24f; cardElevation=6f; setCardBackgroundColor(0xFF2D241E.toInt())
            layoutParams=RecyclerView.LayoutParams(-1,-2).apply{ setMargins(20,12,20,12) }
            setContentPadding(18,18,18,18)
        }
        val row = LinearLayout(parent.context).apply{ orientation=LinearLayout.HORIZONTAL }
        val img = ImageView(parent.context).apply{ layoutParams=LinearLayout.LayoutParams(140,200).apply{ setMargins(0,0,24,0) }; scaleType=ImageView.ScaleType.CENTER_CROP }
        val col = LinearLayout(parent.context).apply{ orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,-2,1f) }
        val cat = TextView(parent.context).apply{ textSize=10f; setTextColor(0xFFD4AF37.toInt()) }
        val t1 = TextView(parent.context).apply{ textSize=16f; setTextColor(0xFFF5E6D3.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) }
        val t2 = TextView(parent.context).apply{ textSize=13f; setTextColor(0xFFB8A99A.toInt()) }
        col.addView(cat); col.addView(t1); col.addView(t2); row.addView(img); row.addView(col); card.addView(row)
        return VH(card,img,t1,t2,cat)
    }
    override fun getItemCount()=books.size
    override fun onBindViewHolder(holder: VH, position: Int){
        val b=books[position]
        holder.t1.text=b.title
        holder.t2.text="${b.author}\n${b.isbn}"
        holder.cat.text=b.category.uppercase()
        if(b.cover.isNotEmpty()) holder.img.load(b.cover) else holder.img.setImageResource(android.R.drawable.ic_menu_gallery)
        holder.view.setOnClickListener{ (holder.view.context as MainActivity).editBook(b) }
        holder.view.setOnLongClickListener{ (holder.view.context as MainActivity).deleteBook(b); true }
    }
}
