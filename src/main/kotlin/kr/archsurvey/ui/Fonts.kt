package kr.archsurvey.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** 두 앱과 같은 글꼴: Pretendard Regular/Bold(OFL 1.1), 숫자 IBM Plex Mono 400/500(OFL 1.1) */
object Fonts {
    val sans = FontFamily(
        Font(kr.archsurvey.pg.R.font.pretendard_regular, FontWeight.Light),
        Font(kr.archsurvey.pg.R.font.pretendard_regular, FontWeight.Normal),
        Font(kr.archsurvey.pg.R.font.pretendard_regular, FontWeight.Medium),
        Font(kr.archsurvey.pg.R.font.pretendard_bold, FontWeight.SemiBold),
        Font(kr.archsurvey.pg.R.font.pretendard_bold, FontWeight.Bold),
        Font(kr.archsurvey.pg.R.font.pretendard_bold, FontWeight.ExtraBold),
    )
    val mono = FontFamily(
        Font(kr.archsurvey.pg.R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(kr.archsurvey.pg.R.font.ibm_plex_mono_medium, FontWeight.Medium),
    )
}
