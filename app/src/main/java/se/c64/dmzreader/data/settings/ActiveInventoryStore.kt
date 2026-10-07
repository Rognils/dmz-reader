package se.c64.dmzreader.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Vilket inventory som ligger i Issue-tabellen just nu. */
data class ActiveInventory(val datasetVersion: String, val schemaVersion: Int)

interface ActiveInventoryStore {
    fun observe(): Flow<ActiveInventory?>
    suspend fun get(): ActiveInventory?
    suspend fun set(value: ActiveInventory)
}

/** Användarens val i biblioteket. */
interface LibraryPreferences {
    /** Senast valda publikation, eller null om inget val har gjorts. */
    fun observeSelectedPublication(): Flow<String?>
    suspend fun setSelectedPublication(publicationId: String)
}

/** En enda DataStore-fil för appens inställningar. */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreActiveInventoryStore(context: Context) : ActiveInventoryStore {
    private val dataStore = context.applicationContext.settingsDataStore

    override fun observe(): Flow<ActiveInventory?> = dataStore.data.map { preferences ->
        val datasetVersion = preferences[DATASET_VERSION]
        val schemaVersion = preferences[SCHEMA_VERSION]
        if (datasetVersion != null && schemaVersion != null) {
            ActiveInventory(datasetVersion, schemaVersion)
        } else {
            null
        }
    }

    override suspend fun get(): ActiveInventory? = observe().first()

    override suspend fun set(value: ActiveInventory) {
        dataStore.edit { preferences ->
            preferences[DATASET_VERSION] = value.datasetVersion
            preferences[SCHEMA_VERSION] = value.schemaVersion
        }
    }

    private companion object {
        val DATASET_VERSION = stringPreferencesKey("active_dataset_version")
        val SCHEMA_VERSION = intPreferencesKey("active_schema_version")
    }
}

class DataStoreLibraryPreferences(context: Context) : LibraryPreferences {
    private val dataStore = context.applicationContext.settingsDataStore

    override fun observeSelectedPublication(): Flow<String?> =
        dataStore.data.map { preferences -> preferences[SELECTED_PUBLICATION] }

    override suspend fun setSelectedPublication(publicationId: String) {
        dataStore.edit { preferences -> preferences[SELECTED_PUBLICATION] = publicationId }
    }

    private companion object {
        val SELECTED_PUBLICATION = stringPreferencesKey("library_selected_publication")
    }
}

/** Inställningar för permanenta nedladdningar (planens FAS 5). */
interface DownloadPreferences {
    /** Sant (standard): nedladdningar väntar på ett nät som inte debiteras per mängd. */
    fun observeWifiOnly(): Flow<Boolean>
    suspend fun wifiOnly(): Boolean = observeWifiOnly().first()
    suspend fun setWifiOnly(value: Boolean)

    /** Om användaren redan har fått frågan om notiser för nedladdningar. */
    suspend fun notificationQuestionShown(): Boolean
    suspend fun setNotificationQuestionShown()
}

class DataStoreDownloadPreferences(context: Context) : DownloadPreferences {
    private val dataStore = context.applicationContext.settingsDataStore

    override fun observeWifiOnly(): Flow<Boolean> = dataStore.data.map { it[WIFI_ONLY] ?: true }

    override suspend fun setWifiOnly(value: Boolean) {
        dataStore.edit { it[WIFI_ONLY] = value }
    }

    override suspend fun notificationQuestionShown(): Boolean =
        dataStore.data.map { it[NOTIFICATION_QUESTION_SHOWN] ?: false }.first()

    override suspend fun setNotificationQuestionShown() {
        dataStore.edit { it[NOTIFICATION_QUESTION_SHOWN] = true }
    }

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val NOTIFICATION_QUESTION_SHOWN = booleanPreferencesKey("download_notification_question_shown")
    }
}
