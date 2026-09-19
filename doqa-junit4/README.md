# Адаптер JUnit 4: `app.doqa:doqa-junit4`

Адаптер передаёт результаты тестов JUnit 4 в DoQA: через API DoQA или через файлы
Allure-совместимого формата, которые загружаются в DoQA отдельным шагом. Для каждого теста DoQA
получает исход, шаги, фикстуры, параметры, вложения и ссылки. По этим данным DoQA создаёт и
обновляет автотесты.

Ошибки отправки не останавливают тесты и не меняют результат сборки: адаптер пишет WARNING в лог и
продолжает работу.

Минимальная поддерживаемая версия — **JUnit 4.13**; адаптер собирается и тестируется с 4.13.2.
Требуется JDK 11 или новее. Для JUnit 5 используйте [`doqa-junit5`](../doqa-junit5/README.md).

---

## Быстрый старт

**Шаг 1.** Добавьте зависимость:

```xml
<dependency>
  <groupId>app.doqa</groupId>
  <artifactId>doqa-junit4</artifactId>
  <version>0.1.5</version>
  <scope>test</scope>
</dependency>
```

```groovy
testImplementation("app.doqa:doqa-junit4:0.1.5")   // Gradle
```

Все модули монорепозитория выпускаются с одной версией; актуальная указана в
[корневом README](../README.md).

**Шаг 2 (обязательный).** Зарегистрируйте listener. В JUnit 4 нет механизма, через который
адаптер мог бы подключить `RunListener` сам. Без этого шага адаптер ничего не отправляет и не
записывает.

Для Maven достаточно одной настройки surefire, тесты менять не нужно:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <properties>
      <property>
        <name>listener</name>
        <value>app.doqa.junit4.DoqaRunListener</value>
      </property>
    </properties>
  </configuration>
</plugin>
```

В Gradle и при запуске из IDE такой настройки в JUnit 4 нет. Отметьте тестовые классы или их
базовый класс рунером адаптера, он сам подключит listener:

```java
import app.doqa.junit4.DoqaRunner;
import org.junit.runner.RunWith;

