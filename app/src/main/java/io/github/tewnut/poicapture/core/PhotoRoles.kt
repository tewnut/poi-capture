package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * A kind of photo of a place in POI mode ("Mặt tiền", "Biển hiệu", …). Part of the app config, not of the code: the
 * team can change the list on its server, a contributor in Hồ sơ. The [id] goes into the export (`photo.role`), so
 * keep ids stable once data is flowing; [label] and [hint] are only shown.
 * - [cover]: the photo the agent uses as the place's cover (one role; the first one if none is marked).
 * - [advance]: one photo is usually enough, so after it the next role without a photo is selected by itself.
 */
data class PhotoRole(val id: String, val label: String, val hint: String = "", val cover: Boolean = false, val advance: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("hint", hint)
        .put("cover", cover).put("advance", advance)
}

/** The photo kinds offered in POI mode, in shooting order. [source]: "default", "local" (Hồ sơ) or "server". */
data class PhotoRoles(val roles: List<PhotoRole>, val source: String = "default") {
    val cover: PhotoRole get() = roles.firstOrNull { it.cover } ?: roles.first()

    fun label(id: String?): String = roles.firstOrNull { it.id == id }?.label
        ?: DEFAULT.roles.firstOrNull { it.id == id }?.label ?: id ?: "Ảnh"

    /**
     * The role to select after a photo of [shot]: when that role is an "advance" one, the next role in order that has
     * no photo yet ([counts] includes the photo just taken), wrapping round; otherwise (or when all have photos) stay.
     */
    fun next(shot: String, counts: Map<String, Int>): String {
        val i = roles.indexOfFirst { it.id == shot }
        if (i < 0 || !roles[i].advance) return shot
        val order = roles.drop(i + 1) + roles.take(i)
        return order.firstOrNull { (counts[it.id] ?: 0) == 0 }?.id ?: shot
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("source", source)
        .put("photoRoles", JSONArray().apply { roles.forEach { put(it.toJson()) } })

    /** Same list with one role on / off (a contributor hides the kinds the team does not use). */
    fun without(id: String) = copy(roles = roles.filter { it.id != id }, source = "local")
    fun with(role: PhotoRole) = copy(roles = roles + role, source = "local")
    fun moved(id: String, by: Int): PhotoRoles {
        val i = roles.indexOfFirst { it.id == id }
        val j = (i + by).coerceIn(0, roles.size - 1)
        if (i < 0 || i == j) return this
        val l = roles.toMutableList(); l.add(j, l.removeAt(i))
        return copy(roles = l, source = "local")
    }

    companion object {
        const val FACADE = "facade"
        const val SIGN = "sign"
        const val SIDE = "side"
        const val INSIDE = "inside"
        const val INFO = "info"
        const val CONTEXT = "context"

        val DEFAULT = PhotoRoles(listOf(
            PhotoRole(FACADE, "Mặt tiền", "Chụp thẳng, thấy cả cửa hàng (ảnh bìa)", cover = true, advance = true),
            PhotoRole(SIGN, "Biển hiệu", "Gần, đọc được tên và số điện thoại", advance = true),
            PhotoRole(SIDE, "Bên hông"),
            PhotoRole(INSIDE, "Bên trong"),
            PhotoRole(INFO, "Thông tin", "Giờ mở cửa, menu, bảng giá, SĐT trên cửa"),
            PhotoRole(CONTEXT, "Toàn cảnh", "Cửa hàng giữa các nhà bên cạnh"),
        ))

        private val ID = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

        /**
         * Read a config ({"photoRoles": [{id, label, hint?, cover?, advance?}, …]} or the bare array). Null when it is
         * not usable: no role, an id that is not lowercase a-z 0-9 _ -, a duplicate id or an empty label.
         */
        fun fromJson(o: Any?, source: String): PhotoRoles? {
            val a = when (o) {
                is JSONObject -> o.optJSONArray("photoRoles")
                is JSONArray -> o
                else -> null
            } ?: return null
            val roles = (0 until a.length()).map { i ->
                val r = a.optJSONObject(i) ?: return null
                PhotoRole(r.optString("id").trim(), r.optString("label").trim(), r.optString("hint").trim(),
                    r.optBoolean("cover", false), r.optBoolean("advance", false))
            }
            if (roles.isEmpty() || roles.any { !ID.matches(it.id) || it.label.isEmpty() } || roles.map { it.id }.toSet().size != roles.size) return null
            return PhotoRoles(roles, source)
        }

        /** An id for a role a contributor adds: the label without accents, lowercase, words joined by "-". */
        fun slug(label: String, taken: Collection<String>): String {
            val base = Normalizer.normalize(label.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "anh" }
            var id = base
            var n = 2
            while (id in taken) id = "$base-${n++}"
            return id
        }
    }
}
