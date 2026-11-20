package app.kotowski.keepsimalive.res

import android.content.Context
import android.content.res.XmlResourceParser
import android.util.Xml
import app.kotowski.keepsimalive.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

/**
 * Pins the launcher icon wiring: the manifest points at the adaptive mipmaps,
 * both adaptive icons reference the white background, the vector icon and the
 * monochrome (themed icon) layer, and the vectors are well-formed (512 canvas,
 * scaled into the launcher safe zone, valid paths).
 */
@RunWith(RobolectricTestRunner::class)
class LauncherIconTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `manifest icon and round icon point at the launcher mipmaps`() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.ic_launcher, info.icon)
        // roundIcon is no longer exposed on the SDK stubs, so verify it in the manifest
        val attrs = applicationManifestAttributes()
        assertEquals("@mipmap/ic_launcher", attrs["icon"])
        assertEquals("@mipmap/ic_launcher_round", attrs["roundIcon"])
    }

    private fun applicationManifestAttributes(): Map<String, String> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(File("src/main/AndroidManifest.xml").readText()))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "application") {
                return (0 until parser.attributeCount).associate { i ->
                    parser.getAttributeName(i) to parser.getAttributeValue(i)
                }
            }
            event = parser.next()
        }
        error("<application> element not found in AndroidManifest.xml")
    }

    @Test
    fun `both adaptive icons reference the background, the vector icon and the monochrome layer`() {
        for (mipmap in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val layers = adaptiveLayers(mipmap)
            assertEquals(setOf("background", "foreground", "monochrome"), layers.keys)
            assertEquals(R.drawable.ic_launcher_background, layers["background"])
            assertEquals(R.drawable.ic_launcher_foreground, layers["foreground"])
            assertEquals(R.drawable.ic_launcher_monochrome, layers["monochrome"])
        }
    }

    @Test
    fun `vector icon is the 512 canvas art scaled into the launcher safe zone`() {
        val vector = parseVector(R.drawable.ic_launcher_foreground)
        assertEquals(512f, vector.viewportWidth, 0.5f)
        assertEquals(512f, vector.viewportHeight, 0.5f)
        val group = vector.groups.single()
        assertTrue("foreground must be scaled down into the safe zone", group.scaleX < 1f)
        assertEquals("uniform scale", group.scaleX, group.scaleY, 0.01f)
        assertEquals("scale pivot must be the canvas center", vector.viewportWidth / 2f, group.pivotX, 0.5f)
        assertEquals("scale pivot must be the canvas center", vector.viewportHeight / 2f, group.pivotY, 0.5f)
        assertTrue(vector.paths.isNotEmpty())
        // the parser returns colors in canonical #ffRRGGBB form (alpha expanded, lowercased)
        val color = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
        for (path in vector.paths) {
            assertTrue("pathData must not be empty", !path.pathData.isNullOrBlank())
            assertTrue(
                "invalid fillColor ${path.fillColor}",
                path.fillColor != null && color.matches(path.fillColor),
            )
        }
        assertTrue(
            "sim body green #96f1ad expected",
            vector.paths.any { it.fillColor?.rgbPart()?.equals("96f1ad", ignoreCase = true) == true },
        )
    }

    @Test
    fun `monochrome icon is a single-color vector scaled into the launcher safe zone`() {
        val vector = parseVector(R.drawable.ic_launcher_monochrome)
        assertEquals(512f, vector.viewportWidth, 0.5f)
        assertEquals(512f, vector.viewportHeight, 0.5f)
        val group = vector.groups.single()
        assertTrue("monochrome must be scaled down into the safe zone", group.scaleX < 1f)
        assertEquals("uniform scale", group.scaleX, group.scaleY, 0.01f)
        assertEquals("scale pivot must be the canvas center", vector.viewportWidth / 2f, group.pivotX, 0.5f)
        assertEquals("scale pivot must be the canvas center", vector.viewportHeight / 2f, group.pivotY, 0.5f)
        // card outline, chip outline, chip contact grid and the heartbeat line
        assertEquals(4, vector.paths.size)
        for (path in vector.paths) {
            assertTrue("pathData must not be empty", !path.pathData.isNullOrBlank())
            // themed icons are tinted by the launcher: one solid stroke color, no fills
            // (the parser may return colors with or without the alpha prefix)
            val stroke = path.strokeColor?.lowercase()
            assertTrue(
                "strokeColor must be solid white, was $stroke",
                stroke == "#ffffff" || stroke == "#ffffffff",
            )
            assertTrue("stroke must have a width", path.strokeWidth != null && path.strokeWidth > 0f)
            assertTrue("monochrome art must not use fills", path.fillColor == null)
        }
        // the heartbeat line is the thick open polyline and must run past the card
        // edges (card outline spans x 131..392 in the 512 canvas); the contact grid
        // is open too, so pick the line by its stroke width
        val line = vector.paths.single { it.strokeWidth == 20f }
        // the binary XML parser returns enum attrs as ordinals (Paint.Cap: butt=0, round=1, square=2)
        assertEquals("round caps", "1", line.strokeLineCap)
        val numbers = line.pathData.orEmpty().split(Regex("[^0-9.\\-]+"))
        val xs = numbers.mapNotNull { it.toFloatOrNull() }
        assertTrue("line must start left of the card", xs.minOrNull()!! < 131f)
        assertTrue("line must end right of the card", xs.maxOrNull()!! > 392f)
    }

    private fun adaptiveLayers(mipmapResId: Int): Map<String, Int> {
        val parser = context.resources.getXml(mipmapResId)
        try {
            val layers = mutableMapOf<String, Int>()
            var rootName: String? = null
            var event = parser.eventType
            while (event != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG) {
                    if (rootName == null) {
                        rootName = parser.name
                    }
                    if (parser.name == "background" || parser.name == "foreground" || parser.name == "monochrome") {
                        layers[parser.name] = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
                    }
                }
                event = parser.next()
            }
            assertEquals("adaptive-icon", rootName)
            return layers
        } finally {
            parser.close()
        }
    }

    private data class VectorSpec(
        val viewportWidth: Float,
        val viewportHeight: Float,
        val groups: List<Group>,
        val paths: List<Path>,
    )

    private data class Group(
        val scaleX: Float,
        val scaleY: Float,
        val pivotX: Float,
        val pivotY: Float,
    )

    private data class Path(
        val pathData: String?,
        val fillColor: String?,
        val strokeColor: String?,
        val strokeWidth: Float?,
        val strokeLineCap: String?,
    )

    private fun parseVector(drawableResId: Int): VectorSpec {
        val parser = context.resources.getXml(drawableResId)
        try {
            var viewportWidth = 0f
            var viewportHeight = 0f
            var rootFound = false
            val groups = mutableListOf<Group>()
            val paths = mutableListOf<Path>()
            var event = parser.eventType
            while (event != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG) {
                    when (parser.name) {
                        "vector" -> {
                            rootFound = true
                            viewportWidth = attrValue(parser, "viewportWidth")?.toFloat() ?: 0f
                            viewportHeight = attrValue(parser, "viewportHeight")?.toFloat() ?: 0f
                        }

                        "group" -> {
                            groups +=
                                Group(
                                    attrValue(parser, "scaleX")?.toFloat() ?: 1f,
                                    attrValue(parser, "scaleY")?.toFloat() ?: 1f,
                                    attrValue(parser, "pivotX")?.toFloat() ?: 0f,
                                    attrValue(parser, "pivotY")?.toFloat() ?: 0f,
                                )
                        }

                        "path" -> {
                            paths +=
                                Path(
                                    attrValue(parser, "pathData"),
                                    attrValue(parser, "fillColor"),
                                    attrValue(parser, "strokeColor"),
                                    attrValue(parser, "strokeWidth")?.toFloat(),
                                    attrValue(parser, "strokeLineCap"),
                                )
                        }
                    }
                }
                event = parser.next()
            }
            assertTrue("vector root not found", rootFound)
            return VectorSpec(viewportWidth, viewportHeight, groups, paths)
        } finally {
            parser.close()
        }
    }

    // Robolectric's parser is namespace-aware: attribute names come back as local
    // names (no "android:" prefix), so look them up by local name.
    private fun attrValue(
        parser: XmlResourceParser,
        name: String,
    ): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun String.rgbPart(): String = substringAfterLast('#').takeLast(6)

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