@RunWith(DoqaRunner.class)
public class LoginTest { … }
```

Оба способа можно использовать вместе: если listener уже зарегистрирован, рунер второй не
добавляет.

**Шаг 3.** Запустите тесты.

Если подключение к DoQA не настроено, адаптер записывает результаты в `./results/` в
Allure-совместимом формате. Эти файлы принимает конвейер загрузки DoQA, их также можно открыть в
Allure Report. Загрузить файлы в DoQA можно командой `doqactl upload` или отдельной джобой CI.
Аннотации не обязательны: каждый тест получает идентификатор автотеста автоматически.

**Шаг 4 (необязательно).** Чтобы отправлять результаты в DoQA через API, создайте `doqa.properties`
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

## Listener и рунер

`DoqaRunListener` обязателен: он передаёт в DoQA всё, что JUnit 4 сообщает listeners.
`@RunWith(DoqaRunner.class)` необязателен: рунер добавляет данные, которые видны только внутри
рунера.

| Что | Только listener | С `@RunWith(DoqaRunner.class)` |
|---|---|---|
| Исход, сообщение, stack trace, длительность теста | да | да |
| `Doqa.step` / `@Step`, вложения, ссылки, метки, параметры | да, всё в одном блоке шагов | да, с разбивкой по фазам |
| `@Before` / `@After` | входят в тело результата, отдельных узлов нет | **отдельный шаг на каждый метод** в блоках setup и teardown |
| `@BeforeClass` / `@AfterClass` | один общий узел на класс; время измеряется между событиями JUnit | **отдельный узел на каждый метод** с точным временем |
| Шаги и вложения внутри фикстур класса | да, кроме прогонов с surefire `parallel` (см. [Ограничения](#ограничения)) | да |
| Тесты, по которым JUnit не присылает событий: падение `@BeforeClass`, `@Ignore` на классе | да, адаптер сам формирует результаты по тестам класса | да, эту часть выполняет listener |
| Выборочный прогон (режим 0) | тесты **выполняются**, но результаты невыбранных не отправляются | невыбранный тест **не выполняется** |
| Порядок выполнения по плану DoQA | нет | да, для методов внутри класса |
| Реальные значения аргументов параметризованного теста | нет, только разбор имени инвокации | нужна фабрика для `Parameterized` (см. ниже) |

Рунер наследует `BlockJUnit4ClassRunner`, поэтому его нельзя использовать в классах, у которых уже
есть свой рунер (`SpringRunner`, `MockitoJUnitRunner`, `Parameterized`, `Suite`, `Enclosed`). Для
таких классов работают listener и правило `DoqaSelectRule` (см.
[Выборочный прогон](#выборочный-прогон-и-порядок-по-плану-doqa)).

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
| `token` | `DOQA_TOKEN` | токен проекта или персональный токен (другое имя переменной: `DOQA_PRIVATE_TOKEN`) | — |
| `spaceId` | `DOQA_SPACE_ID` | id пространства (другое имя переменной: `DOQA_PROJECT_ID`) | — |
| `configurationId` | `DOQA_CONFIGURATION_ID` | конфигурация прогона (браузер, ОС, окружение) | — |
| `testRunId` | `DOQA_TEST_RUN_ID` | существующий прогон (нужен для режимов 0 и 1) | — |
| `testRunName` | `DOQA_TEST_RUN_NAME` | имя создаваемого прогона (режим 2) | — |
| `adapterMode` | `DOQA_ADAPTER_MODE` | режим выбора прогона: `0`/`selective`, `1`/`existing`, `2`/`new` ([см. ниже](#режимы-прогона)) | `2` |
| `importRealtime` | `DOQA_IMPORT_REALTIME` | `true` — отправлять результаты по ходу прогона: пакет на каждый завершённый класс вместе с его `@AfterClass` | `false` (один пакет в конце) |
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
работает только с ними. Этот режим DoQA включает, когда запускает выбранные автотесты в CI: он
передаёт в пайплайн `DOQA_TEST_RUN_ID` и `DOQA_ADAPTER_MODE=0`. Какие тесты адаптер JUnit 4 может
не выполнять, описано в следующем разделе.

---

## Выборочный прогон и порядок по плану DoQA

В JUnit 4 нет общего механизма, который позволил бы адаптеру исключить тест: `RunListener` не может
пропустить тест, а surefire не даёт подключить внешний `Filter`. Поэтому в режиме 0 есть три
уровня. Выбор зависит от того, готовы ли вы менять тестовые классы.

**1. Только listener (менять ничего не нужно).** Все тесты выполняются, но результаты автотестов,
которых нет в прогоне, не отправляются. Прогон в DoQA получается правильным, время CI не
сокращается.

**2. `@RunWith(DoqaRunner.class)`.** Невыбранный тест не выполняется: рунер помечает его как
ignored, JUnit сообщает `skipped`, адаптер его результат не отправляет. Рунер также включает порядок
по плану.

**3. `DoqaSelectRule` для классов со своим рунером.** Правило пропускает невыбранный тест через
assumption: не выполняются ни `@Before`, ни тело теста.

```java
import app.doqa.junit4.DoqaSelectRule;
import org.junit.ClassRule;
import org.junit.Rule;

@RunWith(SpringRunner.class)
public class CheckoutTest {

    @Rule
    public final DoqaSelectRule doqa = new DoqaSelectRule();              // на каждый тест

