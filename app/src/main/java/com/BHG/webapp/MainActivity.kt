package com.BHG.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.BHG.webapp.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import java.net.URISyntaxException
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private var mainFrameError = false

    private var lastFailedUrl: String? = null

    private var customView: View? = null

    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var isFullscreen = false

    private var pendingPermissionRequest: PermissionRequest? = null

    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null

    private var pendingGeolocationOrigin: String? = null

    private var notificationPromptRequested = false

    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    private val loadTimeout = Runnable {
        showLoadingIndicator(false)
        binding.swipeRefresh.isRefreshing = false
        showErrorPage()
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileCallback ?: return@registerForActivityResult
            pendingFileCallback = null
            callback.onReceiveValue(extractUris(result.resultCode, result.data))
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            resolvePendingMediaPermission()
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            resolvePendingGeolocation()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        }

    private fun extractUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null

        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            val uris = ArrayList<Uri>(clip.itemCount)
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let(uris::add)
            }
            if (uris.isNotEmpty()) return uris.toTypedArray()
        }

        return data.data?.let { arrayOf(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Guard: never show the app while signed out (killed session, post sign-out, etc.).
        if (FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            if (isFullscreen) {
                v.updatePadding(left = 0, top = 0, right = 0, bottom = 0)
                return@setOnApplyWindowInsetsListener insets
            }

            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom)
            )
            insets
        }

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            setBuiltInZoomControls(true)
            setDisplayZoomControls(false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        applyProgressBarSizes()
        showLoadingIndicator(false)

        if (AppConfig.ENABLE_OFFLINE_PAGE) {
            binding.retryButton.setOnClickListener { retryLoad() }
        } else {
            binding.errorView.visibility = View.GONE
        }

        if (AppConfig.ENABLE_SWIPE_REFRESH) {
            configureSwipeRefresh()
        } else {
            binding.swipeRefresh.isEnabled = false
        }

        if (AppConfig.ENABLE_DOWNLOADS) {
            binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https") return false
                if (!AppConfig.OPEN_EXTERNAL_LINKS) return true

                return launchExternalApp(url, scheme)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mainFrameError = false
                startLoadTimeout()
                showLoadingIndicator(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                cancelLoadTimeout()
                showLoadingIndicator(false)
                binding.swipeRefresh.isRefreshing = false
                if (!mainFrameError) {
                    lastFailedUrl = null
                    hideErrorPage()
                    requestNotificationPermissionIfNeeded()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return

                mainFrameError = true
                lastFailedUrl = request.url.toString()
                cancelLoadTimeout()
                showLoadingIndicator(false)
                binding.swipeRefresh.isRefreshing = false
                showErrorPage()
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (binding.errorView.visibility == View.VISIBLE &&
                    !mainFrameError &&
                    newProgress >= AppConfig.RETRY_REVEAL_PROGRESS_PERCENT.coerceIn(1, 100)
                ) {
                    hideErrorPage()
                }

                if (AppConfig.SHOW_HORIZONTAL_PROGRESS_BAR) {
                    binding.progressBar.progress = newProgress
                }
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                if (!AppConfig.ENABLE_FILE_UPLOAD) return false

                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback

                val intent = fileChooserParams.createIntent()
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, AppConfig.ALLOW_MULTIPLE_FILES)

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    pendingFileCallback = null
                    filePathCallback.onReceiveValue(null)
                    true
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (pendingPermissionRequest != null) {
                    request.deny()
                    return
                }

                val missing = request.resources.mapNotNull(::androidPermissionFor)
                    .filterNot(::hasPermission)
                    .distinct()

                if (missing.isEmpty()) {
                    request.grant(request.resources)
                    return
                }

                pendingPermissionRequest = request
                mediaPermissionLauncher.launch(missing.toTypedArray())
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                    return
                }

                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin ?: origin, false, false)
                pendingGeolocationCallback = callback
                pendingGeolocationOrigin = origin
                locationPermissionLauncher.launch(locationPermissions)
            }

            override fun onGeolocationPermissionsHidePrompt() {
                pendingGeolocationCallback = null
                pendingGeolocationOrigin = null
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (!AppConfig.ENABLE_FULLSCREEN_VIDEO || customView != null) {
                    callback.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback
                isFullscreen = true
                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.root.requestApplyInsets()
                WindowInsetsControllerCompat(window, binding.root).apply {
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() {
                exitFullscreen(notifyCallback = false)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    exitFullscreen(notifyCallback = true)
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            binding.webView.loadUrl(AppConfig.HOME_URL)
        } else if (binding.webView.restoreState(savedInstanceState) == null) {
            binding.webView.loadUrl(AppConfig.HOME_URL)
        }
    }

    private fun launchExternalApp(url: Uri, scheme: String): Boolean {
        if (scheme == "intent") return launchIntentUrl(url.toString())

        return try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
            true
        } catch (_: ActivityNotFoundException) {
            true
        }
    }

    private fun launchIntentUrl(rawUrl: String): Boolean {
        val intent = try {
            Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
        } catch (_: URISyntaxException) {
            return true
        }

        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null

        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            val fallbackUri = fallback?.let(Uri::parse)
            val fallbackScheme = fallbackUri?.scheme?.lowercase()
            if (fallbackScheme == "http" || fallbackScheme == "https") {
                binding.webView.loadUrl(fallback)
            }
            true
        }
    }

    private fun exitFullscreen(notifyCallback: Boolean) {
        val view = customView ?: return
        val callback = customViewCallback
        customView = null
        customViewCallback = null
        isFullscreen = false

        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
        binding.root.requestApplyInsets()

        if (notifyCallback) callback?.onCustomViewHidden()
    }

    private fun configureSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }

        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.webView.scrollY > 0
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https") return

        val manager = getSystemService(DownloadManager::class.java) ?: return

        val request = DownloadManager.Request(uri)
        request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        mimeType?.let(request::setMimeType)
        userAgent?.takeIf { it.isNotEmpty() }?.let { request.addRequestHeader("User-Agent", it) }
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }

        try {
            manager.enqueue(request)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
    }

    private fun showErrorPage() {
        if (!AppConfig.ENABLE_OFFLINE_PAGE) return

        binding.retryProgress.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        binding.errorMessage.setText(
            if (isOffline()) R.string.no_internet_connection else R.string.page_load_failed
        )
        binding.errorView.visibility = View.VISIBLE
    }

    private fun hideErrorPage() {
        if (binding.errorView.visibility != View.GONE) {
            binding.errorView.visibility = View.GONE
        }
    }

    private fun retryLoad() {
        mainFrameError = false
        binding.retryButton.visibility = View.GONE
        binding.retryProgress.visibility = View.VISIBLE
        binding.webView.loadUrl(lastFailedUrl ?: AppConfig.HOME_URL)
    }

    private fun isOffline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startLoadTimeout() {
        if (!AppConfig.ENABLE_OFFLINE_PAGE) return
        cancelLoadTimeout()
        mainHandler.postDelayed(loadTimeout, AppConfig.PAGE_LOAD_TIMEOUT_MS.coerceAtLeast(1000L))
    }

    private fun cancelLoadTimeout() {
        mainHandler.removeCallbacks(loadTimeout)
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun requestNotificationPermissionIfNeeded() {
        if (notificationPromptRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return

        notificationPromptRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun resolvePendingMediaPermission() {
        val request = pendingPermissionRequest ?: return
        pendingPermissionRequest = null

        val granted = request.resources.filter { resource ->
            val permission = androidPermissionFor(resource)
            permission == null || hasPermission(permission)
        }

        if (granted.isEmpty()) {
            request.deny()
        } else {
            request.grant(granted.toTypedArray())
        }
    }

    private fun resolvePendingGeolocation() {
        val callback = pendingGeolocationCallback
        val origin = pendingGeolocationOrigin
        pendingGeolocationCallback = null
        pendingGeolocationOrigin = null

        if (callback == null || origin == null) return
        callback.invoke(origin, hasLocationPermission(), false)
    }

    private fun applyProgressBarSizes() {
        binding.progressBar.layoutParams = binding.progressBar.layoutParams.apply {
            height = dpToPx(AppConfig.HORIZONTAL_PROGRESS_BAR_HEIGHT_DP.coerceAtLeast(1))
        }

        val diameter = dpToPx(AppConfig.CIRCULAR_PROGRESS_SIZE_DP.coerceAtLeast(1))
        binding.circularProgress.layoutParams = binding.circularProgress.layoutParams.apply {
            width = diameter
            height = diameter
        }
    }

    private fun showLoadingIndicator(visible: Boolean) {
        binding.progressBar.visibility =
            if (AppConfig.SHOW_HORIZONTAL_PROGRESS_BAR && visible) View.VISIBLE else View.GONE
        binding.circularProgress.visibility =
            if (AppConfig.SHOW_CIRCULAR_PROGRESS && visible) View.VISIBLE else View.GONE
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        cancelLoadTimeout()
        exitFullscreen(notifyCallback = false)
        pendingPermissionRequest = null
        pendingGeolocationCallback = null
        pendingGeolocationOrigin = null
        super.onDestroy()
    }
}
