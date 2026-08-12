# DoQA JUnit 4 Adapter - `app.doqa:doqa-junit4`

Адаптер отправляет результаты ваших JUnit 4 тестов в DoQA: автотесты создаются/обновляются сами,
результаты приходят с шагами, фикстурами, параметрами, вложениями и ссылками. Работает в двух
режимах - **напрямую в API** DoQA или **файлами** (Allure-совместимые артефакты, без сети и без
токена в тестовом процессе). Ничего не ломает: если DoQA недоступен или не настроен, ваши тесты
проходят как обычно, а ошибка отправки становится WARNING в логе.

Минимальная поддерживаемая версия - **JUnit 4.13** (адаптер собирается и тестируется против
4.13.2). Если вы уже на JUnit 5, вам нужен [`doqa-junit5`](../doqa-junit5/README.md).

---

## Быстрый старт (2 минуты)

**Шаг 1.** Добавьте зависимость:

```xml
<dependency>
  <groupId>app.doqa</groupId>
  <artifactId>doqa-junit4</artifactId>
  <version>0.1.4</version>
  <scope>test</scope>
</dependency>
```

```groovy
testImplementation("app.doqa:doqa-junit4:0.1.4")   // Gradle
```

Все модули монорепозитория релизятся одной версией - актуальную смотрите в
[корневом README](../README.md).

**Шаг 2 (обязательный).** Зарегистрируйте листенер. В JUnit 4 нет ни SPI, ни ServiceLoader'а для
`RunListener` - подцепить себя к прогону сам адаптер не может, и без этого шага он просто молчит.

Maven (surefire) - одна настройка на весь прогон, тесты править не нужно:

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

Gradle и запуск из IDE - такой точки регистрации в JUnit 4 нет вовсе, поэтому разметьте тестовые
классы (или их базовый класс) рунером: он сам подцепит листенер к своему прогону.

```java
import app.doqa.junit4.DoqaRunner;
import org.junit.runner.RunWith;

@RunWith(DoqaRunner.class)
public class LoginTest { … }
```

Оба способа можно совмещать: если внешний листенер уже зарегистрирован, рунер второй не добавляет.

**Шаг 3.** Запустите тесты.

Без какой-либо конфигурации адаптер уже пишет результаты в `./results/` - файлы
Allure-совместимого формата, который принимает конвейер загрузки DoQA (заодно они открываются
обычным Allure Report). Загрузить их в DoQA можно командой `doqactl upload` или CI-джобой.
Аннотации не обязательны: каждый тест получает стабильный идентификатор автоматически.

**Шаг 4 (опционально).** Чтобы слать результаты сразу в DoQA - создайте `doqa.properties`
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

## Два слоя: листенер обязателен, рунер добавляет глубину

`DoqaRunListener` - обязательный слой: он умеет всё, что JUnit 4 публикует наружу.
`@RunWith(DoqaRunner.class)` - опциональный слой поверх: он добавляет то, что видно только изнутри
рунера.

| Что | Только листенер | + `@RunWith(DoqaRunner.class)` |
|---|---|---|
| Исход, сообщение, stack trace, длительность теста | да | да |
| `Doqa.step` / `@Step`, вложения, ссылки, метки, параметры | да, всё в одном блоке шагов | да, разложено по фазам |
| `@Before` / `@After` | входят в тело результата, отдельных узлов нет | **отдельный шаг на каждый метод** в блоках setup / teardown |
| `@BeforeClass` / `@AfterClass` | один агрегированный узел на класс, время замеряется между событиями JUnit | **точный узел на каждый метод** и точное время |
| Шаги и вложения внутри класс-фикстур | да, кроме прогонов с surefire `parallel` (см. «Ограничения») | да |
| Синтетика: падение `@BeforeClass`, `@Ignore` на классе | да (JUnit не присылает по таким тестам событий - адаптер разворачивает класс сам) | да, листенер работает и здесь |
| Селективный прогон (mode 0) | report-time гейт: тесты **исполняются**, но результаты невыбранных не отправляются | **физический деселект**: невыбранный тест не исполняется вообще |
| Порядок прохождения по плану DoQA | нет | да, методы внутри класса |
| Реальные значения аргументов параметризованного теста | нет, только разбор имени инвокации | нужна фабрика для `Parameterized` (см. ниже) |

