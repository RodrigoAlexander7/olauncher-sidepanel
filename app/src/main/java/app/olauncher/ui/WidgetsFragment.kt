package app.olauncher.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    private var bindingWidgetPackageName = ""

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
        if (result.resultCode == Activity.RESULT_OK) {
            val appWidgetId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            if (appWidgetId != -1) {
                saveWidgetIdAndRefresh(bindingWidgetPackageName, appWidgetId)
            }
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
        hostHelper = AppWidgetHostHelper(requireContext())

        setupSwipeGesture()
        binding.btnBackToHome.setOnClickListener { findNavController().navigateUp() }

        setupTasksWidget()
        setupCalendarWidget()
        setupGalleryWidget()
    }

    override fun onStart() {
        super.onStart()
        hostHelper.startListening()
    }

    override fun onStop() {
        super.onStop()
        hostHelper.stopListening()
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
        val tasksWidgetId = prefs.googleTasksWidgetId
        val tasksInfo = hostHelper.findWidgetProviderInfo(Constants.GOOGLE_TASKS_PACKAGE_NAME)

        if (tasksWidgetId != -1 && tasksInfo != null) {
            val widgetView = hostHelper.createWidgetView(requireContext(), tasksWidgetId, tasksInfo)
            if (widgetView != null) {
                binding.tasksWidgetContainer.removeAllViews()
                binding.tasksWidgetContainer.addView(widgetView)
                return
            }
        }

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
        val calendarWidgetId = prefs.googleCalendarWidgetId
        val calendarInfo = hostHelper.findWidgetProviderInfo(Constants.GOOGLE_CALENDAR_PACKAGE_NAME)

        if (calendarWidgetId != -1 && calendarInfo != null) {
            val widgetView = hostHelper.createWidgetView(requireContext(), calendarWidgetId, calendarInfo)
            if (widgetView != null) {
                binding.calendarWidgetContainer.removeAllViews()
                binding.calendarWidgetContainer.addView(widgetView)
                return
            }
        }

        // Fallback built-in Calendar card
        calendarBinding = ItemWidgetCalendarBinding.inflate(layoutInflater, binding.calendarWidgetContainer, true)
        calendarBinding?.btnConfigureCalendar?.setOnClickListener {
            bindAppWidget(Constants.GOOGLE_CALENDAR_PACKAGE_NAME)
        }

        // Check permission & observe events
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadCalendarEvents(requireContext())
        } else {
            calendarBinding?.tvCalendarStatus?.text = "Tap to grant Calendar permission"
            calendarBinding?.llCalendarFallback?.setOnClickListener {
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }

        viewModel.calendarEvents.observe(viewLifecycleOwner) { events ->
            if (events.isNullOrEmpty()) {
                calendarBinding?.tvCalendarStatus?.text = getString(R.string.no_upcoming_events)
            } else {
                val sb = StringBuilder()
                for (event in events) {
                    sb.append("• ").append(event.title).append(" (").append(event.timeRange).append(")\n")
                }
                calendarBinding?.tvCalendarStatus?.text = sb.toString().trim()
            }
        }
    }

    private fun setupGalleryWidget() {
        galleryBinding = ItemWidgetGalleryBinding.inflate(layoutInflater, binding.galleryWidgetContainer, true)

        val pickListener = View.OnClickListener {
            selectImageLauncher.launch(arrayOf("image/*"))
        }

        galleryBinding?.btnSelectImage?.setOnClickListener(pickListener)
        galleryBinding?.flGalleryImageContainer?.setOnClickListener(pickListener)

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
            galleryBinding?.tvEmptyGallery?.visibility = View.GONE
        } catch (e: Exception) {
            e.printStackTrace()
            galleryBinding?.ivGalleryImage?.visibility = View.GONE
            galleryBinding?.tvEmptyGallery?.visibility = View.VISIBLE
        }
    }

    private fun launchAppOrStore(packageName: String, appLabel: String) {
        val launchIntent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            }
        }
    }

    private fun bindAppWidget(packageName: String) {
        val info = hostHelper.findWidgetProviderInfo(packageName)
        if (info == null) {
            Toast.makeText(context, "Widget not found", Toast.LENGTH_SHORT).show()
            return
        }
        val appWidgetId = hostHelper.allocateAppWidgetId()
        val allowed = hostHelper.appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)
        if (allowed) {
            saveWidgetIdAndRefresh(packageName, appWidgetId)
        } else {
            bindingWidgetPackageName = packageName
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }
            bindWidgetLauncher.launch(intent)
        }
    }

    private fun saveWidgetIdAndRefresh(packageName: String, appWidgetId: Int) {
        if (packageName == Constants.GOOGLE_TASKS_PACKAGE_NAME) {
            prefs.googleTasksWidgetId = appWidgetId
            setupTasksWidget()
        } else if (packageName == Constants.GOOGLE_CALENDAR_PACKAGE_NAME) {
            prefs.googleCalendarWidgetId = appWidgetId
            setupCalendarWidget()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tasksBinding = null
        calendarBinding = null
        galleryBinding = null
        _binding = null
    }
}
