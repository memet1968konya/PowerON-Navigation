package com.poweron.navigation.v2.routing

import com.google.gson.JsonObject
import org.maplibre.android.geometry.LatLng
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

private interface OsrmApi {
    @GET
    fun getRoute(@Url url: String): Call<JsonObject>
}

class RouteClient {

    private val api: OsrmApi = Retrofit.Builder()
        .baseUrl("https://router.project-osrm.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OsrmApi::class.java)

    fun route(
        start: LatLng,
        destination: LatLng,
        onSuccess: (RouteResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val url =
            "route/v1/driving/" +
                "${start.longitude},${start.latitude};" +
                "${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson&steps=true"

        api.getRoute(url).enqueue(
            object : retrofit2.Callback<JsonObject> {

                override fun onResponse(
                    call: Call<JsonObject>,
                    response: retrofit2.Response<JsonObject>
                ) {
                    if (!response.isSuccessful) {
                        onError("Rota HTTP ${response.code()}")
                        return
                    }

                    try {
                        val root = response.body()
                            ?: throw IllegalStateException("Boş rota cevabı")

                        if (root.get("code")?.asString != "Ok") {
                            onError(
                                root.get("message")?.asString
                                    ?: "Rota bulunamadı."
                            )
                            return
                        }

                        val route = root
                            .getAsJsonArray("routes")
                            .get(0)
                            .asJsonObject

                        val coordinates = route
                            .getAsJsonObject("geometry")
                            .getAsJsonArray("coordinates")

                        val points = mutableListOf<LatLng>()

                        coordinates.forEach { element ->
                            val coordinate = element.asJsonArray

                            points.add(
                                LatLng(
                                    coordinate[1].asDouble,
                                    coordinate[0].asDouble
                                )
                            )
                        }

                        onSuccess(
                            RouteResult(
                                points = points,
                                distanceMeters =
                                    route.get("distance").asDouble,
                                durationSeconds =
                                    route.get("duration").asDouble
                            )
                        )
                    } catch (error: Exception) {
                        onError(
                            error.message
                                ?: "Rota verisi okunamadı."
                        )
                    }
                }

                override fun onFailure(
                    call: Call<JsonObject>,
                    throwable: Throwable
                ) {
                    onError(
                        throwable.message
                            ?: "Rota bağlantısı başarısız."
                    )
                }
            }
        )
    }
}
