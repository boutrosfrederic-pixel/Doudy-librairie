
package com.doudy.librairie
import androidx.room.*

@Dao
interface BookDao {
    @Query("SELECT * FROM Book ORDER BY id DESC")
    suspend fun getAll(): List<Book>
    @Insert
    suspend fun insert(book: Book)
    @Delete
    suspend fun delete(book: Book)
}
