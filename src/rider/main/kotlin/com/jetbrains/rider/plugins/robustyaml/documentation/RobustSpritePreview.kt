package com.jetbrains.rider.plugins.robustyaml.documentation

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.CollectionFactory
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustResources
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustSprites
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URL
import javax.imageio.ImageIO

object RobustSpritePreview {
    private val rendered = CollectionFactory.createConcurrentSoftValueMap<String, BufferedImage>()

    fun findImage(scalar: YAMLScalar): VirtualFile? {
        val project = scalar.project

        val spritePath = RobustYamlContext.enclosingSpritePath(scalar)
        if (spritePath != null) {
            val rsi = RobustResources.resolve(project, spritePath)?.takeIf { it.isDirectory }
            return rsi?.findChild("${scalar.textValue}.png")
        }

        if (!RobustYamlContext.isResourcePathValue(scalar)) return null
        val target = RobustResources.resolve(project, scalar.textValue) ?: return null
        if (!target.isDirectory) return target.takeIf { it.extension.equals(PNG, ignoreCase = true) }

        val state = stateNear(scalar)
        if (state != null) {
            target.findChild("$state.png")?.let { return it }
        }
        return target.findChild("icon.png")
            ?: target.children.firstOrNull { it.extension.equals(PNG, ignoreCase = true) }
    }

    /**
     * The frame the gutter may draw beside a line, which is not the frame [findImage] falls back to.
     * A state names its own PNG, and a path to a single PNG is that PNG; but a path to an `.rsi`
     * with no state around it names a directory, and the frame there has to be chosen. `icon.png`
     * is a choice its author made — the first child of the directory is not, and at sixteen pixels
     * the difference is the whole point: the wall of `hierophant.rsi` came out as a corner tile of
     * `full.png` and read as a broken icon. The hover answers such a path with the grid of every
     * state, so nothing is lost by drawing nothing.
     *
     * The cost is measured: of 11563 paths to an `.rsi` in the checkout 4212 carry a `state:`
     * beside them and are unaffected, and of the 4515 directories 60.8% hold an `icon.png`.
     */
    fun gutterFrame(scalar: YAMLScalar): VirtualFile? {
        if (RobustYamlContext.enclosingSpritePath(scalar) != null) return findImage(scalar)
        if (!RobustYamlContext.isResourcePathValue(scalar)) return null
        if (stateNear(scalar) != null) return null

        val target = RobustResources.resolve(scalar.project, scalar.textValue) ?: return null
        if (!target.isDirectory) return target.takeIf { it.extension.equals(PNG, ignoreCase = true) }
        return target.findChild(ICON)
    }

    fun findRsi(scalar: YAMLScalar): VirtualFile? {
        if (RobustYamlContext.enclosingSpritePath(scalar) != null) return null
        if (!RobustYamlContext.isResourcePathValue(scalar)) return null
        if (stateNear(scalar) != null) return null

        val target = RobustResources.resolve(scalar.project, scalar.textValue) ?: return null
        return target.takeIf { it.isDirectory && it.name.endsWith(RSI_SUFFIX, ignoreCase = true) }
    }

    /** Kept as the name the hover calls; the reading itself belongs to [RobustSprites], which the
     * validation of `state:` shares. */
    fun states(rsi: VirtualFile): List<String> = RobustSprites.states(rsi)

    fun frameOf(rsi: VirtualFile): Pair<Int, Int>? = frameSize(rsi)

    fun thumbnail(png: VirtualFile): BufferedImage? = render(png, THUMB_SIZE)

    fun cachedThumbnail(png: VirtualFile): BufferedImage? = rendered[keyOf(png, THUMB_SIZE)]

    fun thumbnailWidth(): Int = THUMB_SIZE

    private fun keyOf(png: VirtualFile, target: Int): String = "${png.url}@${png.timeStamp}@$target"

    private fun render(png: VirtualFile, target: Int): BufferedImage? {
        val key = keyOf(png, target)
        rendered[key]?.let { return it }

        val source = runCatching { png.inputStream.use { ImageIO.read(it) } }.getOrNull() ?: return null
        val image = magnify(cropFirstFrame(source, frameSize(png.parent)), target)
        rendered[key] = image
        return image
    }

