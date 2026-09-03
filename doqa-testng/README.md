# DoQA TestNG Adapter - `app.doqa:doqa-testng`

Адаптер отправляет результаты ваших TestNG-тестов в DoQA: автотесты создаются/обновляются сами,
результаты приходят с шагами, фикстурами, параметрами, вложениями и ссылками. Работает в двух
режимах - **напрямую в API** DoQA или **файлами** (Allure-совместимые артефакты, без сети и без
токена в тестовом процессе). Ничего не ломает: если DoQA недоступен или не настроен, ваши тесты
проходят как обычно, а ошибка отправки становится WARNING в логе.

Минимальная поддерживаемая версия - **TestNG 7.4**: адаптер сознательно не вызывает API новее
(`ITestResult.id()`, `getParameterIndex()`, `upstreamDependencies()`), а собирается и проверяется
против 7.12. Если у вас JUnit, вам нужен [`doqa-junit5`](../doqa-junit5/README.md) или
[`doqa-junit4`](../doqa-junit4/README.md).

---

## Быстрый старт (2 минуты)

**Шаг 1.** Добавьте зависимость - это единственный обязательный шаг:

```xml
<dependency>
  <groupId>app.doqa</groupId>
  <artifactId>doqa-testng</artifactId>
  <version>0.1.5</version>
  <scope>test</scope>
</dependency>
```

```groovy
testImplementation("app.doqa:doqa-testng:0.1.5")   // Gradle
```

Все модули монорепозитория релизятся одной версией - актуальную смотрите в
[корневом README](../README.md).

**Шаг 2.** Запустите тесты.

Регистрировать листенер не нужно: у TestNG есть SPI, и адаптер прописан в
`META-INF/services/org.testng.ITestNGListener` - и отчётный листенер, и интерцептор для
селективных прогонов поднимаются сами (в отличие от JUnit 4, где листенер приходится
прописывать в surefire или подключать рунером).

Без какой-либо конфигурации адаптер уже пишет результаты в `./results/` - файлы
Allure-совместимого формата, который принимает конвейер загрузки DoQA (заодно они открываются
обычным Allure Report). Загрузить их в DoQA можно командой `doqactl upload` или CI-джобой.
Аннотации не обязательны: каждый тест получает стабильный идентификатор автоматически.

**Шаг 3 (опционально).** Чтобы слать результаты сразу в DoQA - создайте `doqa.properties`
в рабочей директории запуска тестов (для Maven это директория модуля; путь можно переопределить
через `-Ddoqa.config=…`) или задайте те же ключи через env/системные properties:

```properties
url=https://demo.doqa.app
token=<project token из настроек пространства>
spaceId=42
```

С этими тремя ключами адаптер переключается в API-режим: сам создаёт тест-ран и наполняет его
в реальном времени по мере прогона (или одним батчем в конце - по умолчанию).

> ⚠️ Токен в конфиге = каждый запуск тестов пишет в DoQA, включая локальные. Обычная схема:
> локально конфига нет (файлы никуда не отправляются), в CI ключи приходят из переменных
> окружения `DOQA_URL` / `DOQA_TOKEN` / `DOQA_SPACE_ID`.

---

## Регистрация: почему ничего не надо прописывать

| Способ | Когда нужен |
|---|---|
| **зависимость `doqa-testng`** *(по умолчанию)* | всегда: файл SPI подхватывает любой хост, который поднимает `org.testng.TestNG` - surefire, Gradle (`test { useTestNG() }`), запуск из IDE, CLI |
| `<listeners><listener class-name="app.doqa.testng.DoqaTestNgListener"/></listeners>` в `testng.xml` | если хост отключил SPI или подменил `ITestNGListenerFactory` |
| surefire `<properties><property><name>listener</name><value>app.doqa.testng.DoqaTestNgListener</value></property></properties>` | maven-специфичная альтернатива тому же |
| `@Listeners(DoqaTestNgListener.class)` на тест-классе | точечно, если хочется явной привязки в коде |

Совмещать способы можно и безопасно: TestNG дедуплицирует листенеры по классу, поэтому двойных
отчётов не бывает. Именно поэтому интерцептор селективного прогона (`DoqaMethodInterceptor`) -
**отдельный** класс: интерцепторы TestNG не дедуплицирует, и «ручная» регистрация листенера не
приводит к двойному деселекту.

