package app.olauncher.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CalendarEventModel(
    val title: String,
    val timeRange: String,
    val location: String
)

class WidgetsViewModel : ViewModel() {

    private val _calendarEvents = MutableLiveData<List<CalendarEventModel>>()
    val calendarEvents: LiveData<List<CalendarEventModel>> get() = _calendarEvents

    private val _galleryImageUri = MutableLiveData<String>()
    val galleryImageUri: LiveData<String> get() = _galleryImageUri

    fun setGalleryImageUri(uri: String) {
        _galleryImageUri.value = uri
    }

    fun loadCalendarEvents(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val eventsList = mutableListOf<CalendarEventModel>()
            try {
                val now = System.currentTimeMillis()
                val calendarEnd = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                }.timeInMillis

                val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(builder, now)
                ContentUris.appendId(builder, calendarEnd)

                val projection = arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION
                )

                val cursor: Cursor? = context.contentResolver.query(
                    builder.build(),
                    projection,
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC"
                )

                cursor?.use {
                    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                    while (it.moveToNext() && eventsList.size < 5) {
                        val title = it.getString(0) ?: "Event"
                        val begin = it.getLong(1)
                        val end = it.getLong(2)
                        val location = it.getString(3) ?: ""
                        val timeStr = "${timeFormat.format(Date(begin))} - ${timeFormat.format(Date(end))}"
                        eventsList.add(CalendarEventModel(title, timeStr, location))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _calendarEvents.postValue(eventsList)
        }
    }
}
