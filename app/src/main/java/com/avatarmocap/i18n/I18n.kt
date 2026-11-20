package com.avatarmocap.i18n

import java.util.Locale

/**
 * Traducción simple español / inglés. [tr] devuelve el texto en el idioma activo.
 * El idioma se elige en Ajustes (automático = idioma del teléfono).
 */
object I18n {
    @Volatile var english: Boolean = !Locale.getDefault().language.startsWith("es")
        private set

    /** 0 = automático (idioma del teléfono), 1 = español, 2 = inglés. */
    fun setMode(mode: Int) {
        english = when (mode) {
            1 -> false
            2 -> true
            else -> !Locale.getDefault().language.startsWith("es")
        }
    }
}

fun tr(es: String, en: String): String = if (I18n.english) en else es
