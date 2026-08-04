package com.poweron.navigation.v2

import android.Manifest
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

    private var currentMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var currentLocation: Location? = null
    private var destinationPoint: LatLng? = null

    private val routeSourceId = "route-source"
    private val routeLayerId = "route-layer"

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

        val locationButton: Button =
            findViewById(R.id.locationButton)

        val routeButton: Button =
            findViewById(R.id.routeButton)

        val clearDestinationButton: Button =
            findViewById(R.id.clearDestinationButton)

        locationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager

        updateManager = UpdateManager(this)

        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            mapLibreMap = map

            map.setStyle(
                Style.Builder().fromUri(
                    "https://tiles.openfreemap.org/styles/liberty"
                )
            ) {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(48.8566, 2.3522))
                    .zoom(11.0)
                    .build()

                setupRouteLayer()

                mapLibreMap.addOnMapLongClickListener { point ->
                    selectDestination(point)
                    true
                }

                requestLocationPermission()
            }
        }

        locationButton.setOnClickListener {
            requestLocationPermission()
        }

        routeButton.setOnClickListener {
            drawRouteToDestination()
        }

        clearDestinationButton.setOnClickListener {
            clearDestination()
        }

        updateManager.checkForUpdate()
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
                        PropertyFactory.lineWidth(7f),
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
        val start = currentLocation
        val end = destinationPoint

        if (start == null) {
            Toast.makeText(
                this,
                "Önce telefonun konumu bulunmalı.",
                Toast.LENGTH_LONG
            ).show()

            requestLocationPermission()
            return
        }

        if (end == null) {
            Toast.makeText(
                this,
                "Haritaya uzun basarak hedef seç.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val line = LineString.fromLngLats(
            listOf(
                Point.fromLngLat(
                    start.longitude,
                    start.latitude
                ),
                Point.fromLngLat(
                    end.longitude,
                    end.latitude
                )
            )
        )

        mapLibreMap.style
            ?.getSourceAs<GeoJsonSource>(routeSourceId)
            ?.setGeoJson(Feature.fromGeometry(line))

        updateDestinationInfo()
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
                    .zoom(16.0)
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
        if (::locationManager.isInitialized) {
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
