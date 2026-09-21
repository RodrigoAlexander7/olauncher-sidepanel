package app.olauncher.ui

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * One instance of a calendar event. Times are kept raw so the view layer can format them with the
 * user's locale settings. For all-day events [begin] and [end] are midnight UTC, not local time -
 * see CalendarContract.Instances.
 */
data class CalendarEventModel(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
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
                val windowEnd = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, DAYS_AHEAD)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                }.timeInMillis

                val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(builder, now)
                ContentUris.appendId(builder, windowEnd)

                val projection = arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
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
                    while (it.moveToNext() && eventsList.size < MAX_EVENTS) {
                        eventsList.add(
                            CalendarEventModel(
                                id = it.getLong(0),
                                title = it.getString(1) ?: "",
                                begin = it.getLong(2),
                                end = it.getLong(3),
                                allDay = it.getInt(4) != 0,
                                location = it.getString(5) ?: ""
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _calendarEvents.postValue(eventsList)
        }
    }

    companion object {
        private const val DAYS_AHEAD = 7
        private const val MAX_EVENTS = 5
    }
}
