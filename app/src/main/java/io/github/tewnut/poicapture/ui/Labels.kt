package io.github.tewnut.poicapture.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.core.CaptureWarning
import io.github.tewnut.poicapture.core.CatalogueError
import io.github.tewnut.poicapture.core.CatalogueException
import io.github.tewnut.poicapture.core.PhotoRole
import io.github.tewnut.poicapture.core.PhotoRoles
import io.github.tewnut.poicapture.core.PoiStatus
import io.github.tewnut.poicapture.core.Road
import io.github.tewnut.poicapture.core.Way
import io.github.tewnut.poicapture.core.roadNameOf
import io.github.tewnut.poicapture.net.RoadDataUnavailable

/*
 * Words for the codes that core/ (pure Kotlin, no Android resources) hands to the UI: place status, sign category ids,
 * built-in photo kinds, capture warnings, catalogue errors, road labels.
 */

/**
 * A message for the screen, resolved in the UI's current language (so it follows a language switch): a string
 * resource and its format arguments. Used where a service or background code produces the message.
 */
data class UiText(@StringRes val res: Int, val args: List<Any>) {
    constructor(@StringRes res: Int, vararg args: Any) : this(res, args.toList())
    fun get(ctx: Context): String = ctx.getString(res, *args.toTypedArray())
}

@Composable
fun UiText.text(): String = stringResource(res, *args.toTypedArray())

@StringRes
fun PoiStatus.labelRes(): Int = when (this) {
    PoiStatus.NEW -> R.string.status_new
    PoiStatus.EXISTS -> R.string.status_exists
    PoiStatus.CLOSED -> R.string.status_closed
    PoiStatus.MOVED -> R.string.status_moved
}

/** Category ids of SignText (plus "services", a chip of the place sheet), in the order the chips show them. */
private val CATEGORY_RES = linkedMapOf(
    "restaurant" to R.string.category_restaurant,
    "cafe" to R.string.category_cafe,
    "drinks" to R.string.category_drinks,
    "grocery" to R.string.category_grocery,
    "pharmacy" to R.string.category_pharmacy,
    "vehicle_repair" to R.string.category_vehicle_repair,
    "phone_shop" to R.string.category_phone_shop,
    "beauty" to R.string.category_beauty,
    "fashion" to R.string.category_fashion,
    "hotel" to R.string.category_hotel,
    "bank" to R.string.category_bank,
    "education" to R.string.category_education,
    "health" to R.string.category_health,
    "electronics" to R.string.category_electronics,
    "hardware" to R.string.category_hardware,
    "services" to R.string.category_services,
)

/** Label of a category id; an unknown value (typed, or saved by an older version) is shown as it is. */
fun categoryLabel(ctx: Context, id: String): String = CATEGORY_RES[id]?.let { ctx.getString(it) } ?: id

@Composable
fun categoryLabel(id: String): String = categoryLabel(LocalContext.current, id)

@StringRes
private fun builtInLabel(id: String): Int? = when (id) {
    PhotoRoles.FACADE -> R.string.role_facade
    PhotoRoles.SIGN -> R.string.role_sign
    PhotoRoles.SIDE -> R.string.role_side
    PhotoRoles.INSIDE -> R.string.role_inside
    PhotoRoles.INFO -> R.string.role_info
    PhotoRoles.CONTEXT -> R.string.role_context
    else -> null
}

@StringRes
private fun builtInHint(id: String): Int? = when (id) {
    PhotoRoles.FACADE -> R.string.role_facade_hint
    PhotoRoles.SIGN -> R.string.role_sign_hint
    PhotoRoles.INFO -> R.string.role_info_hint
    PhotoRoles.CONTEXT -> R.string.role_context_hint
    else -> null
}

/** Label of a photo kind: translated for an unchanged built-in kind, else the list's own text. */
fun roleLabel(ctx: Context, roles: PhotoRoles, role: PhotoRole): String =
    builtInLabel(role.id)?.takeIf { PhotoRoles.isBuiltIn(role, roles.source) }?.let { ctx.getString(it) } ?: role.label

fun roleHint(ctx: Context, roles: PhotoRoles, role: PhotoRole): String =
    if (PhotoRoles.isBuiltIn(role, roles.source)) builtInHint(role.id)?.let { ctx.getString(it) } ?: "" else role.hint

/** Label for a role id found on a photo (may not be in the list any more; null / blank = untagged photo). */
fun roleLabel(ctx: Context, roles: PhotoRoles, id: String?): String {
    roles.roles.firstOrNull { it.id == id }?.let { return roleLabel(ctx, roles, it) }
    if (id.isNullOrBlank()) return ctx.getString(R.string.photo_generic)
    return builtInLabel(id)?.let { ctx.getString(it) } ?: id
}

@Composable
fun roleLabel(roles: PhotoRoles, role: PhotoRole): String = roleLabel(LocalContext.current, roles, role)

@Composable
fun roleHint(roles: PhotoRoles, role: PhotoRole): String = roleHint(LocalContext.current, roles, role)

@Composable
fun roleLabel(roles: PhotoRoles, id: String?): String = roleLabel(LocalContext.current, roles, id)

/** A session's road as shown: auto mode and unnamed roads in the UI's language, else "ref · name". */
fun roadText(ctx: Context, road: Road): String = when {
    road.auto -> ctx.getString(R.string.road_auto)
    road.name.isBlank() && road.ref.isBlank() -> ctx.getString(R.string.road_unnamed, road.highway)
    else -> road.label
}

@Composable
fun roadText(road: Road): String = roadText(LocalContext.current, road)

/** A way's name as shown ("ref · name", or "Unnamed road (type)"). */
fun wayLabel(ctx: Context, w: Way): String = roadNameOf(w) ?: ctx.getString(R.string.road_unnamed, w.highway)

fun CaptureWarning.toUiText(): UiText = when (this) {
    is CaptureWarning.TooFast -> UiText(R.string.warn_too_fast, kmh, maxKmh)
    is CaptureWarning.WeakGps -> UiText(R.string.warn_weak_gps, accuracyM)
    is CaptureWarning.OffRoad -> UiText(R.string.warn_off_road, distanceM)
    CaptureWarning.UTurn -> UiText(R.string.warn_u_turn)
}

/** Text for an error thrown by our own code: known failures in the UI's language, anything else its message. */
fun errorText(ctx: Context, e: Throwable): String = when (e) {
    is CatalogueException -> when (e.error) {
        CatalogueError.NOT_PUBLISHED -> ctx.getString(R.string.catalogue_not_published, e.code ?: 0)
        CatalogueError.FORBIDDEN -> ctx.getString(R.string.catalogue_forbidden, e.code ?: 0)
        CatalogueError.SERVER -> ctx.getString(R.string.catalogue_server, e.code ?: 0)
        CatalogueError.OFFLINE -> ctx.getString(R.string.catalogue_offline)
        CatalogueError.TIMEOUT -> ctx.getString(R.string.catalogue_timeout)
        CatalogueError.OTHER -> ctx.getString(R.string.catalogue_http, e.code ?: 0)
    }
    is RoadDataUnavailable -> ctx.getString(R.string.road_data_unavailable)
    else -> e.message ?: e.javaClass.simpleName
}
