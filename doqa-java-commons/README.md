# doqa-java-commons - общее ядро JVM-адаптеров DoQA

`app.doqa:doqa-java-commons` - фреймворк-агностичное ядро, общее для всех JVM-адаптеров DoQA.
Слой между API-клиентом и фреймворк-специфичной обвязкой:

```
ваши тесты ──► doqa-junit5 (обвязка фреймворка) ──► doqa-java-commons (этот модуль) ──► doqa-client (HTTP/файлы)
```

Всё, чего касается пользовательский тестовый код, живёт здесь - рантайм-фасад `Doqa` и
аннотации `@Doqa*` - поэтому смена тестового фреймворка никогда не требует переписывать импорты.
Всё, что нужно автору адаптера, тоже здесь: дерево шагов, атрибуция, проекция результатов и
сессия репортинга - новый адаптер остаётся тонкой обвязкой своего фреймворка.

**Зависимости**: `app.doqa:doqa-client` (сам без зависимостей) и `org.aspectj:aspectjrt`
(рантайм аспекта `@Step`). Ничего из Allure, JUnit или Jackson в classpath хоста не протекает.

## Требования

- JDK 11 или новее.

## Установка

Тестовым проектам НЕ нужно зависеть от этого артефакта напрямую - подключайте адаптер своего
фреймворка (например, `app.doqa:doqa-junit5`), он принесёт ядро транзитивно. Прямая зависимость
нужна только при написании нового адаптера:

```xml
<dependency>
    <groupId>app.doqa</groupId>
    <artifactId>doqa-java-commons</artifactId>
    <version>0.1.1</version>
</dependency>
```

## Что внутри

### Пользовательский API (стабилен во всех JVM-адаптерах)

- **`app.doqa.Doqa`** - рантайм-фасад для тела теста: `step(...)` (вложенные, с таймингом,
  со значением, чекпоинт без тела, вариант с description), `addAttachments`/`addAttachment`
  (файлы или контент из памяти - байты/текст), `addParameter`, `addLink(s)`, `addMessage`,
  `addLabels`/`addLabel`/`addTags`, `addCaseIds`, `addCreateManualCase` (просит DoQA завести
  связанный ручной кейс), `addTitle`/`addDescription`/`addDisplayName`,
  `addExternalId` (стабильные id динамических тестов) и `captureContext()`/`runWith(...)` для
  переноса контекста теста в порождённые тестом потоки. Вне активного теста каждый вызов -
  безопасный no-op.
- **`app.doqa.annotations`** - аннотации разметки: `@DoqaId`, `@DoqaCaseIds`,
  `@DoqaCreateManualCase`, `@DoqaTitle`, `@DoqaDescription`, `@DoqaDisplayName`, `@DoqaLabels`,
  `@DoqaTags`, `@DoqaLink(s)`, `@DoqaNamespace`, `@DoqaClassName` и `@Step` для декларативных
  шагов (`{param}`-плейсхолдеры в заголовке раскрываются из аргументов метода).
- **`app.doqa.Labels`** - конвенция меток `key:value` и её стандартные ключи
  (`severity`, `owner`, `epic`, `feature`, `story`, `component`).
- **`app.doqa.aspects.DoqaStepAspect`** + бандлированный `META-INF/aop.xml` - AspectJ-аспект,
  превращающий `@Step`-методы в шаги отчёта. Вивится хостом через load-time weaving
  (`-javaagent:aspectjweaver.jar`) или build-time weaving; точные флаги - в README адаптера.
  При сужении области вивинга через `<include>` всегда сохраняйте
  `<include within="app.doqa.aspects.DoqaStepAspect"/>` - сам аспект обязан оставаться в области
  вивинга.

### Внутренний API для адаптеров (`app.doqa.core`)

Публичный для авторов адаптеров, не предназначен для тестового кода:

- **`DoqaSession`** - процесс-глобальная сессия репортинга поверх `doqa-client`. Резолвит sink
  (`api` / `files` / `auto` / `off`) и владеет семантикой доставки: батч-режим копит план и
  флашит чанками по `batchSize` (упавший чанк теряет только себя), realtime-режим стримит каждый
  top-level класс, как только завершился его контейнер - включая `@AfterAll`, файловый sink
  пишет Allure-совместимые результаты сразу. Дедуплицирует определения, предупреждает о
  коллизиях `externalId`, ставит best-effort shutdown-hook flush.
