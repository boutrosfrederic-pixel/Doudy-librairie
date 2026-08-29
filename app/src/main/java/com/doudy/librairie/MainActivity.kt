package com.doudy.librairie

import android.graphics.BitmapFactory
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

object ThemeManager{
    data class Theme(val bg: Int, val header: Int, val card: Int, val text: Int, val sub: Int, val accent: Int)
    val themes = mapOf(
        "Nuit" to Theme(0xFF1A1612.toInt(), 0xFF2D241E.toInt(), 0xFF2D241E.toInt(), 0xFFF5E6D3.toInt(), 0xFFB8A99A.toInt(), 0xFFD4AF37.toInt()),
        "Papier" to Theme(0xFFFDF6E3.toInt(), 0xFFF5E0B3.toInt(), 0xFFFFFFFF.toInt(), 0xFF3D2B1F.toInt(), 0xFF8B7355.toInt(), 0xFFA0522D.toInt()),
        "Velours" to Theme(0xFF1B2F23.toInt(), 0xFF2A4A35.toInt(), 0xFF2A4A35.toInt(), 0xFFE8F5E9.toInt(), 0xFFA5D6A7.toInt(), 0xFF66BB6A.toInt()),
        "Clair" to Theme(0xFFF5F5F7.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF666666.toInt(), 0xFF6200EE.toInt()),
        "Midnight" to Theme(0xFF0F172A.toInt(), 0xFF1E293B.toInt(), 0xFFF1F5F9.toInt(), 0xFF94A3B8.toInt(), 0xFF38BDF8.toInt())
    )
}

