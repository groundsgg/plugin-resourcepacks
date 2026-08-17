package gg.grounds.resourcepacks.velocity

data class ResourcePackEnvironment(val deploymentEnvironment: String) {
    companion object {
        fun from(environment: Map<String, String>): ResourcePackEnvironment {
            val value = environment["GROUNDS_ENVIRONMENT"]
            require(
                value != null &&
                    value.isNotBlank() &&
                    value.none { it.isWhitespace() || it == '/' || it == '\\' }
            ) {
                "GROUNDS_ENVIRONMENT must be a non-blank path segment."
            }
            return ResourcePackEnvironment(value)
        }
    }
}