Выключить адаптер, не убирая зависимость, - `doqa.reporting=off`. У TestNG есть и свой рубильник:
CLI-опция `-spilistenerstoskip app.doqa.testng.DoqaTestNgListener` (в программном прогоне -
`TestNG.setListenersToSkipFromBeingWiredInViaServiceLoaders(...)`).

---

## Что адаптер даёт из коробки

Ни флагов, ни правок тестов - всё в таблице работает сразу после подключения зависимости.

| Фича | Как получается |
|---|---|
| Исход, сообщение, stack trace, длительность | исход берётся из `ITestResult.getStatus()`, время - из `getStartMillis()/getEndMillis()` самого TestNG |
| Шаги `Doqa.step` и `@Step` | все колбэки инвокации приходят на её потоке, поэтому шаги тела ложатся в блок `call` без обёрток |
| `@BeforeMethod` / `@AfterMethod` | отдельный узел на каждый вызов в блоках setup / teardown результата |
| `@BeforeGroups` / `@AfterGroups` | те же блоки setup / teardown теста, для которого фикстура запустилась |
| `@BeforeClass` / `@AfterClass` | класс-фикстуры: узел на каждый метод, время замеряет TestNG (не «между двумя событиями») |
| Шаги, вложения и сообщения **внутри** фикстур | вокруг фикстуры связывается контекст с её узлом - вложенное падает под этот узел, а не теряется |
| Реальные значения аргументов `@DataProvider` / `@Parameters` | `ITestResult.getParameters()` доступны листенеру напрямую → `parameters[]` результата |
| `{param}`-плейсхолдеры в `@DoqaId`/`@DoqaTitle`/… | из тех же значений; имена аргументов - из подписи метода (нужен `-parameters`, см. ниже) |
| `groups` → теги автотеста | `ITestNGMethod.getGroups()`, включая группы, объявленные на классе |
| `@Test(description = "…")` | имя автотеста (если нет `@DoqaDisplayName`) |
| `@Test(enabled = false)` | `skipped` с причиной: TestNG по таким тестам не присылает событий, адаптер читает исключённые методы `<test>` |
| Скип по `dependsOnMethods`/`dependsOnGroups` и из-за упавшей фикстуры | `skipped` + перечисление методов-виновников (`getSkipCausedBy`) и исходная ошибка |
| Ретрай (`@Test(retryAnalyzer = …)`) | отчитываются **все** попытки; упавшая попытка - `failed`/`broken`, а не `skipped` |
| Селективный прогон (mode 0) | **физический деселект**: невыбранный тест не исполняется вообще (см. ниже) |
| Порядок прохождения по плану DoQA | тот же интерцептор возвращает методы `<test>`-блока в порядке плана |
| Realtime | результаты класса уезжают на границе своего `<test>`-блока, когда `@AfterClass` уже отработал |
| Устойчивость | исключение из листенера TestNG превращает в ошибку конфигурации и краснит прогон, поэтому каждый колбэк обёрнут: ошибка репортинга - только WARNING |

**Почему отправка отложена.** Единственное, чего TestNG не даёт, - события после `@AfterMethod`:
финальный колбэк `ITestListener` приходит **до** teardown-фикстур. Поэтому финальные колбэки
только фиксируют исход, а результат отправляется на первом событии, которое доказывает, что
teardown отработал: следующая инвокация или следующая before-фикстура на этом же потоке, конец
класса, конец `<test>`, конец прогона. Инвариант - «ровно один раз и не раньше `@AfterMethod`».
Для вас это невидимо: teardown-шаги просто есть в отчёте.

---

## Как адаптер решает, куда слать (`reporting`)

| `reporting=` | Что происходит |
|---|---|
| `auto` *(по умолчанию)* | есть `url`+`token`+`spaceId` → API; нет → файлы **и WARNING в логе** с перечислением недостающих настроек |
| `api` | только API (без конфига - предупреждение в лог с перечислением недостающих настроек) |
| `files` | только файлы Allure-совместимого формата в `resultsDir` (по умолчанию `results/`) |
| `off` | адаптер выключен полностью |

Файловый режим - это «путь CI-артефактов»: тестовому процессу не нужны ни сеть до DoQA, ни
секреты. Файлы забирает следующий шаг пайплайна:

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

