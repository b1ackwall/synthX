package com.v1per.synthx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.TextPrimary

@Composable
fun rememberWordmark(): AnnotatedString {
    val wordmarkFont = remember {
        FontFamily(Font(R.font.bitcount_grid_double_regular, FontWeight.Normal))
    }
    return remember(wordmarkFont) {
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = TextPrimary,
                    fontFamily = wordmarkFont,
                    fontWeight = FontWeight.Normal,
                ),
            ) { append("synth") }
            withStyle(
                SpanStyle(
                    color = Accent,
                    fontFamily = wordmarkFont,
                    fontWeight = FontWeight.Normal,
                ),
            ) { append("X") }
        }
    }
}
