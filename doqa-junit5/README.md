# Адаптер JUnit 5: `app.doqa:doqa-junit5`

Адаптер передаёт результаты тестов JUnit 5 (Jupiter) в DoQA: через API DoQA или через файлы
Allure-совместимого формата, которые загружаются в DoQA отдельным шагом. Для каждого теста DoQA
получает исход, шаги, фикстуры, параметры, вложения и ссылки. По этим данным DoQA создаёт и
обновляет автотесты.

Ошибки отправки не останавливают тесты и не меняют результат сборки: адаптер пишет WARNING в лог и
продолжает работу.

Требуется JDK 11 или новее.

---

## Быстрый старт

**Шаг 1.** Добавьте зависимость:

```xml
<dependency>
  <groupId>app.doqa</groupId>
  <artifactId>doqa-junit5</artifactId>
  <version>0.1.1</version>
  <scope>test</scope>
</dependency>
```

JUnit Platform подключает listener и фильтр адаптера через `ServiceLoader`, регистрировать их не
нужно. Чтобы в отчёт попадали фикстуры и параметры, включите автоподключение расширений (см.
[Фикстуры и параметры](#фикстуры-и-параметры)).

**Шаг 2.** Запустите тесты.

Если подключение к DoQA не настроено, адаптер записывает результаты в `./results/` в
Allure-совместимом формате. Эти файлы принимает конвейер загрузки DoQA, их также можно открыть в
Allure Report. Загрузить файлы в DoQA можно командой `doqactl upload` или отдельной джобой CI.
Аннотации не обязательны: каждый тест получает идентификатор автотеста автоматически.

**Шаг 3 (необязательно).** Чтобы отправлять результаты в DoQA через API, создайте `doqa.properties`
в рабочей директории запуска тестов (для Maven это каталог модуля) или задайте те же настройки
через переменные окружения или системные свойства JVM. Путь к файлу можно изменить свойством
`-Ddoqa.config=…`.

```properties
url=https://demo.doqa.app
token=<project token из настроек пространства>
spaceId=42
```

Когда заданы `url`, `token` и `spaceId`, адаптер создаёт прогон в DoQA и отправляет в него
результаты: по умолчанию одним пакетом в конце прогона, с `importRealtime=true` после завершения
каждого тестового класса.

> ⚠️ Если эти настройки лежат в `doqa.properties`, в DoQA отправляется каждый запуск тестов,
> включая локальные. Обычно локально файла с настройками нет и результаты записываются в файлы, а
> в CI настройки передаются через переменные окружения `DOQA_URL` / `DOQA_TOKEN` / `DOQA_SPACE_ID`.

---

## Способ отправки (`reporting`)

| `reporting=` | Поведение |
|---|---|
| `auto` *(по умолчанию)* | если заданы `url`, `token` и `spaceId`, результаты отправляются через API; иначе записываются в файлы, и адаптер пишет в лог WARNING со списком недостающих настроек |
| `api` | результаты отправляются только через API; без полного набора настроек адаптер пишет WARNING со списком недостающих настроек и отключается |
| `files` | результаты записываются в `resultsDir` (по умолчанию `results/`) |
| `off` | адаптер отключён |

В файловом режиме тестовому процессу не нужны ни доступ к DoQA, ни токен. Файлы загружает
следующий шаг пайплайна, например:

```yaml
# .gitlab-ci.yml
test:
  script: mvn test          # адаптер пишет results/
  artifacts:
    paths: [results/]

upload-to-doqa:
  needs: [test]
  script: doqactl upload --token "$DOQA_TOKEN" --space "$DOQA_SPACE_ID" results/
```

Если на старте DoQA отклонил запрос или не ответил (401 или 403 из-за токена, 422 при отсутствии
активной CI-привязки, сетевая ошибка), адаптер не отключается, а записывает результаты всего
прогона в `resultsDir` и пишет в лог WARNING с причиной и подсказкой. Если DoQA отклонил пакет
результатов посреди прогона (обрыв связи, отозванный токен), в файлы записывается только этот
пакет, остальные адаптер продолжает отправлять через API.

Рядом с результатами адаптер записывает `doqa-reporting.properties` (`sink=api|files`, `runId`,
`delivered`, `fallbackResults`). По этому файлу джоба загрузки отличает ситуацию «файлов нет,
потому что всё отправлено через API» от ситуации «адаптер не отработал» и загружает то, что
осталось на диске.

---

## Настройки

Источники по возрастанию приоритета: файл `doqa.properties`, переменные окружения `DOQA_*`,
системные свойства JVM `-Ddoqa.*`. Путь к файлу задаётся свойством `-Ddoqa.config=…` или
переменной `DOQA_CONFIG`.

| Ключ (`doqa.properties` / `-Ddoqa.<ключ>`) | Переменная окружения | Назначение | По умолчанию |
|---|---|---|---|
| `reporting` | `DOQA_REPORTING` | `api` / `files` / `auto` / `off` | `auto` |
| `resultsDir` | `DOQA_RESULTS_DIR` | каталог для файлов результатов | `results` |
| `url` | `DOQA_URL` | адрес DoQA | — |
| `token` | `DOQA_TOKEN` | токен проекта или персональный токен | — |
| `spaceId` | `DOQA_SPACE_ID` | id пространства | — |
| `configurationId` | `DOQA_CONFIGURATION_ID` | конфигурация прогона (браузер, ОС, окружение) | — |
| `testRunId` | `DOQA_TEST_RUN_ID` | существующий прогон (нужен для режимов 0 и 1) | — |
| `testRunName` | `DOQA_TEST_RUN_NAME` | имя создаваемого прогона (режим 2) | — |
| `adapterMode` | `DOQA_ADAPTER_MODE` | режим выбора прогона: `0`/`selective`, `1`/`existing`, `2`/`new` ([см. ниже](#режимы-прогона)) | `2` |
| `importRealtime` | `DOQA_IMPORT_REALTIME` | `true` — отправлять результаты по ходу прогона: пакет на каждый завершённый класс вместе с его `@AfterAll` | `false` (один пакет в конце) |
| `certValidation` | `DOQA_CERT_VALIDATION` | `false` — не проверять TLS-сертификат и имя хоста (для самоподписанных сертификатов) | `true` |
| `proxy` | `DOQA_PROXY` | HTTP-прокси, `host:port` | — |
| `environment` | `DOQA_ENVIRONMENT` | метка окружения прогона (матрица окружений DoQA) | — |
| `pipelineId` | `DOQA_PIPELINE_ID` | пайплайн CI, к которому привязывается прогон | `CI_PIPELINE_ID` / `GITHUB_RUN_ID` |
| `ciRunId` | `DOQA_CI_RUN_ID` | id запуска CI, который инициировал DoQA; DoQA передаёт его в пайплайн, адаптер возвращает с результатами | — |
| `branch` | `DOQA_BRANCH` | ветка прогона | `CI_COMMIT_REF_NAME` / `GITHUB_REF_NAME` |
| `batchSize` | `DOQA_BATCH_SIZE` | максимальное число результатов в одном запросе | `100` |
| `requestTimeoutMs` | `DOQA_REQUEST_TIMEOUT_MS` | таймаут HTTP-запроса, мс | `30000` |
| `retries` | `DOQA_RETRIES` | общее число попыток на запрос (повторяются только запросы, которые безопасно повторить) | `3` |
| `retryBackoffMs` | `DOQA_RETRY_BACKOFF_MS` | пауза перед второй попыткой, мс; перед каждой следующей удваивается | `500` |
| `maxTraceLength` | `DOQA_MAX_TRACE_LENGTH` | максимальная длина stack trace в результате, символов | `100000` |
| `maxMessageLength` | `DOQA_MAX_MESSAGE_LENGTH` | максимальная длина сообщения, символов | `10000` |
| `maxParameterLength` | `DOQA_MAX_PARAMETER_LENGTH` | максимальная длина значения параметра, символов | `2000` |

Подробности разбора настроек (алиасы, пустые значения, повторы запросов) описаны в
[README `doqa-client`](../doqa-client/README.md#настройки).

### Режимы прогона

**Режим 2 (`new`, по умолчанию).** Адаптер создаёт прогон и отправляет в него все результаты.
Подходит для CI, где выполняются все тесты.

**Режим 1 (`existing`).** Адаптер отправляет все результаты в существующий прогон `testRunId`,
созданный заранее в интерфейсе DoQA или через API. Если `testRunId` задан, а `adapterMode` нет,
адаптер работает в этом режиме, чтобы указанный прогон не был проигнорирован.

**Режим 0 (`selective`).** Адаптер запрашивает у DoQA список автотестов прогона `testRunId` и
выполняет только их: остальные тесты адаптер исключает ещё на этапе discovery, поэтому на них не
тратится время CI. Этот режим DoQA включает, когда запускает выбранные автотесты в CI: он передаёт в
пайплайн `DOQA_TEST_RUN_ID` и `DOQA_ADAPTER_MODE=0`.

---

## Порядок выполнения по плану DoQA

Список автотестов в режиме 0 приходит из DoQA упорядоченным: это порядок набора запуска, который
задают в интерфейсе DoQA перетаскиванием. Адаптер может выполнять тесты в этом порядке с помощью
своих orderer'ов, но включить их нужно вручную: JUnit Jupiter не позволяет адаптеру задать orderer
программно.

```properties
# src/test/resources/junit-platform.properties
junit.jupiter.testclass.order.default=app.doqa.junit5.DoqaPlanClassOrderer
junit.jupiter.testmethod.order.default=app.doqa.junit5.DoqaPlanMethodOrderer
```

Как работает сортировка:

- **методы внутри класса** сортируются по позиции их `externalId` в плане (`DoqaPlanMethodOrderer`);
- **классы** сортируются по наименьшей позиции своих методов (`DoqaPlanClassOrderer`). Jupiter
  выполняет тесты по классам, поэтому чередовать методы разных классов нельзя;
- тесты, которых нет в плане, выполняются в конце в исходном порядке. В режиме 0 такие тесты и так
  исключаются; сортировка не меняет состав прогона;
- без этих свойств, без сессии DoQA или без плана orderer'ы не меняют порядок по умолчанию.

Ограничения: работает только с Jupiter engine; порядок соблюдается только при последовательном
выполнении (`junit.jupiter.execution.parallel.enabled=false`, это значение по умолчанию); для
`@ParameterizedTest` с плейсхолдером в `externalId` используется позиция первого совпавшего id
плана.

---

## Разметка тестов

Разметка необязательна.

```java
@DoqaLabels({"regression"})                     // класс-уровень: наследуется всеми тестами
class LoginTests {

    @Test
    @DoqaId("LOGIN-1")                          // стабильный ключ автотеста (рекомендуем)
    @DoqaTitle("Успешный вход")
    @DoqaDescription("Проверяет happy-path входа по паролю")
    @DoqaDisplayName("Вход по паролю")          // имя автотеста (иначе - display name JUnit)
    @DoqaLabels({"smoke"})                      // объединится с класс-уровнем
    @DoqaTags({"ui"})
    @DoqaLinks({@DoqaLink(url = "https://tracker/BUG-77", type = "defect", title = "флак на CI")})
    @DoqaCaseIds({1041})                        // привязка к ручным кейсам DoQA (N штук)
    @DoqaCreateManualCase                       // завести связанный ручной кейс для этого автотеста
    void loginHappyPath() { … }
}
```

Ещё две аннотации задают место теста в дереве DoQA: `@DoqaNamespace` (по умолчанию пакет) и
`@DoqaClassName` (по умолчанию простое имя класса). Обе работают и на уровне класса. Аннотации
находятся в пакете `app.doqa.annotations`, фасад — `app.doqa.Doqa`. Они общие для всех
JVM-адаптеров DoQA (`doqa-java-commons`), поэтому при смене фреймворка импорты менять не нужно.

`@DoqaCreateManualCase` запрашивает создание ручного тест-кейса для автотеста, у которого нет
привязки к кейсам. Аннотация без параметров работает на методе и на классе; на классе она действует
на все его тесты и наследуется подклассами. Кейс создаётся независимо от того, включено ли такое
создание в настройках пространства DoQA. Тот же запрос можно сделать из тела теста вызовом
`Doqa.addCreateManualCase()`. Аннотация и вызов дополняют друг друга, выключить создание для
отдельного теста нельзя. Кейс создаётся, когда DoQA получает результат; для автотестов, которые
уже есть в DoQA без кейсов, кейсы задним числом не создаются.

### Идентификатор автотеста

Если `@DoqaId` нет, адаптер берёт идентификатор из отображаемого имени (`[DOQA-123]` или
`@DOQA:123`), затем из Allure `@AllureId` (адаптер читает её без зависимости от Allure), иначе
вычисляет хэш от сигнатуры метода и отображаемого имени (у параметризованных тестов отображаемое
имя в хэш не входит). Хэш не меняется между запусками, пока не изменились пакет, класс, метод,
типы его параметров или отображаемое имя; после их изменения DoQA
создаёт новый автотест. Явный `@DoqaId` сохраняет историю при любых переименованиях.

### Параметризованные тесты

Аргументы каждой инвокации передаются как именованные параметры результата:

```java
@ParameterizedTest
@ValueSource(strings = {"chrome", "firefox"})
void worksIn(String browser) { … }              // parameters: [{name: "browser", value: "chrome"}]
```

Чтобы каждая инвокация стала **отдельным автотестом**, используйте плейсхолдер `{имяАргумента}` в
любой аннотации (`externalId`, `title`, `displayName`, метки, теги, ссылки):

```java
@ParameterizedTest
@ValueSource(strings = {"chrome", "firefox"})
@DoqaId("LOGIN-IN-{browser}")                   // → LOGIN-IN-chrome, LOGIN-IN-firefox
@DoqaTitle("Вход в {browser}")
void loginIn(String browser) { … }
```

> Аргументы инвокаций передаёт расширение `DoqaExtension`, поэтому включите автоподключение
> расширений (см. [Фикстуры и параметры](#фикстуры-и-параметры)). Без него плейсхолдер останется
> нераскрытым (`LOGIN-IN-{browser}`). Чтобы параметры назывались по именам аргументов (`browser`,
> а не `arg0`), включите флаг компилятора `-parameters`: в maven-compiler-plugin это
> `<configuration><parameters>true</parameters></configuration>`.

### Runtime API

Вызовы из тела теста:

```java
import app.doqa.client.LinkType;                          // типы ссылок приходят из doqa-client

Doqa.step("открыть страницу", () -> page.open());        // шаг (вложенные - просто вкладывайте)
int sum = Doqa.step("посчитать", () -> a + b);            // шаг со значением
Doqa.step("чекпоинт пройден");                            // мгновенный passed-шаг без тела
Doqa.step("создать заказ", "POST /orders", () -> …);      // шаг с description
Doqa.addParameter("env", "staging");
Doqa.addAttachments("target/screenshot.png");             // файл к тесту или открытому шагу
Doqa.addAttachment("response.json", bytes, "application/json"); // вложение из памяти, без temp-файла
Doqa.addAttachment("app.log", logText);                   // текстовое вложение (text/plain)
Doqa.addLink("https://jira/TASK-5", LinkType.REQUIREMENT);
Doqa.addLink(url, type, title, description);              // расширенная форма; есть и addLinks(Link...)
Doqa.addMessage("покупатель создан через фабрику");
Doqa.addCaseIds(1042);
Doqa.addCreateManualCase();                               // завести связанный ручной кейс (как @DoqaCreateManualCase)
Doqa.addExternalId("CART-DYN-1");                         // стабильный id динамического (@TestFactory) теста
Doqa.addTitle("…"); Doqa.addDescription("…"); Doqa.addDisplayName("…");
Doqa.addLabels("…"); Doqa.addTags("…");
Doqa.addLabel(Labels.SEVERITY, "critical");               // key:value-метки (severity/owner/epic/…)
```

Вне выполняющегося теста или при `reporting=off` вызовы ничего не делают. Аннотации JUnit `@Tag`
автоматически попадают в теги автотеста, дублировать их через `@DoqaTags` не нужно.

Шаги и вложения из потоков, которые создаёт тест (асинхронный код, собственные executor'ы), нужно
явно связать с контекстом теста:

```java
Doqa.Context ctx = Doqa.captureContext();
executor.submit(() -> Doqa.runWith(ctx, () -> Doqa.step("проверка из воркера")));
```

---

## Фикстуры и параметры

Адаптер работает без дополнительных настроек, но фикстуры, параметры и шаги `@Step` попадают в
отчёт только после двух настроек.

### 1. Фикстуры и параметры: автоподключение расширений

```
# src/test/resources/junit-platform.properties
junit.jupiter.extensions.autodetection.enabled=true
```

Вместо этого можно подключить расширение на классе: `@ExtendWith(DoqaExtension.class)`. После этого:

- `@BeforeEach` и `@AfterEach` попадают в блоки setup и teardown каждого результата;
- `@BeforeAll` и `@AfterAll` попадают в фикстуры класса у всех его тестов. В режиме realtime класс
  отправляется после своего `@AfterAll`, поэтому teardown попадает в отчёт;
- `Doqa.step`, `@Step` и `Doqa.addAttachment*` внутри фикстур класса попадают в узел фикстуры;
- параметры получают имена, плейсхолдеры `{param}` раскрываются.

### 2. Шаги `@Step`: агент AspectJ в тестовой JVM

```java
@Step("авторизоваться под {user}")  // {param}-плейсхолдеры из аргументов метода; без текста - имя метода
void authorize(String user) { … }
```

```xml
<!-- агент должен быть зависимостью вашего проекта: адаптер его не приносит транзитивно -->
<dependency>
  <groupId>org.aspectj</groupId>
  <artifactId>aspectjweaver</artifactId>
  <version>1.9.24</version>
  <scope>test</scope>
</dependency>
```

```xml
<!-- пишет путь к джарнику агента в property ${org.aspectj:aspectjweaver:jar} -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions><execution><phase>initialize</phase><goals><goal>properties</goal></goals></execution></executions>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>-javaagent:${org.aspectj:aspectjweaver:jar} --add-opens java.base/java.lang=ALL-UNNAMED</argLine>
  </configuration>
</plugin>
```

Для Gradle:

```groovy
configurations { doqaAgent }
dependencies { doqaAgent "org.aspectj:aspectjweaver:1.9.24" }
test {
    jvmArgs "-javaagent:${configurations.doqaAgent.singleFile}",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    systemProperty "junit.jupiter.extensions.autodetection.enabled", "true"
}
```

Если агент подключать не нужно, используйте `Doqa.step("…", () -> …)`: он работает без агента.

> Версия `aspectjweaver` ограничивает версию байткода, с которой работает агент. Для новых JDK
> используйте актуальную версию `aspectjweaver` (1.9.24 работает с JDK до 24 включительно).

---

## Исходы

| Что произошло | Исход в DoQA |
|---|---|
| Тест прошёл | `passed` |
| Не выполнилась проверка (`AssertionError`, AssertJ, opentest4j) | `failed` |
| Любое другое исключение (ошибка инфраструктуры, NPE, таймаут) | `broken` |
| `@Disabled` или невыполненное assumption | `skipped` |

DoQA по-разному обрабатывает `failed` и `broken` при кластеризации ошибок и анализе нестабильных
тестов.

---

## Устранение неполадок

| Симптом | Причина и решение |
|---|---|
| Результатов нет ни в DoQA, ни в файлах | задан `reporting=api` без `url`, `token` или `spaceId`: в логе есть WARNING; либо задан `reporting=off` |
| Результаты в `results/`, а ожидались в DoQA | режим `auto` без настроек API, в логе есть WARNING «no reporting configuration found (missing …)». Задайте `url`, `token` и `spaceId`. Если файловый режим нужен, задайте `reporting=files`, и предупреждение пропадёт |
| DoQA недоступен или отклонил токен, в `results/` появились файлы | адаптер перешёл в файловый режим (в логе WARNING «could not establish the test run» или «results chunk failed»). Загрузите `results/` джобой загрузки, как в файловом режиме |
| `NoSuchMethodError: DoqaStepAspect.aspectOf()` | собственный `aop.xml` исключил аспект из области применения; добавьте `<include within="app.doqa.aspects.DoqaStepAspect"/>` |
| Шаги `@Step` не появляются | не подключён `-javaagent:aspectjweaver` (см. выше) |
| На JDK 16+ агент падает или шагов нет | добавьте `--add-opens java.base/java.lang=ALL-UNNAMED` в argLine |
| Параметры называются `arg0`, `arg1` | включите флаг компилятора `-parameters` |
| Нет блоков setup и teardown | не включено автоподключение расширений (см. [Фикстуры и параметры](#фикстуры-и-параметры)) |
| Самоподписанный сертификат | `certValidation=false` (только для тестовых стендов) |
| Локальные запуски создают прогоны в DoQA | уберите токен из локальной конфигурации или задайте локально `reporting=files` |

---

## Переход с Allure

Адаптер читает разметку Allure без зависимости от Allure:

- `@AllureId` — идентификатор `ALLURE-<id>`;
- `@Epic`, `@Feature`, `@Story`, `@Owner`, `@Severity` — метки `key:value`;
- `@Link`, а также `@Issue` и `@TmsLink` со значением-URL — ссылки с типом;
- `@Description` — описание.

`@AllureId` также связывает автотест с ручным тест-кейсом DoQA с этим id: в файловом режиме через
метку `AS_ID`, при отправке через API напрямую. Поэтому привязка к кейсам сохраняется без правок.
Файловый режим записывает Allure-совместимые результаты, поэтому существующий пайплайн загрузки
продолжит работать.

## Сборка из исходников

Адаптер находится в монорепозитории `doqa-java` вместе с модулями `doqa-java-commons` и
`doqa-client`. Maven собирает их из корня одной командой и сам определяет порядок модулей:

```bash
mvn clean verify        # либо точечно: mvn -pl doqa-junit5 -am clean verify
```

## Лицензия

[Apache License 2.0](../LICENSE).
