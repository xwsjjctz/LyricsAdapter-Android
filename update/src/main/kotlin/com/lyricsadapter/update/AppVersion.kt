package com.lyricsadapter.update

/**
 * A version as the app is released: `1.2.3`, or `1.2.3-beta.1` on the way to
 * it. Ordered as semantic versions are, so a pre-release comes before the
 * version it leads to.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: String?) : Comparable<AppVersion> {
    /**
     * Whether an app of this version follows pre-releases: desktop's rule
     * (`electron/updater.ts`), under which everything before 1.0 and every
     * suffixed version does.
     */
    val takesPreReleases: Boolean get() = major == 0 || preRelease != null

    override fun compareTo(other: AppVersion): Int {
        val numbers = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)
        return if (numbers != 0) numbers else comparePreReleases(preRelease, other.preRelease)
    }

    override fun toString(): String = "$major.$minor.$patch" + preRelease?.let { "-$it" }.orEmpty()

    companion object {
        private val PATTERN = Regex("""v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*))?""")

        /** Reads `1.2.3`, `v1.2.3` or either with a suffix; null for anything else. */
        fun parse(text: String): AppVersion? {
            val (major, minor, patch, suffix) = PATTERN.matchEntire(text)?.destructured ?: return null
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt(), suffix.ifEmpty { null })
        }

        /** None is the release itself and comes last; otherwise part by part, numbers by value and before words. */
        private fun comparePreReleases(left: String?, right: String?): Int {
            if (left == null || right == null) return compareValues(left == null, right == null)
            val leftParts = left.split('.')
            val rightParts = right.split('.')
            for (index in 0 until minOf(leftParts.size, rightParts.size)) {
                val order = compareParts(leftParts[index], rightParts[index])
                if (order != 0) return order
            }
            return leftParts.size.compareTo(rightParts.size)
        }

        private fun compareParts(left: String, right: String): Int {
            val leftNumber = left.all(Char::isDigit)
            val rightNumber = right.all(Char::isDigit)
            return when {
                leftNumber && rightNumber -> compareValuesBy(left.trimStart('0'), right.trimStart('0'), String::length, { it })
                leftNumber != rightNumber -> if (leftNumber) -1 else 1
                else -> left.compareTo(right)
            }
        }
    }
}
