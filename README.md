# Адаптеры DoQA для JVM

[![CI](https://github.com/doqa-app/doqa-java/actions/workflows/ci.yml/badge.svg)](https://github.com/doqa-app/doqa-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/app.doqa/doqa-junit5?label=doqa-junit5)](https://central.sonatype.com/artifact/app.doqa/doqa-junit5)
[![Maven Central](https://img.shields.io/maven-central/v/app.doqa/doqa-junit4?label=doqa-junit4)](https://central.sonatype.com/artifact/app.doqa/doqa-junit4)
[![Maven Central](https://img.shields.io/maven-central/v/app.doqa/doqa-testng?label=doqa-testng)](https://central.sonatype.com/artifact/app.doqa/doqa-testng)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Адаптеры передают результаты тестов JUnit 5, JUnit 4 и TestNG в [DoQA](https://doqa.app). Адаптер
отправляет результаты через API DoQA или сохраняет их в файлы Allure-совместимого формата, которые
можно загрузить в DoQA позже. Вместе с исходом теста адаптер передаёт шаги, фикстуры, вложения,
параметры и метаданные.

Существующие тесты менять не нужно. Аннотации `@Doqa*` и фасад `Doqa` нужны только для
дополнительных данных: шагов, вложений, идентификаторов автотестов и ссылок.

Ошибки отправки не останавливают тесты и не меняют результат сборки. Если DoQA недоступен или
отклоняет запрос, адаптер пишет WARNING в лог и записывает в файлы результаты, которые не удалось
отправить.

## Поддерживаемые фреймворки

| Фреймворк | Артефакт |
|---|---|
| JUnit 5 (Jupiter) | [`app.doqa:doqa-junit5`](doqa-junit5/README.md) |
| JUnit 4 (4.13+) | [`app.doqa:doqa-junit4`](doqa-junit4/README.md) |
| TestNG (7.4+) | [`app.doqa:doqa-testng`](doqa-testng/README.md) |

Для всех адаптеров нужен JDK 11 или новее.

## Установка

Добавьте зависимость адаптера своего фреймворка. Для JUnit 5:

```xml
<dependency>
  <groupId>app.doqa</groupId>
  <artifactId>doqa-junit5</artifactId>
  <version>0.1.5</version>
  <scope>test</scope>
</dependency>
```

```groovy
testImplementation("app.doqa:doqa-junit5:0.1.5")   // Gradle
```

Адаптер JUnit 5 подключается сам: JUnit Platform находит его listener через `ServiceLoader`. Чтобы
в отчёт попадали фикстуры и параметры тестов, нужно дополнительно включить автоподключение
расширений JUnit Jupiter (см. [README адаптера](doqa-junit5/README.md)).

Для TestNG подключите артефакт `doqa-testng`. TestNG находит listener и интерцептор адаптера через
`ServiceLoader`, дополнительная регистрация не нужна.

Для JUnit 4 подключите артефакт `doqa-junit4` и зарегистрируйте listener явно: JUnit 4 не
подключает listeners автоматически. В Maven это делается настройкой surefire:

```xml
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <properties>
      <property><name>listener</name><value>app.doqa.junit4.DoqaRunListener</value></property>
    </properties>
  </configuration>
</plugin>
```

В Gradle и при запуске из IDE вместо этого используйте `@RunWith(DoqaRunner.class)` (см.
[README адаптера](doqa-junit4/README.md)).

## Быстрый старт

### Сохранение результатов в файлы

Если подключение к DoQA не настроено, адаптер работает в файловом режиме. После запуска тестов в
каталоге `results/` рабочей директории (для Maven это каталог модуля) появятся файлы в
Allure-совместимом формате. Тестовому процессу в этом режиме не нужны ни доступ к DoQA, ни токен.

Если настройки подключения не заданы, адаптер пишет в лог предупреждение `DoQA: no reporting
configuration found (missing …)`: результаты не отправлены в DoQA. Если файловый режим выбран
намеренно, задайте `reporting=files`, и предупреждения не будет. Загрузить файлы в DoQA можно командой
`doqactl upload` или отдельным шагом CI.

### Отправка результатов в DoQA

Для отправки через API нужны три настройки: адрес DoQA, токен и id пространства. Задайте их в файле
`doqa.properties`, в переменных окружения или в системных свойствах JVM `-Ddoqa.*`:

```properties
# doqa.properties (или DOQA_URL / DOQA_TOKEN / DOQA_SPACE_ID в CI)
url=https://demo.doqa.app
token=<project token>
spaceId=42
```

Когда заданы все три значения, адаптер создаёт прогон в DoQA и после завершения тестов отправляет в
него результаты пакетами по 100 штук (настройка `batchSize`). С `importRealtime=true` результаты
отправляются по мере выполнения тестов: после каждого тестового класса в JUnit 5 и JUnit 4, после
каждого блока `<test>` в TestNG.

Если настройки подключения лежат в `doqa.properties`, в DoQA отправляется каждый запуск тестов,
включая локальные. Поэтому их обычно задают только в CI через переменные окружения `DOQA_URL`,
`DOQA_TOKEN` и `DOQA_SPACE_ID`. Все настройки, режимы прогона и устранение неполадок описаны в
README адаптеров: [JUnit 5](doqa-junit5/README.md), [JUnit 4](doqa-junit4/README.md),
[TestNG](doqa-testng/README.md).

## Что передаёт адаптер

Для каждого теста адаптер передаёт исход, длительность, сообщение об ошибке и stack trace. Исход
`failed` означает, что не выполнилась проверка (`AssertionError`, AssertJ, opentest4j), `broken`
означает любое другое исключение. Отключённые и пропущенные тесты передаются с исходом `skipped`.

Шаги задаются вызовом `Doqa.step(...)` или аннотацией `@Step`. Для `@Step` нужен агент AspectJ в
тестовой JVM. Фикстуры (`@BeforeEach`/`@AfterEach`, `@BeforeAll`/`@AfterAll` и их аналоги в JUnit 4
и TestNG) попадают в отчёт как шаги setup и teardown. Фасад `Doqa` добавляет вложения (файлы и
данные из памяти), параметры, ссылки, метки и привязку к ручным кейсам.

Аннотации Allure (`@AllureId`, `@Epic`, `@Feature`, `@Story`, `@Owner`, `@Severity`, ссылки) и
аннотации JUnit 5 `@Tag` и `@DisplayName` адаптер читает без зависимости от Allure. `@AllureId`
передаётся в DoQA и связывает автотест с ручным тест-кейсом с этим id, как при отправке через API,
так и в файловом режиме.

### Идентификатор автотеста

DoQA связывает результат с автотестом по идентификатору. Адаптер берёт его из `@DoqaId`, затем из
названия теста в форме `[DOQA-123]` или `@DOQA:123`, затем из `@AllureId`. Если ничего из этого нет,
адаптер вычисляет хэш от полного имени класса, имени метода, типов параметров и отображаемого
имени теста. У такого теста переименование класса, метода или отображаемого имени и перенос в
другой пакет меняют идентификатор, и DoQA создаёт новый автотест без прежней истории. Чтобы история
сохранялась, задайте `@DoqaId`.

### Запуск выбранных тестов

Когда DoQA запускает выбранные автотесты в CI, он передаёт в пайплайн переменные `DOQA_TEST_RUN_ID`
и `DOQA_ADAPTER_MODE=0`. В этом режиме адаптер получает из DoQA список автотестов прогона. Адаптеры
JUnit 5 и TestNG исключают остальные тесты до их выполнения. Адаптер JUnit 4 делает это только с
`@RunWith(DoqaRunner.class)` или правилом `DoqaSelectRule`: без них JUnit 4 выполняет все тесты, а
адаптер отправляет только результаты выбранных.

Адаптеры также умеют выполнять тесты в порядке плана DoQA. Как это включить и какие есть
ограничения, описано в README каждого адаптера.

### Ошибки при отправке

Если адаптеру не удалось создать прогон или получить список выбранных тестов, он записывает все
результаты в `results/`. Если DoQA отклонил пакет результатов во время прогона, в файлы
записывается только этот пакет, следующие пакеты адаптер продолжает отправлять. В обоих случаях
адаптер пишет в лог WARNING с причиной, а в `results/` лежит файл `doqa-reporting.properties`. По
этому файлу шаг CI может определить, какие результаты нужно загрузить в DoQA отдельно.

Создание прогона передаёт в DoQA ключ, по которому DoQA распознаёт повторный запрос, поэтому
повтор после сетевой ошибки не создаёт второй прогон.

## Архитектура

```
                 ┌─► doqa-junit5 ─┐
ваши тесты ──────┼─► doqa-junit4 ─┼──► doqa-java-commons ──► doqa-client ──► DoQA
                 └─► doqa-testng ─┘
```

| Модуль | Назначение |
|---|---|
| [`doqa-junit5`](doqa-junit5/README.md) | адаптер JUnit 5 |
| [`doqa-junit4`](doqa-junit4/README.md) | адаптер JUnit 4: `RunListener` и необязательный `DoqaRunner` |
| [`doqa-testng`](doqa-testng/README.md) | адаптер TestNG: listeners и интерцептор, которые TestNG подключает сам |
| [`doqa-java-commons`](doqa-java-commons/README.md) | общая часть адаптеров: фасад `Doqa`, аннотации `@Doqa*`, аспект `@Step`, вычисление идентификатора, сессия отправки |
| [`doqa-client`](doqa-client/README.md) | клиент DoQA Autotest API и запись файлов; внешних зависимостей во время выполнения нет |

Пользовательский API (`app.doqa.Doqa`, `app.doqa.annotations.*`) находится в `doqa-java-commons` и
одинаков во всех адаптерах, поэтому при смене фреймворка импорты менять не нужно. Если адаптера
для вашего фреймворка нет, напишите нам в поддержку support@doqa.app, и мы обязательно его
добавим. Как написать адаптер самостоятельно, описано в [README `doqa-java-commons`](doqa-java-commons/README.md).

## Разработка

Для сборки нужен JDK 11 или новее. Maven устанавливать не нужно: скрипт `./mvnw` скачивает его сам.

```bash
./mvnw clean verify
```

CI собирает проект на JDK 11, 17 и 21.

Все модули выпускаются с одной версией. Релиз выпускается тегом `v<версия>`: workflow `release.yml`
публикует подписанные артефакты в Maven Central и создаёт GitHub Release, в котором заметки к
релизу сгруппированы по меткам `type:` у pull request
([история изменений](https://github.com/doqa-app/doqa-java/releases)).

## Лицензия

[Apache License 2.0](LICENSE)
