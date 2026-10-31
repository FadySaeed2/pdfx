package com.fadysaeed.pdfx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fadysaeed.pdfx.R

/**
 * PDFx shares CalPal's design language: true black for AMOLED, warm paper-and-stone neutrals,
 * one amber accent for the action that matters and teal as the quiet second colour.
 * No cards, no shadows, no gradients; hairlines and type do the structuring.
 */
object Ink {
    val Black = Color(0xFF000000)
    val Well = Color(0xFF070706)
    val Field = Color(0xFF0C0B09)
    val Hairline = Color(0xFF1E1B16)
    val Rule = Color(0xFF2A2620)
    val Edge = Color(0xFF3A352D)

    val Bone = Color(0xFFECE4D3)
    val Sand = Color(0xFFCFC6B4)
    val Dust = Color(0xFFA0978A)
    val Ash = Color(0xFF7D766B)
    val Slate = Color(0xFF6E675C)

    val Amber = Color(0xFFE0A43B)
    val Teal = Color(0xFF3E9F8F)
    val Earth = Color(0xFF8A6644)
    val OnAmber = Color(0xFF1A1206)

    /** The colour of paper behind a page while it renders. */
    val Paper = Color(0xFFF4F1EA)
}

val Bricolage = FontFamily(
    Font(R.font.bricolage_light, FontWeight.Light),
    Font(R.font.bricolage_regular, FontWeight.Normal),
    Font(R.font.bricolage_medium, FontWeight.Medium),
    Font(R.font.bricolage_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_bold, FontWeight.Bold),
    Font(R.font.bricolage_extrabold, FontWeight.ExtraBold),
)

object Type {
    val hero = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Light, fontSize = 64.sp, lineHeight = 62.sp, letterSpacing = (-0.045).em, color = Ink.Bone)
    val display = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Light, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.04).em, color = Ink.Bone)
    val brand = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, letterSpacing = (-0.03).em, color = Ink.Bone)
    val title = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = Ink.Bone)
    val body = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp, color = Ink.Bone)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val note = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp, color = Ink.Dust)
    val small = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = Ink.Dust)
    val eyebrow = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.14.em, color = Ink.Ash)
    val tiny = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 12.sp, color = Ink.Ash)
    val button = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink.OnAmber)
    val figure = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.02.em, color = Ink.Sand)
}

@Composable
fun PdfxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Amber,
            onPrimary = Ink.OnAmber,
            secondary = Ink.Teal,
            background = Ink.Black,
            onBackground = Ink.Bone,
            surface = Ink.Field,
            onSurface = Ink.Bone,
            surfaceVariant = Ink.Field,
            onSurfaceVariant = Ink.Dust,
            outline = Ink.Edge,
        ),
        content = content,
    )
}
