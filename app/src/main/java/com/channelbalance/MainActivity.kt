package com.channelbalance

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.button.MaterialButton
import kotlin.random.Random

/**
 * Launcher activity: on/off switch for the background balance service, a button
 * that opens the saved-configs menu, a live status line, plus a bottom quote
 * carousel that picks a random hand-drawn quote each time the app opens.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serviceSwitch: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var quoteImageView: ImageView
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton

    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val quoteDrawables = mutableListOf<Int>()
    private var currentQuote = 0
    private var noSessionToastShown = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (serviceSwitch.isChecked) BalanceService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceSwitch = findViewById(R.id.service_switch)
        statusText = findViewById(R.id.status_text)
        serviceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    BalanceService.start(this)
                }
            } else {
                BalanceService.stop(this)
            }
            refreshStatus()
        }

        findViewById<MaterialButton>(R.id.btn_open_configs).setOnClickListener {
            startActivity(Intent(this, ProfileListActivity::class.java))
        }

        setupQuoteCarousel()
    }

    override fun onResume() {
        super.onResume()
        serviceSwitch.isChecked = isServiceRunning()
        noSessionToastShown = false
        refreshStatus()
        mainHandler.post(statusTicker)
        mainHandler.postDelayed({ warnIfNoSession() }, 1500)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(statusTicker)
    }

    /** Live status: whether the service is on and if audio sessions are attached. */
    private fun refreshStatus() {
        val running = isServiceRunning()
        if (!running) {
            statusText.text = "Enable the balance service to apply your configs"
            return
        }

        val count = ChannelBalancer.attachedSessionCount
        statusText.text = if (count > 0) {
            "balancing $count active audio session(s)"
        } else {
            "waiting for audio... START playback after enabling the service"
        }
        warnIfNoSession()
    }

    /** One-time toast when the service is up but no audio session is attached yet. */
    private fun warnIfNoSession() {
        if (noSessionToastShown) return
        if (!isServiceRunning()) return
        if (ChannelBalancer.attachedSessionCount > 0) return

        noSessionToastShown = true
        Toast.makeText(
            this,
            "NO audio session detected. START playback AFTER enabling the service.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == BalanceService::class.java.name }
    }

    private fun setupQuoteCarousel() {
        quoteImageView = findViewById(R.id.quote_image)
        prevButton = findViewById(R.id.btn_quote_prev)
        nextButton = findViewById(R.id.btn_quote_next)
        val strip = findViewById<LinearLayout>(R.id.quote_strip)

        val names = resources.getStringArray(R.array.quote_images)
        names.forEach { name ->
            val resId = resources.getIdentifier(name, "drawable", packageName)
            if (resId != 0) quoteDrawables.add(resId)
        }

        if (quoteDrawables.isEmpty()) {
            strip.visibility = View.GONE
            return
        }

        currentQuote = Random.nextInt(quoteDrawables.size)
        showQuote(currentQuote)

        prevButton.setOnClickListener { showQuote(wrap(currentQuote - 1)) }
        nextButton.setOnClickListener { showQuote(wrap(currentQuote + 1)) }

        val leftRes = resIdForName(getString(R.string.arrow_left_res))
        val rightRes = resIdForName(getString(R.string.arrow_right_res))
        if (leftRes != 0) prevButton.setImageResource(leftRes) else prevButton.visibility = View.GONE
        if (rightRes != 0) nextButton.setImageResource(rightRes) else nextButton.visibility = View.GONE
    }

    private fun showQuote(index: Int) {
        currentQuote = wrap(index)
        quoteImageView.setImageResource(quoteDrawables[currentQuote])
    }

    private fun wrap(index: Int): Int {
        val size = quoteDrawables.size
        if (size == 0) return 0
        return ((index % size) + size) % size
    }

    private fun resIdForName(name: String): Int =
        resources.getIdentifier(name, "drawable", packageName)
}