Рунер - обычный `BlockJUnit4ClassRunner`, поэтому он не совместим с классами, у которых уже есть
свой рунер (`SpringRunner`, `MockitoJUnitRunner`, `Parameterized`, `Suite`, `Enclosed`). Для них
работают базовый слой и `DoqaSelectRule` (см. «Селективный прогон»).

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

---

## Полная конфигурация

Источники (по возрастанию приоритета): файл `doqa.properties` → переменные окружения `DOQA_*` →
JVM-properties `-Ddoqa.*`. Путь к файлу можно переопределить: `-Ddoqa.config=…` / `DOQA_CONFIG`.

| Ключ (`doqa.properties` / `-Ddoqa.<ключ>`) | Env | Что это | Дефолт |
|---|---|---|---|
| `reporting` | `DOQA_REPORTING` | `api` / `files` / `auto` / `off` | `auto` |
| `resultsDir` | `DOQA_RESULTS_DIR` | каталог файлового режима | `results` |
| `url` | `DOQA_URL` | адрес DoQA | - |
| `token` | `DOQA_TOKEN` | project/personal token | - |
| `spaceId` | `DOQA_SPACE_ID` | id пространства | - |
| `configurationId` | `DOQA_CONFIGURATION_ID` | конфигурация прогона (browser/OS/env) | - |
| `testRunId` | `DOQA_TEST_RUN_ID` | существующий ран (нужен для mode 0 и 1) | - |
| `testRunName` | `DOQA_TEST_RUN_NAME` | имя создаваемого рана (mode 2) | - |
| `adapterMode` | `DOQA_ADAPTER_MODE` | режим выбора рана: `0` - selective, `1` - existing, `2` - new — см. ниже | `2` |
| `importRealtime` | `DOQA_IMPORT_REALTIME` | `true` = стрим результатов по мере прогона (пакет на каждый завершённый класс, вместе с его `@AfterClass`) | `false` (батч в конце) |
| `certValidation` | `DOQA_CERT_VALIDATION` | `false` = доверять самоподписанным TLS (отключает и проверку hostname) | `true` |
| `proxy` | `DOQA_PROXY` | `host:port` | - |
| `environment` | `DOQA_ENVIRONMENT` | метка окружения прогона (матрица окружений DoQA) | - |
| `pipelineId` | `DOQA_PIPELINE_ID` | привязка рана к CI-пайплайну | авто: `CI_PIPELINE_ID` / `GITHUB_RUN_ID` |
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
- **mode 0 / `selective`** - **селективный прогон**: адаптер спрашивает у DoQA, какие автотесты числятся в ране
  `testRunId`, и работает только с ними. Это тот режим, которым Run Player DoQA перезапускает
  выбранные тесты; насколько глубоко он умеет отсекать лишнее в JUnit 4 - см. следующий раздел.

---

## Селективный прогон и порядок по плану DoQA

В JUnit 4 нет глобального хука фильтрации (`RunListener` не может скипнуть тест, а внешний
`Filter` провайдеры surefire наружу не отдают), поэтому у mode 0 три уровня - выбирайте по тому,
готовы ли вы менять тестовые классы.

**1. Базовый слой (ничего менять не нужно).** Report-time гейт: тесты исполняются все, но
результаты автотестов, которых нет в ране, не отправляются. Ран получается корректным, время CI
не экономится.

**2. `@RunWith(DoqaRunner.class)` - физический деселект.** Невыбранный тест не исполняется:
рунер помечает его как ignored, JUnit сообщает `skipped`, адаптер по нему молчит. Это же
включает порядок по плану.

**3. `DoqaSelectRule` - для классов со своим рунером.** Правило скипает невыбранный тест через
assumption: ни `@Before`, ни тело не исполняются.

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

Вне mode 0 - и всегда, когда идентификация не разрешилась, - и рунер, и правило пропускают тест
дальше: адаптер не имеет права выкинуть тест из-за собственной поломки.

