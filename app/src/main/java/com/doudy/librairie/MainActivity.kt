package com.doudy.librairie

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
data class Book(@PrimaryKey val isbn: String, val title: String, val author: String="", val category: String="Roman", val cover: String="", val dateAdded: Long=System.currentTimeMillis())
@Dao interface BookDao{
    @Query("SELECT * FROM Book ORDER BY dateAdded DESC") suspend fun getAll(): List<Book>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insert(b: Book)
    @Delete suspend fun delete(b: Book)
}
@Database(entities=[Book::class], version=2) abstract class AppDatabase: RoomDatabase(){ abstract fun bookDao(): BookDao }

class BookAdapter(var books: List<Book>, val onDelete:(Book)->Unit, val onEdit:(Book)->Unit): RecyclerView.Adapter<BookAdapter.VH>(){
    class VH(val v: android.view.View, val img: ImageView, val tvTitle: TextView, val tvSub: TextView, val tvCat: TextView): RecyclerView.ViewHolder(v)
    override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int): VH {
        val card = com.google.android.material.card.MaterialCardView(p.context).apply{
            radius=32f; cardElevation=8f; setContentPadding(16,16,16,16)
            layoutParams=RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply{ setMargins(16,12,16,12) }
            setCardBackgroundColor(0xFFFFFFFF.toInt())
        }
        val row = android.widget.LinearLayout(p.context).apply{ orientation=0 }
        val img = ImageView(p.context).apply{ layoutParams=android.widget.LinearLayout.LayoutParams(140,200).apply{ setMargins(0,0,24,0) }; scaleType=ImageView.ScaleType.CENTER_CROP }
        val col = android.widget.LinearLayout(p.context).apply{ orientation=1; layoutParams=android.widget.LinearLayout.LayoutParams(0, -2, 1f) }
        val tvCat = TextView(p.context).apply{ setPadding(12,4,12,4); textSize=10f; setTextColor(0xFF6200EE.toInt()); setBackgroundResource(android.R.drawable.btn_default_small) }
        val tvTitle = TextView(p.context).apply{ textSize=16f; setTextColor(0xFF000000.toInt()); maxLines=2 }
        val tvSub = TextView(p.context).apply{ textSize=13f; setTextColor(0xFF666666.toInt()) }
        col.addView(tvCat); col.addView(tvTitle); col.addView(tvSub); row.addView(img); row.addView(col); card.addView(row)
        return VH(card,img,tvTitle,tvSub,tvCat)
    }
    override fun getItemCount()=books.size
    override fun onBindViewHolder(h: VH, pos: Int){
        val b=books[pos]
        h.tvTitle.text=b.title
        h.tvSub.text="${b.author}\n${b.isbn}"
        h.tvCat.text=b.category.uppercase()
        if(b.cover.isNotEmpty()) h.img.load(b.cover) else h.img.setImageResource(android.R.drawable.ic_menu_gallery)
        h.v.setOnClickListener{ onEdit(b) }
        h.v.setOnLongClickListener{ onDelete(b); true }
    }
}

