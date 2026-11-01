package app.projectsteps.core.importer

/**
 * Clearly labelled demo content for trying the app without importing real documents.
 * It is generic on purpose and never presented as the user's own projects.
 */
object DemoData {
    const val PREFIX = "DEMO – "

    private val SAMPLE = """
        # How to use Project Steps

        This is DEMO DATA. It is not one of your projects. Delete it any time from Settings › Projects.

        ## Try the checklist
        - [ ] Tick this box to complete a task
        - [x] This task was already marked done in the demo source
        - [ ] Long-press a task (or use its ⋮ menu) for more actions
          - [ ] Subtasks are indented under their parent
        - [ ] Waiting on: an example reply (shows the WAITING ON flag)

        ## Try reminders
        1. Open a task and add a reminder a few minutes ahead
        2. When the notification appears, try Snooze
        3. Then try Complete from the notification

        ## Notes
        Plain paragraphs like this one are imported as notes, not tasks.
    """.trimIndent()

    fun projects(): List<DraftProject> {
        val doc = MarkdownReader.parse(SAMPLE, "DEMO-Sample.md", SourceFormat.DEMO)
        val draft = Classifier.classify(doc, null, ImportService.sha256(SAMPLE.toByteArray()), "demo/DEMO-Sample.md")
        return listOf(
            draft.copy(
                name = PREFIX + "Sample checklist",
                description = "Demo data, not your projects",
                isDemo = true,
            )
        )
    }
}
