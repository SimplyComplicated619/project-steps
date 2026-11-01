package app.projectsteps.core.model

/**
 * Plain domain enums shared by the importer, database layer and UI.
 * This package must stay free of Android imports so it can be unit-tested on the JVM.
 */

enum class ItemKind { PROJECT, SECTION, TASK, NOTE }

enum class TaskStatus(val label: String) {
    NOT_STARTED("Not Started"),
    IN_PROGRESS("In Progress"),
    BLOCKED("Blocked"),
    DONE("Done");

    companion object {
        fun parse(value: String?): TaskStatus = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}

enum class ProjectStatus(val label: String) {
    NOT_STARTED("Not Started"),
    IN_PROGRESS("In Progress"),
    BLOCKED("Blocked"),
    DONE("Done"),
    ARCHIVED("Archived");

    companion object {
        fun parse(value: String?): ProjectStatus = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}

enum class Priority(val label: String, val rank: Int) {
    NONE("None", 0),
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3),
    URGENT("Urgent", 4);

    companion object {
        fun fromRank(rank: Int): Priority = entries.firstOrNull { it.rank == rank } ?: NONE
    }
}

/** Informational flags. Project Steps never acts on them; they only change how a task is shown. */
enum class TaskFlag(val label: String) {
    NEEDS_APPROVAL("NEEDS APPROVAL"),
    DESTRUCTIVE("DESTRUCTIVE ACTION"),
    WAITING_ON("WAITING ON"),
    BLOCKED("BLOCKED");

    companion object {
        fun encode(flags: Set<TaskFlag>): String = flags.sortedBy { it.ordinal }.joinToString(",") { it.name }

        fun decode(value: String?): Set<TaskFlag> {
            if (value.isNullOrBlank()) return emptySet()
            return value.split(',').mapNotNull { token -> entries.firstOrNull { it.name == token.trim() } }.toSet()
        }
    }
}

enum class Confidence { HIGH, MEDIUM, LOW }
