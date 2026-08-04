package com.poweron.navigation.v2.map

import com.google.gson.Gson
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RadarPoint(
    val latitude: Double,
    val longitude: Double,
    val maxSpeed: String?
)

private data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList()
)

private data class OverpassElement(
    val lat: Double? = null,
    val lon: Double? = null,
    val tags: Map<String, String>? = null
)

class RadarClient {

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()

    fun loadNearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 10_000,
        onSuccess: (List<RadarPoint>) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            val query = """
                [out:json][timeout:12];
                node(around:$radiusMeters,$latitude,$longitude)
                  ["highway"="speed_camera"];
                out body;
            """.trimIndent()

            var lastError = "Radar servisine bağlanılamadı."

            for (endpoint in endpoints) {
                try {
                    val body = FormBody.Builder()
                        .add("data", query)
                        .build()

                    val request = Request.Builder()
                        .url(endpoint)
                        .post(body)
                        .header(
                            "User-Agent",
                            "PowerON-Navigation/2.0 " +
                                "(mehmetbahar196842@gmail.com)"
                        )
                        .header("Accept", "application/json")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastError =
                                "Radar servisi HTTP ${response.code}"
                            continue
                        }

                        val json = response.body?.string()

                        if (json.isNullOrBlank()) {
                            lastError = "Radar servisi boş cevap verdi."
                            continue
                        }

                        val parsed = gson.fromJson(
                            json,
                            OverpassResponse::class.java
                        )

                        val points = parsed.elements.mapNotNull { element ->
                            val lat = element.lat
                                ?: return@mapNotNull null

                            val lon = element.lon
                                ?: return@mapNotNull null

                            RadarPoint(
                                latitude = lat,
                                longitude = lon,
                                maxSpeed =
                                    element.tags?.get("maxspeed")
                            )
                        }

                        onSuccess(points)
                        return@Thread
                    }
                } catch (error: Exception) {
                    lastError =
                        error.message ?: "Radar servisi zaman aşımı."
                }
            }

            onError(lastError)
        }.start()
    }
}
