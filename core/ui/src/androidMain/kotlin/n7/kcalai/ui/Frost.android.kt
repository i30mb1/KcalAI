package n7.kcalai.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

// SDK_INT_FULL появился в 36: на более старых системах поля нет вовсе.
actual val frostSupported: Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2

actual fun Modifier.frost(blur: Dp, saturation: Float): Modifier =
    if (frostSupported) this then FrostElement(blur, saturation) else this

@RequiresApi(Build.VERSION_CODES_FULL.CINNAMON_BUN_2)
private data class FrostElement(val blur: Dp, val saturation: Float) : ModifierNodeElement<FrostNode>() {
    override fun create() = FrostNode(blur, saturation)

    override fun update(node: FrostNode) = node.update(blur, saturation)
}

/**
 * Пустой RenderNode с backdrop-эффектом под содержимым.
 *
 * Сам узел ничего не рисует: эффект берёт то, что уже нарисовано под ним, —
 * ленту, — размывает и кладёт на место. Записывается узел один раз на размер
 * и эффект: прокрутка ленты его не перезаписывает, размытие пересчитывает
 * RenderThread.
 */
@RequiresApi(Build.VERSION_CODES_FULL.CINNAMON_BUN_2)
private class FrostNode(private var blur: Dp, private var saturation: Float) : Modifier.Node(), DrawModifierNode {
    private var backdrop: RenderNode? = null
    private var effect: RenderEffect? = null
    private var effectRadiusPx = -1f

    fun update(blur: Dp, saturation: Float) {
        if (blur == this.blur && saturation == this.saturation) return
        this.blur = blur
        this.saturation = saturation
        effect = null
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        drawIntoCanvas { canvas ->
            // Узел с эффектом рисуется только аппаратным холстом: снимок экрана
            // в картинку идёт программным и получает элемент без стекла.
            val native = canvas.nativeCanvas
            if (width > 0 && height > 0 && native.isHardwareAccelerated) {
                native.drawRenderNode(record(width, height, blur.toPx()))
            }
        }
        drawContent()
    }

    private fun record(width: Int, height: Int, radiusPx: Float): RenderNode {
        val node = backdrop ?: RenderNode("frost").also { backdrop = it }
        if (effect == null || radiusPx != effectRadiusPx) {
            node.setBackdropRenderEffect(frostEffect(radiusPx).also { effect = it })
            effectRadiusPx = radiusPx
        }
        if (node.width != width || node.height != height || !node.hasDisplayList()) {
            node.setPosition(0, 0, width, height)
            node.beginRecording(width, height)
            node.endRecording()
        }
        return node
    }

    private fun frostEffect(radiusPx: Float): RenderEffect {
        val blurred = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        val saturate = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
        return RenderEffect.createColorFilterEffect(saturate, blurred)
    }

    override fun onDetach() {
        backdrop?.discardDisplayList()
        backdrop = null
        effect = null
        effectRadiusPx = -1f
    }
}
