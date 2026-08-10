# DoQA JUnit 5 Adapter - `app.doqa:doqa-junit5`

Адаптер отправляет результаты ваших JUnit 5 тестов в DoQA: автотесты создаются/обновляются сами,
результаты приходят с шагами, фикстурами, параметрами, вложениями и ссылками. Работает в двух
режимах - **напрямую в API** DoQA или **файлами** (Allure-совместимые артефакты, без сети и без
токена в тестовом процессе). Ничего не ломает: если DoQA недоступен или не настроен, ваши тесты
проходят как обычно.

---

## Быстрый старт (2 минуты)

**Шаг 1.** Добавьте зависимость:

```xml
<dependency>
  <groupId>app.doqa</groupId>
  <artifactId>doqa-junit5</artifactId>
  <version>0.1.1</version>
  <scope>test</scope>
</dependency>
```

**Шаг 2.** Запустите тесты.

Без какой-либо конфигурации адаптер уже пишет результаты в `./results/` - файлы
Allure-совместимого формата, который принимает конвейер загрузки DoQA (заодно они открываются
обычным Allure Report). Загрузить их в DoQA можно командой `doqactl upload` или CI-джобой. Аннотации не обязательны: каждый тест
получает стабильный идентификатор автоматически.

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

| Ключ (`doqa.properties` / `-Ddoqa.<ключ>`) | Env | Что это                                                                                                 | Дефолт |
|---|---|---------------------------------------------------------------------------------------------------------|---|
| `reporting` | `DOQA_REPORTING` | `api` / `files` / `auto` / `off`                                                                        | `auto` |
| `resultsDir` | `DOQA_RESULTS_DIR` | каталог файлового режима                                                                                | `results` |
| `url` | `DOQA_URL` | адрес DoQA                                                                                              | - |
| `token` | `DOQA_TOKEN` | project/personal token                                                                                  | - |
| `spaceId` | `DOQA_SPACE_ID` | id пространства                                                                                         | - |
| `configurationId` | `DOQA_CONFIGURATION_ID` | конфигурация прогона (browser/OS/env)                                                                   | - |
| `testRunId` | `DOQA_TEST_RUN_ID` | существующий ран (нужен для mode 0 и 1)                                                                 | - |
| `testRunName` | `DOQA_TEST_RUN_NAME` | имя создаваемого рана (mode 2)                                                                          | - |
| `adapterMode` | `DOQA_ADAPTER_MODE` | режим выбора рана: `0` - selective, `1` - existing, `2` - new — см. ниже                                 | `2` |
| `importRealtime` | `DOQA_IMPORT_REALTIME` | `true` = стрим результатов по мере прогона (пакет на каждый завершённый класс, вместе с его `@AfterAll`) | `false` (батч в конце) |
| `certValidation` | `DOQA_CERT_VALIDATION` | `false` = доверять самоподписанным TLS (отключает и проверку hostname)                                  | `true` |
| `proxy` | `DOQA_PROXY` | `host:port`                                                                                             | - |
| `environment` | `DOQA_ENVIRONMENT` | метка окружения прогона (матрица окружений DoQA)                                                        | - |
| `pipelineId` | `DOQA_PIPELINE_ID` | привязка рана к CI-пайплайну                                                                            | авто: `CI_PIPELINE_ID` / `GITHUB_RUN_ID` |
| `branch` | `DOQA_BRANCH` | ветка прогона                                                                                           | авто: `CI_COMMIT_REF_NAME` / `GITHUB_REF_NAME` |
| `batchSize` | `DOQA_BATCH_SIZE` | максимум результатов в одном батч-запросе                                                               | `100` |
| `requestTimeoutMs` | `DOQA_REQUEST_TIMEOUT_MS` | таймаут HTTP-запроса                                                                                    | `30000` |
| `retries` | `DOQA_RETRIES` | попыток на запрос (ретраятся только безопасные повторы)                                                 | `3` |
| `retryBackoffMs` | `DOQA_RETRY_BACKOFF_MS` | базовая пауза между попытками (экспоненциальная)                                                        | `500` |
| `maxTraceLength` | `DOQA_MAX_TRACE_LENGTH` | лимит длины stack trace в результате (символов)                                                         | `100000` |
| `maxMessageLength` | `DOQA_MAX_MESSAGE_LENGTH` | лимит длины сообщений                                                                                   | `10000` |
| `maxParameterLength` | `DOQA_MAX_PARAMETER_LENGTH` | лимит длины значений параметров                                                                         | `2000` |

### Режимы запуска (API)

- **mode 2 / `new`** *(дефолт)* - адаптер сам создаёт тест-ран и шлёт всё в него. Для CI «просто прогони всё».
- **mode 1 / `existing`** - шлёт всё в существующий ран `testRunId` (ран создали заранее - из UI или API).
  Если задан `testRunId`, а `adapterMode` не задан явно - адаптер сам работает в этом режиме
  (указанный ран никогда не игнорируется молча).
