# Сторонние библиотеки и атрибуция

Screen Translator использует:

- Kotlin и kotlinx.coroutines (JetBrains), AndroidX / Jetpack Compose / Material 3 / DataStore (Android Open Source Project): Apache License 2.0. Тексты лицензий опубликованы в соответствующих проектах и артефактах библиотек.
- Kotlin Serialization JSON 1.8.1 (JetBrains), OkHttp 4.12.0 / Okio (Square) и MockWebServer 4.12.0 (только тесты): Apache License 2.0.
- ML Kit Text Recognition v2 (`com.google.mlkit:text-recognition:16.0.1`), bundled Latin recognizer.
- ML Kit on-device Translation (`com.google.mlkit:translate:17.0.3`) и транзитивные компоненты Google Play services: [условия ML Kit](https://developers.google.com/ml-kit/terms), [условия Translation](https://developers.google.com/ml-kit/language/translation/translation-terms).
- Gradle Wrapper: Apache License 2.0, исходники из официального тега Gradle v8.11.1; бинарный wrapper включён в репозиторий.
- Значок перевода основан на Material Icons `translate` (Google), Apache License 2.0.

Результаты обычного режима обозначены текстом «Powered by Google Translate», Gemini — «Gemini 3.5 Flash-Lite». Это машинный перевод: точность не гарантируется. Логотипы Google не перерисовываются и права на товарные знаки не заявляются.

Режим Gemini обращается к платному/квотируемому сервису пользователя по REST, без встроенного ключа. Применяются [условия Gemini API](https://ai.google.dev/gemini-api/terms) и [требования Google Search grounding](https://ai.google.dev/gemini-api/docs/google-search). Возвращённый Google Search Suggestions HTML сохраняет исходную разметку и отображается в системном Android WebView с отключёнными JavaScript, локальным доступом и мостами. Системный WebView не включён в APK как отдельный движок. Полная сверка актуальных требований Google и проверка конкретной модели в живом API не завершены из-за недоступности ai.google.dev в среде; подробности и ручные проверки — GEMINI_VALIDATION.md.

Ссылки для проверки текущих требований:

- https://developers.google.com/ml-kit/language/translation/translation-terms
- https://cloud.google.com/translate/attribution
- https://developers.google.com/ml-kit/language/translation/android
- https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/develop/background-work/services/fgs/service-types

При подготовке исходной версии 1.0 прямое чтение developer.android.com и developers.google.com было отклонено прокси с HTTP 403. Это историческое наблюдение до публикации среды; при работе над поиском документация Android уже доступна. Поэтому проверка **актуальной редакции требований к графической атрибуции** остаётся открытой; текстовая атрибуция реализована, но соответствие последней редакции branding guidelines не заявляется. Перед публичным распространением требуется проверить указанные документы и, если требуется, включить официальный attribution asset Google. Настройки, сохранённые для исходной версии, впоследствии опубликованы пользователем. Версия 1.1 добавила поиск без дополнительной проверки графической атрибуции переводчика. Ограничения проверки Gemini версии 1.2 приведены выше.
