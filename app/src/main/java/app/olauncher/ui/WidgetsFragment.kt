package app.olauncher.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.text.format.DateUtils
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
import app.olauncher.databinding.ItemCalendarEventBinding
import app.olauncher.databinding.ItemWidgetCalendarBinding
import app.olauncher.databinding.ItemWidgetGalleryBinding
import app.olauncher.databinding.ItemWidgetHostedBinding
import app.olauncher.databinding.ItemWidgetTasksBinding
import app.olauncher.helper.AppWidgetHostHelper
import app.olauncher.listener.OnSwipeTouchListener
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToLong

class WidgetsFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: WidgetsViewModel
    private lateinit var hostHelper: AppWidgetHostHelper

    private var _binding: FragmentWidgetsBinding? = null
    private val binding get() = _binding!!

    private var tasksBinding: ItemWidgetTasksBinding? = null
    private var calendarBinding: ItemWidgetCalendarBinding? = null
    private var galleryBinding: ItemWidgetGalleryBinding? = null

    private var availableCalendars: List<CalendarInfo> = emptyList()

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
            viewModel.loadCalendars(requireContext())
            viewModel.loadCalendarEvents(requireContext(), prefs.calendarId)
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
        if (hasCalendarPermission()) viewModel.loadCalendarEvents(requireContext(), prefs.calendarId)
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

    /**
     * Calendar is drawn from CalendarContract rather than by hosting Google Calendar's own widget:
     * that widget fills its list through a RemoteViewsService that never connects inside our host,
     * so it sits on "Loading..." forever. Reading the provider ourselves is both reliable and
     * cheaper to style.
     */
    private fun setupCalendarWidget() {
        binding.calendarWidgetContainer.removeAllViews()
        calendarBinding = null

        // Release any Google Calendar widget bound by an earlier version of the app.
        if (prefs.googleCalendarWidgetId != -1) {
            releaseWidget(Constants.GOOGLE_CALENDAR_PACKAGE_NAME, prefs.googleCalendarWidgetId)
        }

        val card = ItemWidgetCalendarBinding.inflate(layoutInflater, binding.calendarWidgetContainer, true)
        calendarBinding = card
        card.btnConfigureCalendar.setOnClickListener { openCalendarApp() }

        card.btnSelectCalendar.visibility = View.GONE

        if (hasCalendarPermission()) {
            viewModel.loadCalendars(requireContext())
            viewModel.loadCalendarEvents(requireContext(), prefs.calendarId)
        } else {
            card.tvCalendarStatus.setText(R.string.calendar_permission_required)
            card.llCalendarFallback.setOnClickListener {
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }
    }

    private fun observeCalendarEvents() {
        viewModel.calendarEvents.observe(viewLifecycleOwner) { events ->
            renderCalendarEvents(events.orEmpty())
        }
        viewModel.calendars.observe(viewLifecycleOwner) { calendars ->
            availableCalendars = calendars.orEmpty()
            // The chosen calendar can disappear when an account is removed.
            if (availableCalendars.none { it.id == prefs.calendarId }) {
                if (prefs.calendarId != Constants.ALL_CALENDARS) {
                    prefs.calendarId = Constants.ALL_CALENDARS
                    viewModel.loadCalendarEvents(requireContext(), prefs.calendarId)
                }
            }
            renderCalendarSelector()
        }
    }

    /**
     * Tapping cycles All -> each calendar -> All, matching how the rest of the launcher lets you
     * pick a value without opening a dialog.
     */
    private fun renderCalendarSelector() {
        val card = calendarBinding ?: return
        val selected = availableCalendars.firstOrNull { it.id == prefs.calendarId }
        card.btnSelectCalendar.text = when {
            selected == null -> getString(R.string.all_calendars)
            selected.displayName.isNotBlank() -> selected.displayName
            else -> selected.accountName
        }
        card.btnSelectCalendar.visibility =
            if (availableCalendars.size > 1) View.VISIBLE else View.GONE
        card.btnSelectCalendar.setOnClickListener { cycleCalendar() }
    }

    private fun cycleCalendar() {
        if (availableCalendars.isEmpty()) return
        val ids = listOf(Constants.ALL_CALENDARS) + availableCalendars.map { it.id }
        val next = ids[(ids.indexOf(prefs.calendarId).coerceAtLeast(0) + 1) % ids.size]
        prefs.calendarId = next
        renderCalendarSelector()
        viewModel.loadCalendarEvents(requireContext(), next)
    }

    private fun renderCalendarEvents(events: List<CalendarEventModel>) {
        val card = calendarBinding ?: return
        // The status line and the rows share the container; rebuild the rows from scratch.
        card.llEventsContainer.removeAllViews()
        card.llEventsContainer.addView(card.tvCalendarStatus)

        if (events.isEmpty()) {
            card.tvCalendarStatus.visibility = View.VISIBLE
            card.tvCalendarStatus.setText(R.string.no_upcoming_events)
            return
        }

        card.tvCalendarStatus.visibility = View.GONE
        for (event in events) {
            val row = ItemCalendarEventBinding.inflate(layoutInflater, card.llEventsContainer, true)
            row.tvEventTitle.text = event.title.ifBlank { getString(R.string.event_untitled) }
            row.tvEventWhen.text = getString(R.string.event_when, dayLabel(event), timeLabel(event))
            if (event.location.isBlank()) {
                row.tvEventLocation.visibility = View.GONE
            } else {
                row.tvEventLocation.visibility = View.VISIBLE
                row.tvEventLocation.text = event.location
            }
            row.llCalendarEvent.setOnClickListener { openEvent(event) }
        }
    }

    /**
     * All-day instances are stored at midnight UTC, so they have to be shifted into the local zone
     * before being read as a day or they land on the wrong date west of Greenwich.
     */
    private fun displayTime(event: CalendarEventModel): Long =
        if (event.allDay) event.begin - TimeZone.getDefault().getOffset(event.begin) else event.begin

    private fun dayLabel(event: CalendarEventModel): String {
        val millis = displayTime(event)
        return when (daysFromToday(millis)) {
            0L -> getString(R.string.event_today)
            1L -> getString(R.string.event_tomorrow)
            else -> DateUtils.formatDateTime(
                requireContext(),
                millis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
                    DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_ABBREV_WEEKDAY
            )
        }
    }

    private fun timeLabel(event: CalendarEventModel): String {
        if (event.allDay) return getString(R.string.event_all_day)
        val ctx = requireContext()
        val start = DateUtils.formatDateTime(ctx, event.begin, DateUtils.FORMAT_SHOW_TIME)
        val end = DateUtils.formatDateTime(ctx, event.end, DateUtils.FORMAT_SHOW_TIME)
        return "$start - $end"
    }

    private fun daysFromToday(millis: Long): Long {
        val midnightToday = Calendar.getInstance().atStartOfDay().timeInMillis
        val midnightThen = Calendar.getInstance().apply { timeInMillis = millis }.atStartOfDay().timeInMillis
        // Rounding absorbs the hour a DST change adds to or removes from the difference.
        return ((midnightThen - midnightToday).toDouble() / DateUtils.DAY_IN_MILLIS).roundToLong()
    }

    private fun Calendar.atStartOfDay(): Calendar = apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun openEvent(event: CalendarEventModel) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id)
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            openCalendarApp()
        }
    }

    private fun openCalendarApp() {
        val intent = Intent(Intent.ACTION_VIEW).setData(
            CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
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
     * A widget whose provider declares a configuration activity keeps showing its initialLayout
     * until that activity has run. Binding the id is not enough.
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
