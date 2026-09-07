package com.lain.assistant.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icons drawn as pixels, because everything else here is.
 *
 * Nothing is imported from Material or any icon set. The rest of Lain is pixel art
 * — the portrait, the buttons, the typeface — and a smooth vector glyph dropped
 * into that reads as a piece of someone else's app. These are grids of filled
 * squares, scaled to whatever size the caller asks for, so they stay crisp at any
 * density instead of being a bitmap that softens on a big screen.
 *
 * A glyph is written as rows of text. That is deliberate: the shape is legible in
 * the source, so changing it means editing the picture rather than working out
 * which coordinate is which.
 */
object PixelIcons {

    /**
     * A megaphone, pointing right, with two sound waves.
     *
     * Read the '#' as filled. Columns get taller left to right, which is what makes
     * a flat grid read as a cone rather than a box.
     */
    private val MEGAPHONE = listOf(
        ".....##....",
        "....###...#",
        "...####...#",
        "#######.#.#",
        "#######.#.#",
        "#######.#.#",
        "...####...#",
        "....###...#",
        ".....##...."
    )

    @Composable
    fun Megaphone(color: Color, size: Dp = 14.dp, modifier: Modifier = Modifier) {
        PixelGlyph(MEGAPHONE, color, size, modifier)
    }

    /**
     * Draws a glyph grid, snapping every cell to whole pixels.
     *
     * The rounding is the whole trick. Laying cells out at fractional positions
     * leaves hairline gaps between them on some densities and doubles up on others,
     * which is exactly the smeared look pixel art is supposed to avoid.
     */
    @Composable
    private fun PixelGlyph(rows: List<String>, color: Color, size: Dp, modifier: Modifier) {
        val columns = rows.maxOf { it.length }
        Canvas(modifier = modifier.size(size)) {
            val cell = minOf(this.size.width / columns, this.size.height / rows.size)
            // Centred, so a glyph that isn't square sits in the middle of its box.
            val originX = (this.size.width - cell * columns) / 2f
            val originY = (this.size.height - cell * rows.size) / 2f
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, mark ->
                    if (mark != '#') return@forEachIndexed
                    drawRect(
                        color = color,
                        topLeft = Offset(originX + x * cell, originY + y * cell),
                        size = Size(cell, cell)
                    )
                }
            }
        }
    }
}
