package de.westnordost.streetcomplete.data.quest

import android.content.SharedPreferences
import android.util.Log
import de.westnordost.osmapi.map.data.Element
import de.westnordost.osmapi.map.data.LatLon
import de.westnordost.osmapi.map.data.OsmElement
import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.data.osm.changes.*
import de.westnordost.streetcomplete.data.osm.delete_element.DeleteOsmElement
import de.westnordost.streetcomplete.data.osm.delete_element.DeleteOsmElementDao
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.MergedElementDao
import de.westnordost.streetcomplete.data.osm.osmquest.OsmQuest
import de.westnordost.streetcomplete.data.osm.osmquest.OsmQuestController
import de.westnordost.streetcomplete.data.osm.osmquest.undo.UndoOsmQuest
import de.westnordost.streetcomplete.data.osm.osmquest.undo.UndoOsmQuestDao
import de.westnordost.streetcomplete.data.osm.splitway.OsmQuestSplitWay
import de.westnordost.streetcomplete.data.osm.splitway.OsmQuestSplitWayDao
import de.westnordost.streetcomplete.data.osm.splitway.SplitPolylineAtPosition
import de.westnordost.streetcomplete.data.osmnotes.createnotes.CreateNote
import de.westnordost.streetcomplete.data.osmnotes.createnotes.CreateNoteDao
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestController
import de.westnordost.streetcomplete.quests.note_discussion.NoteAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/** Controls the workflow of quests: Solving them, hiding them instead, splitting the way instead,
 *  undoing, etc. */
