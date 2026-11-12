package com.jari.agent.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak

val Bg = Color(0xFFEEF1F6)
val CardBg = Color.White
val Navy = Color(0xFF002C5F) // Hyundai Blue
val Blue = Color(0xFF1F6BFF)
val BlueSoft = Color(0xFFE9F0FF)
val Ink = Color(0xFF0E1B2E)
val Sub = Color(0xFF8A94A6)
val Line = Color(0xFFE5E9F0)
val Warm = Color(0xFFFF7A45)
val Cool = Color(0xFF2BB3E6)

/** 한글은 기본이 글자 단위 줄바꿈("주세/요") → 앱 전체를 띄어쓰기(어절) 단위로. Android 13+에서 적용 */
private val KoreanText = TextStyle(
    localeList = LocaleList("ko-KR"),
    lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
)

@Composable
fun JariTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(primary = Blue, background = Bg, surface = CardBg, onSurface = Ink),
) {
    ProvideTextStyle(LocalTextStyle.current.merge(KoreanText), content)
}
