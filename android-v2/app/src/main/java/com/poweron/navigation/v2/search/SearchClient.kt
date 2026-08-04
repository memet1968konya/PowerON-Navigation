package com.poweron.navigation.v2.search

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

data class SearchResult(
    val displayName: String,
    val latitude: String,
    val longitude: String
)

private data class PhotonResponse(
    val features: List<PhotonFeature> = emptyList()
)

private data class PhotonFeature(
    val geometry: PhotonGeometry,
    val properties: PhotonProperties
)

private data class PhotonGeometry(
    val coordinates: List<Double> = emptyList()
)

private data class PhotonProperties(
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val postcode: String? = null,
    val city: String? = null,
    val district: String? = null,
    val state: String? = null,
    val country: String? = null,
    val countrycode: String? = null
)

private interface PhotonApi {

    @Headers(
        "User-Agent: PowerON-Navigation/2.0",
        "Accept: application/json"
    )
    @GET("api/")
    fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int = 10,
        @Query("lang") language: String = "de",
        @Query("lat") latitudeBias: Double = 47.5162,
        @Query("lon") longitudeBias: Double = 14.5501
    ): Call<PhotonResponse>
}

class SearchClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request()
                .newBuilder()
                .header(
                    "User-Agent",
                    "PowerON-Navigation/2.0"
                )
                .header("Accept", "application/json")
                .build()

            chain.proceed(request)
        }
        .build()

    private val api: PhotonApi = Retrofit.Builder()
        .baseUrl("https://photon.komoot.io/")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(PhotonApi::class.java)

    fun search(
        query: String,
        onSuccess: (List<SearchResult>) -> Unit,
        onError: (String) -> Unit
    ) {
        val cleanQuery = query.trim()

        if (cleanQuery.length < 3) {
            onSuccess(emptyList())
            return
        }

        api.search(cleanQuery).enqueue(
            object : retrofit2.Callback<PhotonResponse> {

                override fun onResponse(
                    call: Call<PhotonResponse>,
                    response: retrofit2.Response<PhotonResponse>
                ) {
                    if (!response.isSuccessful) {
                        onError(
                            "Adres servisi HTTP ${response.code()}"
                        )
                        return
                    }

                    val results = response.body()
                        ?.features
                        .orEmpty()
                        .filter { feature ->
                            val code = feature.properties.countrycode

                            code == null ||
                                code.equals("AT", ignoreCase = true)
                        }
                        .mapNotNull { feature ->
                            val coordinates =
                                feature.geometry.coordinates

                            if (coordinates.size < 2) {
                                return@mapNotNull null
                            }

                            val properties = feature.properties

                            val firstLine = listOfNotNull(
                                properties.name,
                                listOfNotNull(
                                    properties.street,
                                    properties.housenumber
                                )
                                    .joinToString(" ")
                                    .takeIf { it.isNotBlank() }
                            )
                                .distinct()
                                .joinToString(", ")

                            val secondLine = listOfNotNull(
                                properties.postcode,
                                properties.city
                                    ?: properties.district,
                                properties.state,
                                properties.country
                            )
                                .distinct()
                                .joinToString(", ")

                            val displayName = listOf(
                                firstLine,
                                secondLine
                            )
                                .filter { it.isNotBlank() }
                                .joinToString(" — ")

                            SearchResult(
                                displayName = displayName.ifBlank {
                                    "Adres sonucu"
                                },
                                latitude =
                                    coordinates[1].toString(),
                                longitude =
                                    coordinates[0].toString()
                            )
                        }
                        .distinctBy {
                            "${it.latitude},${it.longitude}"
                        }

                    onSuccess(results)
                }

                override fun onFailure(
                    call: Call<PhotonResponse>,
                    throwable: Throwable
                ) {
                    onError(
                        throwable.message
                            ?: "Adres servisine bağlanılamadı."
                    )
                }
            }
        )
    }
}