class MainActivity: AppCompatActivity(){
    private lateinit var db: AppDatabase
    private lateinit var adapter: BookAdapter
    private var allBooks: List<Book> = emptyList()
    private var isMultiScan = true
    private var currentFilter = "Tous"
    private var currentThemeName = "Nuit"

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()){ if(it!=null) importCsvFile(it) }
    private val bgPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()){ uri -> if(uri!=null) saveCustomBackground(uri) }
    private val scanLauncher = registerForActivityResult(ScanContract()){ r-> if(r.contents!=null){ Toast.makeText(this,"Scanné: ${r.contents}",Toast.LENGTH_SHORT).show(); fetchAndSave(r.contents){ if(isMultiScan){ lifecycleScope.launch{ delay(1200); launchScan() } } } } }

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "doudy-v15").fallbackToDestructiveMigration().build()
        adapter = BookAdapter(emptyList())
        findViewById<RecyclerView>(R.id.recycler).layoutManager=LinearLayoutManager(this)
        findViewById<RecyclerView>(R.id.recycler).adapter=adapter

        // Load saved theme
        val prefs = getSharedPreferences("doudy_theme",0)
        currentThemeName = prefs.getString("theme_name","Nuit")?:"Nuit"
        applyTheme(currentThemeName)

        findViewById<Button>(R.id.btnScan).setOnClickListener{ launchScan() }
        findViewById<Button>(R.id.btnExport).setOnClickListener{ exportCsv() }
        findViewById<Button>(R.id.btnImport).setOnClickListener{ importLauncher.launch("text/*") }
        findViewById<Button>(R.id.btnTheme).setOnClickListener{ showThemeDialog() }
        findViewById<Switch>(R.id.switchRafale).setOnCheckedChangeListener{ _, c-> isMultiScan=c }
        findViewById<EditText>(R.id.search).addTextChangedListener{ applyFilters(it.toString()) }
        val sp = findViewById<Spinner>(R.id.spinnerCat)
        sp.adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Tous","Roman","Manga","BD","Cuisine","Jeunesse","Autre"))
        sp.onItemSelectedListener=object: AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long){ currentFilter=p?.getItemAtPosition(pos).toString(); applyFilters(findViewById<EditText>(R.id.search).text.toString()) }
            override fun onNothingSelected(p: AdapterView<*>?){}
        }
        load()
    }

    private fun applyTheme(name: String){
        val theme = ThemeManager.themes[name]?: ThemeManager.themes["Nuit"]!!
        currentThemeName = name
        getSharedPreferences("doudy_theme",0).edit().putString("theme_name",name).apply()
        findViewById<View>(R.id.rootLayout).setBackgroundColor(theme.bg)
        findViewById<View>(R.id.headerLayout).setBackgroundColor(theme.header)
        findViewById<TextView>(R.id.title).setTextColor(theme.text)
        findViewById<TextView>(R.id.subtitle).setTextColor(theme.sub)
        findViewById<Button>(R.id.btnScan).setBackgroundColor(theme.accent)
        adapter.currentTheme = theme
        adapter.notifyDataSetChanged()
        // custom bg
        val prefs = getSharedPreferences("doudy_theme",0)
        val customPath = prefs.getString("custom_bg_path",null)
        val bgImage = findViewById<ImageView>(R.id.bgImage)
        if(customPath!=null && name=="Perso"){
            val f = File(customPath)
            if(f.exists()){ bgImage.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath)); bgImage.visibility=View.VISIBLE; findViewById<View>(R.id.bgOverlay).visibility=View.VISIBLE }
        } else { bgImage.visibility=View.GONE; findViewById<View>(R.id.bgOverlay).visibility=View.GONE }
    }

    private fun showThemeDialog(){
        val options = arrayOf("🌙 Nuit - Bois sombre","📜 Papier - Ancien","🌿 Velours - Green Library","☀️ Clair - Minimal","🌌 Midnight - Bleu nuit","🖼️ Mon fond d'écran perso...")
        AlertDialog.Builder(this).setTitle("Choisis ton décor").setItems(options){_, which->
            when(which){
                0-> applyTheme("Nuit")
                1-> applyTheme("Papier")
                2-> applyTheme("Velours")
                3-> applyTheme("Clair")
                4-> applyTheme("Midnight")
                5-> bgPickerLauncher.launch("image/*")
            }
        }.show()
    }

    private fun saveCustomBackground(uri: Uri){
        lifecycleScope.launch(Dispatchers.IO){
            try{
                val input = contentResolver.openInputStream(uri)!!
                val file = File(filesDir,"custom_bg.jpg")
                file.outputStream().use{ input.copyTo(it) }
                getSharedPreferences("doudy_theme",0).edit().putString("custom_bg_path",file.absolutePath).putString("theme_name","Perso").apply()
                withContext(Dispatchers.Main){
                    currentThemeName="Perso"
                    val bgImage = findViewById<ImageView>(R.id.bgImage)
                    bgImage.setImageURI(uri); bgImage.visibility=View.VISIBLE
                    findViewById<View>(R.id.bgOverlay).visibility=View.VISIBLE
                    Toast.makeText(this@MainActivity,"Fond perso appliqué!",Toast.LENGTH_SHORT).show()
                }
            }catch(_:Exception){}
        }
    }

    fun launchScan(){ val o=ScanOptions().apply{ setDesiredBarcodeFormats(ScanOptions.EAN_13); setPrompt("RAFALE ${allBooks.size}"); setBeepEnabled(true); setOrientationLocked(true); setCaptureActivity(MyCaptureActivity::class.java) }; scanLauncher.launch(o) }
    private fun applyFilters(q: String){ var list=allBooks; if(currentFilter!="Tous") list=list.filter{ it.category==currentFilter }; if(q.isNotEmpty()){ val s=q.lowercase(); list=list.filter{ it.title.lowercase().contains(s) || it.author.lowercase().contains(s) } }; adapter.books=list; adapter.notifyDataSetChanged(); findViewById<TextView>(R.id.title).text="📚 ${list.size} livres - $currentThemeName" }
    private fun load(){ lifecycleScope.launch{ allBooks=withContext(Dispatchers.IO){ db.bookDao().getAll() }; applyFilters(findViewById<EditText>(R.id.search).text.toString()) } }
    fun deleteBook(b: Book){ AlertDialog.Builder(this).setTitle("Supprimer ${b.title}?").setPositiveButton("Oui"){_,_-> lifecycleScope.launch{ withContext(Dispatchers.IO){ db.bookDao().delete(b) }; load() }}.setNegativeButton("Non",null).show() }
    fun editBook(b: Book){ val lay=LinearLayout(this).apply{ orientation=1; setPadding(32,16,32,16) }; val et1=EditText(this).apply{ setText(b.title) }; val et2=EditText(this).apply{ setText(b.author) }; lay.addView(et1); lay.addView(et2); AlertDialog.Builder(this).setTitle("Editer").setView(lay).setPositiveButton("Sauver"){_,_-> lifecycleScope.launch(Dispatchers.IO){ db.bookDao().insert(b.copy(title=et1.text.toString(), author=et2.text.toString())); withContext(Dispatchers.Main){ load() } }}.show() }
    private fun httpGet(u: String): String? = try{ (URL(u).openConnection() as HttpURLConnection).apply{ setRequestProperty("User-Agent","Mozilla/5.0"); connectTimeout=15000; readTimeout=15000 }.inputStream.bufferedReader().readText() }catch(_:Exception){ null }
    private fun fetchAndSave(isbn: String, onDone:()->Unit={}){ lifecycleScope.launch(Dispatchers.IO){ var title=""; var author=""; var cover=""; var found=false; val b1=httpGet("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data"); if(b1!=null){ try{ val j=JSONObject(b1); if(j.has("ISBN:$isbn")){ val d=j.getJSONObject("ISBN:$isbn"); title=d.optString("title",""); author=d.optJSONArray("authors")?.optJSONObject(0)?.optString("name","")?:""; cover="https://covers.openlibrary.org/b/isbn/$isbn-M.jpg"; if(title.isNotEmpty()) found=true } }catch(_:Exception){} }; if(!found){ val b2=httpGet("https://openlibrary.org/isbn/$isbn.json"); if(b2!=null){ try{ val j=JSONObject(b2); title=j.optString("title",""); if(title.isNotEmpty()){ found=true; cover="https://covers.openlibrary.org/b/isbn/$isbn-M.jpg" } }catch(_:Exception){} } }; if(!found) title="Livre $isbn"; val book=Book(isbn, title, author, cover); db.bookDao().insert(book); withContext(Dispatchers.Main){ load(); Toast.makeText(this@MainActivity,if(found) "Trouvé: $title" else "Ajouté",Toast.LENGTH_SHORT).show(); onDone() } } }
    private fun importCsvFile(uri: Uri){ lifecycleScope.launch(Dispatchers.IO){ try{ val t=contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?: return@launch; var c=0; t.lines().drop(1).forEach{ if(it.isNotBlank()){ val p=it.split(","); if(p.size>=2){ db.bookDao().insert(Book(p[0].trim().replace("\"",""), p[1].trim().replace("\"",""))); c++ } } }; withContext(Dispatchers.Main){ load(); Toast.makeText(this@MainActivity,"$c importés",Toast.LENGTH_LONG).show() } }catch(_:Exception){} } }
    private fun exportCsv(){ lifecycleScope.launch(Dispatchers.IO){ val csv=buildString{ appendLine("ISBN,Titre,Auteur"); allBooks.forEach{ appendLine("${it.isbn},\"${it.title}\",\"${it.author}\"") } }; try{ val file=File(getExternalFilesDir(null),"doudy.csv"); file.writeText(csv); withContext(Dispatchers.Main){ Toast.makeText(this@MainActivity,"Exporté: ${file.absolutePath}",Toast.LENGTH_LONG).show() } }catch(_:Exception){} } }
}