    @ClassRule
    public static final DoqaSelectRule doqaClass = new DoqaSelectRule();  // класс целиком,
                                                                          // если не выбран ни один его тест
}
```

Вне режима 0, а также если идентификатор теста не удалось определить, рунер и правило выполняют
тест как обычно: ошибка адаптера не должна исключать тест из прогона.

**Порядок выполнения по плану.** Список автотестов в режиме 0 приходит из DoQA упорядоченным: это
порядок набора запуска, который задают в интерфейсе DoQA перетаскиванием. `DoqaRunner` выполняет
тесты класса в этом порядке; тесты, которых нет в плане, выполняются в конце в исходном порядке.
Сортировка не меняет состав прогона. Ограничения: сортируются только методы внутри класса (порядок
классов задаёт среда запуска, например настройка surefire `runOrder`); порядок применяется, только
если план получен; классы с `@FixMethodOrder` не сортируются, потому что порядок в них задал автор
теста.

> ⚠️ Если прогон выбрал автотесты, но ни один из них не совпал с найденными тестами, адаптер пишет
> в лог WARNING со списком выбранных `externalId`. Обычно причина в том, что метод переименован
> или класс перенесён. Отправьте в DoQA результаты полного прогона, чтобы каталог автотестов
> получил актуальные идентификаторы.

**Запуск через JUnit Platform.** Если JUnit 4 запускается через `junit-platform` и
`junit-vintage-engine`, исключение тестов на этапе discovery выполняет адаптер
[`doqa-junit5`](../doqa-junit5/README.md) своим `PostDiscoveryFilter`: тесты JUnit 4 приходят к
нему от vintage engine. Адаптер `doqa-junit4` в таком прогоне не участвует, поэтому специфичных для
JUnit 4 данных (узлов `@BeforeClass`, фаз `@Before`/`@After`) в отчёте не будет, а вычисляемый
идентификатор будет другим. Если используете этот вариант, задайте тестам явные `@DoqaId`.

---

## Разметка тестов

Разметка необязательна. Аннотации `@Doqa*` и фасад `app.doqa.Doqa` те же, что в JUnit 5: они
находятся в общем модуле `doqa-java-commons`, поэтому при смене фреймворка импорты менять не нужно.

```java
@DoqaLabels({"regression"})                     // класс-уровень: наследуется всеми тестами
public class LoginTests {

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
    public void loginHappyPath() { … }
}
```

Ещё две аннотации задают место теста в дереве DoQA: `@DoqaNamespace` (по умолчанию пакет) и
`@DoqaClassName` (по умолчанию простое имя класса). Все аннотации `@Doqa*` работают и на уровне
класса и наследуются подклассами. Для скалярных значений аннотация метода важнее аннотации класса;
метки, теги и ссылки объединяются.

`@DoqaCreateManualCase` запрашивает создание ручного тест-кейса для автотеста, у которого нет
привязки к кейсам. Аннотация без параметров работает на методе и на классе. Кейс создаётся
независимо от того, включено ли такое создание в настройках пространства DoQA. Тот же запрос можно
сделать из тела теста вызовом `Doqa.addCreateManualCase()`. Аннотация и вызов дополняют друг друга,
выключить создание для отдельного теста нельзя. Кейс создаётся, когда DoQA получает результат; для
автотестов, которые уже есть в DoQA без кейсов, кейсы задним числом не создаются.

### Идентификатор автотеста

Если `@DoqaId` нет, адаптер берёт идентификатор из отображаемого имени (`[DOQA-123]` или
`@DOQA:123`), затем из Allure `@AllureId` (адаптер читает её без зависимости от Allure), иначе
вычисляет хэш от сигнатуры метода и отображаемого имени. Хэш не меняется между запусками, пока не
изменились пакет, класс, метод или отображаемое имя; после их изменения DoQA создаёт новый
автотест. Явный `@DoqaId` сохраняет историю при любых переименованиях.

### Аннотации JUnit 4

Дублировать уже существующую разметку не нужно, адаптер читает её сам:

| Что в тесте | Что в DoQA |
|---|---|
| `@Category({Smoke.class, Ui.class})` на методе и/или на классе | теги автотеста `Smoke`, `Ui` (простые имена классов-категорий; категории метода и класса объединяются) |
| `@Ignore("нужен стенд")` на методе | `skipped` с причиной |
| `@Ignore` на классе | `skipped` для каждого метода `@Test` в классе |
| вложенные классы (`Suite`, `Enclosed`) | тесты вложенного класса получают и фикстуры класса внешнего класса, а в режиме realtime отправляются вместе с ним |

### Параметризованные тесты

Реальные значения аргументов инвокации адаптер получает только через свою фабрику рунеров (JUnit 4
не передаёт их ни в `Description`, ни listener):

```java
import app.doqa.junit4.DoqaParametersRunnerFactory;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(DoqaParametersRunnerFactory.class)
public class LoginTest {

