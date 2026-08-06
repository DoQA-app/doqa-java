# doqa-client - клиентское ядро DoQA Autotest API (JVM)

`app.doqa:doqa-client` - общее JVM-ядро, на котором работают адаптеры тестовых фреймворков DoQA
(например, `app.doqa:doqa-junit5`). Умеет говорить с DoQA Autotest API напрямую и эмитить
Allure-совместимые файлы результатов для пайплайнов, которые загружают артефакты вместо API.

**Ноль рантайм-зависимостей**: HTTP через `java.net.http` (JDK 11+), JSON через небольшой
встроенный кодек. Ничего из Allure, JUnit или Jackson в classpath хоста не протекает -
библиотеку безопасно добавлять в любой тестовый проект.

## Требования

- JDK 11 или новее.

## Установка

```xml
<dependency>
    <groupId>app.doqa</groupId>
    <artifactId>doqa-client</artifactId>
    <version>0.1.1</version>
</dependency>
```

Большинству проектов этот артефакт напрямую не нужен - подключайте адаптер своего фреймворка
(например, `app.doqa:doqa-junit5`), он принесёт ядро транзитивно.

## Конфигурация

Настройки собираются из трёх источников; приоритет:
**системные properties (`-Ddoqa.*`) > переменные окружения (`DOQA_*`) > файл `doqa.properties`**.
Файл ищется в рабочей директории либо по пути из `-Ddoqa.config` / `DOQA_CONFIG`.

| Ключ (`doqa.properties` / `-Ddoqa.*`) | Env-переменная | Дефолт | Назначение |
|---|---|---|---|
| `url` | `DOQA_URL` | - | базовый URL DoQA |
| `token` (алиас `privateToken`) | `DOQA_TOKEN` (`DOQA_PRIVATE_TOKEN`) | - | API-токен |
| `spaceId` (алиас `projectId`) | `DOQA_SPACE_ID` (`DOQA_PROJECT_ID`) | - | целевое пространство |
| `configurationId` | `DOQA_CONFIGURATION_ID` | - | конфигурация, в которую репортим |
| `testRunId` | `DOQA_TEST_RUN_ID` | - | существующий тест-ран (режимы 0/1) |
| `testRunName` | `DOQA_TEST_RUN_NAME` | - | имя создаваемого рана (режим 2) |
| `adapterMode` | `DOQA_ADAPTER_MODE` | `2` | режим выбора рана (`0|selective` / `1|existing` / `2|new`), см. ниже |
| `importRealtime` | `DOQA_IMPORT_REALTIME` | `false` | `true` = стрим результатов по ходу прогона (пакетом на завершённый класс) |
| `reporting` | `DOQA_REPORTING` | `auto` | sink: `api` / `files` / `auto` / `off` |
| `resultsDir` | `DOQA_RESULTS_DIR` | `results` | каталог файлового синка |
| `environment` | `DOQA_ENVIRONMENT` | - | метка окружения прогона (матрица окружений DoQA) |
| `certValidation` | `DOQA_CERT_VALIDATION` | `true` | проверка TLS (`false` отключает и проверку hostname) |
| `proxy` | `DOQA_PROXY` | - | HTTP-прокси, `host:port` |
| `pipelineId` | `DOQA_PIPELINE_ID` | авто | id CI-пайплайна, показывается на ране |
| `branch` | `DOQA_BRANCH` | авто | ветка, показывается на ране |
| `batchSize` | `DOQA_BATCH_SIZE` | `100` | максимум результатов в одном батч-запросе |
| `requestTimeoutMs` | `DOQA_REQUEST_TIMEOUT_MS` | `30000` | таймаут HTTP-запроса |
| `retries` | `DOQA_RETRIES` | `3` | попыток на запрос (ретраятся только безопасные повторы) |
| `retryBackoffMs` | `DOQA_RETRY_BACKOFF_MS` | `500` | базовая пауза между попытками (экспоненциальная) |
| `maxTraceLength` | `DOQA_MAX_TRACE_LENGTH` | `100000` | лимит длины stack trace (символов) |
| `maxMessageLength` | `DOQA_MAX_MESSAGE_LENGTH` | `10000` | лимит длины сообщений |
| `maxParameterLength` | `DOQA_MAX_PARAMETER_LENGTH` | `2000` | лимит длины значений параметров |

