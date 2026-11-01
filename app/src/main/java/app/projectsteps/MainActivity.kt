package app.projectsteps

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.projectsteps.reminders.Notifications
import app.projectsteps.ui.MainViewModel
import app.projectsteps.ui.ProjectStepsRoot

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the status and navigation bars; Compose applies the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { ProjectStepsRoot(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** A tapped reminder notification opens that exact task (or project). */
    private fun handleIntent(intent: Intent?) {
        val taskId = intent?.getStringExtra(Notifications.EXTRA_TASK_ID)
        val projectId = intent?.getStringExtra(Notifications.EXTRA_PROJECT_ID)
        if (taskId != null || projectId != null) vm.openFromNotification(taskId, projectId)
    }
}
