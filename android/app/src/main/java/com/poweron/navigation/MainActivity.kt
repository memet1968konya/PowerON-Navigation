package com.poweron.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var mapLibreMap: MapLibreMap
    private lateinit var locationManager: LocationManager

    private var currentMarker: Marker? = null

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
        val locationButton: Button = findViewById(R.id.locationButton)

        locationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager

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

                requestLocationPermission()
            }
        }

        locationButton.setOnClickListener {
            requestLocationPermission()
        }

        UpdateManager(this).checkForUpdate()
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
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        val networkEnabled =
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

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
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(
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

        mapLibreMap.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory
                .newCameraPosition(
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
        locationManager.removeUpdates(this)
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
