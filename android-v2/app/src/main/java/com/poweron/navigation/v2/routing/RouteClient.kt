package com.poweron.navigation.v2.routing

import com.google.gson.JsonObject
import org.maplibre.android.geometry.LatLng
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverType: String,
    val modifier: String,
    val roadName: String,
    val roadRef: String,
    val exitNumber: String,
    val lanesText: String
)

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>
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

    private fun readLaneDirections(
        step: com.google.gson.JsonObject
    ): String {
        val intersections = step
            .getAsJsonArray("intersections")
            ?: return ""

        if (intersections.size() == 0) {
            return ""
        }

        val lanes = intersections[0]
            .asJsonObject
            .getAsJsonArray("lanes")
            ?: return ""

        val symbols = mutableListOf<String>()

        lanes.forEach { element ->
            val lane = element.asJsonObject
            val valid = lane.get("valid")?.asBoolean ?: false

            val indications = lane
                .getAsJsonArray("indications")
                ?.map { it.asString }
                .orEmpty()

            val symbol = when {
                indications.any { it.contains("left") } -> "←"
                indications.any { it.contains("right") } -> "→"
                indications.any { it.contains("straight") } -> "↑"
                indications.any { it.contains("uturn") } -> "↶"
                else -> "↑"
            }

            symbols.add(
                if (valid) "[$symbol]" else symbol
            )
        }

        return symbols.joinToString("  ")
    }

    private fun createInstruction(
        type: String,
        modifier: String,
        roadName: String
    ): String {
        val road = if (roadName.isBlank()) {
            ""
        } else {
            " $roadName yoluna"
        }

        return when {
            type == "depart" ->
                "Rotaya başlayın."

            type == "arrive" ->
                "Hedefinize ulaştınız."

            modifier.contains("left") ->
                "$road sola dönün."

            modifier.contains("right") ->
                "$road sağa dönün."

            type == "roundabout" ->
                "Döner kavşağa girin."

            type == "merge" ->
                "$road katılın."

            type == "fork" ->
                "$road devam edin."

            else ->
                if (roadName.isBlank()) {
                    "Düz devam edin."
                } else {
                    "$roadName yolunda devam edin."
                }
        }
    }

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

                        val steps = mutableListOf<RouteStep>()

                        val legs = route.getAsJsonArray("legs")

                        if (legs != null && legs.size() > 0) {
                            val stepArray = legs[0]
                                .asJsonObject
                                .getAsJsonArray("steps")

                            stepArray?.forEach { element ->
                                val step = element.asJsonObject
                                val maneuver = step
                                    .getAsJsonObject("maneuver")

                                val type = maneuver
                                    ?.get("type")
                                    ?.asString
                                    .orEmpty()

                                val modifier = maneuver
                                    ?.get("modifier")
                                    ?.asString
                                    .orEmpty()

                                val roadName = step
                                    .get("name")
                                    ?.asString
                                    .orEmpty()

                                val instruction = createInstruction(
                                    type,
                                    modifier,
                                    roadName
                                )

                                val roadRef = step
                                    .get("ref")
                                    ?.asString
                                    .orEmpty()

                                val exitNumber = maneuver
                                    ?.get("exit")
                                    ?.asString
                                    .orEmpty()

                                val lanesText =
                                    readLaneDirections(step)

                                steps.add(
                                    RouteStep(
                                        instruction = instruction,
                                        distanceMeters = step
                                            .get("distance")
                                            .asDouble,
                                        durationSeconds = step
                                            .get("duration")
                                            .asDouble,
                                        maneuverType = type,
                                        modifier = modifier,
                                        roadName = roadName,
                                        roadRef = roadRef,
                                        exitNumber = exitNumber,
                                        lanesText = lanesText
                                    )
                                )
                            }
                        }

                        onSuccess(
                            RouteResult(
                                points = points,
                                distanceMeters =
                                    route.get("distance").asDouble,
                                durationSeconds =
                                    route.get("duration").asDouble,
                                steps = steps
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
