package com.bookshelf
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
// MainActivity lance ton app Bookshelf Compose. Le fichier IsbnScannerScreen.kt gère le scan rafale
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Ici tu mets ton NavHost : LibraryScreen, ScannerScreen (avec ScannerViewModel pour la rafale), CsvScreen
                // Voir les fichiers fournis pour la logique
                Text("Bookshelf - Build réussi ! Voir les fichiers source inclus.")
            }
        }
    }
}