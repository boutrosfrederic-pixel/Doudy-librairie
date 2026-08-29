package com.doudy.librairie

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Entity
data class Book(@PrimaryKey val isbn: String, val title: String, val author: String="", val cover: String="", val dateAdded: Long=System.currentTimeMillis())
@Dao
interface BookDao{
    @Query("SELECT * FROM Book ORDER BY dateAdded DESC") suspend fun getAll(): List<Book>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insert(b: Book)
    @Delete suspend fun delete(b: Book)
}
@Database(entities=[Book::class], version=1) abstract class AppDatabase: RoomDatabase(){ abstract fun bookDao(): BookDao }

class BookAdapter(var books: List<Book>, val onDelete:(Book)->Unit, val onEdit:(Book)->Unit): RecyclerView.Adapter<BookAdapter.VH>(){
    class VH(val v: android.view.View, val img: ImageView, val tv: TextView): RecyclerView.ViewHolder(v)
    override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int): VH {
        val layout = android.widget.LinearLayout(p.context).apply{
            orientation=android.widget.LinearLayout.HORIZONTAL
            setPadding(16,16,16,16)
            layoutParams=RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            background = android.graphics.drawable.GradientDrawable().apply{ cornerRadius=24f; setColor(0xFFFFFFFF.toInt()) }
        }
        val img = ImageView(p.context).apply{ layoutParams=android.widget.LinearLayout.LayoutParams(120,180) }
        val tv = TextView(p.context).apply{ setPadding(24,0,0,0); textSize=15f }
        layout.addView(img); layout.addView(tv)
        return VH(layout,img,tv)
    }
    override fun getItemCount()=books.size
    override fun onBindViewHolder(h: VH, pos: Int){
        val b=books[pos]
        h.tv.text="${b.title}\n${b.author}\n${b.isbn}"
        if(b.cover.isNotEmpty()) h.img.load(b.cover) else h.img.setImageResource(android.R.drawable.ic_menu_gallery)
        h.v.setOnClickListener{ onEdit(b) }
        h.v.setOnLongClickListener{ onDelete(b); true }
    }
}

class MainActivity: AppCompatActivity(){
    private lateinit var db: AppDatabase
    private lateinit var adapter: BookAdapter
    private var allBooks: List<Book> = emptyList()

    private val scanLauncher = registerForActivityResult(ScanContract()){ r->
        if(r.contents!=null){
            Toast.makeText(this,"Scanné: ${r.contents}",Toast.LENGTH_LONG).show()
            fetchAndSave(r.contents)
        } else {
            Toast.makeText(this,"Scan annulé",Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 100)
        }
        db = Room.databaseBuilder(this, AppDatabase::class.java, "doudy-v11").fallbackToDestructiveMigration().build()
        adapter = BookAdapter(emptyList(),
            onDelete={ b-> AlertDialog.Builder(this).setTitle("Supprimer?").setMessage(b.title).setPositiveButton("Oui"){_,_-> lifecycleScope.launch{ db.bookDao().delete(b); load() }}.setNegativeButton("Non",null).show()},
            onEdit={ b-> editDialog(b) }
        )
        findViewById<RecyclerView>(R.id.recycler).layoutManager=LinearLayoutManager(this)
        findViewById<RecyclerView>(R.id.recycler).adapter=adapter
        findViewById<Button>(R.id.btnScan).setOnClickListener{
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.EAN_13, ScanOptions.EAN_8, ScanOptions.UPC_A)
            options.setPrompt("Portrait - vise le code")
            options.setBeepEnabled(true)
            options.setOrientationLocked(true)
            options.setCaptureActivity(MyCaptureActivity::class.java)
            scanLauncher.launch(options)
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener{ exportCsv() }
        findViewById<EditText>(R.id.search).addTextChangedListener{ t->
            val q=t.toString().lowercase()
            adapter.books = if(q.isEmpty()) allBooks else allBooks.filter{ it.title.lowercase().contains(q) || it.isbn.contains(q) }
            adapter.notifyDataSetChanged()
        }
        load()
    }

    private fun load(){ lifecycleScope.launch{ allBooks = withContext(Dispatchers.IO){ db.bookDao().getAll() }; adapter.books=allBooks; adapter.notifyDataSetChanged(); findViewById<TextView>(R.id.title).text="Ma Librairie - ${allBooks.size} livres" } }

    private fun editDialog(b: Book){
        val edit=EditText(this).apply{ setText(b.title) }
        AlertDialog.Builder(this).setTitle("Corriger").setView(edit).setPositiveButton("Sauver"){_,_-> lifecycleScope.launch(Dispatchers.IO){ db.bookDao().insert(b.copy(title=edit.text.toString())); withContext(Dispatchers.Main){ load() } } }.show()
    }

    private fun httpGet(urlStr: String): String? {
        return try{
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14; Nubia Z60 Ultra)")
            conn.connectTimeout=8000; conn.readTimeout=8000
            conn.inputStream.bufferedReader().readText()
        }catch(e: Exception){ null }
    }

    private fun fetchAndSave(isbn: String){
        lifecycleScope.launch(Dispatchers.IO){
            var title=""; var author=""; var cover=""; var found=false
            httpGet("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn")?.let{ body ->
                try{
                    val j=JSONObject(body)
                    if(j.optInt("totalItems",0)>0){
                        val vi=j.getJSONArray("items").getJSONObject(0).getJSONObject("volumeInfo")
                        title=vi.optString("title",""); author=vi.optJSONArray("authors")?.optString(0)?: ""; cover=vi.optJSONObject("imageLinks")?.optString("thumbnail","")?: ""
                        if(title.isNotEmpty()) found=true
                    }
                }catch(_:Exception){}
            }
            if(!found){
                httpGet("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data")?.let{ body ->
                    try{
                        val j=JSONObject(body)
                        if(j.has("ISBN:$isbn")){
                            val d=j.getJSONObject("ISBN:$isbn")
                            title=d.optString("title",""); author=d.optJSONArray("authors")?.getJSONObject(0)?.optString("name","")?: ""
                            cover=d.optJSONObject("cover")?.optString("medium","")?: "https://covers.openlibrary.org/b/isbn/$isbn-M.jpg"
                            if(title.isNotEmpty()) found=true
                        }
                    }catch(_:Exception){}
                }
            }
            if(!found) title="Livre $isbn (non trouvé)"
            val book=Book(isbn=isbn, title=title, author=author, cover=cover.replace("http://","https://"))
            db.bookDao().insert(book)
            withContext(Dispatchers.Main){ load(); Toast.makeText(this@MainActivity,if(found) "Trouvé: $title" else "Non trouvé",Toast.LENGTH_SHORT).show() }
        }
    }

    private fun exportCsv(){
        lifecycleScope.launch(Dispatchers.IO){
            val csv = buildString{ appendLine("ISBN,Titre,Auteur"); allBooks.forEach{ appendLine("${it.isbn},\"${it.title}\",\"${it.author}\"") } }
            try{
                if(Build.VERSION.SDK_INT>=29){
                    val values=ContentValues().apply{ put(MediaStore.Downloads.DISPLAY_NAME,"doudy_librairie.csv"); put(MediaStore.Downloads.MIME_TYPE,"text/csv") }
                    val uri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                    uri?.let{ contentResolver.openOutputStream(it)?.use{ os-> os.write(csv.toByteArray()) } }
                } else {
                    val f=java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"doudy_librairie.csv")
                    f.writeText(csv)
                }
                withContext(Dispatchers.Main){ Toast.makeText(this@MainActivity,"CSV exporté",Toast.LENGTH_LONG).show() }
            }catch(_:Exception){}
        }
    }
}