    @Parameterized.Parameters(name = "{index}: browser={0}")
    public static List<Object[]> data() {
        return Arrays.asList(new Object[][]{{"chrome"}, {"firefox"}});
    }

    @Parameterized.Parameter
    public String browser;                       // имя поля = имя параметра результата

    @Test
    @DoqaId("LOGIN-IN-{browser}")                // → LOGIN-IN-chrome, LOGIN-IN-firefox
    @DoqaTitle("Вход в {browser}")
    public void loginIn() { … }
}
```

Имена параметров берутся из полей `@Parameterized.Parameter`, а при передаче через конструктор — из
имён его аргументов. Для этого проект должен компилироваться с флагом `-parameters`
(в maven-compiler-plugin это `<configuration><parameters>true</parameters></configuration>`).
Иначе параметры будут называться `arg0..argN`.

**Без фабрики** параметризованный тест тоже отправляется, но параметр восстанавливается из имени
инвокации. `@Parameters(name = "{index}: browser={0}")` даст один параметр `arguments` со значением
`browser=chrome`, а имя по умолчанию (только индекс) не даст ни одного. Плейсхолдеры `{param}` в
этом случае **не раскрываются**. Есть два обходных пути: задать значение из тела теста
(`Doqa.addParameter("browser", browser)`, после чего `@DoqaId("LOGIN-{browser}")` раскроется) или
сразу задать идентификатор: `Doqa.addExternalId("LOGIN-" + browser)`.

Во всех вариантах инвокации одного метода объединяются в **один** автотест, пока в аннотациях нет
плейсхолдера: суффикс `[0: browser=chrome]` в идентификатор не входит.

### Runtime API

Вызовы из тела теста:

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

Вне выполняющегося теста или при `reporting=off` вызовы ничего не делают.

Шаги и вложения из потоков, которые создаёт тест (асинхронный код, собственные executor'ы), нужно
явно связать с контекстом теста:

```java
Doqa.Context ctx = Doqa.captureContext();
executor.submit(() -> Doqa.runWith(ctx, () -> Doqa.step("проверка из воркера")));
```

---

## Фикстуры и шаги `@Step`

Адаптер работает без дополнительных настроек, но с ними отчёт полнее.

### 1. Фикстуры и фазы: `@RunWith(DoqaRunner.class)`

Отдельный узел на каждый `@Before`/`@After`, точные узлы `@BeforeClass`/`@AfterClass`, шаги внутри
фикстур класса и разбивку шагов по фазам setup, call и teardown даёт только рунер (см. таблицу в
разделе [Listener и рунер](#listener-и-рунер)). Для классов со своим рунером работает только
listener: всё, что происходит в тесте и его фикстурах, попадает в один блок шагов результата.

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
    <properties>
      <property>
        <name>listener</name>
        <value>app.doqa.junit4.DoqaRunListener</value>
      </property>
    </properties>
  </configuration>
</plugin>
```

Для Gradle (listener регистрируется через `@RunWith(DoqaRunner.class)` в тестах):

```groovy
configurations { doqaAgent }
dependencies { doqaAgent "org.aspectj:aspectjweaver:1.9.24" }
test {
    jvmArgs "-javaagent:${configurations.doqaAgent.singleFile}",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED"
}
```

Если агент подключать не нужно, используйте `Doqa.step("…", () -> …)`: он работает без агента.

> Версия `aspectjweaver` ограничивает версию байткода, с которой работает агент. Для новых JDK
> используйте актуальную версию `aspectjweaver` (1.9.24 работает с JDK до 24 включительно).

---

## Исходы

