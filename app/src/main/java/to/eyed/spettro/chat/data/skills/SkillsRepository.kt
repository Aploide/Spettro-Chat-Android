package to.eyed.spettro.chat.data.skills

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import to.eyed.spettro.chat.data.store.SkillDao
import to.eyed.spettro.chat.data.store.SkillEntity
import to.eyed.spettro.chat.data.tools.ToolArgs
import to.eyed.spettro.chat.data.tools.ToolResult
import java.security.SecureRandom

@Serializable
data class Skill(
    val id: String,
    val name: String,
    /** The /slash trigger, unique, [a-z0-9-]. */
    val slug: String,
    val description: String,
    val instructions: String,
    val emoji: String = "✨",
    val builtin: Boolean = false,
)

/**
 * Bundled + user-created skills. A skill's instructions join the system
 * prompt when it's active on a conversation; the model can also pull any
 * skill itself mid-run through the load-skill tool.
 */
class SkillsRepository(private val dao: SkillDao) {
    companion object {
        const val LOAD_SKILL = "load-skill"
        const val MAX_INSTRUCTIONS_CHARS = 8_000
    }

    /** Bundled first, then user skills alphabetically. */
    val all: Flow<List<Skill>> = dao.all().map { entities ->
        BUNDLED_SKILLS + entities.map { it.toDomain() }
    }

    suspend fun allOnce(): List<Skill> = BUNDLED_SKILLS + dao.allOnce().map { it.toDomain() }

    suspend fun byId(id: String): Skill? =
        BUNDLED_SKILLS.firstOrNull { it.id == id } ?: dao.byId(id)?.toDomain()

    suspend fun bySlug(slug: String): Skill? =
        BUNDLED_SKILLS.firstOrNull { it.slug == slug } ?: dao.bySlug(slug)?.toDomain()

    /** Saves a user skill; slugs must be unique across bundled + user. */
    suspend fun save(skill: Skill): Result<Skill> {
        val slug = skill.slug.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')
        if (skill.name.isBlank()) return Result.failure(IllegalArgumentException("The skill needs a name."))
        if (slug.isBlank()) return Result.failure(IllegalArgumentException("The skill needs a slug."))
        if (skill.instructions.isBlank()) {
            return Result.failure(IllegalArgumentException("The skill needs instructions."))
        }
        val clash = bySlug(slug)
        if (clash != null && clash.id != skill.id) {
            return Result.failure(IllegalArgumentException("The slug /$slug is already taken."))
        }
        if (BUNDLED_SKILLS.any { it.id == skill.id }) {
            return Result.failure(IllegalArgumentException("Built-in skills can't be edited — duplicate one instead."))
        }
        val now = System.currentTimeMillis()
        val resolved = skill.copy(
            slug = slug,
            instructions = skill.instructions.take(MAX_INSTRUCTIONS_CHARS),
            builtin = false,
        )
        dao.upsert(
            SkillEntity(
                id = resolved.id,
                name = resolved.name.trim(),
                slug = resolved.slug,
                description = resolved.description.trim(),
                instructions = resolved.instructions,
                emoji = resolved.emoji.ifBlank { "✨" },
                createdAt = now,
                updatedAt = now,
            ),
        )
        return Result.success(resolved)
    }

    suspend fun delete(id: String) {
        if (BUNDLED_SKILLS.none { it.id == id }) dao.delete(id)
    }

    fun newId(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return "skill:" + bytes.joinToString("") { "%02x".format(it) }
    }

    // --- load-skill tool (the model pulls a skill itself mid-run) ---

    /** The load-skill spec, rebuilt per run so its catalog stays current. */
    suspend fun loadSkillSpec(): to.eyed.spettro.chat.data.api.ToolSpec {
        val catalog = allOnce().joinToString("\n") { "- ${it.slug}: ${it.description.ifBlank { it.name }}" }
        return to.eyed.spettro.chat.data.api.ToolSpec(
            name = LOAD_SKILL,
            description = "Load a skill: focused instructions for a specific kind of task, written by the " +
                "user or bundled with the app. Call it when the request clearly matches a skill below, " +
                "then follow the returned instructions for the rest of the conversation.\n" +
                "Available skills:\n$catalog",
            parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"The skill's slug from the list."}},"required":["name"]}""",
        )
    }

    suspend fun executeLoad(argumentsJson: String): ToolResult {
        val name = ToolArgs.string(argumentsJson, "name")?.lowercase()?.removePrefix("/")
            ?: return ToolResult("load-skill requires a name", isError = true)
        val skill = bySlug(name)
            ?: return ToolResult(
                "unknown skill: $name. Valid names: " + allOnce().joinToString { it.slug },
                isError = true,
            )
        return ToolResult("## Skill: ${skill.name}\n\n${skill.instructions}")
    }

    private fun SkillEntity.toDomain() = Skill(
        id = id,
        name = name,
        slug = slug,
        description = description,
        instructions = instructions,
        emoji = emoji,
        builtin = false,
    )
}
