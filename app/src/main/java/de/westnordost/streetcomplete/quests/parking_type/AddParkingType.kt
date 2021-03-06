package de.westnordost.streetcomplete.quests.parking_type

import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.osmquest.OsmFilterQuestType
import de.westnordost.streetcomplete.data.osm.changes.StringMapChangesBuilder

class AddParkingType : OsmFilterQuestType<String>() {

    override val elementFilter = """
        nodes, ways, relations with
          amenity = parking
          and (!parking or parking = yes)
    """
    override val commitMessage = "Add parking type"
    override val wikiLink = "Tag:amenity=parking"
    override val icon = R.drawable.ic_quest_parking

    override fun getTitle(tags: Map<String, String>) = R.string.quest_parkingType_title

    override fun createForm() = AddParkingTypeForm()

    override fun applyAnswerTo(answer: String, changes: StringMapChangesBuilder) {
        changes.add("parking", answer)
    }
}
