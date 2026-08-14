package to.eyed.spettro.chat.data.skills

/**
 * Skills compiled into the app. Ids are stable ("builtin:<slug>") so a chat
 * keeps its skill across app updates; users can duplicate one into an
 * editable copy but never modify these directly.
 *
 * There is deliberately exactly one bundled skill: the skill that writes
 * skills. Everything else is meant to be created by the user — by hand in the
 * skills sheet, or by the assistant through this skill and the create-skill
 * tool.
 */
internal val BUNDLED_SKILLS: List<Skill> = listOf(
    Skill(
        id = "builtin:skill-maker",
        name = "Skill maker",
        slug = "skill-maker",
        description = "Designs new skills with you and saves them with the create-skill tool.",
        emoji = "🛠️",
        builtin = true,
        instructions = """
            You are acting as a skill author: you turn what the user wants into a new, reusable skill.
            A skill is a focused set of instructions applied to a chat; it has a name, a /slug trigger
            (lowercase letters, digits, hyphens), a one-line description, an emoji, and the instructions
            themselves (8,000 characters max).

            Work like this:
            1. Understand the job the skill should do. If the goal, audience, or tone is unclear,
               ask — one short round of questions at most.
            2. Draft the instructions. Write them as directives to the assistant ("You are acting
               as…"), not as documentation. Good instructions state the role, the exact working
               procedure, the output format, and what to do in edge cases (unclear input, missing
               data, requests outside the skill's scope). Mention the app's tools (web-search,
               web-fetch, calendar-events, get-location, save-memory, …) when the skill should
               lean on them. Keep them tight: every sentence must change behavior.
            3. Show the user the draft — name, /slug, emoji, description, instructions — and ask
               for a quick confirmation or tweaks.
            4. Once the user agrees, save it with the create-skill tool. Report the /slug they can
               use to trigger it, and remind them they can edit it anytime under Settings → Skills.

            Rules:
            - The description must be one line that lets the model decide when to auto-load the
              skill, so lead with what it does, not how.
            - Never invent capabilities the app does not have; skills only shape instructions.
            - If a skill with the same slug already exists, pick a close alternative and say so.
            - When asked to improve an existing skill, read it first with the load-skill tool, save
              the improved version under a fresh slug, and tell the user they can delete the old one
              under Settings → Skills.
        """.trimIndent(),
    ),
)
