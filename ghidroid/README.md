# Ghidroid

Дизассемблер и декомпилятор для Android на движке **Ghidra 12.1.4**. Открывает `.so`, `.dll`, `.exe`, `.sys`, `.dylib`, `.o` и сырые образы прошивок; показывает листинг, псевдокод на C, функции, строки и hex — всё считается прямо на телефоне, без сервера.

Интерфейс — Jetpack Compose в стиле **Material 3 Expressive**: `MaterialExpressiveTheme` с экспрессивной анимацией, `LargeFlexibleTopAppBar`, `ShortNavigationBar`, плавающая панель `HorizontalFloatingToolbar` с переключателем C / ASM на `ToggleButton`, связанные группы кнопок, `LoadingIndicator` / `ContainedLoadingIndicator`, `LinearWavyProgressIndicator`, формы `MaterialShapes`.

## Что внутри

- **Настоящий Ghidra, а не порт.** C++-исходники декомпилятора и SLEIGH из Ghidra собраны через NDK в `libghidroid.so` (см. `app/src/main/cpp/ghidra/PATCHES.md` — два маленьких патча). Спецификации всех процессоров Ghidra (180 языков: x86/x86-64, ARM/Thumb, AArch64, MIPS, PowerPC, RISC-V, SPARC, AVR, Xtensa, 68k, 6502, Z80, PIC, Dalvik и др.) скомпилированы в `.sla` и лежат в assets.
- **Загрузчики** (Kotlin, `loader/`): ELF (исполняемые, `.so`, `.o`; символы, импорт через релокации, DT_NEEDED, Build ID, режим Thumb по символам и маппинг-символам `$a/$t`), PE (экспорт, импорт и отложенный импорт, `.pdata`, COFF-символы MinGW, PDB, переходники `jmp [IAT]`), Mach-O (тонкие и универсальные, `__stubs`, указатели на символы, `LC_FUNCTION_STARTS`), сырой образ с выбором процессора и базового адреса.
- **Анализ**: заглушки PLT распознаются для любой архитектуры маленьким распространителем констант по p-code; поиск строк (UTF-8 / UTF-16); поиск функций по целям вызовов; перекрёстные ссылки (в том числе через `adrp+add`, `movw/movt` и литеральные пулы).
- **Код**: листинг с подсветкой, переходами по адресам, комментариями (имена функций, строки) и опциональными байтами; декомпилятор с подсветкой токенов Ghidra, переходами по функциям и глобалам, подсветкой всех вхождений переменной, переносом строк по ширине экрана; на планшете — листинг и C рядом. История «назад / вперёд», «Перейти» по адресу или имени.
- Демангинг C++, русская и английская локализации, светлая / тёмная тема, динамические цвета Android 12+.

## Сборка

```bash
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # тесты загрузчиков, движка и скриншоты UI
```

Нужны JDK 17+, Android SDK (`platforms;android-37.0`), NDK `29.0.14206865` и CMake `3.31.6`. Для тестов на машине разработчика также нужны `cmake`, компилятор C++ и `zlib`: задача `buildHostEngine` собирает тот же `libghidroid` под хост, и JVM-тесты гоняют настоящий Ghidra на бинарниках из `app/src/test/resources/fixtures` (ELF x86-64 / ARM Thumb / AArch64, PE x64, Mach-O arm64). `ScreenshotTest` (Robolectric) запускает всё приложение и сохраняет скриншоты в `app/build/screenshots`.

Без `keystore.properties` релиз подписывается debug-ключом, чтобы APK можно было сразу установить.

Обновить спецификации процессоров из другого релиза Ghidra: `tools/compile_sla.sh /путь/к/ghidra`.

## Архитектура

```
app/src/main/cpp/
├── ghidra/          исходники декомпилятора Ghidra 12.1.4 (Apache 2.0)
├── engine.cc/.hh    загрузка образа через mmap, сессия SLEIGH, дизассемблер, анализ p-code,
│                    декомпиляция с разметкой подсветки, поиск ссылок и заглушек
└── jni_bridge.cc    JNI для com.ghidroid.engine.NativeBridge
app/src/main/java/com/ghidroid/
├── engine/          JNI, поток движка с большим стеком, установка спецификаций
├── loader/          ELF / PE / Mach-O / raw
├── analysis/        Program, импорт символов, анализ строк и функций, разбор вывода декомпилятора
├── data/            библиотека открытых файлов, настройки
└── ui/              Compose: главная, рабочее пространство (обзор, функции, код, строки, hex), настройки
```

Ghidra разработана АНБ США и распространяется по лицензии Apache 2.0; Ghidroid — независимый проект.