Если DoQA отвергла запрос или не ответила **на старте** (401/403 на токен, 422 «нет активной
CI-привязки», сеть) - адаптер не выключается, а уходит в файловый режим на весь прогон: результаты
пишутся в `resultsDir`, в лог - WARNING с причиной и подсказкой, что делать. Если пакет результатов
отвергнут **посреди прогона** (обрыв связи, отозванный токен) - в файлы уходит только этот пакет,
остальное продолжает идти по API. Рядом с результатами лежит `doqa-reporting.properties`
(`sink=api|files`, `runId`, `delivered`, `fallbackResults`): по нему джоба загрузки отличает «файлов
нет, потому что всё ушло по API» от «адаптер не отработал» и догружает то, что осталось на диске.

---

## Полная конфигурация

Источники (по возрастанию приоритета): файл `doqa.properties` → переменные окружения `DOQA_*` →
JVM-properties `-Ddoqa.*`. Путь к файлу можно переопределить: `-Ddoqa.config=…` / `DOQA_CONFIG`.

| Ключ (`doqa.properties` / `-Ddoqa.<ключ>`) | Env | Что это | Дефолт |
|---|---|---|---|
| `reporting` | `DOQA_REPORTING` | `api` / `files` / `auto` / `off` | `auto` |
| `resultsDir` | `DOQA_RESULTS_DIR` | каталог файлового режима | `results` |
| `url` | `DOQA_URL` | адрес DoQA | - |
| `token` | `DOQA_TOKEN` | project/personal token (алиас env - `DOQA_PRIVATE_TOKEN`) | - |
| `spaceId` | `DOQA_SPACE_ID` | id пространства (алиас env - `DOQA_PROJECT_ID`) | - |
| `configurationId` | `DOQA_CONFIGURATION_ID` | конфигурация прогона (browser/OS/env) | - |
| `testRunId` | `DOQA_TEST_RUN_ID` | существующий ран (нужен для mode 0 и 1) | - |
| `testRunName` | `DOQA_TEST_RUN_NAME` | имя создаваемого рана (mode 2) | - |
| `adapterMode` | `DOQA_ADAPTER_MODE` | режим выбора рана: `0` - selective, `1` - existing, `2` - new — см. ниже | `2` |
| `importRealtime` | `DOQA_IMPORT_REALTIME` | `true` = стрим результатов по мере прогона (пакет на каждый завершённый `<test>`-блок, вместе с `@AfterClass` его классов) | `false` (батч в конце) |
| `certValidation` | `DOQA_CERT_VALIDATION` | `false` = доверять самоподписанным TLS (отключает и проверку hostname) | `true` |
| `proxy` | `DOQA_PROXY` | `host:port` | - |
| `environment` | `DOQA_ENVIRONMENT` | метка окружения прогона (матрица окружений DoQA) | - |
| `pipelineId` | `DOQA_PIPELINE_ID` | привязка рана к CI-пайплайну | авто: `CI_PIPELINE_ID` / `GITHUB_RUN_ID` |
| `ciRunId` | `DOQA_CI_RUN_ID` | id CI-запуска, инициированного из DoQA - приезжает в пайплайн сам и уезжает обратно с результатами | - |
| `branch` | `DOQA_BRANCH` | ветка прогона | авто: `CI_COMMIT_REF_NAME` / `GITHUB_REF_NAME` |
| `batchSize` | `DOQA_BATCH_SIZE` | максимум результатов в одном батч-запросе | `100` |
| `requestTimeoutMs` | `DOQA_REQUEST_TIMEOUT_MS` | таймаут HTTP-запроса | `30000` |
| `retries` | `DOQA_RETRIES` | попыток на запрос (ретраятся только безопасные повторы) | `3` |
| `retryBackoffMs` | `DOQA_RETRY_BACKOFF_MS` | базовая пауза между попытками (экспоненциальная) | `500` |
| `maxTraceLength` | `DOQA_MAX_TRACE_LENGTH` | лимит длины stack trace в результате (символов) | `100000` |
| `maxMessageLength` | `DOQA_MAX_MESSAGE_LENGTH` | лимит длины сообщений | `10000` |
| `maxParameterLength` | `DOQA_MAX_PARAMETER_LENGTH` | лимит длины значений параметров | `2000` |

### Режимы запуска (API)

- **mode 2 / `new`** *(дефолт)* - адаптер сам создаёт тест-ран и шлёт всё в него. Для CI «просто прогони всё».
- **mode 1 / `existing`** - шлёт всё в существующий ран `testRunId` (ран создали заранее - из UI или API).
  Если задан `testRunId`, а `adapterMode` не задан явно - адаптер сам работает в этом режиме
  (указанный ран никогда не игнорируется молча).