    /**
     * The first frame scaled to fit [size], for the gutter. [magnify] cannot do it: it enlarges by
     * whole factors and never shrinks, while a frame of 32 has to come down to the height of a line
     * — the gutter scales an icon by the font zoom alone and does not clamp it, so anything taller
     * would climb onto the neighbouring rows.
     */
    fun fitted(png: VirtualFile, size: Int): BufferedImage? {
        val key = "${png.url}@${png.timeStamp}@fit$size"
        rendered[key]?.let { return it }

        val source = runCatching { png.inputStream.use { ImageIO.read(it) } }.getOrNull() ?: return null
        val frame = cropFirstFrame(source, frameSize(png.parent))
        val longest = maxOf(frame.width, frame.height).coerceAtLeast(1)
        val image = if (longest <= size) magnify(frame, size) else shrink(frame, size.toDouble() / longest)
        rendered[key] = image
        return image
    }

    private fun shrink(image: BufferedImage, factor: Double): BufferedImage {
        val width = (image.width * factor).toInt().coerceAtLeast(1)
        val height = (image.height * factor).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        // Bilinear, not nearest: shrinking pixel art by a fractional factor drops whole rows of
        // pixels otherwise, and a silhouette with holes in it reads worse than a soft one.
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        graphics.drawImage(image, 0, 0, width, height, null)
        graphics.dispose()
        return scaled
    }

    fun imageKey(png: VirtualFile): String? =
        runCatching { png.toNioPath().toUri().toURL().toExternalForm() }.getOrNull()
            ?: runCatching { URL(png.url).toExternalForm() }.getOrNull()

    fun loadImage(png: VirtualFile): BufferedImage? = render(png, TARGET_SIZE)

    fun describe(png: VirtualFile): String {
        val frame = frameSize(png.parent)
        val name = png.nameWithoutExtension
        val location = png.parent?.name.orEmpty()
        val size = frame?.let { "${it.first}×${it.second}" }.orEmpty()
        return listOf(name, location, size).filter { it.isNotEmpty() }.joinToString("  ")
    }

    private fun stateNear(scalar: YAMLScalar): String? {
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        val mapping = keyValue.parentMapping ?: return null
        return (mapping.getKeyValueByKey("state")?.value as? YAMLScalar)?.textValue
    }

    private fun frameSize(directory: VirtualFile?): Pair<Int, Int>? {
        val rsi = directory?.takeIf { it.name.endsWith(RSI_SUFFIX, ignoreCase = true) } ?: return null
        val meta = rsi.findChild(META) ?: return null
        val text = runCatching { VfsUtilCore.loadText(meta) }.getOrNull() ?: return null
        val match = FRAME_SIZE.find(text) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun cropFirstFrame(image: BufferedImage, frame: Pair<Int, Int>?): BufferedImage {
        if (frame == null) return image
        val (width, height) = frame
        if (width <= 0 || height <= 0) return image
        if (image.width <= width && image.height <= height) return image
        return image.getSubimage(0, 0, minOf(width, image.width), minOf(height, image.height))
    }

    private fun magnify(image: BufferedImage, target: Int): BufferedImage {
        val longest = maxOf(image.width, image.height).coerceAtLeast(1)
        val factor = (target / longest).coerceIn(1, MAX_FACTOR)
        if (factor == 1) return image

        val scaled = BufferedImage(image.width * factor, image.height * factor, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        graphics.drawImage(image, 0, 0, scaled.width, scaled.height, null)
        graphics.dispose()
        return scaled
    }

    private const val ICON = "icon.png"
    private const val PNG = "png"
    private const val META = "meta.json"
    private const val RSI_SUFFIX = ".rsi"
    private const val TARGET_SIZE = 256
    private const val THUMB_SIZE = 64
    private const val MAX_FACTOR = 8

    private val FRAME_SIZE =
        Regex(""""size"\s*:\s*\{\s*"x"\s*:\s*(\d+)\s*,\s*"y"\s*:\s*(\d+)""", RegexOption.DOT_MATCHES_ALL)
}
