package com.example.archaracters

import android.content.Context

data class CharacterInfo(val file: String, val name: String, val persona: String)

object Characters {
    private const val DEFAULT_PERSONA = "شخصية ودودة ومرحة وفضولية"
    private const val NAME_PREFIX = "الاسم:"

    /**
     * يقرأ كل ملفات .glb من assets/models.
     * ملف نصي بنفس الاسم (.txt) يحدد شخصية الكائن، وسطره الأول يمكن أن يكون "الاسم: ..."
     * ليظهر اسم عربي في القائمة (وإلا يُستخدم اسم الملف).
     */
    fun load(ctx: Context): List<CharacterInfo> {
        val files = ctx.assets.list("models")?.filter { it.endsWith(".glb", true) }?.sorted()
            ?: emptyList()
        return files.map { f ->
            val base = f.substringBeforeLast('.')
            val raw = try {
                ctx.assets.open("models/$base.txt").bufferedReader().use { it.readText() }.trim()
            } catch (e: Exception) {
                ""
            }
            var name = base.replace('_', ' ').replace('-', ' ')
            var persona = raw
            val lines = raw.lines()
            if (lines.isNotEmpty() && lines[0].trim().startsWith(NAME_PREFIX)) {
                val custom = lines[0].trim().removePrefix(NAME_PREFIX).trim()
                if (custom.isNotEmpty()) name = custom
                persona = lines.drop(1).joinToString("\n").trim()
            }
            CharacterInfo(f, name, persona.ifEmpty { DEFAULT_PERSONA })
        }
    }
}
