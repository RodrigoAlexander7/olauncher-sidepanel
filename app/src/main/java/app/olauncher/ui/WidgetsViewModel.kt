package app.olauncher.ui

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.olauncher.data.Constants
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

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String
)

class WidgetsViewModel : ViewModel() {

    private val _calendarEvents = MutableLiveData<List<CalendarEventModel>>()
    val calendarEvents: LiveData<List<CalendarEventModel>> get() = _calendarEvents

    private val _calendars = MutableLiveData<List<CalendarInfo>>()
    val calendars: LiveData<List<CalendarInfo>> get() = _calendars

    private val _galleryImageUri = MutableLiveData<String>()
    val galleryImageUri: LiveData<String> get() = _galleryImageUri

    fun setGalleryImageUri(uri: String) {
        _galleryImageUri.value = uri
    }

    fun loadCalendars(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<CalendarInfo>()
            try {
                val projection = arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME
                )
                context.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
                )?.use {
                    while (it.moveToNext()) {
                        list.add(
                            CalendarInfo(
                                id = it.getLong(0),
                                displayName = it.getString(1) ?: "",
                                accountName = it.getString(2) ?: ""
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _calendars.postValue(list)
        }
    }

    /**
     * Loads upcoming events from [calendarId], or from every calendar when it is
     * [Constants.ALL_CALENDARS]. Reading every calendar means the same event can turn up several
     * times - holiday calendars in particular are subscribed once per Google account - so
     * identical occurrences are collapsed. The key includes the start time on purpose: two
     * occurrences of a recurring event share a title and must stay separate rows.
     */
    fun loadCalendarEvents(context: Context, calendarId: Long) {
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

                val selection: String?
                val selectionArgs: Array<String>?
                if (calendarId == Constants.ALL_CALENDARS) {
                    selection = null
                    selectionArgs = null
                } else {
                    selection = "${CalendarContract.Instances.CALENDAR_ID} = ?"
                    selectionArgs = arrayOf(calendarId.toString())
                }

                val cursor: Cursor? = context.contentResolver.query(
                    builder.build(),
                    projection,
                    selection,
                    selectionArgs,
                    "${CalendarContract.Instances.BEGIN} ASC"
                )

                val seen = mutableSetOf<Triple<String, Long, Boolean>>()
                cursor?.use {
                    while (it.moveToNext() && eventsList.size < MAX_EVENTS) {
                        val title = it.getString(1) ?: ""
                        val begin = it.getLong(2)
                        val allDay = it.getInt(4) != 0
                        if (!seen.add(Triple(title, begin, allDay))) continue
                        eventsList.add(
                            CalendarEventModel(
                                id = it.getLong(0),
                                title = title,
                                begin = begin,
                                end = it.getLong(3),
                                allDay = allDay,
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
        private const val DAYS_AHEAD = 14
        private const val MAX_EVENTS = 7
    }
}
