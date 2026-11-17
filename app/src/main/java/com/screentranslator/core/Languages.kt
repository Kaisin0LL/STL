package com.screentranslator.core

data class Language(val code: String, val name: String)
data class LanguagePair(val source: String = "en", val target: String = "ru")
object Languages {
    // Latin OCR only. Translation support does not imply OCR support.
    val sourceCodes = setOf("en", "de", "fr", "es", "it", "pt")
    val targets = listOf(
        "af:Африкаанс", "sq:Албанский", "ar:Арабский", "be:Белорусский", "bg:Болгарский",
        "bn:Бенгальский", "ca:Каталанский", "cs:Чешский", "cy:Валлийский", "da:Датский",
        "de:Немецкий", "el:Греческий", "en:Английский", "eo:Эсперанто", "es:Испанский",
        "et:Эстонский", "fa:Персидский", "fi:Финский", "fr:Французский", "ga:Ирландский",
        "gl:Галисийский", "gu:Гуджарати", "he:Иврит", "hi:Хинди", "hr:Хорватский",
        "ht:Гаитянский креольский", "hu:Венгерский", "id:Индонезийский", "is:Исландский",
        "it:Итальянский", "ja:Японский", "ka:Грузинский", "kn:Каннада", "ko:Корейский",
        "lt:Литовский", "lv:Латышский", "mk:Македонский", "mr:Маратхи", "ms:Малайский",
        "mt:Мальтийский", "nl:Нидерландский", "no:Норвежский", "pl:Польский", "pt:Португальский",
        "ro:Румынский", "ru:Русский", "sk:Словацкий", "sl:Словенский", "sv:Шведский",
        "sw:Суахили", "ta:Тамильский", "te:Телугу", "th:Тайский", "tl:Тагальский",
        "tr:Турецкий", "uk:Украинский", "ur:Урду", "vi:Вьетнамский", "zh:Китайский"
    ).map { val (code, name) = it.split(':'); Language(code, name) }.sortedBy { it.name }
    val sources = targets.filter { it.code in sourceCodes }
    fun supports(pair: LanguagePair) = pair.source in sourceCodes &&
        targets.any { it.code == pair.target } && pair.source != pair.target
    fun supports(pair: LanguagePair, mode: AppMode) = if (mode == AppMode.LOCAL) supports(pair) else pair.source != pair.target && targets.any { it.code == pair.source } && targets.any { it.code == pair.target }
    fun name(code: String) = targets.firstOrNull { it.code == code }?.name ?: code
}
