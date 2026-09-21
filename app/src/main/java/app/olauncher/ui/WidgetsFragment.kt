package app.olauncher.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentWidgetsBinding
import app.olauncher.databinding.ItemWidgetCalendarBinding
import app.olauncher.databinding.ItemWidgetGalleryBinding
import app.olauncher.databinding.ItemWidgetHostedBinding
import app.olauncher.databinding.ItemWidgetTasksBinding
import app.olauncher.helper.AppWidgetHostHelper
import app.olauncher.listener.OnSwipeTouchListener

class WidgetsFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: WidgetsViewModel
    private lateinit var hostHelper: AppWidgetHostHelper

    private var _binding: FragmentWidgetsBinding? = null
    private val binding get() = _binding!!

    private var tasksBinding: ItemWidgetTasksBinding? = null
    private var calendarBinding: ItemWidgetCalendarBinding? = null
    private var galleryBinding: ItemWidgetGalleryBinding? = null

    // Widget id being bound/configured right now. Kept so a cancelled flow can release it again.
    private var pendingWidgetPackageName = ""
    private var pendingWidgetId = -1

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            prefs.galleryImageUri = it.toString()
            viewModel.setGalleryImageUri(it.toString())
        }
    }

    private val bindWidgetLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val appWidgetId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            ?.takeIf { it != -1 } ?: pendingWidgetId
        if (result.resultCode == Activity.RESULT_OK && appWidgetId != -1) {
            configureAppWidget(pendingWidgetPackageName, appWidgetId)
        } else {
            abandonPendingWidget()
        }
    }

    private val configureWidgetLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingWidgetId != -1) {
            saveWidgetIdAndRefresh(pendingWidgetPackageName, pendingWidgetId)
            clearPendingWidget()
        } else {
            abandonPendingWidget()
        }
    }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.loadCalendarEvents(requireContext())
        } else {
            Toast.makeText(context, "Calendar permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWidgetsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = ViewModelProvider(this)[WidgetsViewModel::class.java]
        hostHelper = AppWidgetHostHelper.getInstance(requireContext())
        hostHelper.startListening()

        setupSwipeGesture()
        binding.btnBackToHome.setOnClickListener { findNavController().navigateUp() }

        setupTasksWidget()
        setupCalendarWidget()
        observeCalendarEvents()
        setupGalleryWidget()
    }

    override fun onResume() {
        super.onResume()
        // Providers stop pushing RemoteViews while the host is not listening; ask them to redraw.
        hostHelper.startListening()
        hostHelper.requestUpdate(prefs.googleTasksWidgetId)
        hostHelper.requestUpdate(prefs.googleCalendarWidgetId)
        if (hasCalendarPermission()) viewModel.loadCalendarEvents(requireContext())
    }

    private fun setupSwipeGesture() {
        val swipeListener = object : OnSwipeTouchListener(requireContext()) {
            override fun onSwipeLeft() {
                findNavController().navigateUp()
            }

            override fun onSwipeRight() {
                findNavController().navigateUp()
            }
        }
        binding.svWidgetsLayout.setOnTouchListener(swipeListener)
    }

    private fun setupTasksWidget() {
        binding.tasksWidgetContainer.removeAllViews()
        tasksBinding = null

        val hosted = renderHostedWidget(
            binding.tasksWidgetContainer,
            prefs.googleTasksWidgetId,
            Constants.GOOGLE_TASKS_PACKAGE_NAME,
            getString(R.string.google_tasks)
        )
        if (hosted) return

        // Fallback built-in Tasks card
        tasksBinding = ItemWidgetTasksBinding.inflate(layoutInflater, binding.tasksWidgetContainer, true)
        tasksBinding?.btnConfigureTasks?.setOnClickListener {
            bindAppWidget(Constants.GOOGLE_TASKS_PACKAGE_NAME)
        }
        tasksBinding?.llTasksFallback?.setOnClickListener {
            bindAppWidget(Constants.GOOGLE_TASKS_PACKAGE_NAME)
        }
    }

    private fun setupCalendarWidget() {
        binding.calendarWidgetContainer.removeAllViews()
        calendarBinding = null

        val hosted = renderHostedWidget(
            binding.calendarWidgetContainer,
            prefs.googleCalendarWidgetId,
            Constants.GOOGLE_CALENDAR_PACKAGE_NAME,
            getString(R.string.google_calendar)
        )
        if (hosted) return

        // Fallback built-in Calendar card
        calendarBinding = ItemWidgetCalendarBinding.inflate(layoutInflater, binding.calendarWidgetContainer, true)
        calendarBinding?.btnConfigureCalendar?.setOnClickListener {
            bindAppWidget(Constants.GOOGLE_CALENDAR_PACKAGE_NAME)
        }

        if (hasCalendarPermission()) {
            viewModel.loadCalendarEvents(requireContext())
        } else {
            calendarBinding?.tvCalendarStatus?.setText(R.string.calendar_permission_required)
            calendarBinding?.llCalendarFallback?.setOnClickListener {
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }
    }

    private fun observeCalendarEvents() {
        viewModel.calendarEvents.observe(viewLifecycleOwner) { events ->
            val status = calendarBinding?.tvCalendarStatus ?: return@observe
            if (events.isNullOrEmpty()) {
                status.setText(R.string.no_upcoming_events)
            } else {
                status.text = events.joinToString("\n") { "• ${it.title} (${it.timeRange})" }
            }
        }
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Renders the hosted AppWidget for [appWidgetId] into [container], with a header that lets the
     * user re-run the provider's configuration or drop the widget. Returns false when there is no
     * usable widget, so the caller can fall back to the built-in card.
     */
    private fun renderHostedWidget(
        container: FrameLayout,
        appWidgetId: Int,
        packageName: String,
        title: String
    ): Boolean {
        if (appWidgetId == -1) return false
        if (hostHelper.getBoundWidgetInfo(appWidgetId) == null) {
            // Provider uninstalled, or the id was allocated but never really bound - release it.
            releaseWidget(packageName, appWidgetId)
            return false
        }

        val widgetView = hostHelper.createWidgetView(requireContext(), appWidgetId) ?: return false

        val hosted = ItemWidgetHostedBinding.inflate(layoutInflater, container, true)
        hosted.tvHostedWidgetHeader.text = title
        val pxHeight = (350 * resources.displayMetrics.density).toInt()
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, pxHeight)
        hosted.hostedWidgetHolder.addView(widgetView, lp)

        hosted.btnHostedWidgetConfigure.setOnClickListener {
            configureAppWidget(packageName, appWidgetId, rebindIfNotConfigurable = true)
        }
        hosted.btnHostedWidgetRemove.setOnClickListener {
            releaseWidget(packageName, appWidgetId)
            refreshWidget(packageName)
        }
        return true
    }

    private fun bindAppWidget(packageName: String) {
        val info = hostHelper.findWidgetProviderInfo(packageName)
        if (info == null) {
            Toast.makeText(context, "Widget not found", Toast.LENGTH_SHORT).show()
            return
        }
        val appWidgetId = hostHelper.allocateAppWidgetId()
        pendingWidgetPackageName = packageName
        pendingWidgetId = appWidgetId

        if (hostHelper.appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)) {
            configureAppWidget(packageName, appWidgetId)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }
            bindWidgetLauncher.launch(intent)
        }
    }

    /**
     * A widget whose provider declares a configuration activity keeps showing its initialLayout -
     * the "Loading..." placeholder - until that activity has run. Binding the id is not enough.
     */
    private fun configureAppWidget(
        packageName: String,
        appWidgetId: Int,
        rebindIfNotConfigurable: Boolean = false
    ) {
        pendingWidgetPackageName = packageName
        pendingWidgetId = appWidgetId

        val configure = hostHelper.getBoundWidgetInfo(appWidgetId)?.configure
        if (configure == null) {
            if (rebindIfNotConfigurable) {
                releaseWidget(packageName, appWidgetId)
                clearPendingWidget()
                bindAppWidget(packageName)
            } else {
                saveWidgetIdAndRefresh(packageName, appWidgetId)
                clearPendingWidget()
            }
            return
        }

        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
            component = configure
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        try {
            configureWidgetLauncher.launch(intent)
        } catch (e: Exception) {
            // Most configuration activities are not exported, so launching them directly throws.
            // The host API asks the system to start them for us, but the result then lands on the
            // activity instead of here - onResume picks the configured widget up either way.
            e.printStackTrace()
            try {
                hostHelper.appWidgetHost.startAppWidgetConfigureActivityForResult(
                    requireActivity(), appWidgetId, 0, REQUEST_CONFIGURE_WIDGET, null
                )
                saveWidgetIdAndRefresh(packageName, appWidgetId)
                clearPendingWidget()
            } catch (e2: Exception) {
                e2.printStackTrace()
                Toast.makeText(context, "Could not configure widget", Toast.LENGTH_SHORT).show()
                abandonPendingWidget()
            }
        }
    }

    private fun abandonPendingWidget() {
        if (pendingWidgetId != -1 && pendingWidgetId != storedWidgetId(pendingWidgetPackageName)) {
            hostHelper.deleteAppWidgetId(pendingWidgetId)
        }
        clearPendingWidget()
    }

    private fun clearPendingWidget() {
        pendingWidgetId = -1
        pendingWidgetPackageName = ""
    }

    private fun releaseWidget(packageName: String, appWidgetId: Int) {
        hostHelper.deleteAppWidgetId(appWidgetId)
        when (packageName) {
            Constants.GOOGLE_TASKS_PACKAGE_NAME -> prefs.googleTasksWidgetId = -1
            Constants.GOOGLE_CALENDAR_PACKAGE_NAME -> prefs.googleCalendarWidgetId = -1
        }
    }

    private fun storedWidgetId(packageName: String): Int = when (packageName) {
        Constants.GOOGLE_TASKS_PACKAGE_NAME -> prefs.googleTasksWidgetId
        Constants.GOOGLE_CALENDAR_PACKAGE_NAME -> prefs.googleCalendarWidgetId
        else -> -1
    }

    private fun saveWidgetIdAndRefresh(packageName: String, appWidgetId: Int) {
        val previousId = storedWidgetId(packageName)
        if (previousId != -1 && previousId != appWidgetId) hostHelper.deleteAppWidgetId(previousId)

        when (packageName) {
            Constants.GOOGLE_TASKS_PACKAGE_NAME -> prefs.googleTasksWidgetId = appWidgetId
            Constants.GOOGLE_CALENDAR_PACKAGE_NAME -> prefs.googleCalendarWidgetId = appWidgetId
        }
        refreshWidget(packageName)
    }

    private fun refreshWidget(packageName: String) {
        if (_binding == null) return
        when (packageName) {
            Constants.GOOGLE_TASKS_PACKAGE_NAME -> setupTasksWidget()
            Constants.GOOGLE_CALENDAR_PACKAGE_NAME -> setupCalendarWidget()
        }
    }

    private fun setupGalleryWidget() {
        galleryBinding = ItemWidgetGalleryBinding.inflate(layoutInflater, binding.galleryWidgetContainer, true)

        val pickListener = View.OnClickListener {
            selectImageLauncher.launch(arrayOf("image/*"))
        }

        galleryBinding?.btnSelectImage?.setOnClickListener(pickListener)
        galleryBinding?.btnEditImage?.setOnClickListener(pickListener)

        galleryBinding?.ivGalleryImage?.setOnClickListener {
            val uriStr = prefs.galleryImageUri
            if (uriStr.isNotEmpty()) {
                val uri = Uri.parse(uriStr)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "No app found to open image", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val savedUri = prefs.galleryImageUri
        if (savedUri.isNotEmpty()) {
            displayGalleryImage(savedUri)
        }

        viewModel.galleryImageUri.observe(viewLifecycleOwner) { uriStr ->
            displayGalleryImage(uriStr)
        }
    }

    private fun displayGalleryImage(uriStr: String) {
        if (uriStr.isEmpty() || galleryBinding == null) return
        try {
            val uri = Uri.parse(uriStr)
            galleryBinding?.ivGalleryImage?.setImageURI(uri)
            galleryBinding?.ivGalleryImage?.visibility = View.VISIBLE
            galleryBinding?.btnEditImage?.visibility = View.VISIBLE
            galleryBinding?.llEmptyGallery?.visibility = View.GONE
        } catch (e: Exception) {
            e.printStackTrace()
            galleryBinding?.ivGalleryImage?.visibility = View.GONE
            galleryBinding?.btnEditImage?.visibility = View.GONE
            galleryBinding?.llEmptyGallery?.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tasksBinding = null
        calendarBinding = null
        galleryBinding = null
        _binding = null
    }

    companion object {
        private const val REQUEST_CONFIGURE_WIDGET = 5001
    }
}