- **mode 0 / `selective`** - **селективный прогон**: адаптер спрашивает у DoQA, какие автотесты числятся
  в ране `testRunId`, и физически исполняет только их. Это тот режим, которым Run Player DoQA
  перезапускает выбранные тесты; честные границы - в следующем разделе.

---

## Параллельность

TestNG-параллель поддержана как есть - `parallel="methods"`, `parallel="classes"`,
`parallel="tests"`, `threadPoolSize` у `@Test`, `@DataProvider(parallel = true)`. Основание:
TestNG исполняет инвокацию **вместе с её фикстурами на одном потоке** и присылает все её колбэки
на этом же потоке, поэтому контекст шагов привязан к потоку без оговорок, а `@BeforeMethod` /
`@AfterMethod` попадают именно в свой результат, а не в чужой.

Реестр открытых инвокаций и все подметания потокобезопасны: при `parallel="tests"` завершившийся
`<test>`-блок отправляет только свои классы и не может «обрубить» инвокацию соседнего блока,
у которой ещё идёт teardown.

---

## Селективный прогон и порядок по плану DoQA

Как это работает: TestNG один раз на `<test>`-блок отдаёт интерцептору все его тест-методы (уже
после фильтрации группами и `<methods><include>`, до первого класса) и исполняет ровно тот список,
который вернули. Адаптер возвращает только выбранные раном автотесты - невыбранные не исполняются
и не рождают ни одного события, никаких «skipped»-призраков. В момент отправки `externalId`
проверяется ещё раз: шаблонные id (`login_{browser}`) на этапе деселекта сравниваются по маске,
а точные - по факту.

Вне mode 0 (и всегда, когда идентификация не разрешилась) интерцептор - строгий no-op: возвращается
тот же список, ни отфильтрованный, ни скопированный. Адаптер не имеет права выкинуть тест из
прогона из-за собственной поломки.

> ⚠️ **Зависимости деселектнутых тестов возвращаются в прогон.** Если выбранный тест объявляет
> `dependsOnMethods`/`dependsOnGroups` на тест, которого в ране нет, выбросить зависимость нельзя:
> TestNG роняет весь `<test>`-блок целиком («… is depending on method …, which is not annotated
> with @Test or not included») и прогон не даёт ничего. Поэтому адаптер добирает транзитивное
> замыкание зависимостей обратно: эти методы **исполняются**, но их результаты **не отправляются** -
> ран содержит ровно то, что просил. Время CI на них тратится.

> ⚠️ **Граф зависимостей сильнее плана.** TestNG пересчитывает `dependsOnMethods` уже **после**
> интерцептора, поэтому зависимость всегда идёт перед своим зависимым, какую бы позицию ей ни дал
> план. А вот `@Test(priority)` порядок из интерцептора перебивает: возвращённый список побеждает.

