package com.poweron.navigation.v2

import com.poweron.navigation.v2.update.UpdateManager

import android.Manifest
import com.poweron.navigation.v2.search.SearchResult
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.text.TextWatcher
import android.text.Editable
import com.poweron.navigation.v2.download.MapDownloadManager
import android.os.Looper
import android.os.Handler
import android.app.DownloadManager
import com.poweron.navigation.v2.search.SearchClient
import com.poweron.navigation.v2.routing.RouteClient
import com.poweron.navigation.v2.map.RadarClient
import com.poweron.navigation.v2.routing.RouteStep
import com.poweron.navigation.v2.voice.VoiceManager
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var mapLibreMap: MapLibreMap
    private lateinit var locationManager: LocationManager
    private lateinit var destinationText: TextView
    private lateinit var updateManager: UpdateManager
    private lateinit var searchClient: SearchClient
    private lateinit var searchAdapter: ArrayAdapter<String>
    private var searchResults: List<SearchResult> = emptyList()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var selectingSuggestion = false
    private lateinit var routeClient: RouteClient
    private lateinit var radarClient: RadarClient
    private lateinit var voiceManager: VoiceManager
    private lateinit var mapDownloadManager: MapDownloadManager
    private val downloadHandler = Handler(Looper.getMainLooper())
    private var activeMapDownloadId: Long = -1L
    private lateinit var searchInput: AutoCompleteTextView

    private var currentMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private val radarMarkers = mutableListOf<Marker>()
    private var radarsLoaded = false
    private var currentLocation: Location? = null
    private var destinationPoint: LatLng? = null
    private var currentRouteSteps: List<RouteStep> = emptyList()

    private val routeSourceId = "route-source"
    private val routeLayerId = "route-layer"
    private val buildings3dLayerId = "poweron-3d-buildings"
    private var buildings3dEnabled = true

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            val coarseGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineGranted || coarseGranted) {
                startLocationUpdates()
            } else {
                Toast.makeText(
                    this,
                    "Konum izni verilmedi.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        destinationText = findViewById(R.id.destinationText)
        searchInput = findViewById(R.id.searchInput)

        val searchButton: Button =
            findViewById(R.id.searchButton)

        val locationButton: Button =
            findViewById(R.id.locationButton)

        val buildings3dButton: Button =
            findViewById(R.id.buildings3dButton)

        val radarButton: Button =
            findViewById(R.id.radarButton)

        val routeButton: Button =
            findViewById(R.id.routeButton)

        val instructionsButton: Button =
            findViewById(R.id.instructionsButton)

        val offlineMapsButton: Button =
            findViewById(R.id.offlineMapsButton)

        val clearDestinationButton: Button =
            findViewById(R.id.clearDestinationButton)

        locationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager

        updateManager = UpdateManager(this)
        searchClient = SearchClient()
        searchAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )
        searchInput.setAdapter(searchAdapter)
        routeClient = RouteClient()
        radarClient = RadarClient()
        voiceManager = VoiceManager(this)
        mapDownloadManager = MapDownloadManager(this)

        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            mapLibreMap = map

            map.setStyle(
                Style.Builder().fromUri(
                    "https://tiles.openfreemap.org/styles/bright"
                )
            ) {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(48.2082, 16.3738))
                    .zoom(16.0)
                    .tilt(55.0)
                    .bearing(0.0)
                    .build()

                setupRouteLayer()
                setup3dBuildings()

                mapLibreMap.addOnMapLongClickListener { point ->
                    selectDestination(point)
                    true
                }

                requestLocationPermission()
            }
        }

        searchButton.setOnClickListener {
            performAddressSearch()
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performAddressSearch()
                true
            } else {
                false
            }
        }

        searchInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    if (selectingSuggestion) {
                        return
                    }

                    scheduleAddressSuggestions(
                        text?.toString().orEmpty()
                    )
                }

                override fun afterTextChanged(
                    editable: Editable?
                ) = Unit
            }
        )

        searchInput.setOnItemClickListener { _, _, position, _ ->
            val result = searchResults.getOrNull(position)
                ?: return@setOnItemClickListener

            val latitude = result.latitude.toDoubleOrNull()
            val longitude = result.longitude.toDoubleOrNull()

            if (latitude == null || longitude == null) {
                return@setOnItemClickListener
            }

            selectingSuggestion = true
            searchInput.setText(result.displayName, false)
            selectingSuggestion = false

            selectDestination(
                LatLng(latitude, longitude)
            )

            destinationText.text = result.displayName
            searchInput.dismissDropDown()
        }

        radarButton.setOnClickListener {
            loadNearbyRadars(forceReload = true)
        }

        buildings3dButton.setOnClickListener {
            toggle3dBuildings(buildings3dButton)
        }

        locationButton.setOnClickListener {
            requestLocationPermission()
        }

        routeButton.setOnClickListener {
            drawRouteToDestination()
        }

        instructionsButton.setOnClickListener {
            showRouteInstructions()
        }

        offlineMapsButton.setOnClickListener {
            showOfflineMapsDialog()
        }

        clearDestinationButton.setOnClickListener {
            clearDestination()
        }

        updateManager.checkForUpdate()
    }

    private fun scheduleAddressSuggestions(
        query: String
    ) {
        searchRunnable?.let {
            searchHandler.removeCallbacks(it)
        }

        val cleanQuery = query.trim()

        if (cleanQuery.length < 2) {
            searchResults = emptyList()
            searchAdapter.clear()
            searchAdapter.notifyDataSetChanged()
            searchInput.dismissDropDown()
            return
        }

        searchRunnable = Runnable {
            loadAddressSuggestions(cleanQuery)
        }

        searchHandler.postDelayed(
            searchRunnable!!,
            350L
        )
    }

    private fun loadAddressSuggestions(
        query: String
    ) {
        searchClient.search(
            query = query,
            onSuccess = { results ->
                runOnUiThread {
                    if (
                        searchInput.text.toString().trim() != query
                    ) {
                        return@runOnUiThread
                    }

                    searchResults = results

                    searchAdapter.clear()
                    searchAdapter.addAll(
                        results.map { it.displayName }
                    )
                    searchAdapter.notifyDataSetChanged()

                    if (results.isNotEmpty()) {
                        searchInput.showDropDown()
                    } else {
                        searchInput.dismissDropDown()
                    }
                }
            },
            onError = {
                runOnUiThread {
                    searchResults = emptyList()
                    searchAdapter.clear()
                    searchAdapter.notifyDataSetChanged()
                    searchInput.dismissDropDown()
                }
            }
        )
    }

    private fun performAddressSearch() {
        val query = searchInput.text.toString().trim()

        if (query.length < 3) {
            Toast.makeText(
                this,
                "En az 3 karakter yaz.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        destinationText.text = "Adres aranıyor…"

        searchClient.search(
            query = query,
            onSuccess = { results ->
                runOnUiThread {
                    if (results.isEmpty()) {
                        destinationText.text =
                            "Adres bulunamadı: $query"
                        return@runOnUiThread
                    }

                    val labels = results
                        .map { it.displayName }
                        .toTypedArray()

                    AlertDialog.Builder(this)
                        .setTitle("Arama sonuçları")
                        .setItems(labels) { _, index ->
                            val result = results[index]

                            val latitude =
                                result.latitude.toDoubleOrNull()

                            val longitude =
                                result.longitude.toDoubleOrNull()

                            if (
                                latitude == null ||
                                longitude == null
                            ) {
                                Toast.makeText(
                                    this,
                                    "Geçersiz koordinat.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@setItems
                            }

                            val point = LatLng(
                                latitude,
                                longitude
                            )

                            selectDestination(point)

                            destinationText.text =
                                result.displayName
                        }
                        .setNegativeButton("İptal", null)
                        .show()
                }
            },
            onError = { message ->
                runOnUiThread {
                    destinationText.text = message

                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun loadNearbyRadars(
        forceReload: Boolean = false
    ) {
        val location = currentLocation

        if (location == null) {
            Toast.makeText(
                this,
                "Önce konumun bulunmalı.",
                Toast.LENGTH_LONG
            ).show()

            requestLocationPermission()
            return
        }

        if (radarsLoaded && !forceReload) {
            return
        }

        destinationText.text = "Yakındaki radarlar aranıyor…"

        radarClient.loadNearby(
            latitude = location.latitude,
            longitude = location.longitude,
            onSuccess = { radars ->
                runOnUiThread {
                    radarMarkers.forEach {
                        mapLibreMap.removeMarker(it)
                    }

                    radarMarkers.clear()

                    radars.forEach { radar ->
                        val speedText =
                            radar.maxSpeed?.let {
                                "Hız sınırı: $it km/s"
                            } ?: "Hız bilgisi yok"

                        val marker = mapLibreMap.addMarker(
                            MarkerOptions()
                                .position(
                                    LatLng(
                                        radar.latitude,
                                        radar.longitude
                                    )
                                )
                                .title("Sabit radar")
                                .snippet(speedText)
                        )

                        radarMarkers.add(marker)
                    }

                    radarsLoaded = true

                    destinationText.text =
                        "${radars.size} sabit radar bulundu."

                    Toast.makeText(
                        this,
                        "${radars.size} radar haritaya eklendi.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onError = { message ->
                runOnUiThread {
                    destinationText.text = message

                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun setup3dBuildings() {
        val style = mapLibreMap.style ?: return

        if (style.getLayer(buildings3dLayerId) != null) {
            return
        }

        if (style.getSource("openmaptiles") == null) {
            Toast.makeText(
                this,
                "3D bina kaynağı bulunamadı.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val buildingsLayer = FillExtrusionLayer(
            buildings3dLayerId,
            "openmaptiles"
        ).apply {
            sourceLayer = "building"
            minZoom = 14f

            setProperties(
                PropertyFactory.fillExtrusionColor("#C9D1D9"),
                PropertyFactory.fillExtrusionOpacity(0.88f),
                PropertyFactory.fillExtrusionHeight(
                    get("render_height")
                ),
                PropertyFactory.fillExtrusionBase(
                    get("render_min_height")
                ),
                PropertyFactory.fillExtrusionVerticalGradient(true)
            )
        }

        val firstLabelLayer = style.layers
            .firstOrNull { it is SymbolLayer }
            ?.id

        if (firstLabelLayer != null) {
            style.addLayerBelow(
                buildingsLayer,
                firstLabelLayer
            )
        } else {
            style.addLayer(buildingsLayer)
        }

        buildings3dEnabled = true
    }

    private fun toggle3dBuildings(button: Button) {
        val style = mapLibreMap.style ?: return
        val layer = style.getLayer(buildings3dLayerId)

        if (buildings3dEnabled) {
            layer?.setProperties(
                PropertyFactory.visibility("none")
            )

            mapLibreMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(
                        mapLibreMap.cameraPosition
                    )
                        .tilt(0.0)
                        .build()
                ),
                700
            )

            buildings3dEnabled = false
            button.text = "3D"
        } else {
            if (layer == null) {
                setup3dBuildings()
            } else {
                layer.setProperties(
                    PropertyFactory.visibility("visible")
                )
            }

            mapLibreMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(
                        mapLibreMap.cameraPosition
                    )
                        .tilt(55.0)
                        .build()
                ),
                700
            )

            buildings3dEnabled = true
            button.text = "2D"
        }
    }

    private fun setupRouteLayer() {
        val style = mapLibreMap.style ?: return

        if (style.getSource(routeSourceId) == null) {
            style.addSource(
                GeoJsonSource(
                    routeSourceId,
                    FeatureCollection.fromFeatures(emptyArray())
                )
            )
        }

        if (style.getLayer(routeLayerId) == null) {
            style.addLayer(
                LineLayer(routeLayerId, routeSourceId)
                    .withProperties(
                        PropertyFactory.lineWidth(12f),
                        PropertyFactory.lineOpacity(0.9f)
                    )
            )
        }
    }

    private fun selectDestination(point: LatLng) {
        if (!::mapLibreMap.isInitialized) {
            return
        }

        destinationPoint = point

        destinationMarker?.let {
            mapLibreMap.removeMarker(it)
        }

        destinationMarker = mapLibreMap.addMarker(
            MarkerOptions()
                .position(point)
                .title("Hedef")
                .snippet(
                    "%.6f, %.6f".format(
                        point.latitude,
                        point.longitude
                    )
                )
        )

        updateDestinationInfo()

        mapLibreMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(point, 16.0),
            900
        )
    }

    private fun drawRouteToDestination() {
        val location = currentLocation
        val destination = destinationPoint

        if (location == null) {
            Toast.makeText(
                this,
                "Önce konumun bulunmalı.",
                Toast.LENGTH_LONG
            ).show()
            requestLocationPermission()
            return
        }

        if (destination == null) {
            Toast.makeText(
                this,
                "Önce hedef seç.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        destinationText.text = "Yol rotası hesaplanıyor…"

        val startPoint = LatLng(
            location.latitude,
            location.longitude
        )

        routeClient.route(
            start = startPoint,
            destination = destination,
            onSuccess = { result ->
                runOnUiThread {
                    val geometry = LineString.fromLngLats(
                        result.points.map {
                            Point.fromLngLat(
                                it.longitude,
                                it.latitude
                            )
                        }
                    )

                    mapLibreMap.style
                        ?.getSourceAs<GeoJsonSource>(routeSourceId)
                        ?.setGeoJson(
                            Feature.fromGeometry(geometry)
                        )

                    val kilometers =
                        result.distanceMeters / 1000.0

                    val minutes =
                        (result.durationSeconds / 60.0)
                            .toInt()
                            .coerceAtLeast(1)

                    currentRouteSteps = result.steps

                    val firstInstruction =
                        result.steps.firstOrNull()?.instruction
                            ?: "Rota hazır."

                    destinationText.text =
                        "Yol mesafesi: %.1f km\nTahmini süre: %d dakika\n%s"
                            .format(
                                kilometers,
                                minutes,
                                firstInstruction
                            )

                    voiceManager.speak(firstInstruction)

                    val bounds =
                        org.maplibre.android.geometry.LatLngBounds
                            .Builder()
                            .includes(result.points)
                            .build()

                    mapLibreMap.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(
                            bounds,
                            100
                        ),
                        1200
                    )
                }
            },
            onError = { message ->
                runOnUiThread {
                    destinationText.text = message

                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun showOfflineMapsDialog() {
        val downloaded =
            mapDownloadManager.isAustriaMapDownloaded()

        val file = mapDownloadManager.getAustriaMapFile()

        val sizeText =
            if (downloaded && file != null) {
                val megabytes =
                    file.length().toDouble() /
                        1024.0 /
                        1024.0

                "%.1f MB".format(megabytes)
            } else {
                "İndirilmedi"
            }

        val options =
            if (downloaded) {
                arrayOf(
                    "Austria haritası: $sizeText",
                    "Haritayı yeniden indir",
                    "Haritayı sil"
                )
            } else {
                arrayOf(
                    "Austria haritası: İndirilmedi",
                    "Austria haritasını indir"
                )
            }

        AlertDialog.Builder(this)
            .setTitle("Çevrimdışı Haritalar")
            .setItems(options) { _, index ->
                if (downloaded) {
                    when (index) {
                        1 -> startAustriaMapDownload()
                        2 -> deleteAustriaMap()
                    }
                } else if (index == 1) {
                    startAustriaMapDownload()
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun startAustriaMapDownload() {
        activeMapDownloadId =
            mapDownloadManager.startAustriaDownload()

        destinationText.text =
            "Austria haritası indiriliyor: %0"

        monitorMapDownload()
    }

    private fun monitorMapDownload() {
        if (activeMapDownloadId < 0L) {
            return
        }

        val status = mapDownloadManager.getStatus(
            activeMapDownloadId
        )

        if (status == null) {
            destinationText.text =
                "Harita indirme bilgisi alınamadı."
            return
        }

        when (status.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                destinationText.text =
                    "Austria haritası indirildi: %100"

                Toast.makeText(
                    this,
                    "Austria haritası indirildi.",
                    Toast.LENGTH_LONG
                ).show()

                activeMapDownloadId = -1L
            }

            DownloadManager.STATUS_FAILED -> {
                destinationText.text =
                    "Austria haritası indirilemedi."

                Toast.makeText(
                    this,
                    "Harita indirme başarısız.",
                    Toast.LENGTH_LONG
                ).show()

                activeMapDownloadId = -1L
            }

            DownloadManager.STATUS_PAUSED -> {
                destinationText.text =
                    "Harita indirme duraklatıldı: " +
                        "%${status.percent}"

                scheduleDownloadCheck()
            }

            else -> {
                destinationText.text =
                    "Austria haritası indiriliyor: " +
                        "%${status.percent}"

                scheduleDownloadCheck()
            }
        }
    }

    private fun scheduleDownloadCheck() {
        downloadHandler.postDelayed(
            {
                monitorMapDownload()
            },
            1000L
        )
    }

    private fun deleteAustriaMap() {
        if (mapDownloadManager.deleteAustriaMap()) {
            destinationText.text =
                "Austria çevrimdışı haritası silindi."

            Toast.makeText(
                this,
                "Harita silindi.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "Harita silinemedi.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showRouteInstructions() {
        if (currentRouteSteps.isEmpty()) {
            Toast.makeText(
                this,
                "Önce rota oluştur.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val instructions = currentRouteSteps
            .mapIndexed { index, step ->
                val distanceText =
                    if (step.distanceMeters >= 1000.0) {
                        "%.1f km".format(
                            step.distanceMeters / 1000.0
                        )
                    } else {
                        "${step.distanceMeters.toInt()} m"
                    }

                "${index + 1}. ${step.instruction} ($distanceText)"
            }
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Yol Tarifi")
            .setItems(instructions) { _, index ->
                voiceManager.speak(
                    currentRouteSteps[index].instruction
                )
            }
            .setPositiveButton("Kapat", null)
            .show()
    }

    private fun clearDestination() {
        if (::mapLibreMap.isInitialized) {
            destinationMarker?.let {
                mapLibreMap.removeMarker(it)
            }

            mapLibreMap.style
                ?.getSourceAs<GeoJsonSource>(routeSourceId)
                ?.setGeoJson(
                    FeatureCollection.fromFeatures(emptyArray())
                )
        }

        destinationMarker = null
        destinationPoint = null
        currentRouteSteps = emptyList()

        destinationText.text =
            "Hedef seçmek için haritaya uzun bas"
    }

    private fun updateDestinationInfo() {
        val end = destinationPoint ?: return
        val start = currentLocation

        if (start == null) {
            destinationText.text =
                "Hedef: %.5f, %.5f".format(
                    end.latitude,
                    end.longitude
                )
            return
        }

        val distanceKm = haversineKm(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude
        )

        destinationText.text =
            "Hedef: %.5f, %.5f\nKuş uçuşu mesafe: %.2f km".format(
                end.latitude,
                end.longitude,
                distanceKm
            )
    }

    private fun haversineKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val value =
            sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        return 2 * earthRadiusKm * asin(sqrt(value))
    }

    private fun requestLocationPermission() {
        val finePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val coarsePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (
            finePermission == PackageManager.PERMISSION_GRANTED ||
            coarsePermission == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startLocationUpdates() {
        val finePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val coarsePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (
            finePermission != PackageManager.PERMISSION_GRANTED &&
            coarsePermission != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val gpsEnabled =
            locationManager.isProviderEnabled(
                LocationManager.GPS_PROVIDER
            )

        val networkEnabled =
            locationManager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
            )

        if (!gpsEnabled && !networkEnabled) {
            Toast.makeText(
                this,
                "Telefonun konum servisini aç.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (gpsEnabled) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                3f,
                this
            )
        }

        if (networkEnabled) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                3000L,
                5f,
                this
            )
        }

        val lastLocation =
            locationManager.getLastKnownLocation(
                LocationManager.GPS_PROVIDER
            ) ?: locationManager.getLastKnownLocation(
                LocationManager.NETWORK_PROVIDER
            )

        lastLocation?.let {
            showLocation(it)
        }
    }

    private fun showLocation(location: Location) {
        if (!::mapLibreMap.isInitialized) {
            return
        }

        currentLocation = location

        if (!radarsLoaded) {
            loadNearbyRadars()
        }

        val point = LatLng(
            location.latitude,
            location.longitude
        )

        currentMarker?.let {
            mapLibreMap.removeMarker(it)
        }

        currentMarker = mapLibreMap.addMarker(
            MarkerOptions()
                .position(point)
                .title("Konumum")
                .snippet(
                    "Doğruluk: ${location.accuracy.toInt()} metre"
                )
        )

        updateDestinationInfo()

        mapLibreMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(point)
                    .zoom(17.5)
                    .build()
            ),
            1200
        )
    }

    override fun onLocationChanged(location: Location) {
        showLocation(location)
    }

    override fun onProviderEnabled(provider: String) {
        requestLocationPermission()
    }

    override fun onProviderDisabled(provider: String) {
        Toast.makeText(
            this,
            "Konum sağlayıcısı kapatıldı.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()

        if (::updateManager.isInitialized) {
            updateManager.resumePendingInstall()
        }
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(this)
        }

        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        searchHandler.removeCallbacksAndMessages(null)
        downloadHandler.removeCallbacksAndMessages(null)
        if (::locationManager.isInitialized) {
        if (::voiceManager.isInitialized) {
            voiceManager.shutdown()
        }

            locationManager.removeUpdates(this)
        }

        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
