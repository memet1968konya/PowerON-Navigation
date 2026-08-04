package com.poweron.navigation.v2.radio

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RadioStation(
    val id: Int,
    val name: String,
    val category: String,
    val country: String,
    val url: String,
    val enabled: Boolean
)

class StationClient {

    companion object {
        private const val STATIONS_URL =
            "https://raw.githubusercontent.com/" +
                "memet1968konya/PowerON-Navigation/" +
                "navigation-v2/data/radio/stations.json"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun load(
        onSuccess: (List<RadioStation>) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val request = Request.Builder()
                    .url(STATIONS_URL)
                    .header(
                        "User-Agent",
                        "PowerON-Navigation/2.0"
                    )
                    .header("Cache-Control", "no-cache")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onError(
                            "İstasyon listesi HTTP ${response.code}"
                        )
                        return@Thread
                    }

                    val json = response.body?.string()

                    if (json.isNullOrBlank()) {
                        onError("İstasyon listesi boş.")
                        return@Thread
                    }

                    val type = object :
                        TypeToken<List<RadioStation>>() {}.type

                    val stations: List<RadioStation> =
                        Gson().fromJson(json, type)

                    onSuccess(
                        stations.filter {
                            it.enabled && it.url.isNotBlank()
                        }
                    )
                }
            } catch (error: Exception) {
                onError(
                    error.message
                        ?: "İstasyon listesi alınamadı."
                )
            }
        }.start()
    }
}
