package com.poweron.navigation.v2.search

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

data class SearchResult(
    @SerializedName("display_name")
    val displayName: String,
    @SerializedName("lat")
    val latitude: String,
    @SerializedName("lon")
    val longitude: String
)

private interface NominatimApi {
    @Headers(
        "User-Agent: PowerON-Navigation/2.0",
        "Accept-Language: de,tr,en"
    )
    @GET("search")
    fun search(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("limit") limit: Int = 8,
        @Query("countrycodes") countryCodes: String = "at"
    ): Call<List<SearchResult>>
}

class SearchClient {
    private val api: NominatimApi = Retrofit.Builder()
        .baseUrl("https://nominatim.openstreetmap.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(NominatimApi::class.java)

    fun search(
        query: String,
        onSuccess: (List<SearchResult>) -> Unit,
        onError: (String) -> Unit
    ) {
        val cleanQuery = query.trim()

        if (cleanQuery.length < 3) {
            onError("En az 3 karakter yaz.")
            return
        }

        api.search(cleanQuery).enqueue(
            object : retrofit2.Callback<List<SearchResult>> {
                override fun onResponse(
                    call: Call<List<SearchResult>>,
                    response: retrofit2.Response<List<SearchResult>>
                ) {
                    if (!response.isSuccessful) {
                        onError("Arama hatası: HTTP ${response.code()}")
                        return
                    }

                    onSuccess(response.body().orEmpty())
                }

                override fun onFailure(
                    call: Call<List<SearchResult>>,
                    throwable: Throwable
                ) {
                    onError(
                        throwable.message ?: "Adres araması başarısız."
                    )
                }
            }
        )
    }
}