- **mode 0 / `selective`** - **селективный прогон**: адаптер спрашивает у DoQA, какие автотесты числятся в ране
  `testRunId`, и физически исполняет только их (остальные деселектятся ещё на discovery - экономия
  времени CI). Это тот режим, которым Run Player DoQA перезапускает выбранные тесты.

---

## Порядок прохождения по плану DoQA (opt-in)

Селективный список mode 0 приходит от DoQA **упорядоченным** (порядок набора запуска, задаётся в
UI drag-sort'ом). Адаптер умеет исполнять тесты в этом порядке - через план-ориентированные
orderer'ы, но **включить их можете только вы** (JUnit Jupiter не позволяет адаптеру навязать
orderer программно):

```properties
# src/test/resources/junit-platform.properties
junit.jupiter.testclass.order.default=app.doqa.junit5.DoqaPlanClassOrderer
junit.jupiter.testmethod.order.default=app.doqa.junit5.DoqaPlanMethodOrderer
```

Семантика:

- **методы внутри класса** - по позиции их `externalId` в плане (`DoqaPlanMethodOrderer`);
- **классы** - по минимальной позиции их методов (`DoqaPlanClassOrderer`): Jupiter исполняет
  тесты **класс-блоками**, межклассовое чередование методов невозможно физически;
- тесты вне плана - стабильно в хвост (mode 0 их и так деселектит; порядок никогда не меняет
  СОСТАВ прогона);
- **без properties / без DoQA-сеанса / без плана** orderer'ы - строгий no-op: поведение по
  умолчанию не меняется.

Честные ограничения: только Jupiter-engine; порядок гарантируется только при последовательном
исполнении (`junit.jupiter.execution.parallel.enabled=false` - дефолт); при `@ParameterizedTest`
с плейсхолдером в `externalId` берётся позиция первого совпавшего id плана.

---

## Разметка тестов (всё опционально)

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

Ещё две аннотации управляют местом теста в дереве DoQA: `@DoqaNamespace` (по умолчанию - пакет)
и `@DoqaClassName` (по умолчанию - простое имя класса). Обе работают и на уровне класса.
Аннотации живут в пакете `app.doqa.annotations`, рантайм-фасад - `app.doqa.Doqa`: они общие для
всех JVM-адаптеров DoQA (`doqa-java-commons`), смена фреймворка не потребует править импорты.

`@DoqaCreateManualCase` - точечный opt-in на автоматическое создание ручного тест-кейса,
связанного с «сиротским» автотестом (тем, у которого нет привязки к кейсам). Маркер без
параметров: работает и на методе, и на классе (тогда - для всех его тестов, наследуется
подклассами) и действует независимо от того, включено ли такое создание в настройках
пространства DoQA. То же самое доступно из тела теста - `Doqa.addCreateManualCase()`; аннотация
и рантайм-вызов складываются, точечно выключить создание нельзя. Кейс заводится в момент приёма
результата: ретроспективно, для уже накопленных «сиротских» автотестов, ничего не создаётся.

Если `@DoqaId` нет, идентификатор ищется в таком порядке:
`[DOQA-123]` или `@DOQA:123` в display name → Allure `@AllureId` (читается без зависимости от
Allure - удобно при миграции) → детерминированный хэш сигнатуры метода. История автотеста
стабильна в любом случае; явный id делает её устойчивой ещё и к переименованиям.

### Параметризованные тесты

Аргументы каждой инвокации уезжают как именованные параметры результата:

```java
@ParameterizedTest
@ValueSource(strings = {"chrome", "firefox"})
void worksIn(String browser) { … }              // parameters: [{name: "browser", value: "chrome"}]
```

Хотите **отдельный автотест на каждую инвокацию** - используйте плейсхолдер `{имяАргумента}`
в любой аннотации (`externalId`, `title`, `displayName`, labels/tags/links/…):

```java
@ParameterizedTest
@ValueSource(strings = {"chrome", "firefox"})
@DoqaId("LOGIN-IN-{browser}")                   // → LOGIN-IN-chrome, LOGIN-IN-firefox
@DoqaTitle("Вход в {browser}")
void loginIn(String browser) { … }
```

> Аргументы инвокаций захватывает расширение `DoqaExtension` - включите автодетект расширений
> (см. раздел «Фикстуры» ниже), иначе плейсхолдер останется нераскрытым (`LOGIN-IN-{browser}`).
> А чтобы имена аргументов были настоящими (`browser`, а не `arg0`), включите флаг компилятора
> `-parameters`: в maven-compiler-plugin - `<configuration><parameters>true</parameters></configuration>`.

### Runtime-API - из тела теста

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