**Порядок прохождения по плану.** Селективный список mode 0 приходит от DoQA упорядоченным
(порядок набора запуска, задаётся в UI drag-sort'ом). Интерцептор возвращает методы в этом порядке,
тесты вне плана стабильно уходят в хвост, состав прогона порядок никогда не меняет. Честные
границы: порядок применяется только в mode 0 и только внутри одного `<test>`-блока (TestNG может
дополнительно сгруппировать методы по инстансам классов), а ограничения графа зависимостей выше.

> ⚠️ Если ран выбрал автотесты, но ни один из них не совпал с тем, что нашёл хост, в логе будет
> WARNING со списком выбранных `externalId`: обычно это переименованный метод или переехавший
> класс - перерепортите сюит, чтобы каталог DoQA подхватил актуальные id.

---

## Разметка тестов (всё опционально)

Аннотации `@Doqa*` и рантайм-фасад `app.doqa.Doqa` - те же, что в JUnit-адаптерах: они живут в
общем ядре `doqa-java-commons`, смена фреймворка не потребует править импорты.

```java
@DoqaLabels({"regression"})                     // класс-уровень: наследуется всеми тестами
public class LoginTests {

    @Test(groups = {"smoke"}, description = "Проверяет happy-path входа по паролю")
    @DoqaId("LOGIN-1")                          // стабильный ключ автотеста (рекомендуем)
    @DoqaTitle("Успешный вход")
    @DoqaDescription("Проверяет happy-path входа по паролю")
    @DoqaDisplayName("Вход по паролю")          // имя автотеста (иначе - description / имя метода)
    @DoqaLabels({"smoke"})                      // объединится с класс-уровнем
    @DoqaTags({"ui"})                           // сложатся с нативными groups
    @DoqaLinks({@DoqaLink(url = "https://tracker/BUG-77", type = "defect", title = "флак на CI")})
    @DoqaCaseIds({1041})                        // привязка к ручным кейсам DoQA (N штук)
    @DoqaCreateManualCase                       // завести связанный ручной кейс для этого автотеста
    public void loginHappyPath() { … }
}
```

Ещё две аннотации управляют местом теста в дереве DoQA: `@DoqaNamespace` (по умолчанию - пакет)
и `@DoqaClassName` (по умолчанию - простое имя класса). Все `@Doqa*` работают и на уровне класса
(скаляры - метод важнее класса; метки, теги и ссылки складываются) и наследуются подклассами.

`@DoqaCreateManualCase` - точечный opt-in на автоматическое создание ручного тест-кейса,
связанного с «сиротским» автотестом (тем, у которого нет привязки к кейсам). Маркер без
параметров: работает и на методе, и на классе, и действует независимо от того, включено ли такое
создание в настройках пространства DoQA. То же самое доступно из тела теста -
`Doqa.addCreateManualCase()`; аннотация и рантайм-вызов складываются, точечно выключить создание
нельзя. Кейс заводится в момент приёма результата: ретроспективно, для уже накопленных
«сиротских» автотестов, ничего не создаётся.

Если `@DoqaId` нет, идентификатор ищется в таком порядке:
`[DOQA-123]` или `@DOQA:123` в имени (для TestNG это `@Test(description)`, иначе имя метода) →
Allure `@AllureId` (читается без зависимости от Allure - удобно при миграции) → детерминированный
хэш сигнатуры метода. История автотеста стабильна в любом случае; явный id делает её устойчивой
ещё и к переименованиям.

### Нативная разметка TestNG

Двойная разметка не нужна - то, что вы уже написали, адаптер читает сам:

| Что в тесте | Что в DoQA |
|---|---|
| `@Test(groups = {"smoke", "api"})` на методе и/или на классе | теги автотеста: `smoke`, `api` (метод и класс объединяются) |
| `@Test(description = "…")` | имя автотеста; `[DOQA-123]` в описании подхватится каскадом идентификации |
| `@Test(testName = "…")` / `ITest#getTestName()` | имя конкретной инвокации в отчёте; в идентичность **не** входит, поэтому не дробит историю |
| `@Test(enabled = false)` | `skipped` с причиной `disabled with @Test(enabled = false)` |
| `@Test(dependsOnMethods = …)` / `dependsOnGroups` | скип с перечислением методов, которые не прошли |
| `@Test(retryAnalyzer = …)` | каждая попытка - отдельный результат, все они складываются в один автотест |
| `@DataProvider`, `@Parameters` из `testng.xml`, `@Factory` | параметры результата (реальные значения аргументов метода) |

### Параметризованные тесты

Значения аргументов TestNG отдаёт листенеру сам, поэтому и `parameters[]`, и `{param}`-подстановка
работают без дополнительной настройки:

```java
public class LoginTest {

    @DataProvider(name = "browsers")
    public Object[][] browsers() {
        return new Object[][]{{"chrome"}, {"firefox"}};
    }

    @Test(dataProvider = "browsers")
    @DoqaId("LOGIN-IN-{browser}")                // → LOGIN-IN-chrome, LOGIN-IN-firefox
    @DoqaTitle("Вход в {browser}")
    public void loginIn(String browser) { … }    // parameters: [{name: "browser", value: "chrome"}]
}
```

Имена параметров берутся из подписи метода, поэтому проект стоит компилировать с флагом
`-parameters` (в maven-compiler-plugin - `<configuration><parameters>true</parameters></configuration>`).
Иначе параметры будут называться `arg0..argN`, а `{param}` по имени не раскроется.

Без плейсхолдера все инвокации одного метода сворачиваются в **один** автотест, а аргументы уезжают
в `parameters[]` - это касается и `@DataProvider`, и `invocationCount`, и `@Factory`-инстансов.
Аргументы, которые TestNG инжектит сам (`ITestContext`, `ITestResult`, `XmlTest`, `Method`), в
параметры не попадают.

### Runtime-API - из тела теста

```java
import app.doqa.Doqa;
import app.doqa.Labels;
import app.doqa.client.LinkType;                          // типы ссылок приходят из doqa-client

Doqa.step("открыть страницу", () -> page.open());          // шаг (вложенные - просто вкладывайте)
int sum = Doqa.step("посчитать", () -> a + b);             // шаг со значением
Doqa.step("чекпоинт пройден");                             // мгновенный passed-шаг без тела
Doqa.step("создать заказ", "POST /orders", () -> …);       // шаг с description
Doqa.addParameter("env", "staging");
Doqa.addAttachments("target/screenshot.png");              // файл к тесту или открытому шагу
Doqa.addAttachment("response.json", bytes, "application/json"); // вложение из памяти, без temp-файла
Doqa.addAttachment("app.log", logText);                    // текстовое вложение (text/plain)
Doqa.addLink("https://jira/TASK-5", LinkType.REQUIREMENT);
Doqa.addLink(url, type, title, description);               // расширенная форма; есть и addLinks(Link...)
Doqa.addMessage("покупатель создан через фабрику");
Doqa.addCaseIds(1042);
Doqa.addCreateManualCase();                                // завести связанный ручной кейс
Doqa.addExternalId("CART-DYN-1");                          // стабильный id, заданный в рантайме
Doqa.addTitle("…"); Doqa.addDescription("…"); Doqa.addDisplayName("…");
Doqa.addLabels("…"); Doqa.addTags("…");
Doqa.addLabel(Labels.SEVERITY, "critical");                // key:value-метки (severity/owner/epic/…)
```

Все вызовы безопасны: вне активного теста (или при `reporting=off`) они просто no-op. Внутри
фикстур они тоже работают - запись уходит в узел этой фикстуры.

Шаги и вложения из потоков, которые тест порождает сам (async-код, свои executor'ы), нужно
явно перенести в контекст теста:

```java
Doqa.Context ctx = Doqa.captureContext();
executor.submit(() -> Doqa.runWith(ctx, () -> Doqa.step("проверка из воркера")));
```

---

## Шаги `@Step` через AspectJ

Фикстуры и параметры в TestNG не требуют ничего включать, а вот аннотация `@Step` - требует:
её раскрывает аспект, который нужно вплести в тестовую JVM агентом (LTW).

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

Gradle-эквивалент:

```groovy
configurations { doqaAgent }
dependencies { doqaAgent "org.aspectj:aspectjweaver:1.9.24" }
test {
    useTestNG()
    jvmArgs "-javaagent:${configurations.doqaAgent.singleFile}",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED"
}
```

Не хотите агент - используйте явный `Doqa.step("…", () -> …)`, он работает всегда.

> Версия weaver'а определяет максимальную версию байткода хоста: для новых JDK берите
> актуальный `aspectjweaver` (1.9.24 покрывает JDK ≤ 24).

---

## Маппинг исходов

Исход выводится **только** из статуса `ITestResult`, никогда из «есть ли throwable»: у
успешного `@Test(expectedExceptions)` исключение на результате лежит, а упавшая попытка ретрая
приходит как скип.

| Что случилось | Исход в DoQA |
|---|---|
| `SUCCESS` (в том числе совпавший `@Test(expectedExceptions)`) | `passed` |
| `FAILURE` с `AssertionError` (`org.testng.Assert`, `SoftAssert.assertAll()`, AssertJ, opentest4j) | `failed` |
| `FAILURE` с любым другим исключением (NPE, инфраструктура, таймаут `@Test(timeOut = …)`) | `broken` |
| `SKIP`: `SkipException`, скип по `dependsOnMethods`/`dependsOnGroups`, скип из-за упавшей фикстуры | `skipped` - в сообщении причина и список методов из `getSkipCausedBy()` |
| Упавшая попытка ретрая (`SKIP` + `wasRetried()`) | `failed` / `broken` по исключению - **не** `skipped` |
| `SUCCESS_PERCENTAGE_FAILURE` | `failed` / `broken` по исключению: сама инвокация упала, «в пределах процента» - вердикт метода, а не инвокации |
| `@Test(expectedExceptions)` при несовпадении | TestNG кидает `org.testng.TestException`, а это не `AssertionError` → `broken` |
| `@Test(enabled = false)` | `skipped` |

`failed` vs `broken` - важное различие: кластеризация ошибок и flaky-аналитика DoQA обрабатывают
их по-разному.

---

## Ограничения

Честный список того, чего адаптер не делает или делает не полностью.

- **Фикстуры уровня `<suite>` и `<test>`** (`@BeforeSuite`/`@AfterSuite`/`@BeforeTest`/`@AfterTest`)
  **не прикрепляются к результатам**: в модели DoQA нет такого уровня. Они исполняются под
  «пустым» контекстом - шаги и вложения внутри них в отчёт не попадут, но и не приклеятся к чужому
  тесту, которому этот поток достанется следующим.
- **Минимум TestNG 7.4.** Адаптер сознательно не вызывает `ITestResult.id()`,
  `getParameterIndex()`, `upstreamDependencies()` и другой API новее; собирается и проверяется
  против 7.12. На версиях ниже 7.4 гарантий нет.
- **`@Factory`-инстансы и `invocationCount` сворачиваются в один автотест**: подпись метода у них
  одна, а инстанс в идентичность не входит. Инвокации складываются в автотест как попытки;
  различать их можно через параметры результата - аргументы `@DataProvider` попадают туда сами,
  а параметры фабрики адаптер не читает, их нужно задать самим
  (`Doqa.addParameter("browser", browser)`).
- **Класс-фикстуры пишутся один раз на класс за прогон.** Класс, встречающийся в нескольких
  `<test>`-блоках, и `@Factory` с несколькими инстансами проходят полный цикл несколько раз -
  в отчёте останется первый набор узлов `@BeforeClass`/`@AfterClass` (иначе они бы задвоились у
  каждого теста класса).
- **`@BeforeGroups` исполняется один раз на группу**, поэтому его узел окажется в блоке setup
  первого теста этой группы - не у всех её тестов.
- **Параметры `testng.xml`, не переданные в аргументы метода, в результат не попадают**, и имена
  аргументов берутся из подписи метода, а не из `@Parameters`. `@CustomAttribute` тоже не читается -
  для метаданных есть `@DoqaLabels`/`@DoqaTags` и нативные `groups`.
- **Realtime-гранулярность - `<test>`-блок**, а не класс: `IClassListener.onAfterClass` в TestNG
  приходит **до** `@AfterClass`, и стриминг оттуда терял бы teardown класса. Под surefire без
  `testng.xml` весь прогон - один синтетический `<test>`, то есть realtime уедет одним пакетом в
  конце; хотите стримить частями - разбейте прогон на несколько `<test>` в `testng.xml`.
- **Порядок по плану** работает только в mode 0, только внутри `<test>`-блока и только в рамках
  графа `dependsOn*` (см. предыдущий раздел).
- **Несколько форков = несколько ранов.** При `forkCount>1` или `reuseForks=false` каждый форк -
  своя JVM, и в mode 2 каждая создаст свой тест-ран. Задавайте `testRunId` + `adapterMode=1`
  (или mode 0) - тогда все форки пишут в один ран.
- **Не подключайте `doqa-testng` и другой адаптер DoQA к одному прогону**: идентификация
  фреймворка в общем ядре одна на JVM, и адаптеры перезапишут её друг другу.
- **Переход между фреймворками без явного `@DoqaId` начинает историю автотеста заново**:
  fallback-id считается от подписи метода с префиксом фреймворка (`testng:<hash>` против
  `junit5:<hash>`/`junit4:<hash>`). Лечится единственным способом - проставить `@DoqaId` до переезда.
- **`@Test(description)` входит в fallback-id.** У теста без `@DoqaId` описание участвует в хэше
  подписи (оно же - имя автотеста), поэтому правка описания начнёт историю заново. Параметризованные
  тесты этим не затронуты: у них имя в хэш не идёт.

---

## Траблшутинг

| Симптом | Причина и лечение |
|---|---|
| Результатов нет вообще, в логе ни строчки от DoQA | адаптера нет в прогоне: зависимость не в test-scope, либо стоит `doqa.reporting=off`, либо хост отключил SPI (`-spilistenerstoskip`, свой `ITestNGListenerFactory`) - тогда пропишите листенер в `testng.xml` (см. «Регистрация») |
| `Tests run: 0` или часть тестов молча не пошла, сборка зелёная | на test-classpath оказались и TestNG, и JUnit Platform: surefire выбирает **один** провайдер, тесты второго фреймворка не запускаются вовсе. Уберите лишний фреймворк либо зафиксируйте провайдер явно (`surefire-testng` в `<dependencies>` плагина) |
| Результаты в `results/`, а ждали в DoQA | это `auto` без API-конфига - в логе есть WARNING «no reporting configuration found (missing …)»; задайте `url`/`token`/`spaceId`. Если файловый режим выбран сознательно, поставьте `reporting=files` - предупреждение исчезнет |
| DoQA недоступна или отвергла токен, а в `results/` появились файлы | так и задумано: адаптер деградировал в файловый режим (в логе WARNING «could not establish the test run» либо «results chunk failed»); догрузите `results/` джобой загрузки, как в файловом режиме |
| `NoSuchMethodError: DoqaStepAspect.aspectOf()` | вы сузили вивинг своим `aop.xml` и исключили аспект - верните `<include within="app.doqa.aspects.DoqaStepAspect"/>` |
| `@Step`-шаги не появляются | не подключён `-javaagent:aspectjweaver` (см. «Шаги `@Step` через AspectJ»); `Doqa.step(...)` работает и без агента |
| На JDK 16+ падает вивер / нет шагов | добавьте `--add-opens java.base/java.lang=ALL-UNNAMED` к argLine |
| Параметры называются `arg0`, `arg1` | включите `-parameters` у компилятора: имена аргументов адаптер берёт из подписи метода |
| `{param}` в `@DoqaId`/`@DoqaTitle` не раскрылся | имя в плейсхолдере не совпало с именем аргумента (частый случай - те же `arg0` без `-parameters`), либо у инвокации аргументов нет вовсе (`invocationCount`, `@Factory`) - задайте значение сами: `Doqa.addParameter(...)` / `Doqa.addExternalId(...)` |
| В селективном ране исполняются невыбранные тесты | это добор зависимостей: без них TestNG уронил бы весь `<test>`. Их результаты в ран не уезжают; чтобы не платить временем, разорвите `dependsOnMethods` между выбранными и невыбранными тестами |
| Селективный прогон упал с «… is depending on method …, which is not annotated with @Test or not included» | так выглядит незакрытое замыкание зависимостей - это баг адаптера, напишите в поддержку support@doqa.app. Смотреть на значения `dependsOnMethods`/`dependsOnGroups` (TestNG трактует их как регулярные выражения) у методов, попавших в выборку; временный обход - `adapterMode=1` (прогон без деселекта) или `@Test(ignoreMissingDependencies = true)` |
| Селективный ран не выполнил ничего, в логе WARNING со списком `externalId` | id в DoQA и в коде разъехались (переименование/переезд) - перерепортите сюит, чтобы каталог подхватил актуальные id |
| Один прогон превратился в несколько ранов DoQA | `forkCount>1` / `reuseForks=false`: задайте `testRunId` и `adapterMode=1` |
| Локальные прогоны спамят раны в DoQA | уберите токен из локального конфига или поставьте локально `reporting=files` |
| Самоподписанный сертификат | `certValidation=false` (только для тестовых стендов!) |
| В отчёте нет шагов из `@BeforeSuite`/`@BeforeTest` | так и задумано: у этих фикстур нет уровня в модели DoQA (см. «Ограничения»); перенесите значимые шаги в `@BeforeClass`/`@BeforeMethod` |
| Нужно выключить адаптер для одного прогона | `-Ddoqa.reporting=off` либо TestNG-рубильник `-spilistenerstoskip app.doqa.testng.DoqaTestNgListener` |

Ошибка отправки **никогда не роняет сборку** - адаптер пишет WARNING и продолжает.

---

## Миграция с Allure

- **Разметка подхватывается автоматически и без зависимости от Allure**: `@AllureId`
  (атрибуция `ALLURE-<id>`), `@Epic`/`@Feature`/`@Story`/`@Owner`/`@Severity` (→ `key:value`-метки),
  `@Link` и URL-значные `@Issue`/`@TmsLink` (→ типизированные ссылки), `@Description`. Файловый
  режим эмитит Allure-совместимые результаты с лейблом `framework: testng` - существующий пайплайн
  загрузки продолжит работать.
- Регистрация тоже знакомая: `allure-testng` подключается тем же механизмом SPI, так что менять
  придётся только зависимость - ни `testng.xml`, ни surefire править не нужно.

## Сборка адаптера из исходников

Адаптер живёт в монорепозитории `doqa-java` вместе со своими зависимостями
(`doqa-java-commons`, `doqa-client`) и собирается из его корня одной командой - порядок
модулей разруливает reactor:

```bash
mvn clean verify        # либо точечно: mvn -pl doqa-testng -am clean verify
```

## Лицензия

[Apache License 2.0](../LICENSE).
