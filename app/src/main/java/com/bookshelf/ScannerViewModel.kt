package com.bookshelf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Gère le scan en rafale
class ScannerViewModel: ViewModel() {
    private val _scannedBooks = MutableStateFlow<List<Book>>(emptyList())
    val scannedBooks = _scannedBooks.asStateFlow()
    private val _scannedIsbns = MutableStateFlow<Set<String>>(emptySet())

    fun addScanned(book: Book): Boolean {
        if (_scannedIsbns.value.contains(book.isbn)) return false // déjà scanné
        _scannedIsbns.value += (book.isbn ?: "")
        _scannedBooks.value = _scannedBooks.value + book
        return true
    }
    fun removeAt(index: Int) {
        val list = _scannedBooks.value.toMutableList()
        _scannedIsbns.value -= list[index].isbn ?: ""
        list.removeAt(index)
        _scannedBooks.value = list
    }
    fun clear() { _scannedBooks.value = emptyList(); _scannedIsbns.value = emptySet() }
}