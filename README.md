# DoQA для JVM - автотесты, которые сами попадают в TMS

[![CI](https://github.com/doqa-app/doqa-java/actions/workflows/ci.yml/badge.svg)](https://github.com/doqa-app/doqa-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/app.doqa/doqa-junit5)](https://central.sonatype.com/artifact/app.doqa/doqa-junit5)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Одна зависимость - и результаты ваших JUnit-тестов появляются в [DoQA](https://doqa.app) сами:
с шагами, фикстурами, вложениями, параметрами и стабильной историей по каждому тесту. Без
переписывания тестов, без своего инфраструктурного кода, без риска для сборки - если DoQA
недоступен или не настроен, тесты проходят как обычно.

## Быстрый старт

```xml
<dependency>
  <groupId>app.doqa</groupId>
  <artifactId>doqa-junit5</artifactId>
  <version>0.1.1</version>
  <scope>test</scope>
</dependency>
```

```groovy
testImplementation("app.doqa:doqa-junit5:0.1.1")   // Gradle
```

Уже на этом шаге адаптер пишет в `results/` файлы Allure-совместимого формата - его
принимает конвейер загрузки DoQA, а мигрирующим с Allure не нужно менять пайплайн. Загрузить
их в DoQA можно артефактом CI. А чтобы слать напрямую в API, достаточно трёх ключей:

```properties
# doqa.properties (или DOQA_URL / DOQA_TOKEN / DOQA_SPACE_ID в CI)
url=https://demo.doqa.app
token=<project token>
spaceId=42
```

Подробный гайд - конфигурация, аннотации, режимы, траблшутинг - в
[README адаптера](doqa-junit5/README.md).

## Что вы получаете

- **Полный отчёт** - дерево шагов (`@Step` и `Doqa.step`),
  setup/teardown вплоть до `@BeforeAll`/`@AfterAll`, вложения (файлы и контент из памяти),
  параметры инвокаций, ссылки на задачи и требования, метки.
- **Стабильная история тестов** - каскад идентификации (`@DoqaId` → id в заголовке →
  Allure `@AllureId` → детерминированный хэш): переименования и рефакторинги не рвут историю
  и flaky-аналитику.
- **Селективные прогоны** - Run Player DoQA перезапускает выбранные тесты, а адаптер физически
  исполняет только их (деселект на discovery - экономия времени CI) и умеет проходить их в
  порядке плана.
- **Живой прогресс** - realtime-режим стримит результаты по мере прогона, батч-режим шлёт всё
  одним потоком чанков в конце; и то и другое переживает сбои сети без потери прогона.
- **Не ломает сборку** - ошибка репортинга логируется и не роняет тесты; ретраи
  уважают идемпотентность (дубликат рана невозможен).
- **Переезд с Allure без переписывания** - Allure-разметка (`@AllureId`, `@Epic`,
  `@Owner`, `@Severity`, ссылки) и нативные JUnit `@Tag`/`@DisplayName` подхватываются
  автоматически.
- **Файловый режим для строгих контуров** - тестовому процессу не нужны ни сеть до DoQA,
  ни секреты: результаты уезжают артефактом пайплайна.

## Поддерживаемые фреймворки

| Фреймворк | Артефакт |
|---|---|
| JUnit 5 (Jupiter) | [`app.doqa:doqa-junit5`](doqa-junit5/README.md) |

## Как это устроено

```
ваши тесты ──► doqa-junit5 ──► doqa-java-commons ──► doqa-client ──► DoQA
```

| Модуль | Что это |
|---|---|
| [`doqa-junit5`](doqa-junit5/README.md) | адаптер JUnit 5 - то, что подключают в тестовый проект |
| [`doqa-java-commons`](doqa-java-commons/README.md) | общее ядро адаптеров: фасад `Doqa`, аннотации `@Doqa*`, аспект `@Step`, атрибуция, сессия репортинга |
| [`doqa-client`](doqa-client/README.md) | клиент DoQA Autotest API и файловый sink; ноль рантайм-зависимостей |

Пользовательский API (`app.doqa.Doqa`, `app.doqa.annotations.*`) живёт в ядре и одинаков во
всех адаптерах - смена фреймворка не потребует править импорты. Не нашли адаптер своего
фреймворка, напишите нам в поддержку support@doqa.app, мы обязательно добавим поддержку. А так же в [README ядра](doqa-java-commons/README.md) есть рецепт из четырёх шагов, как разработать нужный адаптер.

## Сборка

Нужен только JDK 11+ (CI проверяет 11, 17 и 21), Maven приедет через wrapper:

```bash
./mvnw clean verify
```

Все модули релизятся одной версией; релиз - тег `v<версия>`, дальше CI сам публикует
подписанные артефакты в Maven Central и собирает release notes из `type:`-лейблов PR
([история изменений](https://github.com/doqa-app/doqa-java/releases)).

## Лицензия

[Apache License 2.0](LICENSE)
