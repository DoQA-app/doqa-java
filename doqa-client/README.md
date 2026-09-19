# doqa-client: клиент DoQA Autotest API для JVM

`app.doqa:doqa-client` отправляет запросы к DoQA Autotest API и записывает результаты в файлы
Allure-совместимого формата. На нём работают адаптеры тестовых фреймворков DoQA, например
`app.doqa:doqa-junit5`.

Внешних зависимостей во время выполнения у модуля нет: HTTP-запросы выполняются через
`java.net.http` (JDK 11+), JSON обрабатывается встроенным кодеком. Классы Allure, JUnit и Jackson
модуль в classpath проекта не добавляет.

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

Обычно этот артефакт напрямую не нужен: подключите адаптер своего фреймворка (например,
`app.doqa:doqa-junit5`), он добавит клиент транзитивно.

## Настройки

Клиент читает настройки из трёх источников. По убыванию приоритета: системные свойства JVM
`-Ddoqa.*`, переменные окружения `DOQA_*`, файл `doqa.properties`. Файл ищется в рабочей директории
или по пути из `-Ddoqa.config` / `DOQA_CONFIG` и читается в UTF-8.

| Ключ (`doqa.properties` / `-Ddoqa.*`) | Переменная окружения | По умолчанию | Назначение |
|---|---|---|---|
| `url` | `DOQA_URL` | — | базовый URL DoQA |
| `token` (алиас `privateToken`) | `DOQA_TOKEN` (`DOQA_PRIVATE_TOKEN`) | — | токен API |
| `spaceId` (алиас `projectId`) | `DOQA_SPACE_ID` (`DOQA_PROJECT_ID`) | — | id пространства |
| `configurationId` | `DOQA_CONFIGURATION_ID` | — | конфигурация прогона |
| `testRunId` | `DOQA_TEST_RUN_ID` | — | существующий прогон (режимы 0 и 1) |
| `testRunName` | `DOQA_TEST_RUN_NAME` | — | имя создаваемого прогона (режим 2) |
| `adapterMode` | `DOQA_ADAPTER_MODE` | `2` | режим выбора прогона (`0`/`selective`, `1`/`existing`, `2`/`new`), см. ниже |
| `importRealtime` | `DOQA_IMPORT_REALTIME` | `false` | `true` — отправлять результаты по ходу прогона (пакет на каждый завершённый класс) |
| `reporting` | `DOQA_REPORTING` | `auto` | способ отправки: `api` / `files` / `auto` / `off` |
| `resultsDir` | `DOQA_RESULTS_DIR` | `results` | каталог для файлов результатов |
| `environment` | `DOQA_ENVIRONMENT` | — | метка окружения прогона (матрица окружений DoQA) |
| `certValidation` | `DOQA_CERT_VALIDATION` | `true` | проверка TLS; `false` отключает и проверку имени хоста |
| `proxy` | `DOQA_PROXY` | — | HTTP-прокси, `host:port` |
| `pipelineId` | `DOQA_PIPELINE_ID` | из переменных CI | id пайплайна CI, который DoQA показывает у прогона |
| `branch` | `DOQA_BRANCH` | из переменных CI | ветка, которую DoQA показывает у прогона |
| `batchSize` | `DOQA_BATCH_SIZE` | `100` | максимальное число результатов в одном запросе |
| `requestTimeoutMs` | `DOQA_REQUEST_TIMEOUT_MS` | `30000` | таймаут HTTP-запроса, мс |
| `retries` | `DOQA_RETRIES` | `3` | общее число попыток на запрос; какие запросы повторяются, описано ниже |
| `retryBackoffMs` | `DOQA_RETRY_BACKOFF_MS` | `500` | пауза перед второй попыткой, мс; перед каждой следующей удваивается |
| `maxTraceLength` | `DOQA_MAX_TRACE_LENGTH` | `100000` | максимальная длина stack trace, символов |
| `maxMessageLength` | `DOQA_MAX_MESSAGE_LENGTH` | `10000` | максимальная длина сообщения, символов |
| `maxParameterLength` | `DOQA_MAX_PARAMETER_LENGTH` | `2000` | максимальная длина значения параметра, символов |

`pipelineId` и `branch` по умолчанию берутся из переменных CI: `CI_PIPELINE_ID` и
`CI_COMMIT_REF_NAME` в GitLab, `GITHUB_RUN_ID` и `GITHUB_REF_NAME` в GitHub Actions. Переменные
`DOQA_PIPELINE_ID` и `DOQA_BRANCH` имеют приоритет над ними.

