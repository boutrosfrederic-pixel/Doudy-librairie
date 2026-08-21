package com.bookshelf
import android.content.Context
import android.net.Uri
import java.io.*

object CsvHelper {
    fun export(books: List<Book>, file: File) {
        file.writeText("title,author,isbn,totalPages,pagesRead,status,category,rating,notes
")
        books.forEach { b ->
            file.appendText("\"${b.title}\",\"${b.author}\",${b.isbn},${b.totalPages},${b.pagesRead},${b.status},${b.category},${b.rating},\"${b.notes.replace("\"", "'")}\")
")
        }
    }
    fun import(input: InputStream): List<Book> {
        return input.bufferedReader().readLines().drop(1).mapNotNull { line ->
            try {
                // parsing simple CSV - pour prod utiliser kotlin-csv
                val parts = line.split(",")
                Book(title = parts[0].trim('"'), author = parts[1].trim('"'), isbn = parts.getOrNull(2), totalPages = parts.getOrNull(3)?.toIntOrNull() ?: 300, pagesRead = parts.getOrNull(4)?.toIntOrNull() ?: 0, status = parts.getOrNull(5) ?: "envie")
            } catch(e: Exception) { null }
        }
    }
}