class BookAdapter(var books: List<Book>): RecyclerView.Adapter<BookAdapter.VH>(){
    var currentTheme = ThemeManager.themes["Nuit"]!!
    class VH(val view: View, val img: ImageView, val t1: TextView, val t2: TextView, val cat: TextView): RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH {
        val card = androidx.cardview.widget.CardView(p.context).apply{ radius=24f; cardElevation=6f; layoutParams=RecyclerView.LayoutParams(-1,-2).apply{ setMargins(20,12,20,12) }; setContentPadding(18,18,18,18) }
        val row=LinearLayout(p.context).apply{ orientation=0 }
        val img=ImageView(p.context).apply{ layoutParams=LinearLayout.LayoutParams(140,200).apply{ setMargins(0,0,24,0) }; scaleType=ImageView.ScaleType.CENTER_CROP }
        val col=LinearLayout(p.context).apply{ orientation=1; layoutParams=LinearLayout.LayoutParams(0,-2,1f) }
        val cat=TextView(p.context).apply{ textSize=10f }
        val t1=TextView(p.context).apply{ textSize=16f; setTypeface(null, android.graphics.Typeface.BOLD) }
        val t2=TextView(p.context).apply{ textSize=13f }
        col.addView(cat); col.addView(t1); col.addView(t2); row.addView(img); row.addView(col); card.addView(row)
        return VH(card,img,t1,t2,cat)
    }
    override fun getItemCount()=books.size
    override fun onBindViewHolder(h: VH, pos: Int){
        val b=books[pos]
        (h.view as androidx.cardview.widget.CardView).setCardBackgroundColor(currentTheme.card)
        h.t1.setTextColor(currentTheme.text); h.t2.setTextColor(currentTheme.sub); h.cat.setTextColor(currentTheme.accent)
        h.t1.text=b.title; h.t2.text="${b.author}\n${b.isbn}"; h.cat.text=b.category.uppercase()
        if(b.cover.isNotEmpty()) h.img.load(b.cover) else h.img.setImageResource(android.R.drawable.ic_menu_gallery)
        h.view.setOnClickListener{ (h.view.context as MainActivity).editBook(b) }
        h.view.setOnLongClickListener{ (h.view.context as MainActivity).deleteBook(b); true }
    }
}
