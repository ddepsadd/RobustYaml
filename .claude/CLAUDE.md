# RobustYaml

Плагин для Rider, добавляющий поддержку YAML-прототипов Robust Toolbox (Space Station 14):
подсветка, автокомплит, навигация в `**/Prototypes/**/*.yml`.

## Стек

| Что | Версия | Примечание |
|---|---|---|
| Rider | 2026.2 (RD-2026.2) | |
| Gradle | 9.6.1 | 8.x не парсит Java 25 |
| JDK | 25.0.3 | задаётся блоком `jvmWrapper` в `build.gradle.kts` |
| Kotlin | 2.4.10 | обязан совпадать с метаданными платформы (2.4.0) |
| IntelliJ Platform Gradle Plugin | 2.18.1 | |

Сборка и запуск: `./gradlew :runIde` без `-x`. Пропуск `compileKotlin`/`compileDotNet` приводит
к тому, что правки не попадают в песочницу, и кажется, что код не работает.

## Классы

| Файл | Назначение |
|---|---|
| `RobustYamlAnnotator` | подсветка: вид прототипа, имена компонентов, пути к ресурсам |
| `RobustYamlColors` | `TextAttributesKey`, наследуются от ролей темы |
| `RobustYamlColorSettingsPage` | страница Settings → Editor → Color Scheme |
| `RobustYamlContext` | общие проверки PSI-контекста, используются аннотатором и автокомплитом |
| `RobustComponentNameIndex` | `FileBasedIndex` по `.cs`: имена компонентов (`[RegisterComponent]`, `[ComponentProtoName]`) |
| `RobustComponentIndex` | фасад запросов к индексу компонентов, кэш до изменения структуры VFS |
| `RobustPrototypeKindIndex` | `FileBasedIndex` по `.cs`: виды прототипов из `[Prototype]` |
| `RobustPrototypeIdIndex` | `FileBasedIndex` по `.yml`: id прототипов, значение — offset объявления |
| `RobustPrototypeIdsByKindIndex` | `FileBasedIndex` по `.yml`: вид → id этого вида |
| `RobustPrototypeIndex` | фасад для обоих индексов прототипов |
| `RobustDataFieldIndex` | `FileBasedIndex` по `.cs`: датафилды классов, базовые классы, алиасы |
| `RobustDataFields` | поля компонента/прототипа со склейкой по цепочке наследования |
| `RobustPrototypeSymbolContributor` | прототипы в Goto Symbol (`Ctrl+Alt+Shift+N`) |
| `RobustResources` | корни `Resources`, резолв путей вида `/Audio/...` и `Objects/....rsi` |
| `RobustSpritePreview` | кадр спрайта для тултипа: `meta.json`, обрезка, увеличение |
| `RobustSpriteDocumentationProvider` | ховер с картинкой спрайта и составом `.rsi` |
| `RobustSpriteLinkHandler` | клик по имени состояния кладёт его в буфер обмена |
| `RobustYamlReferenceContributor` | ссылки: компонент, вид прототипа, id прототипа, путь, состояние |
| `RobustValidation` | общая логика проверок для аннотатора и инспекций |
| `RobustUnknownFieldInspection` | weak warning на ключ, которого нет в датафилдах типа |
| `RobustUnknownPrototypeIdInspection` | ошибка на `parent`/`proto`/`prototype`/`entity` с неизвестным id |
| `RobustDuplicatePrototypeIdInspection` | ошибка на повторный id в пределах одного вида |
| `RobustInvalidEnumValueInspection` | ошибка на значение, которого нет среди членов enum |
| `RobustInvalidScalarValueInspection` | ошибка на значение, которое не парсится в число или bool |
| `RobustMissingRequiredFieldInspection` | ошибка на компонент без обязательного датафилда |
| `RobustRequiredFields` | обязательные поля с учётом наследования прототипов |
| `ChangeComponentNameFix` | quick fix с ближайшим именем компонента |
| `ChangeFieldNameFix` | quick fix с ближайшим именем датафилда |
| `ChangePrototypeIdFix` | quick fix с ближайшим id нужного вида |
| `ChangeEnumValueFix` | quick fix с ближайшим членом enum |
| `ChangeLocalizationIdFix` | quick fix с ближайшим ключом `.ftl` |
| `ChangeTypeTagFix` | quick fix с ближайшим типом для `!type:` |
| `AddRequiredFieldsFix` | quick fix: дописать недостающие обязательные ключи |
| `RobustComponentCompletionContributor` | автокомплит компонентов, видов прототипов, id и значений enum |
| `RobustColorProvider` | гуттер-пикер для hex-цветов Robust (`#RGB`…`#RRGGBBAA`) |
| `RobustXmlDoc` | `///`-блок над объявлением или атрибутом в `.cs` → HTML |
| `RobustTypeDocumentationProvider` | ховер с summary компонента, вида прототипа и датафилда |
| `RobustPrototypeRootsProvider` | делает каталоги прототипов частью проекта |
| `RobustPrototypeTreeRoot` | корень «Robust Prototypes» в Solution Explorer |
| `RobustYamlReferenceIndex` | `FileBasedIndex` по `.yml`: ссылки файла — компоненты, `parent`, id |
| `RobustYamlValueIndex` | `FileBasedIndex` по `.yml`: значения-кандидаты для Find Usages |
| `RobustFindUsagesHandlerFactory` | Alt+F7 по id прототипа и ключу локализации поверх индекса значений |
| `RobustRenameProcessor` | Shift+F6 по id прототипа: правит значение объявления и все ссылки |
| `RobustTargetElementEvaluator` | делает объявление id и ссылку целью для Alt+F7 и Shift+F6 |
| `RobustElementDescription` | имя и тип объявления id в UI поиска и рефакторинга |
| `RobustFileWritingAccess` | разрешает запись в прототипы и `Locale` вне content root |
| `RobustDeclarationTarget` | переход в `.cs` на строку объявления типа, а не в начало файла |
| `RobustLocaleIndex` | `FileBasedIndex` по `.ftl`: id сообщений, значение — offset объявления |
| `RobustLocaleUsageIndex` | `FileBasedIndex` по `.cs` и `.ftl`: литералы-ключи и ссылки `{ key }` |
| `RobustLocaleUsages` | ссылка на ключ в файле без парсера и обход таких ссылок |
| `RobustLocaleRenameProcessor` | Shift+F6 по ключу локализации: объявления всех культур, `.cs`, `.ftl`, YAML |
| `RobustLocaleRenameHandler` | Shift+F6 внутри `.ftl`, где PSI нет и цель берётся по offset каретки |
| `RobustLocaleNameValidator` | какое имя считается ключом в диалоге переименования |
| `RobustLocaleNavigation` | Ctrl+клик и Alt+F7 внутри `.ftl`: цель берётся по offset каретки |
| `RobustLocalization` | фасад к индексу локализации, цель перехода по ключу |
| `RobustUnknownLocalizationIdInspection` | предупреждение на `LocId`, которого нет в `.ftl` |
| `RobustLocalizationDocumentationProvider` | ховер по ключу: текст сообщения по всем культурам |
| `RobustPrototypeDocumentationProvider` | ховер по id прототипа: сущность, категория и общий случай |
| `RobustEntityLoc` | имя, описание и суффикс сущности так, как их увидит игрок |
| `RobustMarkup` | разметка Robust (`[color]`, `[bold]`, …) в HTML попапа |
| `RobustMigrations` | словарь переименований из `Resources/migration.yml` |
| `RobustProblemFiles` | фоновый расчёт проблемных файлов и каталогов, цвет волны |
| `RobustSolutionExplorerProblems` | подчёркивание проблемных узлов в Solution Explorer |
| `RobustLookupCharFilter` | пробел закрывает автокомплит, а не выбирает элемент — как в C# |
| `RobustSequenceIndentHandler` | `-` на свежей строке возвращается к отступу своего ключа |
| `RobustSequenceEnterHandler` | Enter под ключом-последовательностью сразу даёт `- ` на её уровне |
| `RobustCodeStyleModifier` | отступ последовательностей в прототипах — на уровне ключа |
| `RobustYamlSettings` / `RobustYamlConfigurable` | настройки: автопоиск + список каталогов |
| `protocol/.../RobustYamlModel.kt` | rd-модель: `componentFields(string) -> RobustDataField[]` |
| `RobustYamlHost.cs` | бэкенд: датафилды типа через symbol cache ReSharper |
| `RobustBackend` | project-сервис: rd-вызов с фронтенда и кэш ответов |

## Неочевидные решения

**`AdditionalLibraryRootsProvider` обязателен.** Без него демон подсветки не запускается на файлах
вне content root: `TextEditorHighlightingPassRegistrarImpl.instantiatePasses` не создаёт проходы
для файла без валидного `CodeInsightContext`. Проявляется при открытии SS14 солюшеном, когда
`Resources` не входит в проект. Не работают и потому были отброшены: `DefaultHighlightingSettingProvider`
с `FORCE_HIGHLIGHTING`, `HighlightingSettingsPerFile.setHighlightingSettingForRoot`, свой
`ProblemHighlightFilter` (фильтры комбинируются по И).

**`equals`/`hashCode` у `SyntheticLibrary` обязаны учитывать корни.** По ним платформа считает
дельту при `fireAdditionalLibraryChanged`; иначе изменение настроек не применится до перезапуска.

**Имена компонентов берутся из имён файлов, а не из PSI.** У C# на фронтенде Rider нет парсера
(`CSharpParserDefinition.createParser` → `CSharpDummyParser`), весь анализ .NET живёт в бэкенде
за rd-протоколом. Эвристика `SpriteComponent.cs` → `Sprite` промахивается на `[ComponentProtoName]`
(17 файлов из 2414 в ss14-wega).

**Корней ресурсов два и они вне content root.** `Resources` рядом с прототипами и
`RobustToolbox/Resources` (там `error.rsi`, `EngineFonts`). Путь с ведущим `/` считается от корня
ресурсов, без него — от `Resources/Textures` (405 из 11690 `sprite:` в ss14-wega абсолютные).
Индекс по `.yml` опрашивается в `ProjectScope.getAllScope`, а не `getContentScope`: прототипы
подключены как `SyntheticLibrary` и в content scope не попадают.

**Ключ `icon:` не является путём.** Из 112 значений 74 — id прототипов статус-иконок
(`JobIconPilot`). В списке путевых ключей только `sprite`, `rsiPath`, `path`, `sound`, `texturePath`:
на них 16273 значения и 1 реальный промах (`empty.rsi` в `vending_machines.yml`).

**Индекс id читает строку целиком, а не «до конца значения».** `id: BaseMappingSpawnAction # not
abstract` — 660 таких строк (423 уникальных id), и все они выпадали из индекса, потому что regex
требовал конец строки сразу за значением. Ещё три вида — `id : X` с пробелом перед двоеточием
(YAML это допускает) и один в кавычках. Из-за одного такого объявления 12 ссылок `parent:`
выглядели битыми; после фикса на ss14-wega не резолвится 0 ссылок из 18824.

**Для `state:` ошибки не подсвечиваются.** 6818 из 18479 объявлены в прототипах, где спрайт
унаследован через `parent:`, — статически такой `state` не проверить. Только ссылка и автокомплит.

**Аргументы атрибута нельзя матчить как `\(([^)]*)\)`.** В
`[DataField("graph", required:true, customTypeSerializer:typeof(PrototypeIdSerializer<...>))]`
вложенный `typeof(...)` рвёт захват, и атрибут не распознаётся вовсе. Матчится только
необязательный первый строковый литерал: `\[DataField(?:Attribute)?\s*(?:\(\s*"([^"]+)")?`.
Эта ошибка стоила 4% покрытия полей компонентов.

**Датафилды: имя из поля, плюс `[IdDataField]`/`[ParentDataField]`/`[AbstractDataField]`**
(они дают `id`, `parent`, `abstract` — без них покрытие полей прототипов 57%).
Поля собираются по цепочке наследования: класс → базовые по именам из `class X : Y`.

**Атрибут не обязан быть первым в списке.** `[ViewVariables(VVAccess.ReadWrite), DataField("fixtures")]`
и `[NetworkedComponent, RegisterComponent]` — обычное дело, поэтому все атрибутные регексы
начинаются с `[\[,]\s*`, а не с `\[`. Только `Fixtures.fixtures` — 568 ключей.

**Класс без датафилдов всё равно нужен в индексе.** `[RegisterComponent] public sealed partial class
PointLightComponent : SharedPointLightComponent` не содержит ни одного `DataField`, а
`BasePowerNetComponent : BaseNetConnectorComponent<IPowerNet>` — ни `DataField`, ни
`RegisterComponent`: любой отбор файлов по маркеру рвёт цепочку наследования на таком звене
(1581 ключ на одном `PointLight`). Индексируются все `.cs` с объявлением класса — 12374 файла,
14500 записей, полный прогон вне IDE 3 секунды. Запись `class:` пишется для каждого класса,
у которого есть базы или поля.

**Поле после вложенного класса принадлежит внешнему.** В `AccessOverriderComponent` между
`DenialSound` и `AccessLevels` объявлен вложенный `WriteToTargetAccessReaderIdMessage`, поэтому
«последний класс перед offset» врёт. Владелец ищется по диапазонам фигурных скобок
(`classScopes` — посимвольный автомат со строками, символьными литералами, `@""` и комментариями),
самый узкий диапазон, содержащий offset. Без этого — 46 потерянных ключей и мусорные поля
в автокомплите вложенных типов.

**`class` нельзя искать без модификаторов.** `\bclass\s+(\w+)` попадает в текст `/// Use Cost
property on derived class instead.` — дальше все датафилды файла приписываются классу `instead`
(так терялся весь `ListingPrototype`). Объявлению обязан предшествовать хотя бы один модификатор,
и ловить надо `class|record|struct`.

