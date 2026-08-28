package com.jetbrains.rider.plugins.robustyaml.highlighting

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.util.Function
import com.intellij.util.ui.JBImageIcon
import com.jetbrains.rider.plugins.robustyaml.documentation.RobustSpritePreview
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The sprite itself in the gutter, beside the line that names it. The frame is the one the hover
 * shows, only fitted to a line: the gutter scales an icon by the zoom of the editor alone
 * (`EditorGutterComponentImpl.scaleIcon` → `EditorUIUtil.scaleIcon`) and widens its area to the
 * widest icon there is, so the size is ours to choose and the only real ceiling is the height of a
 * row. At that size it is a silhouette and not a picture — enough to tell one thing from another
 * while scrolling, and the hover is a mouse away for the detail.
 *
 * The icon goes on one line of the pair, not on both: where a `state:` stands beside a path, the
 * frame belongs to the state, and marking the path too would only repeat it a line above. A path
 * to an `.rsi` with no state around it is drawn only when the directory names its own `icon.png`;
 * which frame to show is otherwise a guess, and a guessed frame shrunk to sixteen pixels is what
 * made the wall of `hierophant.rsi` look like a broken icon. See `RobustSpritePreview.gutterFrame`.
 *
 * Reading the file happens in [collectSlowLineMarkers], the pass the platform runs off the first
 * one: a file of entities names hundreds of sprites, and every one of them is a `meta.json` and a
 * PNG. Repeats cost nothing — the rendered frames live in the soft-value cache of
 * [RobustSpritePreview] and are shared with the hover.
 */
class RobustSpriteGutter : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        for (element in elements) {
            // A marker belongs on a leaf: the platform asks about every element of the file, and one
            // hung on a composite would be recomputed for the whole subtree under it.
            if (element.firstChild != null) continue
            val scalar = element.parent as? YAMLScalar ?: continue
            val png = frameOf(scalar) ?: continue

            val image = RobustSpritePreview.fitted(png, ICON_SIZE) ?: continue
            result += LineMarkerInfo(
                element,
                element.textRange,
                JBImageIcon(image),
                Function { RobustSpritePreview.describe(png) },
                GutterIconNavigationHandler { _, target ->
                    OpenFileDescriptor(target.project, png).navigate(true)
                },
                GutterIconRenderer.Alignment.RIGHT,
            )
        }
    }

    private fun frameOf(scalar: YAMLScalar): VirtualFile? {
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        if (keyValue.value !== scalar) return null
        return RobustSpritePreview.gutterFrame(scalar)
    }

    private companion object {
        /**
         * Sixteen: the height of a row at the default font, and the point where a frame of 32 still
         * shows what it is. Raising it above the row makes icons overlap, lowering it turns a
         * silhouette into noise.
         */
        const val ICON_SIZE = 16
    }
}
