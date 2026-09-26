package com.loosewire.lightious.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ExperienceModeTest {
    @Test
    fun `defaults to focused until the companion provides a profile`() {
        val profile: CompanionProfile? = null

        assertEquals(ExperienceMode.FOCUSED, profile.effectiveExperienceMode())
    }

    @Test
    fun `keeps an explicit library profile as the fallback experience`() {
        val profile = CompanionProfile(
            deviceId = "device",
            account = "account",
            revision = 1,
            mode = ExperienceMode.LIBRARY,
            items = emptyList(),
        )

        assertEquals(ExperienceMode.LIBRARY, profile.effectiveExperienceMode())
    }

    @Test
    fun `discarding an unverified profile preserves its paired session and fails closed`() {
        val session = CompanionSession(
            instanceUrl = "https://invidious.example",
            deviceId = "device",
            account = "account",
            deviceBearer = "credential",
        )
        val staleLibrary = CompanionState(
            session = session,
            profile = CompanionProfile(
                deviceId = "device",
                account = "account",
                revision = 1,
                mode = ExperienceMode.LIBRARY,
                items = emptyList(),
            ),
        )

        val failClosed = staleLibrary.withoutUnverifiedProfile()

        assertEquals(session, failClosed.session)
        assertEquals(null, failClosed.profile)
        assertEquals(ExperienceMode.FOCUSED, failClosed.profile.effectiveExperienceMode())
    }

    @Test
    fun `old explore values migrate to library and current modes round trip`() {
        assertEquals(ExperienceMode.LIBRARY, ExperienceMode.fromWire("explore"))
        assertEquals(ExperienceMode.LIBRARY, Json.decodeFromString<ExperienceMode>("\"EXPLORE\""))
        assertEquals(ExperienceMode.LIBRARY, Json.decodeFromString<ExperienceMode>("\"explore\""))
        ExperienceMode.entries.forEach { mode ->
            assertEquals(mode, Json.decodeFromString<ExperienceMode>(Json.encodeToString(mode)))
        }
        assertEquals(null, ExperienceMode.fromWire("unknown"))
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ExperienceMode>("\"UNKNOWN\"")
        }
    }
}
