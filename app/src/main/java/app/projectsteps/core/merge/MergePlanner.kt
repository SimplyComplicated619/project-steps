package app.projectsteps.core.merge

import app.projectsteps.core.importer.Classifier
import app.projectsteps.core.importer.DraftItem
import app.projectsteps.core.model.ItemKind
import java.security.MessageDigest
import kotlin.math.abs

/** An already-imported item, described by its immutable source metadata. */
data class ExistingItem(
    val id: String,
    val sectionPath: String,
    val sourceBlockIndex: Int,
    val sourceText: String,
    val isSection: Boolean = false,
)

enum class MatchKind {
    /** Same section path and same source text. */
    EXACT,
    /** Same section path, nearby position, text edited (high similarity). */
    EDITED,
    /** Same unique source text at a nearby position, but its heading was renamed. */
    MOVED,
}

data class Match(val draftId: String, val existingId: String, val kind: MatchKind)

data class MergePlan(
    val matches: List<Match>,
    /** Incoming items with no confident match: added as new items. */
    val newDraftIds: List<String>,
    /** Existing items not found in the new source: kept, and marked "not in latest source". */
    val missingExistingIds: List<String>,
)

object Fingerprint {
    private val PUNCT = Regex("[\\p{Punct}\\p{S}]+")
    private val WS = Regex("\\s+")

    /** Normalised text without checkbox/bullet/number markers, case or punctuation. */
    fun normalize(text: String): String {
        val withoutMarkers = text.split('\n').joinToString(" ") { Classifier.parseMarkers(it).rest }
        return withoutMarkers.lowercase().replace(PUNCT, " ").replace(WS, " ").trim()
    }

    /** Stable fingerprint from source metadata: section path + normalised source text. */
    fun of(sectionPath: String, sourceText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${normalize(sectionPath)}|${normalize(sourceText)}".toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** 0..1 similarity based on edit distance of normalised text. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a).take(300)
        val y = normalize(b).take(300)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[y.length].toDouble() / maxOf(x.length, y.length)
    }
}

/**
 * Matches a re-imported document against items previously imported from the same file.
 * Identity is never decided by title alone: every match needs the same source file plus either
 * the same section path or a nearby source position.
 */
object MergePlanner {
    const val NEARBY_BLOCKS = 8
    const val EDIT_SIMILARITY = 0.75

    fun plan(existing: List<ExistingItem>, incoming: List<DraftItem>): MergePlan {
        val matches = ArrayList<Match>()
        val usedExisting = HashSet<String>()
        val matchedDraft = HashSet<String>()

        // Pass 1: exact fingerprint (section path + source text), in document order.
        val byFp = existing.groupBy { Fingerprint.of(it.sectionPath, it.sourceText) }.mapValues { it.value.toMutableList() }
        for (d in incoming) {
            val list = byFp[Fingerprint.of(d.source.sectionPath, d.source.originalText)] ?: continue
            val pick = list.minByOrNull { abs(it.sourceBlockIndex - d.source.blockIndex) } ?: continue
            list.remove(pick)
            usedExisting.add(pick.id); matchedDraft.add(d.id)
            matches.add(Match(d.id, pick.id, MatchKind.EXACT))
        }

        // Pass 2: same section, nearby position, similar text (edited wording).
        for (d in incoming) {
            if (d.id in matchedDraft) continue
            val dPath = Fingerprint.normalize(d.source.sectionPath)
            val best = existing.asSequence()
                .filter { it.id !in usedExisting && Fingerprint.normalize(it.sectionPath) == dPath }
                .filter { abs(it.sourceBlockIndex - d.source.blockIndex) <= NEARBY_BLOCKS }
                .map { it to Fingerprint.similarity(it.sourceText, d.source.originalText) }
                .filter { it.second >= EDIT_SIMILARITY }
                .maxByOrNull { it.second }
            if (best != null) {
                usedExisting.add(best.first.id); matchedDraft.add(d.id)
                matches.add(Match(d.id, best.first.id, MatchKind.EDITED))
            }
        }

        // Pass 2b: a heading renamed in place (same parent path, same position). Headings carry no
        // completion state, so position is enough; tasks always need similar text.
        for (d in incoming) {
            if (d.id in matchedDraft || d.kind != ItemKind.SECTION) continue
            val dPath = Fingerprint.normalize(d.source.sectionPath)
            val e = existing.firstOrNull {
                it.isSection && it.id !in usedExisting && Fingerprint.normalize(it.sectionPath) == dPath &&
                    abs(it.sourceBlockIndex - d.source.blockIndex) <= 2
            } ?: continue
            usedExisting.add(e.id); matchedDraft.add(d.id)
            matches.add(Match(d.id, e.id, MatchKind.EDITED))
        }

        // Pass 3: identical, unique text at a nearby position under a renamed heading.
        val remainingIncoming = incoming.filter { it.id !in matchedDraft }
        val remainingExisting = existing.filter { it.id !in usedExisting }
        val inCount = remainingIncoming.groupingBy { Fingerprint.normalize(it.source.originalText) }.eachCount()
        val exCount = remainingExisting.groupingBy { Fingerprint.normalize(it.sourceText) }.eachCount()
        for (d in remainingIncoming) {
            val key = Fingerprint.normalize(d.source.originalText)
            if (key.length < 4 || inCount[key] != 1 || exCount[key] != 1) continue
            val e = remainingExisting.first { Fingerprint.normalize(it.sourceText) == key }
            if (e.id in usedExisting || abs(e.sourceBlockIndex - d.source.blockIndex) > NEARBY_BLOCKS) continue
            usedExisting.add(e.id); matchedDraft.add(d.id)
            matches.add(Match(d.id, e.id, MatchKind.MOVED))
        }

        return MergePlan(
            matches = matches,
            newDraftIds = incoming.filter { it.id !in matchedDraft }.map { it.id },
            missingExistingIds = existing.filter { it.id !in usedExisting }.map { it.id },
        )
    }
}
