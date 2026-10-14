package gg.grounds.resourcepacks.velocity

import gg.grounds.i18n.Translations
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kyori.adventure.text.Component

class ResourcePacksMessagesTest {
    private val messages =
        Translations.forBundle("gg.grounds.resourcepacks.messages", javaClass.classLoader)

    @Test
    fun `unavailable disconnect preserves English and supports German`() {
        assertEquals(
            Component.text("Resource packs are currently unavailable. Please try again."),
            messages.render(ResourcePacksMessage.PACKS_UNAVAILABLE, Locale.ENGLISH),
        )
        assertEquals(
            Component.text(
                "Ressourcenpakete sind derzeit nicht verfügbar. Bitte versuche es erneut."
            ),
            messages.render(ResourcePacksMessage.PACKS_UNAVAILABLE, Locale.GERMAN),
        )
    }
}
