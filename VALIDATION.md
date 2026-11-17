# Проверка исходной версии 1.0

Это исторический отчёт до добавления поиска; актуальный отчёт — [SEARCH_VALIDATION.md](SEARCH_VALIDATION.md).

Проверено 17 ноября 2026 года в текущей облачной Linux-среде. Проверка Android-приложения на устройстве не выполнялась.

## Сборка и тесты

Подготовлены JDK Temurin 17.0.17+10, Android SDK Platform 35, Build Tools 35.0.0. JDK и SDK загружены из официальных источников с проверкой опубликованных контрольных сумм. Gradle Wrapper 8.11.1 проверен по официальному SHA-256; дистрибутив Gradle закреплён контрольной суммой в wrapper properties.

Выполнена ожидаемая команда:

```bash
./gradlew --no-daemon :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Затем выполнена контрольная сборка с удалением всех выходных файлов:

```bash
cd /workspace/STL
JAVA_HOME=/workspace/toolchains/jdk-17.0.17+10 \
ANDROID_USER_HOME=/workspace/android-user \
GRADLE_USER_HOME=/workspace/gradle-home \
./gradlew --no-daemon clean :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

**Результат: BUILD SUCCESSFUL, 52 задачи выполнены, 49 секунд.** SDK указан в локальном `local.properties`; этот файл не входит в Git. Кэш Gradle использует прокси среды и системное хранилище CA. Проверка TLS не отключалась. Сообщение Android tools о невозможности записи необязательных analytics settings в домашний каталог не препятствует сборке.

| Проверка | Результат |
| --- | --- |
| Debug APK | Собран |
| Android Lint | 0 ошибок, 0 предупреждений в итоговом XML-отчёте после clean |
| Координаты, масштаб, смещения, границы, ландшафт | 6 тестов пройдено |
| Языковые направления и сверка с реальным списком ML Kit | 4 теста пройдено |
| Переходы состояний, отмена, поздние результаты, остановка, поворот | 6 тестов пройдено |
| Ограниченный LRU-кэш с ключом язык + текст | 1 тест пройден |
| Итого JVM-тесты | 17 пройдено, 0 ошибок, 0 падений, 0 пропусков |
| `apksigner verify --verbose` | Успешно, APK Signature Scheme v2 |
| `zipalign -c -P 16 4` | Успешно |
| arm64 ELF LOAD-сегменты нативных библиотек | Выравнивание 0x4000 (16 КБ) |
| `git diff --check` | Успешно |

APK: `app/build/outputs/apk/debug/app-debug.apk` (138 530 134 байта, примерно 132 МиБ). Это универсальный debug APK с bundled OCR и нативными библиотеками разных архитектур. Контрольная сумма относится именно к этому файлу; следующая debug-сборка может иметь другую подпись/метаданные.

```text
SHA-256: a1a78e58958817dbfb2c632155d6453c7115617547608b82f37dfa2846907ace
```

Отчёты текущей сборки:

- `app/build/reports/lint-results-debug.html` и `.xml`;
- `app/build/reports/tests/testDebugUnitTest/index.html`;
- `app/build/test-results/testDebugUnitTest/TEST-*.xml`.

## Не проверено / внешние ограничения

- Реальный Android 15: разрешения, целостная цепочка MediaProjection → Latin OCR → Translation → overlay, загрузка моделей, работа без сети, drag/tap, системные панели, поворот, блокировка, отзыв захвата. Ручной сценарий приведён в README. Выравнивание библиотек не заменяет запуск на устройстве с 16-КБ страницами.
- GitHub Actions: workflow создан, но в этой сессии не запускался. Изменения не отправлялись в GitHub. После push можно получить артефакты `ScreenTranslator-debug` и `ScreenTranslator-reports`.
- Актуальные страницы developer.android.com / developers.google.com недоступны в действующей сети (прокси HTTP 403). Текстовая атрибуция Google добавлена; соответствие актуальным требованиям к официальному графическому attribution asset ещё нужно проверить. Подробности и ссылки — THIRD_PARTY_NOTICES.md.
- В черновике конфигурации облака сохранены `start_skill` с проверенной командой и путями инструментов, а также добавления доменов developer.android.com, developers.google.com, www.gstatic.com и maven.google.com. Поля install_script, repositories и секретов не менялись. Для применения черновика нужно проверить и сохранить изменения в настройках среды, затем опубликовать её. Публикация и восстановление в новой задаче не проверялись.
