package com.poweron.navigation.v2.map

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class RadarPoint(
    val latitude: Double,
    val longitude: Double,
    val maxSpeed: String?
)

class RadarClient {

    companion object {
        private const val RADAR_URL =
            "https://raw.githubusercontent.com/" +
                "memet1968konya/PowerON-Navigation/" +
                "navigation-v2/data/radars/austria-radars.json"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()

    fun loadNearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 30_000,
        onSuccess: (List<RadarPoint>) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val request = Request.Builder()
                    .url(RADAR_URL)
                    .header(
                        "User-Agent",
                        "PowerON-Navigation/2.0"
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onError("Radar HTTP ${response.code}")
                        return@Thread
                    }

                    val json = response.body?.string()

                    if (json.isNullOrBlank()) {
                        onError("Radar listesi boş.")
                        return@Thread
                    }

                    val type = object :
                        TypeToken<List<RadarPoint>>() {}.type

                    val allRadars: List<RadarPoint> =
                        gson.fromJson(json, type)

                    val nearby = allRadars.filter { radar ->
                        distanceMeters(
                            latitude,
                            longitude,
                            radar.latitude,
                            radar.longitude
                        ) <= radiusMeters
                    }

                    onSuccess(nearby)
                }
            } catch (error: Exception) {
                onError(
                    error.message ?: "Radar verisi alınamadı."
                )
            }
        }.start()
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val value =
            sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        return 2 * radius * asin(sqrt(value))
    }
}