Пустое системное свойство (например, `-Ddoqa.pipelineId=`) сбрасывает значение из переменных
окружения и файла. Пустые переменные окружения не учитываются: CI-системы передают их для
незаданных настроек. Значение вида `$DOQA_TOKEN` или `${DOQA_TOKEN}` клиент считает незаданным и
пишет в лог предупреждение: так выглядит переменная CI, которая не существует.

Если числовое значение не разбирается, клиент использует значение по умолчанию. Булевы значения
`1`, `true`, `yes`, `on` и `y` означают «да», любое другое значение означает «нет».

### Способ отправки (`reporting`)

- `api` — результаты отправляются в DoQA Autotest API. Нужны `url`, `token` и `spaceId`; если их
  не хватает, адаптер пишет в лог WARNING и отключается.
- `files` — результаты записываются в `resultsDir` в Allure-совместимом формате без обращения к
  DoQA. Этот режим нужен пайплайнам, которые загружают результаты артефактами.
- `auto` (по умолчанию) — `api`, если заданы `url`, `token` и `spaceId`, иначе `files`. При
  переходе в `files` из-за недостающих настроек адаптер пишет в лог WARNING и перечисляет, каких
  настроек не хватает. Чтобы использовать файловый режим без предупреждения, задайте
  `reporting=files`.
- `off` — адаптер ничего не записывает и не отправляет.

Если прогон не удалось установить (DoQA не отвечает, отклоняет токен или не создаёт прогон,
например, с ответами 401, 403, 422), адаптер пишет результаты всего прогона в файлы и выводит
WARNING с причиной. Если DoQA отклонил пакет результатов уже установленного прогона, в файлы
пишется только этот пакет, остальные адаптер продолжает отправлять через API. В `resultsDir` при
этом лежит файл `doqa-reporting.properties` с полями `sink=api|files`, `runId`, `delivered`,
`fallbackResults`. По нему шаг загрузки в пайплайне определяет, остались ли результаты для
загрузки.

### Режим выбора прогона (`adapterMode`)

- `2` / `new` (по умолчанию) — создать новый прогон и отправить в него все результаты. Запрос на
  создание содержит ключ `external_key`, уникальный для процесса, поэтому повтор запроса не
  создаёт второй прогон.
- `1` / `existing` — отправить все результаты в существующий прогон `testRunId`. Если `testRunId`
  задан, а `adapterMode` нет, используется этот режим, чтобы указанный прогон не был
  проигнорирован.
- `0` / `selective` — отправить в существующий прогон `testRunId` только результаты выбранных в нём
  автотестов. Порядок выборки, заданный в DoQA, сохраняется.

### Повторы запросов

GET-запросы и создание прогона с ключом повторяются при ответах 5xx и сетевых ошибках, любой запрос
повторяется при ответе 429. Остальные POST-запросы при ответе 5xx не повторяются, а при сетевой
ошибке повторяются только в случае, когда соединение не установилось: если ответ потерян, повтор
мог бы создать дубликат прогона или результата.

После 5 запросов подряд, завершившихся ошибкой, клиент 30 секунд не обращается к DoQA и сразу
завершает запросы ошибкой, затем делает одну пробную попытку. Так недоступный сервер не тратит
`retries × timeout` на каждый вызов. Токен в сообщения об ошибках не попадает.

## Использование

Основные классы:

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

Вложения загружаются до отправки результатов, а в результате указываются через
`new Attachment(mediaFileId)`. `client.uploadAttachment(path)` передаёт файл с диска потоком, не
загружая его целиком в память, поэтому подходит для больших видео.
`client.uploadAttachment(name, bytes, contentType)` загружает данные из памяти. Content-type
multipart-части определяется по имени файла.

Для файлового режима `AllureFileWriter` записывает ту же модель `AutotestDef`/`AutotestResult` в
файлы `*-result.json` и `*-container.json`, которые принимает парсер DoQA. Если задан ключ
`environment`, он также записывает `environment.properties`.

HTTP-транспорт задаётся интерфейсом `Transport`. В тестах и адаптерах вместо него можно подставить
свою реализацию и работать без сети.

## Сборка

Модуль находится в монорепозитории `doqa-java` и собирается из его корня:

```bash
mvn clean verify        # либо точечно: mvn -pl doqa-client clean verify
```

Тесты проверяют клиент с фиктивной реализацией `Transport`, доступ в сеть не нужен.

## Лицензия

[Apache License 2.0](../LICENSE)