@Singleton class QuestController @Inject constructor(
    private val osmQuestController: OsmQuestController,
    private val osmNoteQuestController: OsmNoteQuestController,
    private val undoOsmQuestDB: UndoOsmQuestDao,
    private val osmElementDB: MergedElementDao,
    private val splitWayDB: OsmQuestSplitWayDao,
    private val deleteElementDB: DeleteOsmElementDao,
    private val createNoteDB: CreateNoteDao,
    private val prefs: SharedPreferences
): CoroutineScope by CoroutineScope(Dispatchers.Default) {
    /** Create a note for the given OSM Quest instead of answering it. The quest will turn
     * invisible.
     * @return true if successful
     */
    fun createNote(osmQuestId: Long, questTitle: String, text: String, imagePaths: List<String>?): Boolean {
        val q = osmQuestController.get(osmQuestId)
        if (q?.status != QuestStatus.NEW) return false

        val createNote = CreateNote(null, text, q.center, questTitle, ElementKey(q.elementType, q.elementId), imagePaths)
        createNoteDB.add(createNote)

        /* The quests that reference the same element for which the user was not able to
           answer the question are removed because the to-be-created note blocks quest
           creation for other users, so those quests should be removed from the user's
           own display as well. As soon as the note is resolved, the quests will be re-
           created next time they are downloaded */
        removeUnsolvedQuestsForElement(q.elementType, q.elementId)
        return true
    }

    fun createNote(text: String, imagePaths: List<String>?, position: LatLon) {
        val createNote = CreateNote(null, text, position, null, null, imagePaths)
        createNoteDB.add(createNote)
    }

    private fun removeUnsolvedQuestsForElement(elementType: Element.Type, elementId: Long) {
        osmQuestController.deleteAllUnsolvedForElement(elementType, elementId)
        osmElementDB.deleteUnreferenced()
    }

    /** Split a way for the given OSM Quest. The quest will turn invisible.
     * @return true if successful
     */
    fun splitWay(osmQuestId: Long, splits: List<SplitPolylineAtPosition>, source: String): Boolean {
        val q = osmQuestController.get(osmQuestId)
        if (q?.status != QuestStatus.NEW) return false

        val unsolvedQuestTypes = osmQuestController.getAllUnsolvedQuestTypesForElement(q.elementType, q.elementId)
        splitWayDB.add(OsmQuestSplitWay(osmQuestId, q.osmElementQuestType, q.elementId, source, splits, unsolvedQuestTypes))

        removeUnsolvedQuestsForElement(q.elementType, q.elementId)
        return true
    }

    /** Delete the element referred to by the given OSM quest id.
     * @return true if successful
     */
    fun deleteOsmElement(osmQuestId: Long, source: String): Boolean {
        val q = osmQuestController.get(osmQuestId)
        if (q?.status != QuestStatus.NEW) return false

        Log.d(TAG, "Deleted ${q.elementType.name} #${q.elementId} in frame of quest ${q.type.javaClass.simpleName}")

        deleteElementDB.add(DeleteOsmElement(osmQuestId, q.osmElementQuestType, q.elementId, q.elementType, source, q.center))

        osmQuestController.deleteAllForElement(q.elementType, q.elementId)
        osmElementDB.deleteUnreferenced()
        return true
    }

    /** Replaces the previous element which is assumed to be a shop/amenity of sort with another
     *  feature.
     *  @return true if successful
     */
    fun replaceShopElement(osmQuestId: Long, tags: Map<String, String>, source: String): Boolean {
        val q = osmQuestController.get(osmQuestId)
        if (q?.status != QuestStatus.NEW) return false
        val element = osmElementDB.get(q.elementType, q.elementId) ?: return false
        val changes = createReplaceShopChanges(element.tags.orEmpty(), tags)
        Log.d(TAG, "Replaced ${q.elementType.name} #${q.elementId} in frame of quest ${q.type.javaClass.simpleName} with $changes")

        osmQuestController.answer(q, changes, source)
        // current quests are likely invalid after shop has been replaced, so let's remove the unsolved ones
        osmQuestController.deleteAllUnsolvedForElement(q.elementType, q.elementId)

        prefs.edit().putLong(Prefs.LAST_SOLVED_QUEST_TIME, System.currentTimeMillis()).apply()

        return true
    }

    private fun createReplaceShopChanges(previousTags: Map<String, String>, newTags: Map<String, String>): StringMapChanges {
        val changesList = mutableListOf<StringMapEntryChange>()

        // first remove old tags
        for ((key, value) in previousTags) {
            val isOkToRemove = KEYS_THAT_SHOULD_NOT_BE_REMOVED_WHEN_SHOP_IS_REPLACED.none { it.matches(key) }
            if (isOkToRemove && !newTags.containsKey(key)) {
                changesList.add(StringMapEntryDelete(key, value))
            }
        }
        // then add new tags
        for ((key, value) in newTags) {
            val valueBefore = previousTags[key]
            if (valueBefore != null) changesList.add(StringMapEntryModify(key, valueBefore, value))
            else changesList.add(StringMapEntryAdd(key, value))
        }

        return StringMapChanges(changesList)
    }

    /** Apply the user's answer to the given quest. (The quest will turn invisible.)
     * @return true if successful
     */
    fun solve(questId: Long, group: QuestGroup, answer: Any, source: String): Boolean {
        return when(group) {
            QuestGroup.OSM -> solveOsmQuest(questId, answer, source)
            QuestGroup.OSM_NOTE -> solveOsmNoteQuest(questId, answer as NoteAnswer)
        }
    }

    fun getOsmElement(quest: OsmQuest): OsmElement? =
        osmElementDB.get(quest.elementType, quest.elementId) as OsmElement?

    /** Undo changes made after answering a quest. */
    fun undo(quest: OsmQuest) {
        when(quest.status) {
            // not uploaded yet -> simply revert to NEW
            QuestStatus.ANSWERED, QuestStatus.HIDDEN -> {
                osmQuestController.undo(quest)
            }
            // already uploaded! -> create change to reverse the previous change
            QuestStatus.CLOSED -> {
                osmQuestController.revert(quest)
                undoOsmQuestDB.add(UndoOsmQuest(quest))
            }
            else -> {
                throw IllegalStateException("Tried to undo a quest that hasn't been answered yet")
            }
        }
    }

    private fun solveOsmNoteQuest(questId: Long, answer: NoteAnswer): Boolean {
        val q = osmNoteQuestController.get(questId)
        if (q == null || q.status !== QuestStatus.NEW) return false

        require(answer.text.isNotEmpty()) { "NoteQuest has been answered with an empty comment!" }

        osmNoteQuestController.answer(q, answer)
        return true
    }

    private fun solveOsmQuest(questId: Long, answer: Any, source: String): Boolean {
        // race condition: another thread (i.e. quest download thread) may have removed the
        // element already (#282). So in this case, just ignore
        val q = osmQuestController.get(questId)
        if (q?.status != QuestStatus.NEW) return false
        val element = osmElementDB.get(q.elementType, q.elementId) ?: return false

        val changes = createOsmQuestChanges(q, element, answer)
        if (changes == null) {
            // if applying the changes results in an error (=a conflict), the data the quest(ion)
            // was based on is not valid anymore -> like with other conflicts, silently drop the
            // user's change (#289) and the quest
            osmQuestController.fail(q)
            return false
        } else {
            require(!changes.isEmpty()) {
                "OsmQuest $questId (${q.type.javaClass.simpleName}) has been answered by the user but the changeset is empty!"
            }

            Log.d(TAG, "Solved a ${q.type.javaClass.simpleName} quest: $changes")
            osmQuestController.answer(q, changes, source)
            prefs.edit().putLong(Prefs.LAST_SOLVED_QUEST_TIME, System.currentTimeMillis()).apply()
            return true
        }
    }

    private fun createOsmQuestChanges(quest: OsmQuest, element: Element, answer: Any) : StringMapChanges? {
        return try {
            val changesBuilder = StringMapChangesBuilder(element.tags.orEmpty())
            quest.osmElementQuestType.applyAnswerToUnsafe(answer, changesBuilder)
            changesBuilder.create()
        } catch (e: IllegalArgumentException) {
            // applying the changes results in an error (=a conflict)
            null
        }
    }

    /** Make the given quest invisible (per user interaction).  */
    fun hide(questId: Long, group: QuestGroup) {
        when (group) {
            QuestGroup.OSM -> {
                val quest = osmQuestController.get(questId)
                if (quest?.status != QuestStatus.NEW) return
                osmQuestController.hide(quest)
            }
            QuestGroup.OSM_NOTE -> {
                val q = osmNoteQuestController.get(questId)
                if (q?.status != QuestStatus.NEW) return
                osmNoteQuestController.hide(q)
            }
        }
    }

    /** Retrieve the given quest from local database  */
    fun get(questId: Long, group: QuestGroup): Quest? = when (group) {
        QuestGroup.OSM -> osmQuestController.get(questId)
        QuestGroup.OSM_NOTE -> osmNoteQuestController.get(questId)
    }

    companion object {
        private const val TAG = "QuestController"
    }
}

data class QuestAndGroup(val quest: Quest, val group: QuestGroup)

private val KEYS_THAT_SHOULD_NOT_BE_REMOVED_WHEN_SHOP_IS_REPLACED = listOf(
    "landuse", "historic",
    // building/simple 3d building mapping
    "building", "man_made", "building:.*", "roof:.*",
    // any address
    "addr:.*",
    // shop can at the same time be an outline in indoor mapping
    "level", "level:ref", "indoor", "room",
    // geometry
    "layer", "ele", "height", "area", "is_in",
    // notes and fixmes
    "FIXME", "fixme", "note"
).map { it.toRegex() }
