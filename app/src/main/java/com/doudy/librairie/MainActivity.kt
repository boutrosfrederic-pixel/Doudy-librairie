
package com.doudy.librairie

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: BookAdapter

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val title = "Livre " + result.contents.takeLast(6)
            lifecycleScope.launch {
                db.bookDao().insert(Book(title = title, barcode = result.contents))
                loadBooks()
                Toast.makeText(this@MainActivity, "Ajouté: $title", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "doudy-db").build()

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = BookAdapter()
        recycler.adapter = adapter

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            barcodeLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                setPrompt("Scannez le code-barre")
                setBeepEnabled(true)
                setOrientationLocked(false)
            })
        }

        loadBooks()
    }

    private fun loadBooks() {
        lifecycleScope.launch {
            val books = db.bookDao().getAll()
            adapter.submit(books)
        }
    }
}

class BookAdapter : RecyclerView.Adapter<BookAdapter.VH>() {
    private var items = listOf<Book>()
    fun submit(list: List<Book>) { items = list; notifyDataSetChanged() }
    class VH(val view: android.widget.TextView) : RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val tv = android.widget.TextView(parent.context).apply {
            textSize = 16f
            setPadding(20, 30, 20, 30)
        }
        return VH(tv)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.view.text = items[position].title + " - " + items[position].barcode
    }
    override fun getItemCount() = items.size
}
