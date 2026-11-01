package app.projectsteps

import android.app.Application
import android.content.Context
import app.projectsteps.data.AppDatabase
import app.projectsteps.data.BackupRepository
import app.projectsteps.data.ImportRepository
import app.projectsteps.data.ProjectRepository
import app.projectsteps.data.SettingsRepository
import app.projectsteps.reminders.Notifications
import app.projectsteps.reminders.ReminderEngine
import app.projectsteps.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Simple manual dependency container (no DI framework needed for an app this size). */
class AppContainer(context: Context) {
    val db: AppDatabase = AppDatabase.build(context)
    val settings = SettingsRepository(db.settings())
    val scheduler = ReminderScheduler(context, db, settings)
    val engine = ReminderEngine(context, db, settings, scheduler)
    val repo = ProjectRepository(context, db, scheduler)
    val imports = ImportRepository(db, scheduler)
    val backups = BackupRepository(context, db, settings, scheduler)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class ProjectStepsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannel(this)
        // Opening the app also recovers from a Force Stop, which cancels all scheduled work.
        container.appScope.launch {
            container.repo.purgeTrash()
            container.scheduler.rescheduleAll()
        }
    }
}
