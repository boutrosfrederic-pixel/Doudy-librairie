package com.bookshelf
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val isbn: String? = null,
    val totalPages: Int = 300,
    val pagesRead: Int = 0,
    val status: String = "envie", // envie, reading, termine
    val category: String = "Roman",
    val rating: Int = 0,
    val notes: String = "",
    val coverColor: String = "#8D7B6A",
    val dateAdded: Long = System.currentTimeMillis()
)