`pipelineId` и `branch` подхватываются автоматически из стандартных CI-переменных
(`CI_PIPELINE_ID` / `CI_COMMIT_REF_NAME` в GitLab, `GITHUB_RUN_ID` / `GITHUB_REF_NAME` в
GitHub Actions); переопределяются вариантами `DOQA_*`. Явно ПУСТАЯ системная property
(`-Ddoqa.pipelineId=`) очищает значение, унаследованное из нижнего слоя; пустые переменные
окружения игнорируются (CI-системы экспортируют их для незаданных настроек). Файл настроек
читается в UTF-8.

### Режимы репортинга (`reporting`)

- `api` - репорт напрямую в DoQA Autotest API (нужны `url`, `token`, `spaceId`);
- `files` - запись Allure-совместимых файлов результатов в `resultsDir` (без сети и без
  секретов) - для пайплайнов, загружающих результаты артефактами;
- `auto` (дефолт) - `api`, когда API-настройки полны, иначе `files`;
- `off` - репортинг выключен.

### Режимы выбора рана (`adapterMode`)

- `2` / `new` (дефолт) - создать новый тест-ран, репортить всё. Создание несёт per-process
  `external_key`, поэтому повтор запроса никогда не оставит два рана;
- `1` / `existing` - репортить всё в существующий ран (нужен `testRunId`). Если `testRunId`
  задан, а `adapterMode` явно не задан - подразумевается именно этот режим: указанный ран
  никогда не игнорируется молча;
- `0` / `selective` - репортить в существующий ран только выбранные в нём автотесты (нужен
  `testRunId`); серверный порядок выборки сохраняется.

### Семантика доставки

Ретраи уважают идемпотентность: GET-запросы, 429 и создание рана с ключом переигрываются при
5xx/сетевых ошибках; остальные POST повторяются только если соединение вообще не установилось -
потерянный ответ не должен продублировать ран или результат. Circuit breaker размыкается после
нескольких подряд отказов и на время «остывания» отвечает мгновенной ошибкой, чтобы мёртвый
бэкенд не стоил `retries × timeout` на каждый вызов. Токен никогда не попадает в сообщения
ошибок.

## Использование

Высокоуровневые точки входа:

```java
DoqaConfig config = DoqaConfig.resolve();
ApiClient client = new ApiClient(config);
RunContext run = RunContext.establish(client, config);

AutotestDef def = new AutotestDef("LOGIN-1", "Login works")
        .title("Login")
        .steps(List.of(Step.of("open the login page")));
client.upsertAutotests(List.of(def));

AutotestResult result = new AutotestResult("LOGIN-1", Outcome.PASSED)
        .name("Login works")
        .startedOn(startMillis).completedOn(stopMillis).durationMs(stopMillis - startMillis);
client.uploadResults(run.runId(), run.configurationId(), List.of(result));
```

Вложения загружаются заранее и затем указываются в результатах через
`new Attachment(mediaFileId)`: `client.uploadAttachment(path)` стримит файл прямо с диска (без
копии в куче - безопасно для больших видео), `client.uploadAttachment(name, bytes, contentType)`
загружает контент из памяти; content-type multipart-части выводится из имени файла.

Для файлового синка `AllureFileWriter` сериализует ту же модель `AutotestDef`/`AutotestResult`
в файлы `*-result.json` / `*-container.json`, которые принимает парсер DoQA, плюс
`environment.properties`, когда задан ключ `environment`.

`Transport` - инжектируемый HTTP-шов: тесты и адаптеры подставляют фейковую реализацию и
работают полностью офлайн.

## Сборка

Модуль живёт в монорепозитории `doqa-java` и собирается из его корня:

```bash
mvn clean verify        # либо точечно: mvn -pl doqa-client clean verify
```

Тесты - контрактные фикстуры над мок-транспортом, сеть не нужна.

## Лицензия

[Apache License 2.0](../LICENSE)