| Что произошло | Исход в DoQA |
|---|---|
| Тест прошёл (в том числе `@Test(expected = …)`, который выбросил ожидаемое исключение) | `passed` |
| Не выполнилась проверка (`AssertionError`, `ComparisonFailure`, AssertJ, opentest4j) | `failed` |
| Любое другое исключение (ошибка инфраструктуры, NPE, таймаут) | `broken` |
| `@Ignore` на методе или на классе | `skipped`, причина — текст `@Ignore` |
| Невыполненное `Assume` (в тесте, в `@Before`, в `@BeforeClass`) | `skipped` |
| Упал `@BeforeClass` или `@ClassRule` | результат для каждого теста класса; ошибка также записывается в узел `@BeforeClass` |

DoQA по-разному обрабатывает `failed` и `broken` при кластеризации ошибок и анализе нестабильных
тестов.

В JUnit 4 **один тест может упасть дважды**: в теле и в `@After`. JUnit присылает событие на каждую
ошибку, а адаптер объединяет их в **один** результат: сообщения и stack trace склеиваются. Исход
`broken`, если хотя бы одна ошибка не относится к проверкам, иначе `failed`.

---

## Ограничения

Что адаптер не делает или делает иначе, чем адаптер JUnit 5:

- **Минимум JUnit 4.13** (проверяется на 4.13.2). Адаптер не вызывает API, появившийся только в
  4.13, поэтому на 4.12 он не падает. Но в 4.12 нет событий suite и точки перехвата отдельного
  метода фикстуры: listener не собирает узлы `@BeforeClass`/`@AfterClass`, `@Before`/`@After` не
  становятся отдельными шагами даже с рунером, а в режиме realtime результаты отправляются на
  границе классов, а не сразу после `@AfterClass`. Исходы, шаги, вложения и параметры работают. На
  4.11 и ниже работа не гарантируется.
- **Несколько форков создают несколько прогонов.** При `forkCount>1` или `reuseForks=false` каждый
  форк работает в своей JVM, и в режиме 2 каждый создаёт свой прогон. Задайте `testRunId` и
  `adapterMode=1` (или режим 0), тогда все форки пишут в один прогон.
- **surefire `parallel`.** События suite приходят в поток `main`, а тесты выполняются в потоках
  пула. Поэтому без рунера шаги и вложения **внутри** фикстур класса могут не попасть в отчёт (сами
  узлы фикстур записываются). С `@RunWith(DoqaRunner.class)` этого ограничения нет: фикстуры
  записываются в том потоке, в котором выполняются.
- **Без рунера длительность фикстуры класса измеряется между событиями JUnit**, поэтому в неё
  входит и работа рунера в этом интервале (создание экземпляра класса, вычисление `@ClassRule`).
  Рунер измеряет только сами методы.
- **`@Rule` и `@ClassRule` не становятся отдельными узлами** отчёта: правило оборачивает тест
  целиком, отдельной границы у него нет.
- **Порядок по плану** применяется к методам внутри класса, только в режиме 0 с полученным планом
  и никогда к классам с `@FixMethodOrder`. Порядок классов задаёт среда запуска.
- **Тесты `@Theory`** отправляются как обычные тесты, но значения datapoints в параметры
  результата не попадают. Динамических тестов в JUnit 4 нет.
- **Не подключайте `doqa-junit4` и `doqa-junit5` к одному прогону**: общая часть адаптеров хранит
  один фреймворк на JVM, и адаптеры перезапишут его друг у друга.
- **Переход с JUnit 4 на JUnit 5 без явного `@DoqaId` начинает историю автотеста заново**:
  вычисляемый идентификатор содержит префикс фреймворка (`junit4:<hash>` и `junit5:<hash>`), и
  сигнатура у фреймворков разная. Чтобы сохранить историю, задайте `@DoqaId` до перехода.

---

## Устранение неполадок

