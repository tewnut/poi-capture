package io.github.tewnut.poicapture.core

/** The pure decisions of the upload work (capture/UploadWork.kt), kept here so they are tested on the JVM. */
object UploadRules {
    /** Failed attempts before an upload stops trying by itself (Try again starts over). */
    const val MAX_FAILURES = 7
    /** Room kept free besides the zip: the phone needs some to go on working (and the next ride). */
    const val SPARE_BYTES = 64L shl 20

    /**
     * Free space needed to build the zip of a ride of [rideBytes]: the zip is about the ride's size (photos do not
     * compress), plus 5 % and [SPARE_BYTES].
     */
    fun roomNeeded(rideBytes: Long): Long = rideBytes + rideBytes / 20 + SPARE_BYTES

    /**
     * The project's server refused the access key: 401 / 403 from the server's own calls ("direct", "direct urls",
     * "direct complete"). A 403 from R2 on a part ("part: HTTP 403") is an expired presigned link, not the key.
     */
    fun isAuth(code: Int, message: String?): Boolean =
        (code == 401 || code == 403) && message?.startsWith("part:") != true

    /** The disk filled up while writing (ENOSPC), whatever the exception says around it. */
    fun isNoSpace(message: String?): Boolean =
        message != null && (message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true))

    /** After a failure: stop for good (FAILED) or try again later. [failures] counts this one. */
    fun giveUp(permanent: Boolean, failures: Int): Boolean = permanent || failures >= MAX_FAILURES
}
