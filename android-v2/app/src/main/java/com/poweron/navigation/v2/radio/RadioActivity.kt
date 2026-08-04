package com.poweron.navigation.v2.radio

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.poweron.navigation.v2.R

class RadioActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var stationNameInput: EditText
    private lateinit var streamUrlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var stationClient: StationClient
    private lateinit var stationListView: ListView
    private lateinit var stationListTitle: TextView
    private lateinit var stationAdapter: ArrayAdapter<String>
    private var stations: List<RadioStation> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radio)

        stationNameInput = findViewById(R.id.stationNameInput)
        streamUrlInput = findViewById(R.id.streamUrlInput)
        statusText = findViewById(R.id.radioStatusText)
        stationListView =
            findViewById(R.id.stationListView)

        stationListTitle =
            findViewById(R.id.stationListTitle)

        stationClient = StationClient()

        stationAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        )

        stationListView.adapter = stationAdapter

        val playButton: Button =
            findViewById(R.id.playRadioButton)

        val stopButton: Button =
            findViewById(R.id.stopRadioButton)

        val closeButton: Button =
            findViewById(R.id.closeRadioButton)

        player = ExoPlayer.Builder(this).build()

        loadStations()

        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    statusText.text = when (state) {
                        Player.STATE_BUFFERING ->
                            "Yayın bağlanıyor…"

                        Player.STATE_READY ->
                            if (player.playWhenReady) {
                                "Yayın dinleniyor"
                            } else {
                                "Yayın hazır"
                            }

                        Player.STATE_ENDED ->
                            "Yayın sona erdi"

                        else ->
                            statusText.text
                    }
                }

                override fun onPlayerError(
                    error: PlaybackException
                ) {
                    statusText.text =
                        "Yayın hatası: ${error.errorCodeName}"
                }
            }
        )

        stationListView.setOnItemClickListener {
                _, _, position, _ ->

            val station = stations.getOrNull(position)
                ?: return@setOnItemClickListener

            stationNameInput.setText(station.name)
            streamUrlInput.setText(station.url)

            startRadio()
        }

        playButton.setOnClickListener {
            startRadio()
        }

        stopButton.setOnClickListener {
            player.stop()
            statusText.text = "Yayın durduruldu"
        }

        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun loadStations() {
        stationListTitle.text =
            "GitHub'dan istasyonlar yükleniyor…"

        stationClient.load(
            onSuccess = { loadedStations ->
                runOnUiThread {
                    stations = loadedStations

                    stationAdapter.clear()
                    stationAdapter.addAll(
                        loadedStations.map {
                            "${it.name} • ${it.category} • ${it.country}"
                        }
                    )
                    stationAdapter.notifyDataSetChanged()

                    stationListTitle.text =
                        if (loadedStations.isEmpty()) {
                            "Çalışan yayın adresi bulunamadı."
                        } else {
                            "${loadedStations.size} yayın bulundu"
                        }
                }
            },
            onError = { message ->
                runOnUiThread {
                    stationListTitle.text = message
                }
            }
        )
    }

    private fun startRadio() {
        val name = stationNameInput.text
            .toString()
            .trim()
            .ifBlank { "Telsiz yayını" }

        val url = streamUrlInput.text
            .toString()
            .trim()

        if (
            !url.startsWith("https://") &&
            !url.startsWith("http://")
        ) {
            Toast.makeText(
                this,
                "Geçerli bir http veya https yayın adresi gir.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        statusText.text = "$name bağlanıyor…"

        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