| Симптом | Причина и решение |
|---|---|
| Результатов нет, в логе нет ни одной строки от DoQA | **чаще всего** не зарегистрирован listener. Для Maven нужна настройка surefire `listener` (см. [Быстрый старт](#быстрый-старт), шаг 2), для Gradle и IDE — `@RunWith(DoqaRunner.class)` |
| `Tests run: 0` при успешной сборке после появления `junit-jupiter` в test classpath | surefire переключился на `JUnitPlatformProvider` и больше не находит классы JUnit 4. Уберите jupiter из тестовых зависимостей или добавьте `junit-vintage-engine` и запускайте тесты через JUnit Platform |
| Результаты в `results/`, а ожидались в DoQA | режим `auto` без настроек API, в логе есть WARNING «no reporting configuration found (missing …)». Задайте `url`, `token` и `spaceId`. Если файловый режим нужен, задайте `reporting=files`, и предупреждение пропадёт |
| DoQA недоступен или отклонил токен, в `results/` появились файлы | адаптер перешёл в файловый режим (в логе WARNING «could not establish the test run» или «results chunk failed»). Загрузите `results/` джобой загрузки, как в файловом режиме |
| `NoSuchMethodError: DoqaStepAspect.aspectOf()` | собственный `aop.xml` исключил аспект из области применения; добавьте `<include within="app.doqa.aspects.DoqaStepAspect"/>` |
| Шаги `@Step` не появляются | не подключён `-javaagent:aspectjweaver` (см. [Фикстуры и шаги `@Step`](#фикстуры-и-шаги-step)) |
| На JDK 16+ агент падает или шагов нет | добавьте `--add-opens java.base/java.lang=ALL-UNNAMED` в argLine |
| Нет узлов setup и teardown, `@Before`/`@After` не видны отдельно | не подключён `@RunWith(DoqaRunner.class)`: без рунера отдельных узлов нет |
| Параметры называются `arg0`, `arg1` | включите флаг компилятора `-parameters` или передавайте параметры через поля `@Parameterized.Parameter` |
| `{param}` в `@DoqaId` или `@DoqaTitle` не раскрылся | нет `@Parameterized.UseParametersRunnerFactory(DoqaParametersRunnerFactory.class)`; либо задайте значение сами: `Doqa.addParameter(...)` / `Doqa.addExternalId(...)` |
| В выборочном прогоне выполняются невыбранные тесты | listener только не отправляет их результаты; чтобы тесты не выполнялись, нужен `@RunWith(DoqaRunner.class)` или `DoqaSelectRule` |
| Выборочный прогон ничего не выполнил, в логе WARNING со списком `externalId` | идентификаторы в DoQA и в коде разошлись (переименование или перенос); отправьте результаты полного прогона, чтобы каталог получил актуальные идентификаторы |
| Один запуск создал несколько прогонов в DoQA | `forkCount>1` или `reuseForks=false`: задайте `testRunId` и `adapterMode=1` |
| Локальные запуски создают прогоны в DoQA | уберите токен из локальной конфигурации или задайте локально `reporting=files` |
| Самоподписанный сертификат | `certValidation=false` (только для тестовых стендов) |

---

## Переход с Allure

- Адаптер читает разметку Allure без зависимости от Allure: `@AllureId` (идентификатор
  `ALLURE-<id>`), `@Epic`, `@Feature`, `@Story`, `@Owner`, `@Severity` (метки `key:value`), `@Link`,
  а также `@Issue` и `@TmsLink` со значением-URL (ссылки с типом), `@Description`. `@AllureId` также
  связывает автотест с ручным тест-кейсом DoQA с этим id: в файловом режиме через метку `AS_ID`,
  при отправке через API напрямую. Поэтому привязка к кейсам сохраняется без правок. Файловый режим
  записывает Allure-совместимые результаты с меткой `framework: junit4`, поэтому существующий
  пайплайн загрузки продолжит работать.
- `allure-junit4` подключается той же настройкой surefire `listener`, поэтому при переходе нужно
  заменить только имя класса.

## Сборка из исходников

Адаптер находится в монорепозитории `doqa-java` вместе с модулями `doqa-java-commons` и
`doqa-client`. Maven собирает их из корня одной командой и сам определяет порядок модулей:

```bash
mvn clean verify        # либо точечно: mvn -pl doqa-junit4 -am clean verify
```

## Лицензия

[Apache License 2.0](../LICENSE).
