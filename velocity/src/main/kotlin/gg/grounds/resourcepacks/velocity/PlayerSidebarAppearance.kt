package gg.grounds.resourcepacks.velocity

/** Separate from chat: an old accepted chat pack does not contain sidebar graphics. */
internal object SidebarAppearanceEnvironment {
    const val CHANNEL = "grounds:sidebar_style"

    fun from(environment: Map<String, String>): Set<String> {
        val raw = environment["SIDEBAR_FONT_PACK_SHA1S"].orEmpty()
        if (raw.isBlank()) return emptySet()
        val hashes = raw.split(',').map(String::trim)
        require(hashes.all { it.matches(Regex("[0-9a-f]{40}")) }) {
            "SIDEBAR_FONT_PACK_SHA1S must contain lowercase SHA-1 values"
        }
        return hashes.toSet()
    }

    fun eligible(protocol: Int?, bedrock: Boolean?): Boolean = protocol == 777 && bedrock == false
}
