package com.bookshelf
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM Book ORDER BY dateAdded DESC") fun getAll(): Flow<List<Book>>
    @Insert suspend fun insert(book: Book): Long
    @Insert suspend fun insertAll(books: List<Book>)
    @Update suspend fun update(book: Book)
    @Delete suspend fun delete(book: Book)
    @Query("SELECT * FROM Book WHERE isbn = :isbn LIMIT 1") suspend fun getByIsbn(isbn: String): Book?
}