**`class X : Y;` (C# 12) обрывает список баз.** У `public sealed partial class
EmitSoundOnLandComponent : BaseEmitSoundComponent;` тела нет, поэтому `[^\{]+` съедал базу вместе
со следующими объявлениями — и класс, шедший следом, вообще не находился. База читается до `{`,
`;` или конца строки.

**`[IncludeDataField]` — это тоже база.** `[IncludeDataField] public SpriteSpecifier.Rsi Icon`
подмешивает поля `Rsi` (`sprite`, `state`) прямо в ключи компонента: 878 ключей на одном `Icon`.
Тип поля кладётся в список базовых классов по последнему сегменту имени.

Покрытие на ss14-wega (62251 ключ компонентов, 129387 ключей прототипов), замерено прогоном
самого индекса: **99.93%** для компонентов (45 непокрытых) и **99.999%** для прототипов
(1 непокрытый). Каждый из 46 проверен вручную — этих ключей в C# нет: у `PointingArrow` есть
`offset`, `rogue`, `startPosition`, `endTime`, но не `duration`/`step`/`speed`; у `PointLight`
есть `lightMask` и `autoRot`, но не `mask`. Это мёртвый текст в самих прототипах, и его же
можно подсвечивать как ошибку.

**Инспекция полей обязана кэшировать.** Аннотатор и автокомплит спрашивают поля раз на файл или
раз на вызов, а `LocalInspectionTool` — на каждый ключ, то есть тысячи запросов к индексу на файл
при каждом нажатии клавиши. `RobustDataFields` держит `ConcurrentHashMap` в
`CachedValuesManager.getCachedValue(project)` с зависимостью от `PsiModificationTracker.MODIFICATION_COUNT`.
Проверяются только прямые ключи объявления (`declaration.mapping === keyValue.parentMapping`):
ключи вложенных структур принадлежат своим классам, и знать их индекс не обязан.

**Инспекции не работают вне content root — валидация живёт в аннотаторе.**
`HighlightingSettingsPerFile.shouldInspect` содержит
`if (ProjectScope.getLibrariesScope(project).contains(file) && !fileIndex.isInContent(file)) return false`,
а прототипы подключены как `SyntheticLibrary`. Поэтому `LocalInspectionTool` на них не запускается
вообще: аннотатор красит `type: Sprit`, а инспекция на соседний `parent:` молчит. Проверки вынесены
в `RobustValidation` и вызываются из аннотатора, когда
`HighlightingLevelManager.shouldInspect(element)` вернул `false`; инспекции остаются для случая,
когда прототипы всё же в content (и для Inspect Code), дублей не будет.

**Подсветку файла в дереве одним `problemFileHighlightFilter` не включить.**
`GeneralHighlightingPass` сообщает в `WolfTheProblemSolver` только после проверки
`PsiManager.isInProject(file)`, которая сводится к `FileIndexFacade.isInContent`. То есть
для файлов вне content платформа не отдаёт проблемы в Wolf в принципе, независимо от фильтра.
Чтобы дерево красилось, придётся звать `WolfTheProblemSolver.weHaveGotProblems` самим — по данным
индексов, без открытия файлов.

**Ключ `id` валидировать нельзя, а `parent`/`proto`/`prototype`/`entity` — можно.** Вложенных
`id:` 7774, и 3% из них — имена анимаций и состояний (`radiating`, `blinking`, `fade_out`),
а не прототипы. По остальным четырём ключам на ss14-wega 21387 ссылок и ровно одно срабатывание —
настоящая ошибка контента (`MachineBoard.prototype: WeaponEnergyTurretStation`). Не считаются
ссылками: `null` (14 штук), YAML-теги `!type:BoardNodeEntity { ... }` (3), булевы и числа.

**Дубли id считаются в пределах вида, и по файлу их не поймать.** Один и тот же id у разных видов
легален (`Syndicate` — 4 раза: антаг, департамент и прочее), поэтому индекс хранит значение как
`kind@offset` со списком через `;`: два объявления в одном файле иначе схлопнулись бы в одно
значение. На ss14-wega 26984 id, 204 вида, дублей в пределах вида 0.

**BOM ломает построчные regex.** 284 файла прототипов начинаются с `EF BB BF`, из-за чего первая
строка `- type: constructionGraph` не матчилась и 249 id уезжали с пустым видом. В IDE
`FileContent.contentAsText` BOM снимает, но в регексе он всё равно допущен явно.

**Ключ картинки в тултипе — не произвольная строка, а `URL.toExternalForm()`.**
`DocumentationEditorPane` оборачивает `DocumentationImageResolver` в `JBHtmlPaneImageResolver`,
и тот спрашивает мапу внешней формой URL, построенного Swing из атрибута `src`
(`JBHtmlPaneImageResolver.getImage`). Со строкой-меткой (`src='robust-sprite'`) URL не строится,
резолвер не зовётся и поповер вечно крутит спиннер. `isUrlSafe` режет `vbscript`, `smb`,
`javascript`; `file` на Linux проходит. Формы `file:///x` и `file:/x` дают одинаковый
`toExternalForm`, поэтому ключ берётся как `png.toNioPath().toUri().toURL().toExternalForm()`
и подставляется и в `src`, и в мапу.

**У `<img>` обязаны быть `width`/`height`.** `ImageView.updateImageSize` берёт размер из атрибутов,
а без них спрашивает `image.getWidth(imageObserver)`; для не догруженной картинки это `-1`,
и подставляется `DEFAULT_WIDTH = 38` (`bipush 38` в байткоде). Пересчёта после догрузки в уже
показанном поповере не происходит, поэтому спрайт вечно рисуется 38×38 независимо от увеличения.

**Ховер по пути `.rsi` показывает состав, а не случайный кадр.** Раньше без соседнего `state:`
бралась `icon.png`, а при её отсутствии — первый PNG по порядку детей, то есть по сути случайный
спрайт (`cleave` у `abilities_heretic.rsi`). Теперь для пути на каталог `.rsi` рисуется сетка
миниатюр состояний из `meta.json`; когда рядом есть `state:`, поведение прежнее — один кадр.
Имена состояний берутся регексом `"name"\s*:\s*"([^"]+)"` от подстроки после `"states"`: в 400
проверенных `meta.json` ключ `name` вне `states` не встречается ни разу. Каждое состояние — это
отдельный `<state>.png`, поэтому у каждой миниатюры свой файловый URL и трюки с фрагментами не
нужны. Сетка сделана таблицей: `JBHtmlPane` — это Swing HTML, flex в нём не работает. Картинки и
подписи лежат в разных `<tr>`: пока имя было внутри той же ячейки, длинное состояние
(`gazer_beam_charge`) переносилось на вторую строку и `valign='bottom'` сдвигал соседние картинки
по вертикали. Подпись помечена `nowrap`, поэтому ширину колонки диктует самое длинное имя —
из-за этого колонок три, а не четыре: на `equipped-INNERCLOTHING` четвёртая уезжала бы в
горизонтальный скролл. Обрезать имена многоточием нельзя: `equipped-INNERCLOTHING` и
`equipped-INNERCLOTHING-monkey` стали бы неразличимы. Чтение и масштабирование десятков PNG
вынесено в `asyncDocumentation`.

**Отрисованные кадры кэшируются, иначе поповер каждый раз собирается заново.** На
`abilities_heretic.rsi` это 45 чтений PNG с обрезкой и масштабированием на каждое наведение —
видно как перезагрузку картинок. Кэш — `CollectionFactory.createConcurrentSoftValueMap`, ключ
`url@timeStamp@target`: `timeStamp` инвалидирует запись при изменении файла, `target` разделяет
крупное превью (256) и миниатюру сетки (64), мягкие ссылки отдают память под давлением GC.

**Оформление попапов — платформенное, а не самодельные `padding`.** Все четыре таргета собраны из
`DocumentationMarkup`: `DEFINITION_START/END` (шапка с сигнатурой), `CONTENT_START/END` (тело),
`SECTIONS_START` + `SECTION_HEADER_START`/`SECTION_SEPARATOR`/`SECTION_END` (пары ключ-значение),
`GRAYED_START/END` (приглушённое). Отступы и типографика тогда совпадают с C#-документацией.
Цвета берутся из текущей схемы редактора через
`HtmlSyntaxInfoUtil.appendStyledSpan(sb, TextAttributesKey, text, 1.0f)`, причём теми же ключами
`RobustYamlColors`, что и подсветка в редакторе, — имя компонента и путь к ресурсу в попапе того же
цвета, что в YAML. Экранировать текст нужно самому: `appendStyledSpan` этого не делает.

**Кликабельные имена состояний пробовали и откатили.** Имена оборачивались в
`<a href='robust-state:<urlencoded>'>`, а `DocumentationLinkHandler` (EP
`com.intellij.platform.backend.documentation.linkHandler`) по клику клал имя в
`CopyPasteManager.copyTextToClipboard`. Не прижилось: в `LinkResolveResult` есть только
`resolvedTarget`, варианта «обработал, не перерисовывай» нет, поэтому каждый клик перестраивал
поповер — он рос на пару пикселей и переставал закрываться по уходу мыши. Вернуть из handler'а
`null` тоже нельзя: `LinksKt.handleLink` отдаёт необработанную ссылку в `browseAbsolute`, а
`BrowserUtil.isAbsoluteURL` — это `^[\w+.\-]{2,}:`, под который `robust-state:` подходит, и
платформа попыталась бы открыть её внешним приложением. Имена оставлены обычным текстом.

**`computeDocumentation` зовётся в отменяемом read action.** Стек (снимался через
`Throwable().stackTrace`): `ImplKt$computeDocumentation$2 <- InternalReadAction.insideReadAction <-
InternalReadAction.tryReadCancellable <- ApplicationImpl.tryRunReadAction`. Любой запрос write lock
(демон, индексация, ввод) отменяет такой read action, и он стартует заново, поэтому сборка обязана
быть дешёвой: готовый `DocumentationResult.Documentation` мемоизируется в статическом
`createConcurrentSoftValueMap` по ключу `url@timeStamp(meta.json)@цвет`, и повторный вызов
становится O(1). Мигание картинок, из-за которого всё это копалось, на деле возникало только при
клике по ссылке (перестроение попапа) и ушло вместе с отказом от кликабельных имён;
перетаскивание попапа тоже оказалось ложной тревогой — `EditorMouseHoverPopupManager.createHint`
не зовёт `setMovable`, и hover-попап не движим ни для нас, ни для C#. Диагностику по вердикту
`HoverPopupContext.compareTo` (`SAME`/`SIMILAR`/`DIFFERENT`) см. выше: наши таргеты приходили с
одним и тем же `PsiElement`, то есть `SAME`.

**Документация строится синхронно, если кадры уже в кэше.** `asyncDocumentation` показывает
промежуточное состояние, и на каждое наведение картинки мигали заново. Теперь сначала пробуется
сборка только из кэша (`cachedThumbnail`, при первом же промахе — отказ), и лишь при промахе
уходит в асинхронную ветку. Тип возврата у лямбды `asyncDocumentation` — не `DocumentationResult`,
а `DocumentationResult.Documentation`.
Само собой закрытие поповеров плагином не управляется: это хинт платформы, и на тайловых WM он
может висеть до клика.

**PNG внутри `.rsi` — лента кадров.** Первый кадр вырезается по `size` из `meta.json`
(234 из 300 проверенных PNG крупнее кадра), увеличивается nearest-neighbor, иначе пиксель-арт мылится.

**Цвета наследуются от ролей платформы** (`INSTANCE_FIELD`, `METADATA`, `STRING`), как это делает
стоковый YAML. Галочка «Inherit attributes from» стирает пользовательский цвет — это поведение
платформы (`SchemeTextAttributesDescription.apply` пишет `INHERITED_ATTRS_MARKER` вместо значений),
из плагина не чинится.

**Цвет пишется через `updateText`, а не в `textRange`.** `scalar.textRange` у `YAMLQuotedTextImpl`
включает кавычки, и запись `#FF0000` по этому диапазону даёт `color: #FF0000` — в YAML это
комментарий, значение становится пустым. `YAMLScalarImpl.updateText` идёт через
`ElementManipulators.handleContentChange`, а `YAMLScalarElementManipulator.getRangeInElement`
построен по `getContentRanges()`, то есть меняется только содержимое внутри кавычек. В ss14-wega
3656 hex-значений в двойных кавычках, 63 в одинарных и ровно 2 без кавычек — оба в
`Recipes/Construction/Graphs/utilities/lighting.yml`, и оба в игре не работают.

**XML-doc не индексируется, а читается при ховере.** Файл уже известен из
`findComponentFile`/`findKindFile`, а поповер открывается редко, поэтому один `VfsUtilCore.loadText`
дешевле лишнего индекса. Блок собирается снизу вверх от объявления класса: строки `///` копятся,
пустые строки, атрибуты (`[RegisterComponent]`, многострочные `[Access(...)]`) и директивы
препроцессора пропускаются, любая другая строка обрывает поиск — иначе к классу приклеится
документация предыдущего объявления. Прогон самого кода по ss14-wega: summary найден у 1562 из
2414 файлов `*Component.cs` (64%). Из 852 остальных 355 содержат `<summary>` где-то в файле, но
проверенные вручную (`DamageVisualsComponent`, `MapTextComponent`) документируют поля, а не класс.
`<inheritdoc/>` (2226 штук) отдельной ветки не требует: тег стоит вместо `<summary>`, поэтому
summary просто нет и включается тот же подъём к базовому классу.

**Подъём к базе окупается плохо, а обход партиалов обязателен.** Прогон кода индекса и парсера
по ss14-wega: 1555 summary у самого класса, 27 у базы, итого 1582 из 2414 (65%). Прирост дают
почти одни `BaseAccentComponent` — недокументированные компоненты наследуют напрямую `Component`,
а он в стоп-листе, иначе одно и то же описание базового класса движка висело бы на половине
компонентов. Одновременно убран fallback «взять первый класс файла»: он давал 7 попаданий, и все
семь — документация случайного соседнего типа. Файлы класса перебираются все, а не
`getContainingFiles(...).firstOrNull()`: 1013 имён классов объявлены больше чем в одном файле
(у `Clyde` 45 партиалов, у `AtmosphereSystem` 19), документация лежит в одном из них, а порядок
файлов индекс не гарантирует — с ранним выходом поповер появлялся бы через раз и по-разному
после каждой переиндексации.

**Документация датафилда ищется в два шага: индекс, потом текст.** Класс-владелец находится
обходом цепочки наследования по значениям `class:` (`parseFields`), без единого чтения файла;
только потом файлы этого класса открываются и `RobustDataFieldIndex.findField` даёт offset
атрибута, а `RobustXmlDoc.summaryAt` — блок над ним. `findField` обязан сверять владельца через
`ownerAt`, а не довольствоваться тем, что файл взят по нужному классу: в файле обычно несколько
типов (соседние, партиалы, вложенные — как `WriteToTargetAccessReaderIdMessage` внутри
`AccessOverriderComponent`), и одноимённый `[DataField]` вложенного типа подсунул бы чужое
описание молча. Прогон по ss14-wega: 10823 поля в индексе, `findField` находит обратно все 10823,
summary есть у 6713 (62%), полный проход 6.8 секунды.

**Таргет документации обязан переживать write action.** `computeDocumentation` считается
асинхронно, и между ним и `documentationTargets` пользователь успевает печатать. `VirtualFile` и
строки это переживают, поэтому `Pointer.hardPointer(this)` здесь корректен; таргет с `PsiElement`
внутри требовал бы `SmartPsiElementPointer`, иначе элемент станет невалидным и утащит за собой всё
PSI-дерево.

**`colorProvider` объявлен без атрибута `language`.** EP зовётся из `ColorLineMarkerProvider`
(`codeInsight.lineMarkerProvider order="last" language=""`) через `computeSafeIfAny`, то есть на
элементах любого языка и до первого ненулевого ответа — отбор по `element is YAMLScalar` наш.
`setColorTo` платформа сама оборачивает в `WriteAction.run` и сама проверяет `isWritable`.
Формы hex у Robust ровно четыре (`Color.TryFromHex`): длина строки 4, 5, 7, 9, альфа последняя,
короткая форма дублирует нибл. Регистр и наличие альфы сохраняются от исходного значения:
в контенте 2185 значений строчными против 943 прописными.

**Каркас rd-бэкенда: что оказалось не так, как в шаблоне.** `ktOutput` в `protocol/build.gradle.kts`
задваивал путь (`.../plugins/com/jetbrains/rider/plugins/robustyaml`), потому что `RiderPluginId`
уже содержит весь пакет — базовый путь должен быть просто `src/rider/main/kotlin/`.
`[SolutionComponent(Instantiation.DemandAnyThreadSafe)]` для протокольного хоста не годится:
`Demand` означает создание по требованию, а хост никто не запрашивает — endpoint не
зарегистрируется. Нужен `ContainerAsyncAnyThreadSafe`, он создаётся при композиции контейнера
солюшена. Хост обязан быть под `#if RIDER`: `JetBrains.RdBackend`/`Rider.Model` есть только в
`ReSharperPlugin.RobustYaml.Rider.csproj`, а ReSharper-вариант собирается тем же исходником.
Фронтенду нужен `bundledModule("intellij.rider.rdclient.dotnet")` — `project.solution`
(`SolutionHostExtensionsKt`) лежит именно там и без этой зависимости не резолвится.

**Аргумент атрибута нельзя вычислять как константу.** `CSharpAttributeInstance.PositionParameters()`
падает с `Cannot get runtime features with Universal`
(`ModuleReferenceResolveContextExtensions.GetRuntimeFeatures` → `IsCSharpSimplePredefined`):
вычисление константы требует конкретного модуля, а перегрузки `GetSymbolScope` с явным
`IModuleReferenceResolveContext` нет — перезапрос типа через `GetSymbolScope(candidate.Module,
true, true)` ассерт не снимает. Имя берётся из синтаксиса: `member.GetDeclarations()` →
`IAttributesOwnerDeclaration.Attributes` → `Arguments[0].Value as ICSharpLiteralExpression` →
`Literal.GetText().Trim('"')`. Резолв не нужен вовсе.

**Имя атрибута в синтаксисе — без суффикса `Attribute`.** `GetAttributeShortName()` из метаданных
даёт `DataFieldAttribute`, а `IAttribute.Name.ShortName` для `[DataField("spawned")]` — `DataField`.
Из-за несовпадения бэкенд молча возвращал 0 полей. Сравнение идёт по имени со снятым суффиксом.

**Песочница живёт в `.intellijPlatform/sandbox/`.** Каталог `build/idea-sandbox/` остался от старой
версии Gradle-плагина и не обновляется — логи и подложенная dll бэкенда там протухшие, диагностика
по ним врёт. Актуальный `idea.log` и `plugins/ReSharperPlugin.RobustYaml/dotnet/*.dll` — в первом.

Проверенные компиляцией сигнатуры 262: `solution.GetProtocolSolution()` из
`JetBrains.ReSharper.Feature.Services.Protocol`, `ReadLockCookie` из
`JetBrains.ReSharper.Resources.Shell`, `services.Symbols.GetSymbolScope(LibrarySymbolScope.FULL,
caseSensitive: true).GetElementsByShortName(...)`, `member.GetAttributeInstances(false)` +
`GetAttributeShortName()` + `PositionParameters()[0].ConstantValue.StringValue`,
`member.GetXMLDoc(true)?.Value`. Endpoint ставится через `SetSync`, а не `Set` (последний помечен
internal/obsolete). Клиент — `IRdCall.sync(request, RpcTimeouts.default)`, звать только из
фонового потока.

**Бэкенд принимает имя класса, а не имя компонента.** Фронтенд уже резолвит `Sprite` →
`SpriteComponent` и `entity` → `EntityPrototype` своими индексами, поэтому склейка `+ "Component"`
на стороне хоста была лишней и заодно отрезала прототипы: одним `typeFields(className)` работают
оба случая.

**`GetAllSuperTypeElements` для сбора датафилдов непригоден.** Внутри `TypeElementUtil` результат
копится в `HashSet<ITypeElement>`, поэтому предки приходят в порядке хэшей: при одноимённом
`[DataField]` в наследнике и базе победитель случайный и меняется между запусками. Хуже другое —
наружу выходят голые `ITypeElement[]`, без `ISubstitution`, и поле `T NetType` из
`BaseNetConnectorComponent<IPowerNet>` так и уедет во фронтенд буквой `T`. Поэтому обход свой,
в ширину, очередью пар `(ITypeElement, ISubstitution)`: подстановка копится как
`outer.Apply(super.GetSubstitution())` (`SubstitutionImpl.Apply` прогоняет значения аргумента
через себя), стартовое значение — `EmptySubstitution.INSTANCE`, чей `Apply` возвращает аргумент
как есть. Первое встреченное имя выигрывает, то есть поле берётся у ближайшего класса.

**Короткое имя класса неоднозначно — `FirstOrDefault` по нему врёт.** `EntityPrototype` объявлен
в ss14-wega четырежды: настоящий прототип в `Robust.Shared/Prototypes/`, вложенный
`SpriteSpecifier.EntityPrototype` в `Robust.Shared/Utility/SpriteSpecifier.cs` (три члена, ни
одного `[DataField]`) и два в тестах анализаторов. Порядок `GetElementsByShortName` не определён,
и бэкенд молча отдавал 0 полей, взяв вложенный класс. Кандидаты сортируются
(`(it as ITypeMember)?.GetContainingType() == null` — верхнеуровневые первыми: сам `ITypeElement`
интерфейс `ITypeMember` не наследует, им становятся только вложенные) и перебираются до первого
с непустым результатом.

**Вид прототипа для валидации берётся из типа поля и из сериализатора.** В контенте 909 полей
`ProtoId<T>`/`EntProtoId` и 235 `customTypeSerializer: typeof(...)`, тогда как enum-датафилдов
единицы — поэтому первая проверка значений строится вокруг id прототипов, а не enum. Порядок
разбора: сначала `customTypeSerializer` (у `[DataField("graph", customTypeSerializer:
typeof(PrototypeIdSerializer<ConstructionGraphPrototype>))] public string Graph` тип поля — просто
`string`, вид записан только в сериализаторе), иначе тип поля после раскрытия коллекций
(`List<ProtoId<X>>` и `ProtoId<X>[]` встречаются наравне с голым `ProtoId<X>`). Сериализатор
опознаётся по суффиксу `Serializer`, а не по точному имени: их семь штук
(`PrototypeIdSerializer` 149, `AbstractPrototypeIdArraySerializer` 33, `PrototypeIdDictionarySerializer`
24, `PrototypeIdHashSetSerializer` 20, `PrototypeIdListSerializer` 6, `PrototypeIdValueDictionarySerializer`
2, `AbstractPrototypeIdSerializer` 1), и точное сравнение потеряло бы 86 полей из 235.
`EntProtoId` в обеих формах (с параметром и без) — всегда `entity`: параметр там ограничивает
компонент, а не вид. Сам вид читается из `[Prototype("entity")]` на классе прототипа; вывод из
имени класса (`XPrototype` → `x`) применяется только когда атрибут есть, но без литерала —
класс без атрибута прототипом не считается.

**Первый аргумент атрибута — не обязательно тег.** `[DataField(required: true)]` даёт первым
аргументом `required: true`, и «взять первый литерал» называет поле `true`. На ss14-wega таких
объявлений 883 (664 из них `required:`) против 2454 со строковым первым аргументом. Нужны две
проверки: `ICSharpArgument.NameIdentifier == null` (аргумент позиционный) и кавычки по краям
текста литерала. `Trim('"')` тоже неверен — снимать надо ровно одну пару. Регексная версия
(`\(\s*"`) этой ошибки не делает, поэтому промах выглядел как «у части полей бэкенд не знает
типа»: имя `true` просто не совпадало с ключом, тип не печатался, а описание приходило из индекса
и поповер оставался на месте. Спуск по пути на том же промахе обрывается целиком и отдаёт пустой
список — тихая деградация стала явной только там.

**Без `CompilationContextCookie` тип поля не строится.** `CSharpField.get_Type()` в неявном
универсальном контексте роняет NRE внутри `CSharpTypeFactory.CreateType`, а
`GetTypeElementByCLRName` пишет в лог `Implicit UniversalModuleReferenceContext detected` через
`Logger.Fail` — те самые сотни WARN в `idea.log` были наши. Проявлялось как «вложенные ключи
молчат»: на прямых ключах часть типов всё же строилась, а спуск по пути требует `get_Type()` для
каждого поля вложенного типа. Контекст берётся у модуля найденного типа:
`mySolution.GetComponent<PsiModuleResolveContextManager>().GetOrCreateModuleResolveContext(
module.ContainingProjectModule, module, module.TargetFrameworkId)` →
`CompilationContextCookie.GetOrCreate(...)`. `IModuleReferenceResolveContext` лежит в
`JetBrains.Metadata.Reader.API`, а не в `...Psi.Modules`.

**`GetExplicitUniversalContextIfNotSet` ничего не чинит.** Он кладёт в thread-local тот же
`UniversalModuleReferenceContext.Instance`, меняя только `IsContextExplicit()`: ассерт ругается не
на universal, а на то, что контекст никто не выбирал. Поэтому им закрыт лишь поиск по короткому
имени (модуль до поиска неизвестен, курица-яйцо) и fallback для модулей без
`ContainingProjectModule` — типы там по-прежнему могут не строиться, зато лог не засоряется.

**Вложенные ключи резолвит бэкенд, а не фронтенд.** `sprite:` внутри `data:` у `VisualOrgan`
принадлежит не компоненту, а `PrototypeLayerData`, поэтому ховер молчал на всём, что глубже
прямого ключа объявления, — и выглядело это как «работает через раз». Запрос стал парой
`(className, path)`: фронтенд поднимается от ключа до `declaration.mapping`, собирая имена ключей
(нестрого через `YAMLSequence` — элемент списка своего сегмента не даёт), а бэкенд идёт по пути,
раскрывая тип каждого сегмента. У вложенных ключей есть тип, но нет XML-doc: summary всё ещё
достаётся регексным `findField` от класса-владельца, а он вложенных структур не знает.

**Раскрытие словаря обязано идти до раскрытия `IEnumerable`.** `Dictionary<K,V>` — это
`IEnumerable<KeyValuePair<K,V>>`, поэтому `GetElementTypesForGenericEnumerable` даёт
`KeyValuePair<Sex, PrototypeLayerData>`, у которого нет ни одного `[DataField]`, и вложенные ключи
не находятся вовсе. В YAML имя ключа — это `K`, а вложенные ключи принадлежат `V`, значит нужен
`GetElementTypesForGenericType(type, predefined.GenericIDictionary, 1)`. Раскрытие останавливается
на `string`: он тоже `IEnumerable<char>`.

`GetElementTypesForGenericType` помечен `[CanBeNull]` и отдаёт `null` штатно — в частности когда
тип не наследник запрошенного generic-типа (`if (!typeElement.IsDescendantOf(genericTypeElement))
return null;`). То есть на любом не-словаре без проверки на null получается NRE. Соседний
`GetElementTypesForGenericEnumerable` этим не страдает только потому, что внутри делает
`?? EmptyList<IType>.InstanceList`.

**`[IncludeDataField]` на бэкенде — ребро графа, а не поле.** `[IncludeDataField] public
SpriteSpecifier.Rsi Icon` ключа `icon` не даёт, он подмешивает `sprite`/`state` из `Rsi`, поэтому
тип поля уезжает в ту же очередь обхода, что и базы, а сам член в результат не попадает.

**Кэш бэкенда держит `Deferred`, а не готовые списки.** Пока вызов был блокирующим (`sync`),
`computeIfAbsent` не годился: `ConcurrentHashMap` держит лок бина всё время работы лямбды, а внутри
сидело ожидание до `RpcTimeouts.default` — любой другой ключ с тем же хэшем бина ждал вместе с ним,
и `clear()` из VFS-листенера тоже; плюс JDK запрещает мутировать мапу изнутри лямбды
(`IllegalStateException: Recursive update`). С `scope.async { }` лямбда возвращается мгновенно —
под локом создаётся только `Deferred`, ожидание вынесено в `await()` снаружи, а `cache.remove(key)`
при неудаче исполняется уже в корутине. Поэтому пара мап с `locks` больше не нужна: роль защиты от
дублей играет сам `Deferred`. Неудачный ответ по-прежнему не кэшируется, иначе один таймаут при
холодном старте бэкенда навсегда пометил бы тип как «полей нет».

**Отсутствие summary больше не означает отсутствие поповера.** У 852 компонентов из 2414
над классом нет ни одной строки `///` (`SpriteComponent` — как раз такой), база `Component`
в стоп-листе, и раньше ховер по `type: Sprite` молчал вовсе. Теперь при пустом summary печатается
`class <Owner> : <Base>`, а имя класса берётся через `RobustXmlDoc.extract(source, candidates)`,
а не `candidates.first()`: candidates — это гипотезы, построенные из имени в YAML
(`X`, `SharedX`, `XComponent`), и у компонентов с `[ComponentProtoName]` первая из них
не существует.

**«Полей нет» и «бэкенд не смог» — разные ответы, иначе тип спрашивается вечно.** Пока ответом был
просто список, пустота означала и то и другое, а раз пустое не кэшируется, `LatheRecipePrototype/
materials` (`Dictionary<ProtoId<MaterialPrototype>, int>` — значение `int`, полей у него правда нет)
запрашивался заново на каждом проходе демона: в логе четыре одинаковых запроса за 3 мс. Ответ стал
структурой `RobustFieldsReply { resolved, fields }`: `resolved=false` — тип не найден среди
кандидатов или у поля не построился тип (`???`), такое не кэшируется; `resolved=true` с пустым
списком — стабильный факт, кэшируется. Обрыв спуска по пути (нет такого сегмента) считается
`resolved=true`, потому что непостроенные типы отлавливаются отдельной проверкой на каждом шаге.

**Ответ с непостроенными типами кэшировать нельзя — он выглядит успешным.** Один и тот же
`LatheRecipePrototype` в соседних запусках дал `[parent=latheRecipe, result=entity, materials=material,
categories=latheCategory]` и `[parent=latheRecipe]`: 12 полей пришло в обоих случаях, но во втором
типы не построились, и в ховере вместо `EntProtoId?` стояло `???`, а секция `Prototype` исчезала.
Пустой ответ мы не кэшируем, а такой — кэшировали, и `???` залипал до правки `.cs`. Признак взят
из декомпиляции: `UnknownType.GetPresentableName` возвращает буквально `"???"`, поэтому хост
проверяет presentable name (подстрокой — бывает `List<???>`) и на первом же неизвестном типе
отдаёт пустой список, чтобы фронтенд переспросил позже.

**Путь считается от произвольного элемента, а не от маппинга.** У пустого элемента списка (`- `
под `layers:`) своего маппинга ещё нет, поэтому `getParentOfType(position, YAMLMapping)` находил
маппинг компонента, путь получался пустым и автокомплит предлагал поля `Sprite` вместо полей
`PrototypeLayerData`. `pathAt` поднимается по родителям и добавляет сегмент только когда пройденный
узел был **значением** ключа (`current.value === child`): так набираемый ключ в путь не попадает,
а `YAMLSequence` его прозрачно пропускает. `taken` (уже занятые ключи) берётся только если путь
самого маппинга совпал с путём позиции — иначе в элементе списка отфильтровались бы ключи внешнего
компонента.

**Словарь во фронтенде виден по флагу в модели, а не по догадке.** На уровне `reactants:` ключи —
это id реагентов, а поля `ReactantInfo` начинаются уровнем ниже, внутри `Oil:`. Фронтенд отличить
это сам не может (бэкенд съедает словарный сегмент и на оба уровня отвечает полями значения),
поэтому в `RobustDataField` добавлено поле `dictionary`: если владелец последнего сегмента —
словарь, автокомплит полей молчит, а id предлагает `keyKindAt` по `keyPrototypeKind`. Проверка
на `Dictionary<string, X>` при этом не нужна: вид там просто `null`, и списка id не будет.

**Сегмент пути после словаря — это ключ YAML, а не поле.** У `amount:` внутри `Oil:` путь
получается `reactants/Oil`, и бэкенд честно искал в `ReactantInfo` поле с именем `Oil` — отдавал
0 полей на всём, что глубже словаря. Фронтенд про словари не знает (он просто собирает имена
ключей), поэтому сегмент съедается на стороне хоста: после поля, чей тип — `IDictionary<,>`,
следующий сегмент пропускается. Элемент списка своего сегмента не даёт изначально, поэтому
`layers` такой пары не требует.

**Автокомплит полей до бэкенда не доходил и всегда предлагал ключи корня.** Внутри `Oil:` он
показывал `conserveEnergy`, `maxTemp`, `name` — поля самого `ReactionPrototype`, потому что брал
`RobustDataFields.forPrototype` от объявления, не глядя на глубину: регексный индекс знает только
прямые ключи. Теперь путь считается от объявления до маппинга под курсором
(`pathToMapping` — тот же обход, что и `pathTo`, но от маппинга, потому что при автокомплите
`YAMLKeyValue` ещё не существует), и при непустом пути список приходит из `cachedFields(root, path)`.
Индексная ветка остаётся для прямых ключей: она работает без солюшена и без прогретого бэкенда.

**У `[DataRecord]` датафилды — сами члены, атрибута на них нет.** `[DataRecord] public partial record
struct ReactantInfo(FixedPoint2 Amount, bool Catalyst)` не содержит ни одного `[DataField]`, поэтому
`Collect` отдавал по такому типу 0 полей: `reactants:` в каждой реакции знал вид ключа
(`reagent`), но внутри `Oil:` не было ни автокомплита `amount`/`catalyst`, ни описаний. Таких
объявлений 30 в 15 файлах. Требование атрибута снимается только для типа с `[DataRecord]`, но
взамен нужен фильтр мусора, который раньше давал сам атрибут: `!IsStatic` убирает константы и
`static readonly` (у `[DataRecord]`-классов вроде `GridSpawnGroup` они есть), а
`GetAccessRights() == PUBLIC` — приватные backing fields позиционных параметров
(`<Amount>k__BackingField` иначе приехал бы во фронтенд ключом `<amount>k__BackingField`) и
сгенерированное `protected virtual Type EqualityContract` у record-классов. Методы (`Deconstruct`,
`Equals`, `PrintMembers`, операторы) фильтра не требуют: `IMethod` наследует `IFunction`, но не
`ITypeOwner`, а тип возврата лежит в `IFunction.ReturnType`. Атрибут ищется тем же способом, что и
`[Prototype]` — по всем декларациям плюс метаданные: `record struct` частично генерируется
компилятором.

**`EntProtoId?` — это `Nullable<EntProtoId>`, а не аннотация.** `ProtoId<T>` и `EntProtoId` —
`readonly record struct`, поэтому вопросительный знак у них порождает настоящую обёртку, и
`ShortName` у типа поля — `Nullable`. Сравнение по имени промахивалось молча: у
`[DataField] public EntProtoId? Result` в поповере есть и тип, и summary, но нет секции `Prototype`,
а автокомплит и валидация по виду не работают. В контенте таких объявлений 326 (`EntProtoId?` —
129, остальное — `ProtoId<X>?`). Снимается `TypesUtil.Unlift()`: у nullable value type отдаёт
underlying, у остальных — сам тип, так что вызов безопасен без предварительной проверки. У
`HashSet<ProtoId<X>>?` обёртки нет — там `?` относится к ссылочному типу, поэтому `categories`
работал и до фикса.

**Стоковый автокомплит YAML глушится `stopHere`, и для этого нужен `order="first"`.** Ключи, уже
встречающиеся в документе, предлагает `org.jetbrains.yaml.completion.YamlStructuralKeysCompletionContributor`
(`intellij.yaml.jar`) с типом значения вместо вида: рядом с нашим `Oil | reagent` вставали
`{} Chlorine | object` и `Silver | number`. `CompletionResultSet.stopHere()` останавливает только
тех, кто идёт **после** нас, а стоковый contributor зарегистрирован без `order`, то есть порядок
между ним и нами не определён — поэтому наш EP объявлен `order="first"`. Глушим не всегда, а лишь
когда список непустой: иначе там, где мы ничего не знаем, пользователь остался бы вообще без
автокомплита.

**`taken` без маппинга под курсором отключался молча.** На пустой строке внутри объявления PSI не
даёт `YAMLMapping` (позиция ещё принадлежит документу), и фильтр занятых ключей выключался —
автокомплит предлагал `id`, `result`, `completetime`, уже написанные строкой выше. Ищется ближайший
маппинг, чей путь совпал с путём позиции, а при пустом пути запасной вариант — `declaration.mapping`.

**Автокомплит id фильтруется обратным индексом, а не перебором `sites`.** Имён ключей, за которыми
стоит вид прототипа, 163 (в контенте ~15600 значений), тогда как старая эвристика знала четыре —
на `graph:`, `board:`, `spawn:`, `reagent:` автокомплита не было вовсе, а на `parent:` предлагались
все 30620 id вперемешку. Вид ключа даёт `RobustValidation.expectedKind` (та же пара
`prototypeKind`/`keyPrototypeKind`, что и в валидации: у ключа маппинга берётся ключевой вид, у
значения и элемента списка — обычный). Обратная выборка «вид → id» сделана отдельным индексом:
фильтровать готовый `ids(project)` через `sites(id)` нельзя, это 30620 обращений к `processValues`
под read action на каждый Ctrl+Space, и первое же нажатие клавиши выбрасывает всю работу через
`ProcessCanceledException`. Индексер переиспользует разбор `RobustPrototypeIdIndex.prototypeIds`,
поэтому regex один на оба индекса. Замер прогоном самого индекса: 3105 файлов, 205 видов,
30620 id, полный проход 184 мс. Сужение списка: `parent:` — 14083 вместо 30620, `graph:` — 96,
`categories:` — 8. Кэш выборки — `ConcurrentHashMap` в `CachedValuesManager` по виду, потому что
у `entity` за одним ключом стоят тысячи файлов.

**`XmlNode.Value` у элемента — всегда `null`.** `member.GetXMLDoc(true)?.Value` компилируется,
не бросает исключений и честно везёт `null` через rd, поэтому поле `summary` в модели было
заполнено у нуля полей и выглядело это как «бэкенд не отдаёт документацию». Контракт BCL:
текст отдают только узлы `Text`, `CDATA`, `Comment` и `Attribute`, у `Element` и `Document`
свойство равно `null`; содержимое берётся через `InnerXml`/`InnerText`. Нужен `InnerXml`, а не
`InnerText`: внутри summary живут `<see cref>`, `<c>`, `<para>`, и `RobustXmlDoc.toHtml` их
превращает в `<code>`/`<p>`. Сам узел берётся из `GetXMLDescriptionSummary(true)`, запасной путь —
`GetXMLDoc(true)?.SelectSingleNode("descendant-or-self::summary")` (неизвестно, отдаёт ли
`GetXMLDoc` корень `<member>` или сразу `<summary>`). Флаг `expand: true` по документации сборки
наследует doc от базы, когда у члена его нет, и раскрывает `<inheritdoc/>` и `<include/>` — те
самые 2226 тегов, ради которых регексный фронтенд лезет по цепочке наследования руками.

**Ховер асинхронный: `DocumentationResult.asyncDocumentation`.** Kotlin-перегрузка принимает
suspend-лямбду и допускает `null` (проверено компилятором — метаданные читаются плохо), поэтому
внутри зовётся `IRdCall.startSuspending(request)` (scheduler имеет дефолт) вместо `sync`. Чтение
индексов внутри лямбды обязано идти через `smartReadAction(project) { }`: нужен read action, и
дожидаться конца индексации тоже приходится.

**`Wrong thread RdCall` асинхронностью не лечится — нужен шедулер протокола.** Предупреждение
печатает `com.jetbrains.rdclient.protocol.RdDispatcher` (класс лежит в `lib/intellij.rd.client.base.jar`,
в нём же строки «Must be executed on UI thread or background threads with special permissions» и
поля `allowedOnThisThread`/`allowedOnAnyThread`). Протокол Rider разрешает обращения с UI-потока
либо с фонового, которому выдано разрешение через `allowBackgroundThreadAndSuppressPumping`;
корутина на `Dispatchers.Default` не подходит, и переход с `sync` на `startSuspending` сам по себе
ничего не изменил. Вызов оборачивается в
`withContext(model.protocol?.scheduler?.asCoroutineDispatcher)` — `RdBindableBase.protocol`
nullable, у непривязанной модели вызывать нечего.

**Инвалидация кэша типов — по `.cs` в VFS, а не по `PsiModificationTracker`.** Трекер тикает на
любое изменение PSI, включая набор текста в самом YAML, — кэш C#-типов сбрасывался бы на каждое
нажатие клавиши. Цена: правка открытого `.cs` не видна до сохранения. `IRdCall` умеет
`startSuspending(lifetime, req, scheduler)`, но корутины не нужны — все вызывающие
(`computeDocumentation`, автокомплит) и так на фоновом потоке, а лишний вызов снимает кэш.
`MessageBus.connect(CoroutineScope)` избавляет сервис от отдельного `Disposable`.

**Аннотатор бэкенд не ждёт вообще, даже с таймаутом.** `runBlocking` в `annotate` блокирует поток
демона, держащий read lock, а rd-вызов исполняется на шедулере протокола, привязанном к UI-потоку:
взаимная блокировка. Плюс демон отменяется `ProcessCanceledException` на каждое нажатие клавиши,
а `runBlocking` про отмену read action не знает и висит до конца таймаута. Даже успешный таймаут
даёт недетерминированную подсветку: успел ответ — ошибка есть, не успел — нет. Поэтому у
`RobustBackend` две точки входа: `typeFields`/`field` (suspend, для ховера и автокомплита) и
`cachedFields`/`cachedField` — синхронные, читают только готовую мапу `ready`, при промахе тихо
запускают загрузку и возвращают `null`. Когда ответ пришёл, зовётся `DaemonCodeAnalyzer.restart()`
— по байткоду это `markAllFilesDirty` + `synchronized stopProcess`, никаких threading-ассертов,
звать можно из корутины. Перезапуск отложен на 300 мс и склеен `AtomicBoolean`: каждый `restart()`
отменяет текущий проход демона, и без склейки на файле с десятками типов проход никогда не дойдёт
до конца — не тормоза, а livelock, в котором вся посчитанная работа выбрасывается.

**У словаря вид прототипа берётся с ключа, а `UnwrapType` раскрывает его по значению.** В
`Dictionary<ProtoId<X>, Y>` id стоит ключом YAML-маппинга, поэтому `PrototypeKinds` возвращает
пару: `Key` считается через `GetElementTypesForGenericType(..., GenericIDictionary, 0)`, `Value` —
через прежний `UnwrapType` (индекс 1 плюс `IEnumerable`), и обе едут во фронтенд отдельными полями
`keyPrototypeKind`/`prototypeKind`. Тем же расколом отличаются сериализаторы:
`PrototypeIdDictionarySerializer<TValue, TPrototype>` проверяет ключи, а
`PrototypeIdValueDictionarySerializer<TValue, TPrototype>` — значения, различаются только суффиксом
имени. У обоих прототип идёт **вторым** параметром, поэтому `FirstTypeArgument` заменён на
`LastTypeArgument`: у всех семи сериализаторов и у `ProtoId<T>` прототип последний, так что одна
ветка покрывает все случаи (первым аргументом молча забирались 26 полей).

**Один id может принадлежать нескольким видам, поэтому сравнение идёт по множеству.** `Syndicate`
объявлен четырежды (антаг, департамент и прочее), а порядок обхода индекса не гарантирован —
`sites(...).first().kind` дал бы ложную ошибку через раз и по-разному после каждой переиндексации.
Проверка звучит как `kind in kinds`, а сообщение печатает все найденные виды.

**Типизированная проверка id глушит старую эвристику, а не дополняет её.** `unknownPrototypeId`
(четыре имени ключей, существование id без учёта вида) остаётся для проектов без солюшена, но
выходит молча, если `cachedField` по этому же ключу вернул непустой `prototypeKind`, — иначе на
`parent:` висели бы две ошибки сразу. Пока данных от бэкенда нет, `typedField` возвращает `null`,
то есть новая проверка молчит и по неготовности, и по незнанию типа: ошибок по догадке не бывает.

**Хост обязан знать спец-имена датафилдов, иначе `parent` не находится.**
`[ParentDataFieldAttribute(typeof(AbstractPrototypeIdArraySerializer<EntityPrototype>))] public
string[]? Parents` даёт по имени члена ключ `parents`, тогда как в YAML он `parent`. Регексный
индекс на фронтенде спец-имена знал изначально (`[IdDataField]`/`[ParentDataField]`/
`[AbstractDataField]` — 253 атрибута в ss14-wega), хост — нет, поэтому `cachedField(EntityPrototype,
[], "parent")` не находил ничего: типизированная проверка молчала, ошибку выдавала старая эвристика
(у неё quick fix нет), а в ховере не было ни типа, ни секции `Prototype` — summary приходил из
регексного индекса и создавал впечатление, что бэкенд ответил. Там же вторая половина промаха:
сериализатор у `[ParentDataField]` передан **позиционным** `typeof(...)`, а `SerializerArgument`
искал только именованный `customTypeSerializer:` — все 32 объявления в контенте позиционные.
Теперь берётся первый аргумент-`typeof` независимо от имени; посторонний сериализатор вида не даст,
потому что `KindOf` требует `[Prototype]` на классе, а `LastTypeArgument` отсекает типы без
параметров.

**Атрибут ищется по всем декларациям и с запасным путём через метаданные.** Прототипы —
`partial`-классы, и Robust генерирует для них вторую декларацию исходным генератором;
`GetDeclarations().FirstOrDefault()` попадал то в неё, то в настоящую, поэтому вид получал то
`parent`, то `categories` — при неизменном коде между запусками. Перебираются все декларации
(`FindAttribute`), а если деклараций нет вовсе (тип пришёл из скомпилированной сборки), атрибут
берётся через `GetAttributeInstances(AttributesSource.Self)`, и литерал — из `PositionParameters()`.
Литерал обязателен: `[Prototype("tile")] ContentTileDefinition` и `[Prototype("Tag")] TagPrototype`
по имени класса не выводятся, а на этих двух видах 54 поля. Всего в контенте 17 объявлений с
литералом против 172 без. Разбор литерала из метаданных обёрнут в `try`: вычисление константы
требует конкретного модуля и в универсальном контексте падает с `Cannot get runtime features with
Universal`. Вывод `[Prototype(-1)]` (приоритет загрузки, а не имя) обрабатывается тем же
требованием кавычек в `ExplicitName`.

**Виды приходят не сразу: результат зависит от прогретости символьных кэшей ReSharper.** Один и
тот же запрос `EntityPrototype` в соседних запусках дал сначала `categories` без вида, потом с
видом — при неизменном коде. Пустой ответ бэкенда поэтому не кэшируется вовсе (`cache.remove(key)`),
иначе холодный старт навсегда помечает тип как «полей нет»: в логе это видно парой
`0 fields for 'SpriteComponent/layers'` → `14 fields` по одному ключу.

**Корни прототипов ищутся от корней ресурсов, а не как `<верхний уровень>/Prototypes`.** Движковые
прототипы лежат в `RobustToolbox/Resources/EnginePrototypes` — уровнем глубже и с другим именем,
поэтому 128 id (113 `audioPreset`, 5 `shader`, 5 `entity`, 4 `entityCategory`, 1 `uiTheme`) в индекс
не попадали вовсе, а ссылки на них есть в сотнях файлов (`HideSpawnMenu` в 209, `unshaded` в 237).
`autoDetect` берёт каталоги с суффиксом `Prototypes` у base, его детей и корней ресурсов;
`RobustResources.resourceRoots(base)` выделена чистой функцией от каталога, чтобы не возникло цикла
с `collectRoots`. `findPrototypeRoots` обязан кэшироваться на `VFS_STRUCTURE_MODIFICATIONS` плюс
`RobustYamlSettings.state` (`BaseState` — это `ModificationTracker`): платформа спрашивает
`getAdditionalProjectLibraries`/`getRootsToWatch` при каждом построении области индексирования, а
цена вызова выросла с пары `findChild` до обхода десятков каталогов с `containsYaml` на каждого
кандидата.

**`:compileDotNet` не зависит от `:protocol:rdgen`.** После правки `structdef` C#-сторона собирается
против старого сгенерированного конструктора и падает на `CS1729: does not contain a constructor
that takes N arguments`. Модель перегенерируется отдельной задачей `./gradlew :protocol:rdgen`
(`:runIde` делает это сам).

**Отступ после Enter даёт форматтер, и `INDENT_SEQUENCE_VALUE` тут ни при чём.** После
`components:` платформа ставит следующую строку на +2 от ключа, тогда как SS14 пишет элементы
списка на уровне самого ключа. Настройка `YAMLCodeStyleSettings.INDENT_SEQUENCE_VALUE` (рядом
`SEQUENCE_ON_NEW_LINE`, `AUTOINSERT_SEQUENCE_MARKER`) относится к форматированию уже существующей
последовательности; в ss14-wega она и так выключена через `.editorconfig`
(`ij_yaml_indent_sequence_value = false`), а отступ всё равно +2. Пока дефис не набран, позиция
после `components:` текстом неотличима от вложенного маппинга, поэтому обработчиков два.

`RobustSequenceEnterHandler` — это **не** `EnterHandlerDelegate`, а обработчик самого действия
(EP `com.intellij.editorActionHandler`, `action="EditorEnter"`, `order="last"` — последний
зарегистрированный оказывается верхним в цепочке, поэтому сначала зовётся `original.execute`,
а правка делается после него). Делегатом эту точку не взять: `YAMLEnterAtIndentHandler` объявлен
с `order="first"` и, когда сам вставляет `- ` (`shouldInsertAutomaticHyphen`), возвращает из
`preprocessEnter` результат `Stop`, а по байткоду `EnterHandler` это `return` из всей обработки —
ни `postProcessEnter` у остальных, ни стандартная ветка не выполняются. Диагностика этого выглядела
парадоксально: под `reactiveEffects:` наш делегат отрабатывал и честно молчал (`sequence=false`,
там словарь), а под `components:` и `tileReactions:` не вызывался вовсе — ровно там, где YAML
вставляет дефис сам.

Последовательность опознаётся в три шага: `value is YAMLSequence` (список уже непустой — тип
спрашивать не надо), иначе `components` у прототипа, иначе флаг `RobustDataField.sequence`, который
хост считает как «`IEnumerable`, но не строка и не словарь». Средний шаг обязателен:
`ComponentRegistry : Dictionary<string, ComponentRegistryEntry>` — по типу словарь, а в YAML список
маппингов.

**Каретку приходится ставить дважды.** Сразу после `document.replaceString` строка содержит `- `,
но платформа по возвращении из `executeWriteAction` возвращает каретку на свою вычисленную позицию —
перед дефисом, и выглядит это как «дефис не вставился, курсор на пустой строке» (лог показывал
`line is now '  - ', caret at column 4`, а отложенный — `caret at column 2` при том же тексте).
Поэтому позиция повторно выставляется в `invokeLater`, с проверкой, что строка всё ещё ровно
`<отступ>- `: иначе перезапись сдвинула бы каретку у пользователя, успевшего начать печатать.

**Отступ тоже приходится ставить дважды, и строгая проверка это скрывала.** На списке, написанном
с отступом платформы, лог показал пару `Enter under sequence key 'vertices', indent 12` →
`After enter line 18 is '          - ', expected '            - '`: мы пишем 12, а к моменту
`invokeLater` строка уже переиндентирована code style на уровень ключа (10). Сравнение с ожидаемой
строкой не совпадало, страховка молча выходила, и наружу это выглядело как «дефис не туда, каретка
перед ним» — тот же симптом, что и у потерянной каретки, но причина другая. Теперь при расхождении
строка переписывается заново через `WriteCommandAction.runWriteCommandAction` (`invokeLater` идёт
уже вне write action), а условие остаётся узким — `settled.trim() == "-"`, то есть правим только
собственную вставку, к которой никто не успел притронуться.

`RobustSequenceIndentHandler` (EP `com.intellij.typedHandler`, `charTyped`, `order="last"`) остаётся
для дефиса, набранного руками: на строке из одних пробелов плюс `-` он смотрит выше первую непустую
строку и, если она кончается двоеточием, срезает лишние пробелы до её отступа. Здесь тип не нужен
вовсе — дефис уже объявил последовательность. `order="last"` нужен из-за стокового
`YAMLHyphenTypedHandler`: тот реагирует не на дефис, а на **пробел** после него (`c == ' '`, символ
`-` на `caret-2`) и зовёт `CodeStyleManager.adjustLineIndent`, то есть переиндентирует строку по
code style уже после нас. Впрочем, на существующих списках он бесполезен: внутри стоит условие
`sequence.getItems().size() == 1`, поэтому у `components:` с четырьмя элементами стоковая коррекция
не срабатывает вовсе.

**`RobustCodeStyleModifier` (EP `com.intellij.codeStyleSettingsModifier`) — тот же механизм, которым
свои настройки применяет EditorConfig.** Для YAML внутри каталога `Prototypes` он выставляет
`INDENT_SEQUENCE_VALUE = false`, чтобы стоковая переиндентация и Ctrl+Alt+L считали так же, как мы.
На ss14-wega он молчит: `.editorconfig` там уже задаёт `ij_yaml_indent_sequence_value = false`,
и менять нечего — но именно поэтому стало понятно, что отступ после Enter эта настройка не лечит. Проверка «слева одни пробелы» отсекает `useDelay: -0.5`
и второй дефис в `- -5`, а требование двоеточия у строки-владельца — `---` и продолжение уже
начатого списка (там отступ и так верный).

Оба правила висят на одной галочке `alignSequenceDash` и требуют, чтобы позиция была внутри
объявления прототипа (`declarationAround`), — а не чтобы файл лежал в корнях прототипов: при
`autoDetect = false` корней нет вовсе, и правило молчало бы там, где всё остальное работает.
Перед PSI-запросом нужен `PsiDocumentManager.commitDocument`: оба обработчика зовутся, когда
документ уже изменён, а дерево ещё нет. Code style намеренно не читается: включённая галочка
и есть явное намерение писать в стиле SS14. На строке без `-`, стоящей на уровне ключей
прототипа, автокомплит обязан предлагать ключи прототипа (`abstract`, `suffix`), а не `type`:
`type` там — синтаксическая ошибка, элемент списка начинается с дефиса.

**Solution Explorer расширяется двумя EP Rider, а не платформенными.** Корень «Robust Prototypes»
даёт `solutionExplorerRootProvider` (`RiderExtensionPoints.xml`, area `IDEA_PROJECT`, namespace
`com.intellij`; те же EP используют `SolutionRootProvider`, `FoldersRootProvider` для attached
folders и `ScratchRootProvider`), покраску узлов — `solutionExplorerCustomization`
(`SolutionExplorerFileNode.update` в конце проходит по всем расширениям и зовёт
`updateNode(presentation, virtualFile)`). Своих файловых узлов писать не нужно: дети строятся
готовым `SolutionExplorerFileNode(project, file, nested, settings, isRoot, isAttachedFolder)`,
у которого `calculateChildren` берёт детей через `SolutionViewUtilsKt.getChildrenForSolutionView`
— это `VfsUtil.getChildren` минус `.DS_Store`/`obj`/`.idea`, без единого вопроса о принадлежности
проекту. Поэтому прототипы видны при выключенном Show All Files: отбор «чужих» файлов делает
`SolutionExplorerModelNode`, а он к файловым узлам отношения не имеет. Флаг `isAttachedFolder`
у нас `false` — иначе на узле появилось бы меню «Detach».

**Значение узла — список корней, а не константа.** `AbstractTreeNode.equals` — это «класс тот же»
плюс `Comparing.equal(myValue, other.myValue)`, а `hashCode` — хэш значения; по ним
`StructureTreeModel$Node.canReuse(that, element)` решает, оставить ли существующий `Node`
(`allowsChildren` и `hashCode` совпали, `matches(element)` истинно → `this.userObject =
that.userObject`, кэш детей `children: Reference` при этом не инвалидируется). С константой корень
навсегда «тот же самый», и появление нового каталога прототипов (подтянули сабмодуль
`RobustToolbox` — возник `EnginePrototypes`) не показывалось бы до перезапуска IDE, потому что
`findPrototypeRoots` пересчитывается по `VFS_STRUCTURE_MODIFICATIONS`, а дерево про это не знает.

**Расчёт проблемных файлов обязан сравнивать снимок перед `refresh()`.** `updateNode` зовёт
`schedule()`, а `ProjectView.refresh()` заставляет дерево заново звать `update()` на каждом узле,
то есть снова попадает в `updateNode`. Без проверки `if (computed == snapshot) return` это
бесконечный цикл: дерево перестраивается каждые полторы секунды, теряя раскрытые узлы и выделение.
`AtomicBoolean pending` не спасает — он схлопывает заявки внутри одного окна ожидания, а новый круг
начинается после его сброса.

**Уровень проблемы взят из поведения движка, а не придуман.** `ComponentRegistrySerializer.Read`
на неизвестное имя пишет `Log.Error("Unknown component '{compType}' in prototype!")` и делает
`continue` — прототип грузится без компонента; `PrototypeManager.cs:220` на неразрешимого предка
пишет `Sawmill.Error("Encountered invalid prototype while enumerating parents")` и тоже продолжает.
Значит компонент и `parent` — ошибка. Битый id в поле (`EntProtoId`, `ProtoId<T>`, ключи `proto`,
`prototype`, `entity`) при загрузке не проверяется вовсе и падает только при использовании —
это предупреждение. Поэтому индекс ссылок различает три префикса (`c:`, `p:`, `r:`), а не два.
Замер прогоном самих индексов по ss14-wega: 3100 файлов прототипов, 16975 ссылок на компоненты,
8151 на id, полный проход 126 мс, ошибок 0, предупреждение 1 — `WeaponEnergyTurretStation`
в `turrets.yml`.

**`ComponentAvailability.Ignore` ложных срабатываний не даёт.** Сервер регистрирует
`_factory.RegisterIgnore(IgnoredComponents.List)`, движок — `Input`, `AnimationPlayer`,
`GenericVisualizer`, `Sprite`; все эти компоненты объявлены классами в репозитории, поэтому индекс
имён их видит и «неизвестными» они не становятся.

**`migration.yml` даёт точную подсказку, но не делает id валидным.** Ключи словаря в множество
известных id не добавляются: миграция применяется только к картам, поэтому ссылка в прототипе
остаётся мёртвой — засчитай мы её, и настоящие поломки уходили бы молча. Зато это лучший источник
quick fix: не «похожее по Левенштейну», а буквально то, на что автор переименовал. Замер прогоном
`RobustMigrations.parse` по ss14-wega: строк-словарей в файле 617 (487 переименований,
130 удалений со значением `null` или пустым), regex не отвергает ни одной, дублей ключей нет;
единственная мёртвая ссылка контента чинится миграцией, мёртвых ссылок без записи 0, целей
миграции, которых нет среди прототипов, тоже 0 — фикс не может предложить несуществующий id.
Ещё 12 ссылок ведут на id, который миграция переименовывает, но который всё ещё объявлен —
это рабочие ссылки, и их не трогаем.

**`migration.yml` к прототипам не применяется.** `MapMigrationSystem` подписан на
`BeforeEntityReadEvent` от `MapLoaderSystem`, то есть переименовывает сущности только при чтении
карт; поля прототипов через миграции не резолвятся. Из-за этого `MachineBoard.prototype:
WeaponEnergyTurretStation` — настоящая мёртвая ссылка (в `migration.yml` она указана как
`WeaponEnergyTurretStation: WeaponEnergyTurretSecurity`), хотя турели в игре работают: значение
объявлено в `abstract: true` прототипе и перекрыто во всех трёх наследниках.

**`parent:` бывает блочным списком, и это ломало всю цепочку наследования.** Форм три:
скаляр (13305), инлайн-список (2469) и блочный список под ключом (60) — последний построчный regex
не брал вовсе, поэтому `MobVulpkanin` с родителями `BaseMobSpeciesOrganic` и `MobBloodstream`
выглядел как прототип без предков. Замер обязательных полей из-за этого показывал 137 нарушений
вместо нуля. Блочная форма читается **только** для `parent`: под `proto`/`prototype`/`entity` таких
списков в контенте ровно три, и все три — `SexToyComponent.Prototype` типа `List<string>`, то есть
не ссылки вовсе. Элемент списка обязан начинаться со строки (`(?m)^[ \t]*-`): без якоря regex
поймал `-TTS` внутри комментария `- TTS_speaker # Corvax-TTS` и объявил несуществующего предка.

**Обязательные поля считаются на смерженном наследовании, иначе шум в сотни срабатываний.**
`required: true` объявлен у 950 датафилдов, а `RequiredFieldNotMappedException` бросает
сгенерированный код десериализации — уже после того, как `ComponentRegistrySerializer`
(`ITypeInheritanceHandler`) смержил компоненты родителей, и никогда для `abstract: true`
прототипов: те не инстанцируются вовсе (`PrototypeManager.cs:135,175` — «Abstract parent?»).
Из 30493 объявлений 15823 имеют `parent`, 1310 абстрактны, так что проверка ключей только своего
объявления бессмысленна. Подъём по цепочке обязан быть накопительным и идти до конца: у наушников
`Clothing` объявлен трижды — `sprite` в `ClothingHeadsetAltCargo`, `equippedPrefix` в
`ClothingHeadsetAlt` и `slots` только в `ClothingHeadset`; остановка на первом предке с этим же
компонентом дала бы 10 ложных ошибок. Источник `required` — regex-индекс, а не бэкенд: так проверка
работает без солюшена и, главное, замер можно прогнать офлайн. Аргументы атрибута разбираются
балансировкой скобок (`argumentsOf`), потому что `[DataField("graph", required: true,
customTypeSerializer: typeof(PrototypeIdSerializer<X>))]` рвёт любой `\(([^)]*)\)`.
Замер `MeasureRequired` по ss14-wega: 271 компонент с обязательными полями, 12940 проверенных
неабстрактных `entity`, нарушений 0.

**Числа и bool проверяются только по типу от бэкенда — по имени ключа нельзя.** Имён датафилдов,
у которых в одном классе `bool`, а в другом что-то ещё, 26; худший — `state`: одновременно `bool`,
`string` и дюжина enum (`DoorState`, `MobState`, `SignalState`…), а в YAML это ещё и кадр внутри
`.rsi`, то есть 18479 значений, на каждом из которых проверка по имени ругалась бы «not a boolean».
Всего у 378 имён из 6620 тип неоднозначен. Правила парсинга взяты у движка: `BooleanSerializer` —
`bool.Parse`, то есть регистр не важен (иначе 145 ложных на `anchored: True`); целые — `Parse.Int32`
с `NumberStyles.Integer` и `InvariantCulture`, без `AllowThousands`; `FloatSerializer` —
`float.TryParse(..., NumberStyles.Float | NumberStyles.AllowThousands, InvariantCulture)`, поэтому
`drawRate: 3,6` не падает, а тихо читается как `36` — это отдельное сообщение, а не ошибка парсинга.
Ругаться на запятую вообще нельзя: в контенте 907 значений вида `1,0`, и почти все — `Vector2`
и `Vector2i` (`uiWindowPos`, `textOffset`), где запятая разделяет координаты. Уровень ошибки, а не
предупреждения, потому что `float.Parse` при загрузке бросает исключение — в отличие от битого id.

**`TimeSpan` — третий по частоте тип датафилда, и правила у него свои.** Объявлений 694 (после
`float` 1722 и `bool` 1438), плюс `FixedPoint2` 145, так что без них проверка чисел молчала на
`useDelay: fast`. `TimeSpanExt.TryTimeSpan` отвергает строку с запятой, пробелом или двоеточием
(в коде прямо сказано: запятая как десятичный разделитель дала бы величину на порядки больше),
затем принимает число секунд либо число с суффиксом `s`/`m`/`h` — `1.5h` можно, `1h30m` нельзя.
`Validate` у сериализатора мягче `Read` (там есть запасной `double.TryParse` с `NumberStyles.Any`),
ориентироваться надо на `Read`, иначе пропустим значение, на котором движок бросит `FormatException`.
`FixedPoint2` читается через `Parse.Double(value, NumberStyles.Float)` — без `AllowThousands`,
то есть запятая для него ошибка, в отличие от `float`; плюс отдельно принимается `MaxValue`.
Проверка значений идёт своим regex, а не `toDoubleOrNull`: Java принимает `5f`, `NaN` и `0x1p3`,
которых .NET не примет. Прогон по контенту: 792 значения `TimeSpan` и 208 `FixedPoint2`, ноль
несоответствий.

**Пробел не должен выбирать элемент автокомплита — в C# он этого и не делает.** Дефолт платформы
для пробела — `SELECT_ITEM_AND_FINISH_LOOKUP`, после чего символ печатается обычным путём
(`TypedHandler.execute` → `TypedCharImpl.typeChar`; стек снят `DocumentListener`, повешенным на время
вставки), — отсюда `type: Sprite ` с пробелом в конце строки. В Rider на C# пробел лишь закрывает
попап и печатается как есть, выбор оставлен Enter и Tab; у ReSharper это даже отдельная настройка
`CompleteOnSpace` (`JetBrains.ReSharper.Feature.Services.dll`, своя для C#). Поэтому решение — не
подавлять символ через `setAddCompletionChar(false)`, а вернуть `HIDE_LOOKUP` из `CharFilter`
(EP `com.intellij.lookup.charFilter`) внутри объявлений прототипов. Две тупиковые гипотезы стоит
помнить: `DUMMY_IDENTIFIER` тут ни при чём (он кончается пробелом, но в документ не попадает),
а обрезка хвоста бесполезна и в `handleInsert` (строка ещё чистая), и в `invokeLater` (каретка уже
стоит **за** пробелом, и хвост от неё пустой).

**Quick fix по ключу локализации не может считать Левенштейна в лоб.** Ключей 56280, а фикс строится
аннотатором на каждую ошибку и на каждом проходе демона. Поэтому сначала идут дешёвые фильтры —
разница длин не больше правки и общий префикс от 4 символов, — и только потом расстояние. Проверка
префикса написана циклом: `commonPrefixWith` аллоцирует новую строку на каждый из 56280 ключей и
одна эта аллокация давала десятки миллисекунд. Замер: 113 ключей с намеренной опечаткой, 112
подсказаны обратно, среднее 7 мс на вызов. На единственной настоящей мёртвой ссылке контента
подсказки нет — похожего ключа в локализации просто не существует, и выдумывать его фикс не должен;
поэтому сторож и проверяет себя опечатками, а не этой ссылкой.

**Недостающие обязательные поля дописываются через документ, а не через PSI.** Сгенерированный
`YAMLKeyValue` платформа переиндентирует по code style, то есть ровно по тому правилу, которое в
прототипах SS14 неверно. Ключи вставляются текстом на отступе самого `type:` — на нём же стоят
остальные ключи компонента, — а точка вставки берётся как конец строки, на которой кончается
маппинг: иначе хвостовой комментарий уехал бы внутрь вставки.

**`customTypeSerializer` отменяет тип поля, и без флага это 398 ложных ошибок.**
`[DataField("drawdepth", customTypeSerializer: typeof(ConstantSerializer<DrawDepthTag>))] public int
DrawDepth` — тип `int`, а в YAML стоит `drawdepth: Mobs`, потому что значение читает не `int.Parse`,
а сериализатор. Правило общее: любой `customTypeSerializer` подменяет `Read`, поэтому белый список
из одного `ConstantSerializer` завтра снова начнёт ругаться — в модель добавлен флаг
`customSerializer` (`SerializerArgument(attribute) != null` на хосте), и скалярная проверка при нём
молчит целиком. Виды прототипов при этом продолжают работать: там сериализатор не отменяет проверку,
а как раз её и задаёт.

**`ConstantSerializer` вернул `drawdepth` в проверку, но уже как список значений.** Флаг
`customSerializer` глушит скалярную валидацию целиком, и на 398 значениях `drawdepth: Mobs`
не остаётся ни проверки, ни автокомплита. Тег в `ConstantSerializer<DrawDepthTag>` членов не несёт
(`DrawDepthTag` — алиас класса `Robust.Shared.GameObjects.DrawDepth` с одной константой `Default`),
настоящие значения лежат в enum контента, помеченном `[ConstantsFor(typeof(DrawDepthTag))]`.
Обратного поиска «кто помечен этим атрибутом» у symbol cache нет, поэтому enum ищется по короткому
имени тега — движок и контент называют их одинаково (`DrawDepth`), — а атрибут и его аргумент
сверяются уже после, чтобы одноимённый тип без них не прошёл. Искать обязательно в
`LibrarySymbolScope.FULL`, а не в scope модуля-владельца: `drawdepth` объявлен в `Robust.Client`,
enum лежит в `Content.Shared`, а движок на контент не ссылается — в модульном scope его нет вовсе,
и поле молча оставалось без значений. Сверка аргумента при этом необязательная: `typeof(...)`
в универсальном контексте может не зарезолвиться, и тогда хватает самого `[ConstantsFor]`. Найденные члены кладутся в те же `values`,
что и enum, поэтому проверка и автокомплит достаются даром. Сверка по ss14-wega: 31 член enum,
29 разных значений в контенте, несоответствий 0.

**`Color`, векторы и `Angle` — три разных сериализатора с непохожими правилами.** `ColorSerializer`
принимает либо имя из `Color.DefaultColors` (145 штук, сравнение через `ToLower()`; кроме привычных
там `betterviolet`, `ruber`, `vividgamboge`), либо hex из `TryFromHex` — обязательно с `#` и длиной
4, 5, 7 или 9. `Vector2Serializer` разбирает значение через `VectorSerializerUtility.TryParseArgs`,
и разделителей два — `,` и `x`; они перебираются по очереди, берётся **первый** давший нужное число
частей, поэтому откатываться на второй разделитель нельзя: `1x2,3` движок разрежет по запятой
и упадёт на `float.Parse("1x2")`, а «умная» проверка сочла бы это валидным вектором. Тем же кодом
живут `Vector2i` (компоненты через `int.Parse`), `Vector3` и `Vector4`. `AngleSerializer` смотрит
`EndsWith("rad")`: с суффиксом это радианы, без него — **градусы**, и в обоих случаях
`double.Parse` без явных `NumberStyles`, то есть с `AllowThousands` — запятая тут разделитель тысяч,
как у `float`. Замер по ss14-wega: 445 значений `Angle`, 209 `Color`, 64 `Vector2`, 532 `Vector2i`,
ноль несоответствий; `Vector3`/`Vector4` в контенте не встречаются вовсе.

**`LocId` проверяется по `.ftl`, и на это есть три правила движка.** `GetString` на неизвестный id
пишет `Warning` в лог и возвращает **сам id** — игрок увидит `comp-thief-target` вместо текста,
прототип при этом жив, поэтому уровень предупреждение. Вторая цена другая:
`LocIdSerializer.Validate` отдаёт `ErrorNode`, а `Content.YAMLLinter` крутится в CI
(`.github/workflows/yaml-linter.yml`), то есть мёртвый ключ валит PR. `HasMessage` обрезает id
по первой точке (дальше идёт атрибут Fluent, не отдельное сообщение) и ищет в текущей культуре,
затем в fallback — значит ключ из любой локали считается известным. Замер по ss14-wega: 4341 файл
`.ftl`, 56280 id (ru-RU 56118, en-US 28632, pt-BR 190, nl-NL 3), 2668 значений `LocId` в прототипах
и ровно один промах — `tool-quality-clockwork-slab-tool-name` в `_Wega/tool_qualities.yml`,
которого в локализации нет ни в одной культуре.

**Ховер по ключу локализации показывает текст, и условие показа — индекс, а не тип поля.**
Таргет появляется, когда значение оказалось известным id (`hasMessage`), а не когда бэкенд назвал
поле `LocId`: так ховер работает на холодном бэкенде и на полях, до типа которых спуск не дошёл,
а ложных срабатываний не даёт — совпасть с одним из 56280 ключей случайной строке негде.
Тело сообщения читается по offset из индекса: текст после `=` плюс строки с отступом, потому что
именно так Fluent пишет многострочные значения и атрибуты; пустая строка или строка с нулевой
колонки завершают запись. На ss14-wega тела не разбираются у 3 сообщений из 56280 — все три
в `en-US/game-ticking/game-presets/preset-wizard.ftl`, где значение начинается с `{` в нулевой
колонке; по спеке Fluent это junk, а не продолжение, и в `ru-RU` те же три написаны с отступом.
Секции — по культуре, поэтому видно и перевод, и оригинал сразу.

**Проверять хардкод текста вместо `LocId` нельзя, хотя соблазн есть.** Среди 2668 значений
`LocId` человеческого текста нет ни одного. А там, где текст пишут в самом деле — `name:`, `desc:`
у прототипа — тип поля `string`, и это штатный путь: `LocalizationManager.Entity` берёт
`ent-<prototypeId>` (или `localizationId`, если задан), а YAML-текст остаётся дефолтом, когда ключа
нет. Подсветить это — значит покрасить половину контента за то, что он написан правильно.
Первая прикидка «по имени ключа» насчитала 18061 хардкод и все до одного были ложными: `name`
бывает и `LocId`, и `string`, а тип по владельцу знает только бэкенд.

**Корни локализации приходится добавлять в ту же `SyntheticLibrary`.** `Resources/Locale` лежит
вне content root ровно как прототипы, а `findPrototypeRoots` собирает только каталоги с суффиксом
`Prototypes` — без отдельного `findLocaleRoots` индекс `.ftl` оставался бы пустым, и валидация
молчала бы вообще (её и охраняет `hasAnyMessage`: пока индекс пуст, ни одного предупреждения
не выдаётся). Корни локализации ищутся от корней ресурсов, поэтому подхватывается и
`RobustToolbox/Resources/Locale`.

**`FakePsiElement` обязан сообщать текст и длину, иначе он «занимает» весь файл.** По умолчанию
`getText()` там возвращает `null`, а `getTextLength()` — ноль, и этого хватает на два разных сбоя:
превью в Find Usages красит `.ftl` целиком вместо строки ключа, а любой код, читающий `element.text`
как non-null, падает с `NullPointerException: getText(...) must not be null` — так наша же строка
лога уронила `canFindUsages`, из-за чего платформа отвечала «Cannot search for usages from this
location» и в Alt+F7, и в фоновой подсветке использований.

**Превью объявления красит `.ftl` целиком, потому что у plain text весь файл — один элемент.**
Данные к этому отношения не имеют, и это доказано пробой: диапазон, урезанный до одного символа,
заливку не изменил, тогда как в `.yml` подсвечивается ровно ключ. Всё остальное тоже проверено —
offset из индекса указывает точно на ключ (798 в `ru-RU/_wega/heretic/heretic.ftl`), pointer
оказывается `HardElementInfo` (хранится сам объект), `isValid` истинно, а `UsageInfo` строится
`PsiElement2UsageTargetAdapter` именно от нашего элемента. Красит платформа
`SEARCH_RESULT_ATTRIBUTES` по `EXACT_RANGE`, но диапазон берёт не у нас: в файле без парсера
элемент под offset — это весь текст. В обычном редакторе `.ftl` подсвечивается правильно, то есть
фон файла ни при чём.

**Лечится это без парсера — `UsageInfo(файл, начало, конец)` вместо элемента.** У конструктора с
диапазоном платформа берёт границы напрямую, а не спрашивает их у элемента, поэтому в превью
красится ровно ключ. Заглушка-объявление остаётся целью перехода и переименования, но в списке
использований она больше не участвует: объявления сообщения отдаются диапазонами в своих файлах.

**У `.ftl` на фронтенде нет парсера, поэтому цель перехода — `FakePsiElement`.** Plain text файл
это один токен, и `findElementAt(offset)` вернул бы элемент с `textOffset = 0`: Ctrl+клик всегда
приводил бы на первую строку файла с 56 тысячами ключей. `FakePsiElement` наследует
`PsiElementBase`, а тот реализует `NavigatablePsiElement`, поэтому достаточно переопределить
`navigate` и уйти в `OpenFileDescriptor(project, file, offset)`.

**Замена regex-индексов на бэкенд отложена до первого промаха, а не отвергнута.** Промахов эвристики имён
компонентов на ss14-wega ноль: 16975 ссылок, 2521 имя. Издержки замены при этом конкретные:
аннотатор бэкенд не ждёт вовсе (дедлок read lock и шедулера протокола), а значит на холодном старте
`- type:` какое-то время не проверялся бы; автокомплиту и расчёту проблемных файлов нужен полный
список имён, то есть перечисление наследников `Component` по symbol cache — с уже известным
недетерминизмом; индексы работают без солюшена, бэкенд нет. Наследуемый `[ComponentProtoName]`
(движок читает атрибут с `inherit`) — единственная известная дыра regex, в контенте случай один
(`SharedEyeCursorOffsetComponent` → `EyeCursorOffsetComponent`), и имя там совпадает с выводимым
из класса-наследника, поэтому не стреляет.

**Имя компонента — это имя класса минус суффикс `Component` и минус префикс `Client`/`Server`/
`Shared`.** Правило — `ComponentFactory.CalculateComponentName`; префикс мы не снимали, поэтому
валидный `- type: CanBuildWindowOnTop` (класс `SharedCanBuildWindowOnTopComponent`) считался
неизвестным компонентом, и по нему не работали ни поля, ни go-to-definition. Зарегистрированных
компонентов с префиксом в ss14-wega три (`CanBuildWindowOnTop`, `Instrument`, `ItemModule`),
объявлений с префиксом вообще 29. Снимать префикс обязаны оба индекса — `RobustComponentNameIndex`
и `RobustDataFieldIndex` (ключ `component:`), иначе имя и класс разъедутся.

**Ключ вставляется вместе с `": "` и сразу зовёт следующий автокомплит.** `KeyInsertHandler`
дописывает двоеточие с пробелом, двигает и caret, и `setTailOffset`, коммитит документ и просит
`AutoPopupController.scheduleAutoPopup` — без этого после выбора `type` пришлось бы дописывать
`: ` руками и жать Ctrl+Space ради имени компонента. Двоеточие не дописывается, если правее
курсора в строке оно уже есть: иначе дополнение существующего ключа порождает `size:: Normal`.
Автопопап зовётся не всегда, а только когда следующему списку есть что показать: ключ `type` под
`components:`, путевые ключи (`sprite`, `sound`, …) и поля, у которых бэкенд дал `values`,
`keyValues` или вид прототипа. Иначе на `color: ` (`Color?`) вылезает пустое «No suggestions».

**Ключ `type` в пустом элементе под `components:` подсказывается вручную.** Бэкенд на путь
`entity/components` честно отвечает нулём полей: тип там `ComponentRegistry`, то есть словарь,
и по флагу `dictionary` автокомплит полей молчит — а имя компонента предлагать ещё нечему, `type:`
не набран. Пока список пуст, в позицию лезет стоковый YAML-контрибьютор, поэтому единственный ключ
`type` отдаётся отдельной веткой (`!isComponent && name == "entity" && path == ["components"]`).

**Правила сравнения enum задаёт сам движок, а не догадка.** `SerializationManager.ReadEnumValue` —
это `Enum.Parse<TEnum>(node.Value, true)`, то есть регистр не важен (в контенте пишут и `Belt`,
и `BELT`), а `ReadEnumSequence` склеивает последовательность через `", "` и отдаёт тому же
`Enum.Parse` — значит списком `[ A, B ]` записывается любой enum, не только `[Flags]`, и запятая
внутри одного скаляра тоже легальна. Отсюда валидация: значение режется по запятой, каждый кусок
ищется без учёта регистра, а всё, что не похоже на имя члена (числа — их `Enum.Parse` принимает,
`null`, теги `!type:`, якоря, точки), пропускается молча. Автокомплит при этом вставляет
каноническое написание из метаданных.

**Значения enum — пара `values`/`keyValues`, как и виды прототипов.** Замер по ss14-wega: 390
датафилдов типизированы enum (180 разных enum), и ещё 40 — словари с enum-**ключом** (`Gas` 8,
`MobState` 7, `HumanoidVisualLayers` 6), где имя члена стоит ключом YAML-маппинга. Одним полем
такое не покрыть, поэтому хост считает ключевые значения через
`GetElementTypesForGenericType(declared, GenericIDictionary, 0)`, а значения — сам тип с запасным
`UnwrapType` (`List<Gas>` и голый `Gas` в YAML неразличимы). Список членов даёт
`IEnum.EnumMembers` (`IEnumerable<IField>`), обязателен фильтр `IsEnumMember`: иначе приезжает
скрытое `value__`. `Unlift()` нужен по той же причине, что и у `EntProtoId?`.

**Автокомплит бэкенд не ждёт по той же причине, что и аннотатор.** `CompletionProvider` работает
на фоновом потоке, но под read action, а rd-вызов исполняется на шедулере протокола, привязанном
к UI-потоку: заблокировать поток ожиданием — значит держать read lock, пока UI-поток стоит
в очереди за write lock, то есть дедлок, плюс `runBlocking` не знает про отмену read action
и висит до таймаута. Поэтому значения берутся синхронным `cachedField`. На практике промах редкий:
аннотатор зовёт `prototypeIdValues` на каждый `YAMLKeyValue`, то есть открытый файл прогревает
`ready` сам, до первого Ctrl+Space. Ховер устроен иначе (`asyncDocumentation` — suspend вне read
action, индексы читаются точечными `smartReadAction`), поэтому там ответа как раз дожидаются.

**Путь по вложенным ключам несёт с собой корень: `!type:` меняет класс на середине спуска.**
У `shape: !type:PolygonShape` поле объявлено абстрактным `IPhysShape`, конкретный класс выбирает
тег, поэтому спуск обрывался на `shape` и всё, что ниже, было слепым пятном: ни полей, ни
валидации, ни ховера. В контенте 12591 тегированное значение в 1311 файлах и 792 разных типа
(`DamageTrigger` 626, `NestedSelector` 569, `PolygonShape` и прочие), то есть это не редкий случай.
`RobustYamlContext.Origin` — пара «корень плюс путь»: при подъёме от ключа к объявлению тег
обрывает обход, корнем становится класс из тега, а путь считается от него. Нового rd-метода
не потребовалось — хост с самого начала ищет тип по короткому имени.

**`YAMLValue.getTag()` тега `!type:` не видит.** По байткоду `YAMLValueImpl.getTag()` — это
«первый ребёнок значения имеет тип `YAMLTokenTypes.TAG`», а у блочной формы
(`shape: !type:PolygonShape` и маппинг со следующей строки) тег стоит между двоеточием и маппингом,
то есть он ребёнок `YAMLKeyValue`. Метод честно возвращает `null`, и переключение корня молча
не происходило. `taggedType` обходит прямых детей сам и спрашивается дважды — у значения (там тег
лежит у элемента списка `- !type:DamageTrigger`) и у самого ключа. Проверка обязана стоять **до**
строки, добавляющей сегмент пути: иначе `vertices` искался бы как `PolygonShape/shape/vertices`.

**Правила чтения значений раскрываются на коллекцию, иначе `Vector2[]` не проверяется вовсе.**
Тип поля приходит с хоста presentable-именем, а `PRIMITIVES` искался точным совпадением, поэтому
`vertices: [- ValidaciaHyita]` у `PolygonShape` не давал ни одной ошибки. `scalarKind` снимает `[]`
и один параметр дженерика (`List<X>`, `HashSet<X>`), останавливаясь на всём, где параметров больше
одного: словарь в YAML — маппинг, а не список скаляров. Замер после раскрытия: 24093 проверенных
значения против прежних 23971, отвержений по-прежнему 17 известных — то есть покрытие выросло
(`float[]`, `bool?[]`, `List<Vector2>`, `List<TimeSpan>`) без единого нового ложного.

**Замер обязан резать flow-последовательность так же, как PSI.** `[ True, True, True ]` он проверял
одной строкой и насчитал 22 несуществующих ошибки на `float[]` и `bool?[]`, а
`["-0.3,0.5","0.2,1.6"]` разваливался ещё и по запятой внутри кавычек — 8 отвержений на
`List<Vector2>`. В IDE ничего этого нет: аннотатор получает от PSI готовые элементы уже без кавычек.
Разбиение в `items` идёт с флагом кавычек, а не `split(",")`.

**Ключ-последовательность и ключ-словарь вставляются без пробела после двоеточия.** `layers: `
с висящим пробелом — это `KeyInsertHandler`, дописывавший `": "` всегда; у таких ключей значение
начинается со следующей строки. Флаги `sequence`/`dictionary` из модели уже есть, поэтому решение
стоит ровно на них. У ключей словаря вопрос сложнее: `fixtures:` заполняется именами автора
(`fix1`), и пробел зависит от того, есть ли у **значения** словаря датафилды — у `FixtureData` они
есть, значит запись открывает блочный маппинг, а у `Dictionary<string, int>` пробел нужен.

**Чужие элементы автокомплита достраиваются декоратором, а не подавляются.** На уровне словаря
предлагать нечего — ключи произвольные, — и единственный полезный источник это стоковый
контрибьютор с ключами, уже встречавшимися в документе. Он вставляет голое имя, поэтому его
результаты перехватываются `runRemainingContributors` и оборачиваются
`LookupElementDecorator.withInsertHandler`: сначала работает родной handler элемента
(`item.delegate.handleInsert`), потом наш дописывает двоеточие.

**Дефис равняется по существующим элементам списка, а не по ключу.** Правило «элементы на уровне
ключа» верно для нового списка, но в чужом файле список может быть написан с отступом платформы,
и тогда типнутый дефис разрывал его надвое. `sequenceIndent` смотрит первую непустую строку под
ключом, пропуская набираемую: есть элементы — берётся их отступ, нет — отступ ключа. Оба
обработчика (`-` руками и Enter) считают одинаково.

**Список типов для `!type:` — это поиск наследников, а не symbol cache.** Обратного запроса «кто
наследует X» у symbol cache нет, поэтому хост зовёт
`services.Finder.FindInheritors(TypeFactory.CreateType(type), consumer, domain, NullProgressIndicator.Create())`
(`FinderExtensions`, `JetBrains.ReSharper.Psi.Search`), домен —
`SearchDomainFactory.Instance.CreateSearchDomain(solution, false)`. Отбираются неабстрактные классы
и структуры, плюс сам тип поля, если он инстанцируется: у `Container` тег ставят и на него самого.
Путь запроса тот же, что у полей, только последним сегментом идёт имя ключа, — так `Unwrap` снимает
коллекцию и `behaviors: List<IThresholdBehavior>` даёт наследников элемента, а не списка.

**Массив — это `IArrayType`, а не `IDeclaredType`.** `Unlift() as IDeclaredType` на `Vector2[]` даёт
`null`, поэтому `IsSequence` считал массив скаляром (ключ `vertices:` вставлялся с висящим пробелом),
а `UnwrapType` не раскрывал его вовсе — поля внутри элементов любого `X[]` не резолвились. На
`Vector2` это не проявлялось только потому, что у него нет датафилдов. Обе проверки теперь начинают
с массива: `IsSequence` отвечает `true`, `UnwrapType` берёт `ElementType` и продолжает цикл.

**Носитель тега — ближайший узел вверх, а не первый попавшийся элемент списка.** Поиск
`getParentOfType(position, YAMLSequenceItem)` находил внешний `- type: Fixtures`, то есть тег
`shape:` спрашивался у компонента: в логе это видно как `Tag types for '- type: Fixtures': 1`.
Берётся первый родитель, оказавшийся `YAMLKeyValue` **или** `YAMLSequenceItem`, что бы ни
встретилось раньше.

**Флаг `polymorphic` существует, чтобы не искать наследников у каждого `float`.** На пустом
значении (`shape: ` без тега) предложить `!type:X` можно только зная, что тип поля не
инстанцируется, — а проверить это запросом наследников нельзя: `FindInheritors` идёт по всему
решению, и цена одинакова для `IPhysShape` и для `float`. Хост считает признак сам
(`IInterface` либо абстрактный `IClass` после `Unwrap`) и везёт его в модели; фронтенд спрашивает
список только там, где флаг взведён.

**Шесть имён в теге не являются классами вовсе.** `ReflectionManager.TryLooseGetType` отвечает
`Byte`, `Bool`, `Double`, `SByte`, `Single`, `String` из таблицы примитивов **до** перебора сборок,
поэтому `!type:Bool` в `blackboard:` у HTN — легальный тег, но класса с таким именем нет ни в
чекауте, ни в метаданных (CLR-имя — `Boolean`). Поиск наследников по ним не находит ничего, и без
отдельной ветки аннотатор покрасил бы 157 валидных значений. Замер `MeasureTags` считает их
отдельной строкой по той же причине. Мёртвая ссылка в контенте ровно одна —
`!type:WashCreamPieReaction` в `_Wega/.../Xenobiology/base.yml`. Второй кандидат
(`StashActiveHandOperator`) оказался ошибкой самого замера: тег стоял в закомментированной строке
`#      operator: !type:...`, а замер читал `.yml` как обычный текст. Аннотатор такого не видит —
PSI внутри комментария ключа не строит, — поэтому комментарии вырезаются до поиска тегов.

**Тег лежит в PSI в двух разных местах, и спрашивать надо оба.** У `shape: !type:X` с блочным
маппингом токен `TAG` — ребёнок `YAMLKeyValue`, а в форме, под которую написан `YAMLValue.getTag()`,
он первый ребёнок значения. `originAt` обходит все узлы подряд и находил тег всегда, а `unknownTag`
спрашивал только сам ключ — поэтому сломанный тег не подсвечивался, хотя в логе были видны запросы
полей с корнем из этого же тега. Поиск сведён в `RobustYamlContext.tagToken`, им же пользуются
аннотатор (диапазон подсветки) и `ChangeTypeTagFix`.

**Ctrl+клик по тегу — ссылка на носителе, а не на токене.** Референс висит на `YAMLKeyValue` и
`YAMLSequenceItem`, а `rangeInElement` указывает внутрь тега, начинаясь после `!type:`: иначе клик
по префиксу тоже вёл бы к переходу, а подсвеченной ссылкой читался бы весь тег. Цель — файл класса
из `RobustDataFields.declaringFiles`, тот же индекс, что даёт базы и поля.

**Тег правится через документ, а не манипулятором.** `!type:PolygonShape` — это один лексический
токен `YAMLTokenTypes.TAG`, а не `YAMLScalar`: `ElementManipulators.getManipulator` отдаёт по нему
`null`, и quick fix молча ничего не сделал бы. Плюс менять надо часть токена — префикс `!type:`
обязан уцелеть, а про content ranges тега никто не знает. `ChangeTypeTagFix` держит носителя тега
(ключ или элемент списка), а не сам токен: между построением фикса и его применением PSI
перестраивается, и токен становится невалидным.

**Обрыв поиска по лимиту нельзя отдавать как готовый список.** `FindExecution.Stop` на 500-м
наследнике даёт неполный ответ, и валидация назвала бы верный тег ошибкой — то есть предохранитель
сам стал бы источником ложных срабатываний. Поэтому при достижении лимита возвращается
`resolved=true` с пустым списком: пустой список означает «здесь ничего не известно», проверка
молчит, автокомплит не предлагает. Ровно этим же пустой ответ отличается от `resolved=false`,
который не кэшируется вовсе.

**Логи плагина в песочнице включаются `idea.log.debug.categories`.** `logger.debug` по умолчанию
не пишется, и диагностика обработчиков редактора превращается в гадание: `tasks.runIde` передаёт
`-Didea.log.debug.categories=#com.jetbrains.rider.plugins.robustyaml`, после чего в `idea.log`
появляются строки уровня `FINE`. Ими и была найдена причина обоих багов отступа и тега — за один
прогон вместо трёх гипотез.

## Правило: проверять API по дистрибутиву

Сигнатуры не угадывать. Дистрибутив лежит в
`~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.rider/riderRD/2026.2/*/riderRD-2026.2.zip`,
нужный jar извлекается `unzip`, дальше `javap -c -p -cp <jar> <класс>`. Так были найдены причина
молчания демона, требование write action у `fireAdditionalLibraryChanged` и механика inherit-галочки.

**Find Usages ищет не то слово, поэтому обход свой.** `ReferencesSearch` берёт строку у
`PsiNamedElement.getName()`, а имя `YAMLKeyValue` — это ключ: по `id: Crowbar` платформа искала бы
слово `id`. Поэтому `processElementUsages` реализован сам, а `YAMLFindUsagesProvider.canFindUsagesFor`
(`PsiNamedElement || YAMLScalar`) и так пропускает наш элемент — Alt+F7 предлагался и раньше, просто
не находил ничего.

**До фабрики поиска дело доходит не всегда — сначала нужен «именованный элемент».** С кареткой
внутри `id: BaseMobMutant` ссылки нет, а именованным элементом платформа значение не считает,
поэтому `FindUsagesAction` отвечал «Cannot search for usages from this location», не спросив ни
одной `findUsagesHandlerFactory`: цель берётся из `DataContext`
(`ResolverKt.allTargets` → `SearchTargetVariantsDataRuleKt.targetVariants`), а кладёт её туда
`TargetElementUtil`. Лечится своим `TargetElementEvaluatorEx2` (EP `targetElementEvaluator`,
`language="yaml"`): `getNamedElement` отдаёт `YAMLKeyValue`, когда каретка стоит **внутри значения**
объявления id. На самом ключе этого не нужно — `YAMLKeyValue` и так `PsiNamedElement`, и фабрика
получает его обычным путём, а имя для поиска всё равно берётся из значения.

**Каретка чаще стоит на ссылке, чем на объявлении, а платформа отдаёт ключ.** `TargetElementUtil`
поднимается до первого `PsiNamedElement` и находит `YAMLKeyValue` — до ссылки под кареткой он не
доходит, поэтому Alt+F7 на `parent: BaseAction` искал слово `parent`, а на ключе локализации не
находил ничего. Цель поиска поэтому не элемент, а пара «текст плюс вид ссылки»: с объявления и со
ссылки ищется одно и то же. `type:` и вложенный `id:` отсекаются явно — первый именует вид или
компонент, второй в 3% случаев это имя анимации. Ключ локализации опознаётся индексом `.ftl`, как
и в ховере, а не по форме: 111 ключей из 83693 записаны PascalCase (`JobCaptain`) и от id прототипа
неотличимы.

**Область поиска задаёт пользователь, а не плагин.** `FindUsagesManager.findUsages` сразу после
получения options делает `putfield FindUsagesOptions.searchScope`, подставляя
`FindUsagesSettings.getDefaultScopeName()`, поэтому `getFindUsagesHandler().getFindUsagesOptions()`
на область не влияет вовсе. Прототипы подключены `SyntheticLibrary` и в `Solution` не входят —
первый поиск отвечает `270 usages are out of scope 'Solution'`; выбор `All Places` в Find Options
запоминается в настройках IDE, и дальше Alt+F7 работает сразу.

**Кандидатов даёт свой индекс, а не word index.** `IdIndexFilter` пускает файл, если для его типа
есть индексер, а `IdTableBuilding.getFileTypeIndexer` для любого `LanguageFileType` в худшем случае
падает на `SimpleWordsScanner` — то есть `.yml` и `.ftl` в word index есть. Не годится он по другой
причине: сканер режет текст по не-буквенно-цифровым, и `comp-thief-target` лежит там тремя словами
`comp`, `thief`, `target`, целиком не находится никогда. `RobustYamlValueIndex` индексирует значения
двух форм — id прототипа и ключ локализации; точность даёт последующий резолв ссылки, поэтому
лишний ключ стоит лишнего файла, но не ложного результата. Замер `MeasureUsages`: 3100 файлов за
484 мс, 41490 значений, 102241 пара, медиана — один файл на значение, 58% значений живут в
единственном файле; худший id — `BaseItem` в 354 файлах.

**Якорь стоит между двоеточием и значением, и построчный regex терял строку целиком.**
`tooltip: &TextOpenClose door-remote-open-close-text` — `&TextOpenClose` читался как значение,
после него оставался текст, и совпадения не было вовсе: ключ не находился ни в одном поиске.
Таких значений в контенте 46 в 15 файлах, и на `id`/`parent`/`type` их нет ни одного, поэтому
индексы объявлений не пострадали. PSI при этом ведёт себя правильно — якорь в `textValue` скаляра
не входит (тест `testAnchorIsNotPartOfTheValue`), так что после правки regex обход находит ссылку
обычным сравнением текста.

**Алиас — не сущность контента, а способ не писать строку дважды.** Прототипы читает не `YamlStream`,
а собственный `DataNodeParser` поверх событийного парсера YamlDotNet. Якорь кладёт в словарь документа
сам узел (`state.Anchors[ev.Anchor] = node`), алиас возвращает **тот же экземпляр**, и после
`ResolveAliases(state)` понятия «алиас» не существует вовсе: у `EntityPrototype.ID` лежит строка.
Отсюда четыре следствия. Область — документ, а не файл (`DocumentState` создаётся в `ParseDocument`),
но файлов прототипов с `---` в ss14-wega ноль, и алиасов с якорем в другом файле тоже ноль. Ссылка
вперёд **разрешена** — это отступление от спеки: при промахе кладётся `DataNodeAlias`, который
доразрешается вторым проходом, поэтому карту якорей нельзя строить на лету сверху вниз. Дубль якоря
и неразрешимый алиас — `DataParseException`, то есть файл не загрузится. И второй проход чинит только
значения маппинга и элементы списка: алиас ключом маппинга со ссылкой вперёд так и останется
заглушкой (в контенте таких ноль, `<<:` тоже ноль).

**Тринадцать прототипов называют себя алиасом, и это была не дыра в поиске, а ложная ошибка.**
`boardPrototype: &BackgammonBoard BackgammonBoardTabletop` объявляет значение, а четырьмя строками
ниже `id: *BackgammonBoard` объявляет прототип с этим id. Регекс индекса брал значение как
`"?([^\s"#]+)"?`, под которое звёздочка подходит, поэтому в индекс попадал мусорный ключ
`*BackgammonBoard`, а настоящего `BackgammonBoardTabletop` там не было. Поля `boardPrototype`,
`prototypePieceWhite` и соседние объявлены `EntProtoId` либо `PrototypeIdSerializer<EntityPrototype>`,
то есть проверяются типизированной веткой, и `check` печатал «Unknown entity prototype» ровно на тех
строках, где значение и объявлено. Плюс 13 мусорных id в автокомплите и Goto Symbol. Разбор алиасов
в контенте: 758 занимают значение целиком, из них 419 смотрят на структурный якорь, 339 на скалярный,
а из скалярных ищутся вообще только 19 (`id` 13, `tooltip` 3, `graph` 3) — остальное пути (219
`sprite:`), цвета (90) и строки. То есть выигрыш не в 759 ссылках, как обещала дорожная карта,
а в тринадцати ложных ошибках.

**Offset у id через алиас кладётся на алиас, а запись rename переносится на якорь.** Выбор не
свободный: каждый из двух вариантов ломает ровно одну операцию. Offset на значении якоря дал бы
верный rename, но Ctrl+клик по единственной ссылке прыгал бы сам в себя — ссылка и есть строка
якоря. Offset на алиасе даёт верный переход (`findElementAt` попадает в токен алиаса, а
`getParentOfType` поднимает до `YAMLKeyValue` объявления), но `renameElement` пишет через
`(keyValue.value as? YAMLScalar)`, а алиас скаляром не является — `YAMLScalar` и `YAMLAlias` оба
наследуют `YAMLValue`, но это разные ветки. Приведение дало бы `null`, объявление осталось бы
нетронутым, а ссылки переписались бы: битая ссылка на ровном месте. Дотянуться до текста тоже нечем,
манипуляторов в YAML ровно два (`YAMLScalarImpl` и `YAMLKeyValue`). Поэтому offset на алиасе, а
`resolvedScalar` переносит запись на значение якоря — одна правка, и все алиасы едут за ней сами,
как у движка.

**Алиасы отдаются в Find Usages как использования, но не как ссылки.** Текст живёт у якоря, туда же
пишет rename, и алиасу правка не нужна — переписав его, мы заменили бы связь литеральной копией.
Поэтому `processReferences` (общий с rename) их не видит, а `processAliasUsages` зовётся только из
хендлера поиска. Объявление (`id: *X`) из результатов исключается так же, как исключается
написанное текстом. Индекс значений трогать не пришлось: якорь всегда в том же файле, значит файл
и так попадает в кандидаты по искомому тексту.

**Якорь резолвится своим кодом, а не стоковой `YAMLAliasReference`.** Две причины. Стоковый резолв
идёт через `YAMLLocalResolveUtil.getResolveAliasMap` → `CachedValuesManager`, а тот в
`ParsingTestCase` падает с NPE в `PsiCachedValue.isVeryPhysical`: мок-проект не регистрирует
`InjectedLanguageManager`, и заглушка на него — это 18 абстрактных методов. Вторая: своя версия
формулирует правило движка, а не спеки, — якорь ищется по всему файлу, поэтому алиас выше своего
якоря тоже резолвится. Карта якорей отдаётся целиком (`anchoredScalars`), а не ищется на каждый
алиас: в `identification_cards.yml` 218 алиасов, и обход дерева на каждого окупался бы 218 раз.

**Категорию по значению не опознать — `Debug` объявлен дважды.** Есть `entityCategory Debug`
в `EnginePrototypes` и `storeCategory Debug` в `Store/categories.yml`, поэтому правило «id объявлен
как категория» показало бы попап категории сущностей на ссылке из магазина. Вид берётся у бэкенда
(`categories` у `EntityPrototype` — это `HashSet<ProtoId<EntityCategoryPrototype>>?`, то есть
`prototypeKind = entityCategory`), ровно как в валидации; проверка по индексу остаётся впереди
только как дешёвое необходимое условие, чтобы не строить таргет на каждый скаляр под курсором.
Ждать бэкенд здесь можно: ховер считается вне потока демона, `asyncDocumentation` для того и есть.
Ключ `categories:` при этом принадлежит четырём видам сразу — `entity` 1140 значений, `listing` 402,
`latheRecipe` 245, `antagSpecifier` 3, — так что по имени ключа вид не выводится в принципе.

**Показывается только то, что не по умолчанию.** `inheritable` у `EntityCategoryPrototype` равен
`true`, пока не сказано иное, и строка «Inheritable: yes» висела бы на каждом попапе; `hideSpawnMenu`
наоборот, `false` по умолчанию, и именно он объясняет, почему прототип пропал из панели спавна —
у самой частой категории (998 значений из 1140) взведён как раз он. Булевы читаются как у движка:
`BooleanSerializer` — это `bool.Parse`, регистр не важен, поэтому `True` и `FALSE` разбираются, а
`yes` нет. Пустая секция не ошибка: у восьми категорий из двенадцати `description` не задан вовсе —
при отсутствии перевода печатается сам `LocId`. Культура подписывается, лишь когда переводов больше
одного, иначе подпись на каждой строке была бы шумом. Переводы движковых категорий лежат в
`RobustToolbox/Resources/Locale`, а не в контенте: поиск только по `Resources/Locale` показывает
`entity-category-name-hide` объявленным в одной культуре, тогда как на деле их две.

**Ключ `name:` у сущности почти всегда мёртв, и показывать надо не его.** Из 14083 сущностей 9712
несут `name:`, но у 9577 из них есть сообщение `ent-<id>`, которое этот ключ перекрывает, — то есть
попап, читающий YAML, показывал бы девелоперскую заглушку ровно там, где выглядит правдоподобно.
Ещё 4371 сущность своего `name:` не имеет вовсе и берёт его у предка. Правило — `CalcEntityLoc`:
сообщение, затем `SetName`/`SetDesc`/`SetSuffix`, и всё это по цепочке родителей; при пустом
результате имя становится пустой строкой, а не id (таких 386).

**Перевод не наследуется, а YAML наследуется — и в цикле движка это одна строка.** Внутри обхода
предков стоит `var locId = prototype?.CustomLocalizationID ?? $"ent-{prototypeId}"`, где
`prototypeId` — **аргумент метода**, а не прототип текущей итерации: `ent-<предок>` не спрашивается
ни разу. Дотянуться до чужого ключа можно только через `localizationId`, а он обычный датафилд и
приезжает наследнику вместе с остальными (в контенте не встречается ни разу). Написанный текст,
наоборот, наследуется — но через `PushInheritance`, а не через обход: `EnumerateParents` роняет
абстрактных предков (`if (!TryIndex(...)) continue; // Abstract parent?`), а базы вроде `BaseItem`
почти всегда абстрактны. Поэтому наш обход абстрактных предков намеренно **не** пропускает.

**Треть текстов сущностей — это `{ ent-X }`, а не текст.** Из 32 тысяч значений и атрибутов 11593
целиком состоят из ссылки на другую запись (`ent-Crowbar = { ent-BaseCrowbar }`), и без раскрытия
попап показывал бы фигурные скобки вместо имени. Раскрытие идёт внутри одной культуры, как и бандл
Fluent: `ru-RU` в `en-US` не заглядывает. Глубина цепочек больше, чем кажется — лимит в 4 прыжка
оставлял скобки в десяти попапах, `ent-ClothingHeadHeadHatBaseFlipped` доходит до `ent-BaseItem`
за пять, — поэтому лимит 16, и он же остаётся защитой от цикла. Ссылку, которой не во что
раскрыться, оставляем как есть: движок делает ровно то же и пишет ошибку в лог; таких в контенте
16, и три из них — атрибут `.suffix` у сообщения, где его нет.

**Перевод перекрывает YAML даже тогда, когда раскрывается в пустоту.** `{ "" }` — это способ
Fluent погасить суффикс или описание, доставшееся от базы: 3310 текстов раскрываются в пустую
строку. Если считать «пусто» за «перевода нет» и падать обратно на YAML, попап вернёт на экран
ровно тот текст, который переводчик убрал. Поэтому запасной вариант включается по наличию поля
в записи, а пустота отсекается только на выводе.

**Разметку рендерят только зарегистрированные теги, остальные скобки — проза.** Имена и описания
несут rich text: 96 строк YAML и 106 строк `.ftl`, из тегов почти всё — `[color]` (386 вхождений),
дальше `[head]` 20, `[bold]` 12, `[italic]` 4. Правило «квадратные скобки — это тег» съело бы текст:
`[redacted]` (3) и `[folded]` (1) не зарегистрированы ни в движке (`MarkupTagManager`: `color`,
`bold`, `italic`, `bolditalic`, `head`, `bullet`, `font`, `cmdlink`), ни в контенте (`mono`,
`scramble`, `tooltip`, `protodata`, `keybind`, `textlink`) — это обычные слова. Незакрытый тег
закрывается на выходе, иначе цвет протёк бы на остальной попап; закрывающий без открывающего
остаётся текстом. Значение `[color=...]` пропускается в CSS только буквенно-цифровым: имена
`Color.DefaultColors` совпадают с именами CSS, а всё прочее — дыра для инъекции в HTML попапа.

**Совпадение с id — ещё не ссылка, и без бэкенда попап строится только по пяти ключам.** В контенте
47230 значений стоят под каким-то другим ключом и при этом буквально совпадают с объявленным id:
`state:` 1126 из 18479, `suffix:` 737, `time:` 889. Показывать по ним карточку сущности — значит
врать на ровном месте. При этом ключи, которые ссылку действительно несут (`graph`, `result`,
`material`, `ReagentId`, `collection`, `shader`, `tool`), все до одного типизированы `ProtoId<X>`,
то есть вид приходит от бэкенда и запасной вариант им не нужен. Поэтому «вид по единственному
объявлению» разрешён только там, где ключ означает id и без солюшена — `parent`, `proto`,
`prototype`, `entity`, `id`. Цена: на холодном бэкенде попап по `categories:` не появится, как и
по любому другому типизированному ключу.

**Категории наследуются, но не все.** `UpdateCategories` подмешивает категории предков с условием
`if (category.Inheritable)`, а единственная категория контента с `inheritable: false` — это
`HideSpawnMenu`, стоящая на 998 значениях из 1140. Наследовать её вслепую значит заявить, что
тысячи сущностей пропали из панели спавна. Категории, которые приносит компонент через
`[EntityCategory]`, не учитываются вовсе — обратного запроса к атрибуту отсюда нет.

**`UnwrapType` при неудаче отдаёт саму коллекцию, и вид пропадает молча.**
`GetElementTypesForGenericEnumerable` внутри делает `?? EmptyList<IType>.InstanceList`, поэтому
«кэш не прогрет» и «это не коллекция» на выходе неразличимы: у `HashSet<ProtoId<X>>` возвращается
`HashSet`, `KindOfType` видит его короткое имя вместо `ProtoId` и отдаёт null. Один и тот же
`EntityPrototype` в соседних запусках дал `[categories=entityCategory, parent=entity]` и
`[parent=entity]` при неизменных исходниках: 12 полей, `resolved`, тип напечатан целиком — нет
только вида. Проверка на `???` такой ответ пропускает, он кэшируется, и на всю сессию умирают
автокомплит id этого вида и типизированная проверка. `parent` уцелел потому, что вид у него берётся
из аргумента сериализатора и раскрытия коллекции не требует.

Судить приходится по presentable name — единственному, про что здесь точно известно, что оно
построено: имя говорит `ProtoId`, а вида нет, значит ответ неполон, и он уходит как `Unbuilt` тем же
путём, что и непостроенный тип. Ложных срабатываний правило не даёт: каждый из 191 типа, встречаемого
в `ProtoId<X>` в контенте, несёт `[Prototype]` (двадцать один кандидат «без атрибута» оказался
восемнадцатью `EntProtoId<T>`, где параметр ограничивает компонент, параметром дженерика `T` и тремя
прототипами, у которых атрибут записан как `[Prototype(2)]`, `[Prototype("ttsVoice")]` и
`[Prototype, PublicAPI]`). Подстроки `ProtoId` достаточно на оба случая: `EntProtoId` её содержит.

**`element.project` у `YAMLKeyValue` требует read action.** `processElementUsages` зовётся без него
(read берётся на каждый файл внутри), а `PsiElementBase.getProject` идёт через `getManager` и
`SharedImplUtil.getParent`, то есть поднимается по AST и роняет `ThreadingAssertions`. Поиск умирал
до первого файла, Alt+F7 отвечал «Nothing found», а исключение лежало в логе как ошибка плагина.
Симптом при этом выглядел избирательным: с каретки на **значении** цель — заглушка объявления из
`.ftl`, у неё `getProject` идёт через `PsiFile` и read lock не спрашивает, поэтому там всё работало.
Project берётся один раз при создании хендлера, под `ReadAction`.

**Объявление сообщения показывается среди использований, а объявление id — нет.** Для прототипа
каретка обычно и стоит на объявлении, оно одно, и показывать его незачем. У сообщения всё наоборот:
объявлений столько, сколько культур (27636 из 56269 объявлены больше чем в одной), и «где написан
этот текст» — как раз то, что спрашивают у Alt+F7. Без них поиск по `heretic-know-ash-name`
отвечал единственным значением YAML, с которого его и позвали.

**`processElementUsages` зовётся без read action, и падение глушится.** Поиск идёт фоновой задачей,
а платформенные searcher'ы берут read action внутри себя — своему обходу его никто не даст. Наружу
это выглядело как «Alt+F7 не делает ничего»: в логе лежало
`Read access is allowed from inside read-action only` от `#c.i.o.p.Task`, помеченное как
`Suppressed a frequent exception`. Read action берётся **на файл**, а не один на весь поиск: удержание
его над тысячами файлов заблокировало бы любую запись в IDE, а между файлами стоит
`ProgressManager.checkCanceled`, иначе Esc не работает.

**Найденное ещё надо пустить через scope, а прототипы вне решения.** `Nothing found in 'Solution'`
при честно найденных ссылках — это фильтр `FindUsagesOptions.searchScope`, и он же прямо сообщает
`270 usages are out of scope 'Solution'`. Прототипы подключены `SyntheticLibrary` и в solution scope
не входят, поэтому handler переопределяет `getFindUsagesOptions` и ставит
`ProjectScope.getAllScope`. Тот же корень, что у «демон не запускается вне content root» и у
«индекс опрашивается в `getAllScope`»: почти каждая платформенная умолчательная область для этого
плагина — неверная.

**EP `renamePsiElementProcessor` объявлен базой, а платформа кастует к наследнику.** В
`RefactoringExtensionPoints.xml` стоит `interface="...RenamePsiElementProcessorBase"`, но код
рефакторинга приводит расширение к `RenamePsiElementProcessor`, и наследование от базы даёт
`ClassCastException` — в логе он глушится как `Suppressed a frequent exception`, а наружу выглядит
как «Shift+F6 не открывает поле ввода»: `canProcessElement` в логе есть, дальше тишина.

**Ссылки при переименовании платформа не трогает — их переписывает сам процессор.** Диалог
«Selected element is used from non-project files. These usages won't be renamed» говорит буквально
то, что делает: usages в файлах вне проекта пропускаются, а прототипы подключены `SyntheticLibrary`.
В логе при этом всё выглядит исправно — `Renaming 'X': N references`, — но в файлах меняется одно
объявление. Поэтому `renameElement` проходит по `usages` сам и зовёт `handleElementRename` у каждой
`PrototypeIdReference`; повторная запись уже верного текста ничего не портит.

**Undo у рефакторинга по нескольким файлам нужно объявить глобальным.** Иначе Ctrl+Z откатывает
только текущий документ, а остальные файлы остаются переписанными. Команда помечается
`CommandProcessor.markCurrentCommandAsGlobal(project)`, а затронутые файлы отдаются через
`addAffectedFiles` — сами по себе они в команду не попадают, потому что правки идут через
манипуляторы, а не через редактор.

**Имя элемента в UI рефакторинга берётся у `PsiNamedElement`, а у `YAMLKeyValue` это ключ.** Диалог
предлагал «Rename key-value 'id'» и подставлял `id` в поле ввода, то есть звал пользователя
переименовать ключ объявления. Правится `elementDescriptionProvider`: для объявления id
`UsageViewShortNameLocation` отдаёт значение, `UsageViewTypeLocation` — «prototype id». Тем же
чинится заголовок окна Find Usages.

Одними объявлениями это не закрывается: с кареткой на `tooltip: door-remote-open-close-text` окно
называлось «tooltip in All Places», а узел цели — «Key-value». Имя ключа всплывает на любой стороне
ссылки, поэтому описание строится от `searchedTarget`, а не от проверки на объявление, и тип берётся
из того же ответа — «localization message» либо «prototype id».

**Rename объявления id — это правка значения, а не имени.** `YAMLKeyValue.setName` переписывает
ключ, то есть стоковое переименование превратило бы `id: Crowbar` в `Crowbar: Crowbar`; поэтому
`renameElement` пишет новое значение через манипулятор скаляра, а ссылки платформа переписывает
сама, получив их из `findReferences` — того же обхода, что и Find Usages. Каретка чаще стоит на
ссылке, и `substituteElementToRename` подменяет её объявлением; при нескольких объявлениях одного
id (разные виды — `Syndicate` их четыре) рефакторинг отказывается, потому что выбрать вид за
пользователя нельзя, а переименование затронуло бы ссылки всех видов сразу.

**`isReferenceTo` обязан сравнивать не с `resolve()`.** У `PrototypeIdReference` резолв отдаёт
**первое** объявление, а `Syndicate` объявлен четырежды — усечение до одного теряло бы использования
остальных трёх; поэтому цель опознаётся по тому, что она объявляет (`isPrototypeIdDeclaration` плюс
совпадение значения). У `LocalizationIdReference` причина другая: цель — `FakePsiElement`, который
строится заново на каждый резолв, и сравнение по идентичности не совпадает никогда.

**Ключ локализации живёт в четырёх местах, и три из них — файлы без парсера.** Замер
`MeasureLocaleRename` по ss14-wega: из 56269 сообщений 10875 используются из прототипов, **5657 из
строковых литералов `.cs`**, 1936 — ссылками `{ key }` из других сообщений, 13951 строит движок как
`ent-<id>`. Rename, знающий только YAML, порвал бы больше ключей, чем починил, поэтому
`RobustLocaleUsageIndex` индексирует литералы `.cs` и placeable-ссылки `.ftl`, а правки идут через
документ. Ещё одна причина обходить всё: 27636 сообщений объявлены больше чем в одной культуре
(`shell-command-success` — в `ru-RU` и `en-US`), и правка одного объявления расщепила бы сообщение
надвое. Самый широкий rename в контенте — `ent-MarkerBase`, 382 места.

**Литералы `.cs` нельзя брать regex — нужен посимвольный автомат.** `// see "comp-thief-target"` —
это проза, а не использование, и rename переписал бы английский текст. Сканер тот же по устройству,
что `classScopes` в `RobustDataFieldIndex`, плюс состояние для raw strings: без него три кавычки
`"""` читаются как пустой литерал и открывающая кавычка, и остаток файла сканируется как строка —
57 файлов ss14-wega начинаются именно так. Сторож замера сравнивает автомат с наивным regex по тому
же файлу: автомат обязан находить **меньше** (комментарии), но никогда больше; непустая разница
роняет прогон.

**Ссылка `{ ent-MarkerBase }` пишется с заглавными, и строчный regex терял 1936 ссылок.** Ключ вида
`ent-<id>` несёт id прототипа как он написан в YAML, поэтому форма ключа — `[A-Za-z][\w-]*` с
обязательным дефисом, а не kebab строчными. Дефис обязателен ради размера индекса: 6052 литерала
из 12748 файлов `.cs` против всех строковых литералов чекаута иначе. Закрывающая `}` или `.` —
часть шаблона: без неё селектор `{ $count ->` и вызов `{ NUMBER($x) }` читаются ссылками на
несуществующие сообщения.

**Shift+F6 внутри `.ftl` требует `renameHandler`, а не процессора.** Цель платформа ищет подъёмом
по PSI, а в plain text файле подниматься не от чего: каретка попадает на весь файл, и рефакторинг
отказывает, не спросив ни одного процессора. `RenameHandler` (EP `com.intellij.renameHandler`)
получает `DataContext` с редактором, поэтому ключ читается по offset каретки — `RobustLocalization.idAt`
разбирает строку сам: объявление открывает строку и кончается на `=`, ссылка стоит внутри placeable.
Дальше зовётся обычный `PsiElementRenameHandler.rename` с найденным объявлением.

**Без `renameInputValidator` диалог не принял бы ни одного ключа.** `RenameUtil.isValidName` сначала
спрашивает `RenameInputValidatorRegistry`, и **только при его отсутствии** уходит в
`LanguageNamesValidation.isIdentifier` для языка элемента. У `FakePsiElement` язык — `Language.ANY`,
файл plain text, и дефолтный валидатор назвал бы `comp-thief-target` не-идентификатором из-за дефисов:
кнопка Rename осталась бы серой. Паттерн валидатора обязан быть по классу
(`PlatformPatterns.psiElement(MessageDeclaration::class.java)`), иначе перехватывалась бы валидация
имён во всей IDE.

**`ent-*` не запрещается, а объявляется конфликтом.** Имя строит `LocalizationManager.Entity` из id
прототипа, ни один файл его не пишет, и переименование сообщения только оторвало бы его от сущности —
переименовывать надо прототип. Отказ здесь неуместен: `findExistingNameConflicts` показывает, что
именно произойдёт, и оставляет решение пользователю. Таких сообщений 13951, у 12038 из них нет ни
одной ссылки.

**Атрибут Fluent обязан пережить rename.** Ссылка `LocalizationIdReference` покрывает всё значение,
а переименовывается только сообщение: `foo.desc` при записи диапазона целиком потеряло бы `.desc`.
Таких значений в контенте 29. Ровно поэтому же в placeable захватывается только id, а точка
остаётся снаружи шаблона.

**`psi.referenceContributor` для `.ftl` бесполезен, и это видно по байткоду.** Ссылки спрашивают
только у элемента, реализующего `ContributedReferenceHost`: `LeafPsiElement.getReferences` уходит в
`SharedPsiElementImplUtil.getReferences`, а тот зовёт `PsiElement.getReference()` и до
`ReferenceProvidersRegistry` не доходит вовсе. `PsiPlainTextImpl` — обычный `OwnBufferLeafPsiElement`
без этого интерфейса, поэтому провайдер, зарегистрированный на plain text, никто не позовёт.
Взамен берутся две точки, которым offset каретки передаётся явно: `gotoDeclarationHandler`
(`getGotoDeclarationTargets(element, offset, editor)`) для Ctrl+клика и `usageTargetProvider`
(`getTargets(editor, file)`) для Alt+F7. Второй отдаёт `PsiElement2UsageTargetAdapter` от заглушки
объявления, после чего работает обычная фабрика поиска. Подчёркивание ссылки под Ctrl при этом
появляется само: `CtrlMouseHandler` спрашивает тот же `gotoDeclarationHandler`, отдельных ссылок
для него заводить не нужно.

**Ctrl+клик по объявлению ведёт в другие культуры, а не никуда.** Цель под кареткой из списка
исключается, иначе переход был бы в самого себя; остаются те же сообщения в остальных культурах —
27636 из 56269 объявлены больше чем в одной, и «как это написано по-английски» спрашивают чаще, чем
кажется. Со ссылки же целями идут все объявления сразу.

**Правки текстовых файлов идут с конца.** Одна и та же строка `.ftl` может нести и объявление, и
ссылку, а в `.cs` ключ повторяется по нескольку раз; диапазоны ссылок посчитаны заранее, поэтому
любая правка слева сдвинула бы все последующие. Правки группируются по файлу и применяются по
убыванию offset, а перед каждой проверяется, что по этому месту всё ещё стоит старый id.

## Правило: PSI и редактор — тестами, контент — замерами

Замеры стерегут то, что зависит от данных: индексы, правила чтения значений, ссылки, локализацию.
Всё, что зависит от **структуры** — где в дереве лежит тег, кто носитель, что считается сегментом
пути, куда встаёт дефис, — замером не проверяется вовсе, и именно там копились промахи: тег,
прочитанный не у того узла; сегмент, посчитанный за элемент списка; носитель, найденный первым
попавшимся `getParentOfType`. Такие вещи закрываются тестами: `./gradlew :test`, 83 случая,
2 секунды.

**`BasePlatformTestCase` для Rider-плагина не годится.** Light-проект поднимает Rider целиком, и тот
падает ещё на регистрации компонентов: `SolutionHostExtensionsKt.getSolution` бросает
`solution can't be null`. Взят `ParsingTestCase("", "yml", YAMLParserDefinition())` — минимальное
окружение с одним парсером; `declarationAround`, `originAt`, `taggedType` это чистый обход PSI, им
не нужны ни проект, ни индексы, ни бэкенд. Одну заглушку зарегистрировать всё же приходится:
`ReadActionCache` — через него `YAMLScalarImpl.getTextValue` читает текст, а mock-приложение
сервисов не заводит, и без неё каждый тест падает с `getService(...) must not be null`.

Первый же прогон окупил затею: `testTagDoesNotApplyToItsOwnKey` показал `Expected <null> but was:
PolygonShape` — стоя на самом ключе `shape:`, его собственный тег становился корнем, то есть тип
поля искался внутри `PolygonShape`. Отсюда правило: тег описывает содержимое своего значения,
поэтому учитывается, только если обход пришёл вверх **через это значение** (`current.value === child`
у ключа и у элемента списка, для остальных узлов — всегда).

Документные обработчики тестируются без платформы вовсе: `sequenceIndent` и `ownerLine` объявлены
`internal` и принимают `Document`, а в тесте хватает `DocumentImpl("...")`.

## Правило: мерить индекс самим индексом, а не моделью на python

Модель эвристики на python врёт: она успела приписать `- type: DiseaseProtection # комментарий`
предыдущему компоненту и насчитать 536 «мёртвых» ключей вместо 45. Companion-функция индекса
вызывается рефлексией по `build/classes/kotlin/main`: classpath — распакованные jar дистрибутива
плюс `intellij.libraries.fastutil.jar` (иначе `NoClassDefFoundError` на `Hash$Strategy` из
`PluginId`), и обязательны `-Didea.home.path` с пустым `product-info.json` рядом, иначе `ID.create`
падает на `PathManager.getHomeDir`. Полный прогон по 12374 файлам — 3 секунды, дальше покрытие
считается по TSV-дампу.

Замеры живут в `tools/measure/` и запускаются одной командой (`./gradlew :compileKotlin` и затем
`tools/measure/run.sh ~/RiderProjects/ss14-wega`); classpath и фальшивый `idea.home.path` скрипт
собирает сам. `MeasureHoles` — сторож над эвристикой имён: пока он показывает `MISSING: 0`,
regex-индекс эквивалентен чтению `[RegisterComponent]` у каждого класса, и переходить на бэкенд
не за чем; непустой `MISSING` роняет прогон с кодом 1 и служит сигналом к переходу. Ожидаемые
значения на ss14-wega: 0 потерянных имён, 9 лишних (тестовые классы движка), из них 0 используются
в прототипах. `MeasureReferences` печатает то, что подсветит дерево, `MeasureMigrations` стережёт
разбор `migration.yml` и достижимость целей quick fix, `MeasureRequired` — инспекцию обязательных
полей. `MeasureLocaleRename` — второй сторож с ненулевым кодом возврата: он сверяет посимвольный
сканер литералов `.cs` с наивным regex по тем же файлам, и любой литерал, найденный сканером, но
не найденный regex, означает, что сканер потерял место и rename перепишет не литерал. `MeasureAliases` —
третий: он падает, если в индексе id завёлся ключ со звездой (`STARRED`) или если id, объявленный
через алиас, не вышел из индекса обратно (`MISSING`). Ожидаемые значения на ss14-wega: 758 алиасов,
25 скалярных якорей, 13 id через алиас, `MISSING: 0`, `STARRED: 0`, полный проход 350 мс.
`MeasureEntityLoc` — четвёртый: он собирает бандлы всех культур через `RobustLocalization.entryAt`
и раскрывает каждый текст сущности через `RobustLocalization.resolved`, а падает на `LEFTOVER` —
плейсбле, который остался в тексте, хотя та же культура объявляет и сообщение, и запрошенное поле.
Ожидаемые значения: 13961 запись `ent-*` (13960 значений, 13905 `.desc`, 4536 `.suffix`),
3310 текстов раскрываются в пустоту (`{ "" }`), 16 ссылок раскрывать не во что, `LEFTOVER: 0`,
полный проход 80 мс. Стдлиб Kotlin понадобился на компиляцию замеров именно здесь: `resolved`
принимает лямбду, то есть `Function1`.

`MeasureScalars` зовёт `RobustValidation.accepts`, то есть меряет саму отгружаемую валидацию значений,
а не её пересказ на Java; ради этого правила вынесены из `checkScalar` в чистую функцию от пары
(тип, текст). Имя ключа берётся в проверку, только если все `[DataField]` с этим именем во всём
чекауте объявляют один тип: тип по владельцу знает бэкенд, а замер — нет. Ровно этим объясняются
17 отвержений, которые он печатает и которых в IDE не бывает: `on`/`broken` и `delta` — ключи
словарей `spriteStateMap:` и `alertVisuals:`, `bar` — цвет в палитре, `step` — мёртвый ключ
`PointingArrow`. Именно этот замер и вскрыл историю с `drawdepth`: 398 отвержений на ровном месте.

Разбор атрибутов в замерах вынесен в `tools/measure/Cs.java` не для красоты: поиск назад до `}`
или `;` промахивается на `[Access(new[] { typeof(X) })]` и на `// … need Solution data;`, из-за чего
первая версия сторожа объявила `DisposalHolder` и `FitsInDispenser` незарегистрированными.
Комментарии и литералы забиваются пробелами с сохранением смещений, атрибуты собираются с
балансировкой скобок, а имя из `[ComponentProtoName("...")]` читается из исходного текста — в
забланкированном литерал уже пустой, и сторож начинал терять 10 имён на ровном месте.

## Дорожная карта

- [x] Аннотатор
- [x] Автокомплит имён компонентов
- [x] Go-to-definition: из `type: Sprite` в `SpriteComponent.cs`
- [x] Валидация имён компонентов с quick fix
- [x] Пути к ресурсам: Ctrl+клик, автокомплит (`FileReferenceSet`), красное на несуществующих
- [x] `state:` → PNG внутри `.rsi`
- [x] Виды прототипов: навигация в `*Prototype.cs`, автокомплит, валидация
- [x] Индекс id прототипов: Ctrl+клик по `parent:`/`proto:`, автокомплит, Goto Symbol
- [x] Автокомплит id по виду поля из бэкенда (обратный индекс `вид → id`)
- [x] Ховер с превью спрайта
- [x] Автокомплит датафилдов внутри компонента и прототипа
- [x] Инспекция неизвестных полей (weak warning, quick fix)
- [x] Валидация id-ссылок и дублей id
- [x] Прототипы в Solution Explorer своим корнем (без Show All Files)
- [x] Подсветка проблемных файлов в дереве (свой корень + `solutionExplorerCustomization`, без Wolf)
- [x] Hover с XML-doc summary компонента
- [x] Hover с XML-doc summary датафилда
- [x] Пикер цвета для `#rrggbb` (EP `com.intellij.colorProvider`)
- [x] Каркас rd: модель, C#-хост, клиент; тип датафилда в ховере
- [x] Валидация id-значений по виду прототипа из бэкенда (`ProtoId<T>`, `EntProtoId`, сериализаторы)
- [x] Валидация и автокомплит значений enum из бэкенда (`values`/`keyValues`)
- [x] Отступ последовательностей: Enter и `-` в прототипах пишут по стилю SS14
- [x] Валидация чисел и булевых значений из бэкенда
- [x] Кэш ответов бэкенда с инвалидацией по `.cs`
- [x] Бэкенд: цепочка наследования, дженерик-подстановка, `[IncludeDataField]`, прототипы
- [x] Типы вложенных ключей: спуск по пути с раскрытием коллекций и словарей
- [x] XML-doc вложенных ключей: summary с бэкенда, `<inheritdoc/>` раскрывает сам ReSharper
- [x] Асинхронный вызов бэкенда вместо `sync`
- [x] Quick fix по `migration.yml`: мёртвая ссылка → новый id из словаря переименований
- [x] Инспекция обязательных датафилдов с учётом наследования прототипов
- [x] Валидация цветов, векторов и углов; поля с `customTypeSerializer` из проверки исключены
- [x] Локализация: индекс `.ftl`, проверка `LocId`, автокомплит ключей, переход по ключу
- [x] Автокомплит имён цветов и значений `ConstantSerializer`
- [x] Ховер по ключу локализации с текстом сообщения
- [x] Quick fix’ы: недостающие обязательные поля и ближайший ключ локализации
- [x] Полиморфные теги `!type:`: поля, валидация и ховер внутри тегированного значения
- [x] Автокомплит типов после `!type:` и проверка тега по наследникам объявленного типа
- [x] Find Usages для id прототипов и ключей локализации (свой индекс значений и обход)
- [x] Rename для id прототипов: значение объявления, все ссылки, глобальный undo
- [x] Rename для ключей локализации: объявления всех культур, литералы `.cs`, ссылки `.ftl`, YAML
- [ ] Предупреждение о неиспользуемых сообщениях `.ftl` — замер `MeasureDeadLocale` показывает
      4039 кандидатов (7%); литералы и ссылки уже даёт `RobustLocaleUsageIndex`, осталось внести
      префиксы, собираемые в коде (124), префиксы `localizedDataset` (232) и `ent-*` от движка
- [x] Find Usages и Ctrl+клик внутри самого `.ftl` через `gotoDeclarationHandler` и `usageTargetProvider`
- [x] Алиасы YAML: id, объявленный алиасом, в индексе; rename через якорь; алиасы в использованиях
- [x] Ховер по id категории: локализованные `name`/`description`/`suffix` и не-умолчательные флаги
- [x] Ховер по id прототипа: имя, описание и суффикс сущности по правилу движка, категория, общий вид
- [ ] Замена эвристики имён на бэкенд — ждёт первого промаха, который покажет замер