**Порядок прохождения по плану.** Селективный список mode 0 приходит от DoQA упорядоченным
(порядок набора запуска, задаётся в UI drag-sort'ом). `DoqaRunner` исполняет тесты класса в этом
порядке; тесты вне плана стабильно уходят в хвост, состав прогона порядок никогда не меняет.
Честные границы: только методы внутри класса (порядок самих классов задаёт хост, например
`runOrder` у surefire), только при пришедшем плане и никогда для классов с `@FixMethodOrder` -
там порядок пиновал автор теста.

> ⚠️ Если ран выбрал автотесты, но ни один из них не совпал с тем, что нашёл хост, в логе будет
> WARNING со списком выбранных `externalId`: обычно это переименованный метод или переехавший
> класс - перерепортите сюит, чтобы каталог DoQA подхватил актуальные id.

Отдельный вариант - **платформенный запуск**: если вы уже гоняете JUnit 4 через
`junit-platform` + `junit-vintage-engine`, деселект на discovery делает адаптер
[`doqa-junit5`](../doqa-junit5/README.md) своим `PostDiscoveryFilter` (JUnit-4-тесты приходят к
нему от vintage-движка). Это путь другого адаптера, не этого: `doqa-junit4` в таком прогоне не
участвует, JUnit-4-специфика (узлы `@BeforeClass`, фазы `@Before`/`@After`) там не появится, а
fallback-id считается иначе - если пойдёте этим путём, размечайте тесты явными `@DoqaId`.

---

## Разметка тестов (всё опционально)

Аннотации `@Doqa*` и рантайм-фасад `app.doqa.Doqa` - те же, что в JUnit 5: они живут в общем ядре
`doqa-java-commons`, смена фреймворка не потребует править импорты.

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
`[DOQA-123]` или `@DOQA:123` в display name → Allure `@AllureId` (читается без зависимости от
Allure - удобно при миграции) → детерминированный хэш сигнатуры метода. История автотеста
стабильна в любом случае; явный id делает её устойчивой ещё и к переименованиям.

### Нативная разметка JUnit 4

Двойная разметка не нужна - то, что вы уже написали, адаптер читает сам:

| Что в тесте | Что в DoQA |
|---|---|
| `@Category({Smoke.class, Ui.class})` на методе и/или на классе | теги автотеста: `Smoke`, `Ui` (простые имена классов-категорий, метод и класс объединяются) |
| `@Ignore("нужен стенд")` на методе | `skipped` с причиной |
| `@Ignore` на классе | `skipped` по каждому `@Test`-методу класса |
| вложенные классы (`Suite`, `Enclosed`) | тесты вложенного класса получают и класс-фикстуры внешнего класса, а в realtime-режиме уезжают вместе с ним |

### Параметризованные тесты

Реальные значения аргументов инвокации адаптер видит только через свою фабрику рунеров - JUnit 4
не публикует их ни в `Description`, ни листенеру:

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

Имена параметров берутся из полей `@Parameterized.Parameter`, а при инъекции через конструктор -
из имён его аргументов, для чего проект должен компилироваться с флагом `-parameters`
(в maven-compiler-plugin - `<configuration><parameters>true</parameters></configuration>`).
Иначе параметры будут называться `arg0..argN`.

**Без фабрики** параметризованный тест тоже репортится, но параметр восстанавливается из имени
инвокации: `@Parameters(name = "{index}: browser={0}")` даст один параметр `arguments` =
`browser=chrome`, а дефолтное имя (только индекс) - ни одного. `{param}`-плейсхолдеры в этом
случае **не раскрываются**; обходные пути - задать значение из тела теста
(`Doqa.addParameter("browser", browser)`, после чего `@DoqaId("LOGIN-{browser}")` раскроется) или
сразу `Doqa.addExternalId("LOGIN-" + browser)`.

Во всех вариантах инвокации одного метода сворачиваются в **один** автотест, пока в аннотациях нет
плейсхолдера: суффикс `[0: browser=chrome]` в идентификацию не входит.

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

Все вызовы безопасны: вне активного теста (или при `reporting=off`) они просто no-op.

Шаги и вложения из потоков, которые тест порождает сам (async-код, свои executor'ы), нужно
явно перенести в контекст теста:

```java
Doqa.Context ctx = Doqa.captureContext();
executor.submit(() -> Doqa.runWith(ctx, () -> Doqa.step("проверка из воркера")));
```

---

## Фикстуры и шаги-аннотации: что включить

Адаптер работает и без этого раздела, но с ним отчёт полный.

### 1. Фикстуры и фазы → `@RunWith(DoqaRunner.class)`

Отдельный узел на каждый `@Before`/`@After`, точные узлы `@BeforeClass`/`@AfterClass`, шаги внутри
класс-фикстур и разложение шагов по фазам setup / call / teardown даёт только рунер (см. таблицу
в разделе «Два слоя»). Классам со своим рунером остаётся базовый слой: там всё, что происходит в
тесте и его фикстурах, попадает в один блок шагов результата.

### 2. Шаги `@Step` → подключите AspectJ-агент к тестовой JVM

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

Gradle-эквивалент (регистрация листенера - через `@RunWith(DoqaRunner.class)` в тестах):

```groovy
configurations { doqaAgent }
dependencies { doqaAgent "org.aspectj:aspectjweaver:1.9.24" }
test {
    jvmArgs "-javaagent:${configurations.doqaAgent.singleFile}",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED"
}
```

Не хотите агент - используйте явный `Doqa.step("…", () -> …)`, он работает всегда.

> Версия weaver'а определяет максимальную версию байткода хоста: для новых JDK берите
> актуальный `aspectjweaver` (1.9.24 покрывает JDK ≤ 24).

---

## Маппинг исходов

| Что случилось | Исход в DoQA |
|---|---|
| Тест прошёл (в том числе `@Test(expected = …)`, отработавший как ожидалось) | `passed` |
| Упала проверка (`AssertionError`, `ComparisonFailure`, AssertJ, opentest4j) | `failed` |
| Любое другое исключение (инфраструктура, NPE, таймаут) | `broken` |
| `@Ignore` на методе или на классе | `skipped` (причина - текст `@Ignore`) |
| Невыполненное `Assume` (в тесте, в `@Before`, в `@BeforeClass`) | `skipped` |
| Упало `@BeforeClass` / `@ClassRule` | по одному результату на каждый тест класса, ошибка ещё и на узле `@BeforeClass` |

`failed` vs `broken` - важное различие: кластеризация ошибок и flaky-аналитика DoQA обрабатывают
их по-разному.

Отдельный случай JUnit 4: **один тест может упасть дважды** - тело и `@After`. JUnit присылает по
событию на каждую ошибку, адаптер собирает их в **один** результат: сообщения и стек-трейсы
склеиваются, исход - `broken`, если хоть одна ошибка не является assertion, иначе `failed`.

---

## Ограничения

Честный список того, чего адаптер не делает или делает хуже, чем в JUnit 5.

- **Минимум JUnit 4.13** (проверяется на 4.13.2). Адаптер сознательно не вызывает 4.13-only API,
  поэтому форк на 4.12 не падает, но там нет ни suite-событий, ни точки перехвата отдельного
  фикстур-метода: узлы `@BeforeClass`/`@AfterClass` базовым слоем не собираются, `@Before`/`@After`
  не становятся отдельными шагами даже с рунером, а realtime-стрим уезжает по границе классов, а не
  сразу после `@AfterClass`. Исходы, шаги, вложения и параметры работают. На 4.11 и ниже гарантий нет.
- **Несколько форков = несколько ранов.** При `forkCount>1` или `reuseForks=false` каждый форк -
  своя JVM, и в mode 2 каждая создаст свой тест-ран. Задавайте `testRunId` + `adapterMode=1`
  (или mode 0) - тогда все форки пишут в один ран.
- **surefire `parallel`.** Suite-события приходят на `main`, а тесты - на потоках пула, поэтому в
  базовом слое шаги и вложения **внутри** класс-фикстур не гарантируются (сами узлы фикстур
  записываются). С `@RunWith(DoqaRunner.class)` ограничения нет: фикстуры пишутся на том потоке,
  где исполняются.
- **Длительность класс-фикстуры в базовом слое замеряется между событиями JUnit**, поэтому в неё
  попадает и работа рунера в этом окне (конструирование класса, вычисление `@ClassRule`). Рунер
  замеряет сами методы.
- **`@Rule`/`@ClassRule` не становятся отдельными узлами** отчёта: правило оборачивает тест
  целиком, отдельной границы у него нет.
- **Порядок по плану** применяется к методам внутри класса, только в mode 0 с пришедшим планом и
  никогда - к классам с `@FixMethodOrder`. Порядок самих классов задаёт хост.
- **`@Theory`-тесты** репортятся как обычные тесты, но значения датапоинтов в параметры результата
  не попадают. Динамических тестов в JUnit 4 нет вовсе.
- **Не подключайте `doqa-junit4` и `doqa-junit5` к одному прогону**: идентификация фреймворка в
  общем ядре одна на JVM, и адаптеры перезапишут её друг другу.
- **Переход JUnit 4 → JUnit 5 без явного `@DoqaId` начинает историю автотеста заново**:
  fallback-id считается от подписи метода с префиксом фреймворка (`junit4:<hash>` против
  `junit5:<hash>`), да и сама подпись у фреймворков разная. Лечится единственным способом -
  проставить `@DoqaId` до переезда.

---

## Траблшутинг

| Симптом | Причина и лечение |
|---|---|
| Результатов нет вообще, в логе ни строчки от DoQA | **самый частый случай**: листенер не зарегистрирован. Maven - surefire-свойство `listener` (см. «Быстрый старт», шаг 2), Gradle/IDE - `@RunWith(DoqaRunner.class)` |
| `Tests run: 0`, сборка зелёная - после появления `junit-jupiter` на test-classpath | surefire переключился на `JUnitPlatformProvider` и JUnit-4-классы больше никто не находит: уберите jupiter из тестовых зависимостей либо добавьте `junit-vintage-engine` и переходите на платформенный запуск |
| Результаты в `results/`, а ждали в DoQA | это `auto` без API-конфига - в логе есть WARNING «no reporting configuration found (missing …)»; задайте `url`/`token`/`spaceId`. Если файловый режим выбран сознательно, поставьте `reporting=files` - предупреждение исчезнет |
| `NoSuchMethodError: DoqaStepAspect.aspectOf()` | вы сузили вивинг своим `aop.xml` и исключили аспект - верните `<include within="app.doqa.aspects.DoqaStepAspect"/>` |
| `@Step`-шаги не появляются | не подключён `-javaagent:aspectjweaver` (см. «Фикстуры и шаги») |
| На JDK 16+ падает вивер / нет шагов | добавьте `--add-opens java.base/java.lang=ALL-UNNAMED` к argLine |
| Нет узлов setup/teardown, `@Before`/`@After` не видны отдельно | не подключён `@RunWith(DoqaRunner.class)` - базовый слой отдельных узлов не даёт |
| Параметры называются `arg0`, `arg1` | включите `-parameters` у компилятора либо инжектьте параметры полями `@Parameterized.Parameter` |
| `{param}` в `@DoqaId`/`@DoqaTitle` не раскрылся | нет `@Parameterized.UseParametersRunnerFactory(DoqaParametersRunnerFactory.class)`; либо задайте значение сами - `Doqa.addParameter(...)` / `Doqa.addExternalId(...)` |
| В селективном ране невыбранные тесты всё равно исполняются | базовый слой отсекает только отправку результатов; для физического деселекта нужен `@RunWith(DoqaRunner.class)` или `DoqaSelectRule` |
| Селективный ран не выполнил ничего, в логе WARNING со списком `externalId` | id в DoQA и в коде разъехались (переименование/переезд) - перерепортите сюит, чтобы каталог подхватил актуальные id |
| Один прогон превратился в несколько ранов DoQA | `forkCount>1` / `reuseForks=false`: задайте `testRunId` и `adapterMode=1` |
| Локальные прогоны спамят раны в DoQA | уберите токен из локального конфига или поставьте локально `reporting=files` |
| Самоподписанный сертификат | `certValidation=false` (только для тестовых стендов!) |

Ошибка отправки **никогда не роняет сборку** - адаптер пишет WARNING и продолжает.

---

## Миграция с Allure

- **Allure**: разметка подхватывается автоматически и без зависимости от Allure - `@AllureId`
  (атрибуция `ALLURE-<id>`), `@Epic`/`@Feature`/`@Story`/`@Owner`/`@Severity` (→ `key:value`-метки),
  `@Link` и URL-значные `@Issue`/`@TmsLink` (→ типизированные ссылки), `@Description`. Файловый
  режим эмитит Allure-совместимые результаты с лейблом `framework: junit4` - существующий пайплайн
  загрузки продолжит работать.
- Регистрация тоже знакомая: `allure-junit4` подключается тем же surefire-свойством `listener`,
  так что менять придётся только имя класса.

## Сборка адаптера из исходников

Адаптер живёт в монорепозитории `doqa-java` вместе со своими зависимостями
(`doqa-java-commons`, `doqa-client`) и собирается из его корня одной командой - порядок
модулей разруливает reactor:

```bash
mvn clean verify        # либо точечно: mvn -pl doqa-junit4 -am clean verify
```

## Лицензия

[Apache License 2.0](../LICENSE).
