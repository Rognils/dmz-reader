package se.c64.dmzreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.backup.BACKUP_FORMAT
import se.c64.dmzreader.data.backup.parseBackup

/**
 * Säkerhetskopians appVersion är appens riktiga version, inte en hårdkodad text:
 * den ska vara densamma som BuildConfig och som systemet uppger för det installerade paketet.
 */
@RunWith(AndroidJUnit4::class)
class BackupVersionDeviceTest {
    @Test
    fun exportedBackupCarriesTheInstalledAppVersion() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val backup = parseBackup(appContainer(context).backupRepository.export())
        assertEquals(BuildConfig.VERSION_NAME, backup.appVersion)
        assertEquals(installed, backup.appVersion)
        assertEquals(BACKUP_FORMAT, backup.backupFormat)
    }
}