class MainActivity: AppCompatActivity(){
    private lateinit var db: AppDatabase
    private lateinit var adapter: BookAdapter
    private var allBooks: List<Book> = emptyList()
    private var isMultiScan = true
    private var currentFilter = "Tous"

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()){ uri -> uri?.let{ importCsvFile(it) } }

    private val scanLauncher = registerForActivityResult(ScanContract()){ r->
        if(r.contents!=null){
            Toast.makeText(this,"Scanné: ${r.contents}",Toast.LENGTH_SHORT).show()
            fetchAndSave(r.contents){
                if(isMultiScan){ lifecycleScope.launch{ delay(1000); launchScan() } }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.CAMERA),100)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "doudy-v13").fallbackToDestructiveMigration().build()
        adapter = BookAdapter(emptyList(),
            onDelete={ b-> AlertDialog.Builder(this).setTitle("Supprimer ${b.title}?").setPositiveButton("Oui"){_,_-> lifecycleScope.launch{ db.bookDao().delete(b); load() }}.setNegativeButton("Non",null).show() },
            onEdit={ b-> editDialog(b) }
        )
        findViewById<RecyclerView>(R.id.recycler).layoutManager=LinearLayoutManager(this)
        findViewById<RecyclerView>(R.id.recycler).adapter=adapter
        findViewById<Button>(R.id.btnScan).setOnClickListener{ launchScan() }
        findViewById<Button>(R.id.btnExport).setOnClickListener{ exportCsv() }
        findViewById<Button>(R.id.btnImport).setOnClickListener{ importLauncher.launch("text/*") }
        findViewById<android.widget.Switch>(R.id.switchRafale).setOnCheckedChangeListener{ _, c-> isMultiScan=c }
        findViewById<EditText>(R.id.search).addTextChangedListener{ t-> applyFilters(t.toString()) }
        findViewById<Spinner>(R.id.spinnerSort).apply{
            adapter=ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Date","Titre","Auteur","Catégorie"))
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener{
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long){ sortBooks(pos) }
                override fun onNothingSelected(p: AdapterView<*>?){}
            }
        }
        findViewById<Spinner>(R.id.spinnerCat).apply{
            adapter=ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Tous","Roman","Manga","BD","Cuisine","Jeunesse","Autre"))
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener{
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long){ currentFilter=adapter.getItem(pos).toString(); applyFilters(findViewById<EditText>(R.id.search).text.toString()) }
                override fun onNothingSelected(p: AdapterView<*>?){}
            }
        }
        load()
    }

    fun launchScan(){
        val o=ScanOptions().apply{
            setDesiredBarcodeFormats(ScanOptions.EAN_13, ScanOptions.EAN_8, ScanOptions.UPC_A)
            setPrompt(if(isMultiScan) "RAFALE - ${allBooks.size} livres - Suivant!" else "Vise le code")
            setBeepEnabled(true); setOrientationLocked(true); setCaptureActivity(MyCaptureActivity::class.java)
        }
        scanLauncher.launch(o)
    }

    private fun applyFilters(query: String){
        var list = allBooks
        if(currentFilter!="Tous") list = list.filter{ it.category==currentFilter }
        if(query.isNotEmpty()){ val q=query.lowercase(); list=list.filter{ it.title.lowercase().contains(q) || it.author.lowercase().contains(q) || it.isbn.contains(q) } }
        adapter.books=list; adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.title).text="Ma Librairie - ${list.size}/${allBooks.size}"
    }

    private fun sortBooks(pos: Int){
        allBooks = when(pos){ 1->allBooks.sortedBy{ it.title.lowercase() } 2->allBooks.sortedBy{ it.author.lowercase() } 3->allBooks.sortedBy{ it.category } else->allBooks.sortedByDescending{ it.dateAdded } }
        applyFilters(findViewById<EditText>(R.id.search).text.toString())
    }

    private fun load(){ lifecycleScope.launch{ allBooks = withContext(Dispatchers.IO){ db.bookDao().getAll() }; applyFilters(findViewById<EditText>(R.id.search).text.toString()) } }

    private fun editDialog(b: Book){
        val lay=android.widget.LinearLayout(this).apply{ orientation=1; setPadding(32,16,32,16) }
        val etTitle=EditText(this).apply{ setText(b.title) }; val etAuthor=EditText(this).apply{ setText(b.author); hint="Auteur" }
        val sp=Spinner(this).apply{ adapter=ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Roman","Manga","BD","Cuisine","Jeunesse","Autre")); setSelection((adapter as ArrayAdapter<String>).getPosition(b.category)) }
        lay.addView(etTitle); lay.addView(etAuthor); lay.addView(sp)
        AlertDialog.Builder(this).setTitle("Editer").setView(lay).setPositiveButton("Sauver"){_,_-> lifecycleScope.launch(Dispatchers.IO){ db.bookDao().insert(b.copy(title=etTitle.text.toString(), author=etAuthor.text.toString(), category=sp.selectedItem.toString())); withContext(Dispatchers.Main){ load() } } }.show()
    }

    private fun httpGet(urlStr: String): String? = try{
        (URL(urlStr).openConnection() as HttpURLConnection).apply{ setRequestProperty("User-Agent","Mozilla/5.0"); connectTimeout=8000; readTimeout=8000 }.inputStream.bufferedReader().readText()
    }catch(_:Exception){ null }

    private fun fetchAndSave(isbn: String, onDone: ()->Unit={}){
        lifecycleScope.launch(Dispatchers.IO){
            var title=""; var author=""; var cover=""; var found=false
            httpGet("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn")?.let{ body ->
                try{ val j=JSONObject(body); if(j.optInt("totalItems",0)>0){ val vi=j.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo"); title=vi.optString("title",""); author=vi.optJSONArray("authors")?.optString(0)?: ""; cover=vi.optJSONObject("imageLinks")?.optString("thumbnail","")?: ""; if(title.isNotEmpty()) found=true } }catch(_:Exception){}
            }
            if(!found){ httpGet("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data")?.let{ body -> try{ val j=JSONObject(body); if(j.has("ISBN:$isbn")){ val d=j.getJSONObject("ISBN:$isbn"); title=d.optString("title",""); author=d.optJSONArray("authors")?.getJSONObject(0)?.optString("name","")?: ""; cover=d.optJSONObject("cover")?.optString("medium","")?: ""; if(title.isNotEmpty()) found=true } }catch(_:Exception){} } }
            if(!found) title="Livre $isbn"
            val book=Book(isbn=isbn, title=title, author=author, cover=cover.replace("http://","https://"))
            db.bookDao().insert(book)
            withContext(Dispatchers.Main){ load(); Toast.makeText(this@MainActivity,if(found) "Ajouté: $title" else "Ajouté (à compléter)",Toast.LENGTH_SHORT).show(); onDone() }
        }
    }

    private fun importCsvFile(uri: Uri){
        lifecycleScope.launch(Dispatchers.IO){
            try{
                val text=contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?: return@launch
                var c=0; text.lines().drop(1).forEach{ line -> if(line.isNotBlank()){ val p=line.split(","); if(p.size>=2){ db.bookDao().insert(Book(isbn=p[0].trim().replace("\"",""), title=p[1].trim().replace("\"",""), author=if(p.size>2) p[2].replace("\"","") else "")); c++ } } }
                withContext(Dispatchers.Main){ load(); Toast.makeText(this@MainActivity,"$c importés",Toast.LENGTH_LONG).show() }
            }catch(_:Exception){}
        }
    }

    private fun exportCsv(){
        lifecycleScope.launch(Dispatchers.IO){
            val csv=buildString{ appendLine("ISBN,Titre,Auteur,Categorie"); allBooks.forEach{ appendLine("${it.isbn},\"${it.title}\",\"${it.author}\",${it.category}") } }
            try{
                if(Build.VERSION.SDK_INT>=29){ val v=ContentValues().apply{ put(MediaStore.Downloads.DISPLAY_NAME,"doudy_librairie.csv"); put(MediaStore.Downloads.MIME_TYPE,"text/csv") }; val u=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v); u?.let{ contentResolver.openOutputStream(it)?.use{ os-> os.write(csv.toByteArray()) } } }
                else{ java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"doudy_librairie.csv").writeText(csv) }
                withContext(Dispatchers.Main){ Toast.makeText(this@MainActivity,"CSV exporté dans Téléchargements",Toast.LENGTH_LONG).show() }
            }catch(_:Exception){}
        }
    }
}
