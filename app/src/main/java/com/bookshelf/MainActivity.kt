package com.bookshelf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(this, BookshelfDatabase::class.java, "bookshelf.db")
            .fallbackToDestructiveMigration().build()
        val dao = db.bookDao()
        
        setContent {
            MaterialTheme {
                var books by remember { mutableStateOf(listOf<Book>()) }
                var query by remember { mutableStateOf("") }
                var filterStatus by remember { mutableStateOf("Tous") }
                var filterCategory by remember { mutableStateOf("Tous") }
                var showAdd by remember { mutableStateOf(false) }
                var editBook by remember { mutableStateOf<Book?>(null) }
                var isbnInput by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) { dao.getAll().collect { books = it } }

                val statuses = listOf("Tous", "À lire", "En cours", "Lu", "Prêté")
                val categories = listOf("Tous", "Roman", "BD", "Scolaire", "Jeunesse", "Autre")

                val filtered = books.filter {
                    (query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true)) &&
                    (filterStatus == "Tous" || it.status == filterStatus) &&
                    (filterCategory == "Tous" || it.category == filterCategory)
                }

                Scaffold(
                    topBar = { TopAppBar(title = { Text("Doudy Librairie 📚 ${books.size}") }) },
                    floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Text("+") } }
                ) { padding ->
                    Column(Modifier.padding(padding).padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Text("📖 ${books.count{it.status=="En cours"}} en cours")
                            Text("✅ ${books.count{it.status=="Lu"}} lus")
                            Text("📚 ${books.size} total")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(query, { query = it }, label = { Text("Rechercher") }, modifier = Modifier.fillMaxWidth())
                        Row { statuses.forEach { s -> FilterChip(selected = filterStatus==s, onClick = { filterStatus=s }, label = { Text(s) }, modifier=Modifier.padding(end=4.dp)) } }
                        Row { categories.forEach { c -> FilterChip(selected = filterCategory==c, onClick = { filterCategory=c }, label = { Text(c) }, modifier=Modifier.padding(end=4.dp)) } }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedTextField(isbnInput, { isbnInput = it }, label = { Text("ISBN") }, modifier = Modifier.weight(1f))
                            Button(onClick = {
                                scope.launch {
                                    val fetched = withContext(Dispatchers.IO) { fetchFromOpenLibrary(isbnInput) }
                                    if(fetched != null) {
                                        dao.insert(Book(title=fetched.first, author=fetched.second, status="À lire", category="Roman", totalPages=300))
                                        isbnInput=""
                                    }
                                }
                            }, modifier=Modifier.padding(start=8.dp)) { Text("Ajouter") }
                        }
                        LazyColumn(Modifier.padding(top=12.dp)) {
                            items(filtered) { b ->
                                Card(Modifier.fillMaxWidth().padding(bottom=8.dp).clickable{ editBook=b },
                                    colors=CardDefaults.cardColors(containerColor = try{Color(android.graphics.Color.parseColor(b.coverColor))}catch(e:Exception){Color(0xFFD6EAF8)})) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(b.title, style=MaterialTheme.typography.titleMedium)
                                        Text("${b.author} • ${b.status} • ${b.category}", style=MaterialTheme.typography.bodySmall)
                                        if(b.totalPages>0){
                                            LinearProgressIndicator(progress = b.pagesRead.toFloat()/b.totalPages.toFloat(), modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween){
                                                Text("${b.pagesRead}/${b.totalPages} pages", style=MaterialTheme.typography.labelSmall)
                                                TextButton(onClick = { scope.launch { dao.delete(b) } }){ Text("Supprimer") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if(showAdd || editBook != null){
                    val currentEdit = editBook // FIX SMART CAST
                    var title by remember{mutableStateOf(currentEdit?.title ?: "")}
                    var author by remember{mutableStateOf(currentEdit?.author ?: "")}
                    var cat by remember{mutableStateOf(currentEdit?.category ?: "Roman")}
                    var stat by remember{mutableStateOf(currentEdit?.status ?: "À lire")}
                    var total by remember{mutableStateOf((currentEdit?.totalPages ?: 300).toString())}
                    var read by remember{mutableStateOf((currentEdit?.pagesRead ?: 0).toString())}

                    AlertDialog(
                        onDismissRequest = { showAdd=false; editBook=null },
                        title={Text(if(currentEdit!=null) "Modifier" else "Ajouter")},
                        text={
                            Column {
                                OutlinedTextField(title,{title=it}, label={Text("Titre")})
                                OutlinedTextField(author,{author=it}, label={Text("Auteur")})
                                OutlinedTextField(total,{total=it}, label={Text("Pages totales")})
                                OutlinedTextField(read,{read=it}, label={Text("Pages lues")})
                                Row { listOf("Roman","BD","Scolaire","Jeunesse","Autre").forEach { c-> FilterChip(cat==c, {cat=c}, {Text(c)}, modifier=Modifier.padding(2.dp)) } }
                                Row { listOf("À lire","En cours","Lu","Prêté").forEach { s-> FilterChip(stat==s, {stat=s}, {Text(s)}, modifier=Modifier.padding(2.dp)) } }
                            }
                        },
                        confirmButton = {
                            Button(onClick={
                                scope.launch{
                                    if(currentEdit != null){
                                        val updated = currentEdit.copy(title=title.ifBlank{"Sans titre"}, author=author.ifBlank{"Inconnu"}, category=cat, status=stat, totalPages=total.toIntOrNull()?:300, pagesRead=read.toIntOrNull()?:0)
                                        dao.update(updated)
                                    } else {
                                        dao.insert(Book(title=title.ifBlank{"Sans titre"}, author=author.ifBlank{"Inconnu"}, category=cat, status=stat, totalPages=total.toIntOrNull()?:300, pagesRead=read.toIntOrNull()?:0))
                                    }
                                    showAdd=false; editBook=null
                                }
                            }){Text(if(currentEdit!=null) "Enregistrer" else "Ajouter")}
                        },
                        dismissButton = { TextButton(onClick={showAdd=false; editBook=null}){Text("Annuler")} }
                    )
                }
            }
        }
    }

    fun fetchFromOpenLibrary(isbn: String): Pair<String,String>? {
        return try{
            val clean = isbn.replace("-","").trim()
            if(clean.length<10) return null
            val json = URL("https://openlibrary.org/api/books?bibkeys=ISBN:$clean&format=json&jscmd=data").readText()
            val obj = JSONObject(json).optJSONObject("ISBN:$clean") ?: return null
            val title = obj.optString("title","")
            val author = obj.optJSONArray("authors")?.optJSONObject(0
