package com.doudy.librairie

import android.net.Uri
import android.os.Bundle
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

@Entity data class Book(@PrimaryKey val isbn: String, val title: String, val author: String="", val category: String="Roman", val cover: String="", val dateAdded: Long=System.currentTimeMillis())
@Dao interface BookDao{ @Query("SELECT * FROM Book ORDER BY dateAdded DESC") suspend fun getAll(): List<Book>; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insert(b: Book); @Delete suspend fun delete(b: Book) }
@Database(entities=[Book::class], version=1) abstract class AppDatabase: RoomDatabase(){ abstract fun bookDao(): BookDao }

class MainActivity: AppCompatActivity(){
    private lateinit var db: AppDatabase
    private lateinit var adapter: BookAdapter
    private var allBooks: List<Book> = emptyList()
    private var isMultiScan = true
    private var currentFilter = "Tous"
    
    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()){ it?.let{ importCsvFile(it) } }
    private val scanLauncher = registerForActivityResult(ScanContract()){ r-> if(r.contents!=null){ Toast.makeText(this,"Scanné: ${r.contents}",Toast.LENGTH_SHORT).show(); fetchAndSave(r.contents){ if(isMultiScan){ lifecycleScope.launch{ delay(1200); launchScan() } } } } }

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // BASE NEUVE ANTI-CRASH
        db = Room.databaseBuilder(this, AppDatabase::class.java, "doudy-v14-final").fallbackToDestructiveMigration().build()
        adapter = BookAdapter(emptyList(), { b-> AlertDialog.Builder(this).setTitle("Supprimer ${b.title}?").setPositiveButton("Oui"){_,_-> lifecycleScope.launch{ db.bookDao().delete(b); load() }}.setNegativeButton("Non",null).show() }, { b-> editDialog(b) })
        findViewById<RecyclerView>(R.id.recycler).layoutManager=LinearLayoutManager(this)
        findViewById<RecyclerView>(R.id.recycler).adapter=adapter
        findViewById<Button>(R.id.btnScan).setOnClickListener{ launchScan() }
        findViewById<Button>(R.id.btnExport).setOnClickListener{ exportCsv() }
        findViewById<Button>(R.id.btnImport).setOnClickListener{ importLauncher.launch("text/*") }
        findViewById<Switch>(R.id.switchRafale).setOnCheckedChangeListener{ _, c-> isMultiScan=c }
        findViewById<EditText>(R.id.search).addTextChangedListener{ applyFilters(it.toString()) }
        findViewById<Spinner>(R.id.spinnerCat).adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Tous","Roman","Manga","BD","Cuisine","Jeunesse","Autre"))
        findViewById<Spinner>(R.id.spinnerCat).onItemSelectedListener=object: AdapterView.OnItemSelectedListener{ override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long){ currentFilter=p?.getItemAtPosition(pos).toString(); applyFilters(findViewById<EditText>(R.id.search).text.toString()) }; override fun onNothingSelected(p: AdapterView<*>?){} }
        load()
    }

    fun launchScan(){ val o=ScanOptions().apply{ setDesiredBarcodeFormats(ScanOptions.EAN_13); setPrompt("RAFALE ON - ${allBooks.size} livres"); setBeepEnabled(true); setOrientationLocked(true); setCaptureActivity(MyCaptureActivity::class.java) }; scanLauncher.launch(o) }
    private fun applyFilters(q: String){ var list=allBooks; if(currentFilter!="Tous") list=list.filter{ it.category==currentFilter }; if(q.isNotEmpty()){ val s=q.lowercase(); list=list.filter{ it.title.lowercase().contains(s) || it.author.lowercase().contains(s) } }; adapter.books=list; adapter.notifyDataSetChanged(); findViewById<TextView>(R.id.title).text="📚 ${list.size} livres" }
    private fun load(){ lifecycleScope.launch{ allBooks=withContext(Dispatchers.IO){ db.bookDao().getAll()