- **`DoqaContexts` / `RuntimeContext` / `StepNode` / `Steps`** - реестр контекстов per-test
  (ключ - уникальный id теста во фреймворке, плюс thread-local «текущий» указатель), собранное
  состояние и механика стека шагов. Удалённый контекст закрывается: опоздавшие записи из чужих
  потоков отбрасываются, а не портят уже отрепорченный тест.
- **`Attribution` / `SignatureHash`** - каскад externalId: явный `@DoqaId` →
  `[DOQA-123]`/`@DOQA:123` в display name → Allure `@AllureId` (читается рефлективно) →
  детерминированный сигнатурный хэш `<framework>:<sha1>`.
- **`MetaReader` / `AllureCompat`** - сбор метаданных из аннотаций, включая рефлективный
  миграционный мост Allure (`@Epic`/`@Feature`/`@Story`/`@Owner`/`@Severity` →
  `key:value`-метки, `@Link`/`@Issue`/`@TmsLink` → типизированные ссылки); нативные `@Doqa*`
  всегда выигрывают.
- **`ResultBuilder`** - проецирует завершённый тест в wire-модель (`AutotestDef` +
  `AutotestResult`): шаги по фазам, параметры, загрузка вложений (мемоизирована для общих
  класс-фикстур), подстановка `{param}`-плейсхолдеров, усечение сообщений/трейсов/параметров до
  настроенных лимитов и гейт селективного режима - тест вне выборки не загружает ничего.
- **`ClassFixtures`** - класс-фикстуры уровня `@BeforeAll`/`@AfterAll` с ключом по объявляющему
  классу и резолвом вдоль цепочки вложенных классов.
- **`PlanSelection`** - селективный запуск (режим 0) без фреймворочных типов: что вошло в ран
  (`active` / `allows`) и на каком месте плана (`planIndex`, с кэшем позиций по исполняющему
  классу). Любой сбой резолва - fail-open: тест остаётся в ране, порядок остаётся исходным.
- **`Placeholders`**, **`Outcomes`**, **`AttachmentRef`**, **`TestRef`**, **`Limits`** -
  вспомогательные части (шаблонный матчинг с кэшем скомпилированных паттернов, таксономия
  failed-vs-broken плюс печать стек-трейса, носители вложений, фреймворк-независимые координаты
  теста, усечение).

## Как написать адаптер поверх ядра

Обвязка фреймворка отвечает ровно за четыре вещи:

1. **Идентичность** - вызвать `AdapterRuntime.configure("<framework>", "<framework label>")` в
   static-инициализаторе каждой точки входа (listener, discovery-фильтр, ...): id становится
   префиксом fallback-externalId, label попадает в результаты файлового синка.
2. **Жизненный цикл** - на старте теста `DoqaContexts.open(uniqueId)` и заполнить
   `RuntimeContext.testRef` (собирайте `TestRef` в ОДНОЙ фабрике на адаптер, чтобы discovery,
   ordering и репортинг сходились в идентичностях - особенно во флаге «parameterized»);
   ре-`bind` контекста на исполняющем потоке; на завершении `DoqaContexts.remove(...)`,
   проекция через `ResultBuilder.build(ctx, outcome, message, traces, session.uploader(),
   session::allowsId, session.config)` и передача результата в `session.report(built)`.
3. **Точки flush** - `session.flushClass(fqcn)` на завершении контейнера top-level класса
   («top-level» считайте через `DoqaSession.topLevelClass(fqcn)` - буферы сессии ключуются так
   же), `session.flush()` в конце плана. Ошибка репортинга никогда не должна вылетать в сборку
   пользователя.
4. **Опциональные discovery-фичи** - селективный запуск и порядок по плану, если фреймворк даёт
   для этого хуки: `PlanSelection.active()` / `allows(ref)` для деселекта и
   `PlanSelection.planIndex(ref)` как ключ стабильной сортировки, `PlanSelection.reset()` на
   границе прогона.

`app.doqa:doqa-junit5` - эталонная реализация этого рецепта.

## Конфигурация

Вся конфигурация (подключение, выбор рана, тюнинг доставки, лимиты усечения) резолвится в
`doqa-client` и документирована в README адаптера - ядро лишь потребляет готовый `DoqaConfig`.

## Сборка

Модуль живёт в монорепозитории `doqa-java` и собирается из его корня - reactor сам соберёт
`doqa-client` первым:

```bash
mvn clean verify
```

Тесты - контрактные фикстуры над ядром (каскад атрибуции, Allure-мост, семантика шагов,
усечение) - без сети и без запуска тестового движка.

## Лицензия

[Apache License 2.0](../LICENSE)
