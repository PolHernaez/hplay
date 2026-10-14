package com.hplay.app

import com.hplay.app.cast.AppLinks
import com.hplay.app.cast.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinksTest {
    @Test fun youtubeVariants() {
        for (u in listOf(
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=x",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ?feature=share",
        )) {
            val l = AppLinks.parse(u)!!
            assertEquals(u, Service.YOUTUBE, l.service)
            assertEquals(u, "dQw4w9WgXcQ", l.id)
        }
        assertEquals("https://www.youtube.com/watch?v=abc&t=42", AppLinks.parse("https://youtu.be/abc?t=42s")!!.url)
    }

    @Test fun otherServices() {
        assertEquals("80057281", AppLinks.parse("https://www.netflix.com/es/title/80057281?s=a")!!.id)
        assertEquals("https://www.netflix.com/title/80057281", AppLinks.parse("https://www.netflix.com/watch/80057281")!!.url)
        assertEquals("spotify:track:4uLU6hMCjMI75M1A2tKUQC", AppLinks.parse("https://open.spotify.com/intl-es/track/4uLU6hMCjMI75M1A2tKUQC?si=1")!!.url)
        assertEquals("ibai", AppLinks.parse("https://www.twitch.tv/ibai")!!.id)
        assertEquals(Service.DISNEY, AppLinks.parse("https://www.disneyplus.com/video/abc-123")!!.service)
    }

    @Test fun plainWebIsNotAnApp() {
        assertNull(AppLinks.parse("https://example.com/video.mp4"))
        assertNull(AppLinks.parse("hola"))
    }
}
