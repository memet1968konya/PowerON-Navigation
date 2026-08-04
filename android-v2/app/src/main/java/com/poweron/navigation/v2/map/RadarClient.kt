package com.poweron.navigation.v2.map

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class RadarPoint(
    val latitude: Double,
    val longitude: Double,
    val maxSpeed: String?
)

private data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList()
)

private data class OverpassElement(
    val lat: Double?,
    val lon: Double?,
    val tags: Map<String, String>? = null
)

private interface OverpassApi {
    @GET("api/interpreter")
    fun query(
        @Query("data") query: String
    ): Call<OverpassResponse>
}

class RadarClient {

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
                .newBuilder()
                .header(
                    "User-Agent",
                    "PowerON-Navigation/2.0 " +
                        "(mehmetbahar196842@gmail.com)"
                )
                .header("Accept", "application/json")
                .header("Accept-Language", "de,tr,en")
                .build()

            chain.proceed(request)
        }
        .build()

    private val api: OverpassApi = Retrofit.Builder()
        .baseUrl("https://overpass.kumi.systems/")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OverpassApi::class.java)

    fun loadNearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 30_000,
        onSuccess: (List<RadarPoint>) -> Unit,
        onError: (String) -> Unit
    ) {
        val query = """
            [out:json][timeout:25];
            (
              node(around:$radiusMeters,$latitude,$longitude)
                ["highway"="speed_camera"];
            );
            out body;
        """.trimIndent()

        api.query(query).enqueue(
            object : retrofit2.Callback<OverpassResponse> {

                override fun onResponse(
                    call: Call<OverpassResponse>,
                    response: retrofit2.Response<OverpassResponse>
                ) {
                    if (!response.isSuccessful) {
                        onError("Radar servisi HTTP ${response.code()}")
                        return
                    }

                    val points = response.body()
                        ?.elements
                        .orEmpty()
                        .mapNotNull { element ->
                            val lat = element.lat ?: return@mapNotNull null
                            val lon = element.lon ?: return@mapNotNull null

                            RadarPoint(
                                latitude = lat,
                                longitude = lon,
                                maxSpeed = element.tags?.get("maxspeed")
                            )
                        }

                    onSuccess(points)
                }

                override fun onFailure(
                    call: Call<OverpassResponse>,
                    throwable: Throwable
                ) {
                    onError(
                        throwable.message
                            ?: "Radar bilgisi alınamadı."
                    )
                }
            }
        )
    }
}
