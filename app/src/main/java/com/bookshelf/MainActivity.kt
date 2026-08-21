package com.bookshelf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.room.Room
import kotlinx.coroutines.launch

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
                var showAdd by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                
                LaunchedEffect(Unit) { 
                    dao.getAll().collect { list -> books = list }
                }

                Scaffold(
                    topBar = { TopAppBar(title = { Text("Doudy Librairie 📚") }) },
                    floatingActionButton = {
                        FloatingActionButton(onClick = { showAdd = true }) { Text("+") }
                    }
                ) { padding ->
                    LazyColumn(Modifier.padding(padding).padding(16.dp)) {
                        items(books) { b ->
                            Card(Modifier.fillMaxWidth().padding(bottom=8.dp), colors=CardDefaults.cardColors(containerColor = try{Color(android.graphics.Color.parseColor(b.coverColor))}catch(e:Exception){Color(0xFFD6EAF8)})) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(b.title, style=MaterialTheme.typography.titleMedium)
                                    Text("${b.author} • ${b.status} • ${b.category}", style=MaterialTheme.typography.bodySmall)
                                    if(b.totalPages>0){
                                        // CORRECTION : progress en Float, pas en lambda {}
                                        LinearProgressIndicator(progress = b.pagesRead.toFloat()/b.totalPages.toFloat(), modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                                        Text("${b.pagesRead}/${b.totalPages} pages", style=MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
                if(showAdd){
                    var title by remember{mutableStateOf("")}
                    var author by remember{mutableStateOf("")}
                    AlertDialog(
                        onDismissRequest = {showAdd=false},
                        title={Text("Ajouter un livre")},
                        text={
                            Column{
                                OutlinedTextField(title,{title=it}, label={Text("Titre")})
                                OutlinedTextField(author,{author=it}, label={Text("Auteur")})
                            }
                        },
                        confirmButton = {
                            Button(onClick={
                                scope.launch{
                                    dao.insert(Book(title=title.ifBlank{"Sans titre"}, author=author.ifBlank{"Inconnu"}))
                                    showAdd=false
                                }
                            }){Text("Ajouter")}
                        },
                        dismissButton = { TextButton(onClick={showAdd=false}){Text("Annuler")} }
                    )
                }
            }
        }
    }
}