Все вызовы безопасны: вне активного теста (или при `reporting=off`) они просто no-op.
Нативные JUnit `@Tag` автоматически попадают в `tags` автотеста - двойная разметка не нужна.

Шаги и вложения из потоков, которые тест порождает сам (async-код, свои executor'ы), нужно
явно перенести в контекст теста:

```java
Doqa.Context ctx = Doqa.captureContext();
executor.submit(() -> Doqa.runWith(ctx, () -> Doqa.step("проверка из воркера")));
```

---

## Фикстуры и шаги-аннотации: два флага, максимум богатства

Адаптер работает и без них, но с ними отчёт полный.

### 1. Фикстуры + параметры инвокаций → включите автодетект расширений

```
# src/test/resources/junit-platform.properties
junit.jupiter.extensions.autodetection.enabled=true
```

(или точечно `@ExtendWith(DoqaExtension.class)` на классе). Это даёт:
- `@BeforeEach`/`@AfterEach` → блоки setup/teardown в каждом результате;
- `@BeforeAll`/`@AfterAll` → класс-фикстуры у всех тестов класса (в realtime-режиме класс
  отправляется после своего `@AfterAll`, так что teardown никогда не теряется);
- `Doqa.step`/`@Step`/`Doqa.addAttachment*` внутри класс-фикстур попадают в узел фикстуры;
- именованные параметры и плейсхолдеры `{param}`.

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
  </configuration>
</plugin>
```

Gradle-эквивалент:

```groovy
configurations { doqaAgent }
dependencies { doqaAgent "org.aspectj:aspectjweaver:1.9.24" }
test {
    jvmArgs "-javaagent:${configurations.doqaAgent.singleFile}",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    systemProperty "junit.jupiter.extensions.autodetection.enabled", "true"
}
```

Не хотите агент - используйте явный `Doqa.step("…", () -> …)`, он работает всегда.

> Версия weaver'а определяет максимальную версию байткода хоста: для новых JDK берите
> актуальный `aspectjweaver` (1.9.24 покрывает JDK ≤ 24).

---

## Маппинг исходов

| Что случилось | Исход в DoQA |
|---|---|
| Тест прошёл | `passed` |
| Упала проверка (`AssertionError`, AssertJ, opentest4j) | `failed` |
| Любое другое исключение (инфраструктура, NPE, таймаут) | `broken` |
| `@Disabled` / assumption | `skipped` |

`failed` vs `broken` - важное различие: кластеризация ошибок и flaky-аналитика DoQA обрабатывают
их по-разному.

---

## Траблшутинг

| Симптом | Причина и лечение |
|---|---|
| Результатов нигде нет | `reporting=api` без `url`/`token`/`spaceId` - смотрите WARNING в логе; либо `reporting=off` |
| Результаты в `results/`, а ждали в DoQA | это `auto` без API-конфига - в логе есть WARNING «no reporting configuration found (missing …)»; задайте `url`/`token`/`spaceId`. Если файловый режим выбран сознательно, поставьте `reporting=files` - предупреждение исчезнет |
| `NoSuchMethodError: DoqaStepAspect.aspectOf()` | вы сузили вивинг своим `aop.xml` и исключили аспект - верните `<include within="app.doqa.aspects.DoqaStepAspect"/>` |
| `@Step`-шаги не появляются | не подключён `-javaagent:aspectjweaver` (см. выше) |
| На JDK 16+ падает вивер / нет шагов | добавьте `--add-opens java.base/java.lang=ALL-UNNAMED` к argLine |
| Параметры называются `arg0`, `arg1` | включите `-parameters` у компилятора |
| Нет setup/teardown блоков | не включён автодетект расширений (см. «Фикстуры») |
| Самоподписанный сертификат | `certValidation=false` (только для тестовых стендов!) |
| Локальные прогоны спамят раны в DoQA | уберите токен из локального конфига или поставьте локально `reporting=files` |

Ошибка отправки **никогда не роняет сборку** - адаптер пишет WARNING и продолжает.

---

## Миграция с Allure

- **Allure**: разметка подхватывается автоматически и без зависимости от Allure - `@AllureId`
  (атрибуция `ALLURE-<id>`), `@Epic`/`@Feature`/`@Story`/`@Owner`/`@Severity` (→ `key:value`-метки),
  `@Link` и URL-значные `@Issue`/`@TmsLink` (→ типизированные ссылки), `@Description`. Файловый
  режим эмитит Allure-совместимые результаты - существующий пайплайн загрузки продолжит работать.

## Сборка адаптера из исходников

Адаптер живёт в монорепозитории `doqa-java` вместе со своими зависимостями
(`doqa-java-commons`, `doqa-client`) и собирается из его корня одной командой - порядок
модулей разруливает reactor:

```bash
mvn clean verify        # либо точечно: mvn -pl doqa-junit5 -am clean verify
```

## Лицензия

[Apache License 2.0](../LICENSE).
