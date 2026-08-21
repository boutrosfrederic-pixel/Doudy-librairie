package com.bookshelf
import retrofit2.http.GET
import retrofit2.http.Query
// GET https://openlibrary.org/api/books?bibkeys=ISBN:978...&format=json&jscmd=data
data class OlBook(val title: String, val authors: List<Map<String,String>>?, val number_of_pages: Int?)
interface OpenLibraryService {
    @GET("api/books")
    suspend fun getByIsbn(@Query("bibkeys") bibkeys: String, @Query("format") format: String="json", @Query("jscmd") jscmd: String="data"): Map<String, OlBook>
}