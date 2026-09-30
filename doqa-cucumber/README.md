# Адаптер Cucumber-JVM: `app.doqa:doqa-cucumber`

Плагин Cucumber-JVM передаёт результаты сценариев в DoQA: через API DoQA или через файлы
Allure-совместимого формата, которые загружаются в DoQA отдельным шагом. Каждый сценарий становится
автотестом, шаги Gherkin становятся шагами результата, хуки `@Before` и `@After` становятся шагами
setup и teardown, а строки Examples у Scenario Outline становятся результатами одного автотеста с
параметрами. Кейсы DoQA
привязываются тегами в `.feature`.

Ошибки отправки не останавливают сценарии и не меняют результат сборки: плагин пишет WARNING в лог
и продолжает работу.

Один модуль работает со всеми тремя способами запустить Cucumber: JUnit Platform
(`cucumber-junit-platform-engine`, JUnit 5 и 6), JUnit 4 (`cucumber-junit`) и TestNG
(`cucumber-testng`). Поддерживаются Cucumber-JVM **7.x и 8.x**; плагин собран против Cucumber 7.0.0
и использует только API плагинов (`io.cucumber.plugin`), одинаковый в этих версиях. Требуется JDK 11
или новее, Cucumber 8 сам требует JDK 17. На JUnit Platform нужна версия 1.8 или новее.

---

## Быстрый старт

**Шаг 1.** Добавьте зависимость:

```xml
<dependency>
  <groupId>app.doqa</groupId>
  <artifactId>doqa-cucumber</artifactId>
  <version>0.1.8</version>
  <scope>test</scope>
</dependency>
```

```groovy
testImplementation("app.doqa:doqa-cucumber:0.1.8")   // Gradle
```

Все модули монорепозитория выпускаются с одной версией; актуальная указана в
[корневом README](../README.md).

**Шаг 2.** Добавьте плагин в конфигурацию Cucumber. У Cucumber нет автоматического подключения
плагинов, поэтому без этой строки плагин не работает.

JUnit Platform, `src/test/resources/junit-platform.properties` (через запятую с плагинами, которые
там уже есть):

```properties
cucumber.plugin=app.doqa.cucumber.DoqaCucumberPlugin
```

Если плагины заданы на классе `@Suite` через `@ConfigurationParameter`, допишите плагин туда: это
значение перекрывает и файл, и `-Dcucumber.plugin`.

```java
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, app.doqa.cucumber.DoqaCucumberPlugin")
public class RunCucumberTest {
}
```

JUnit 4 и TestNG:

```java
@CucumberOptions(plugin = {"pretty", "app.doqa.cucumber.DoqaCucumberPlugin"})
```

Здесь плагины из всех источников складываются, поэтому плагин можно добавить и снаружи, не меняя
код: переменной окружения `CUCUMBER_PLUGIN=app.doqa.cucumber.DoqaCucumberPlugin` в CI или
`-Dcucumber.plugin=…`.

Запуск из IntelliJ IDEA идёт через CLI Cucumber, мимо JUnit Platform: там читаются
`cucumber.properties`, переменные окружения и `-D`.

Если модуль подключён, а строки нет, на JUnit Platform первый сценарий даёт WARNING с готовой
строкой для конфигурации. На JUnit 4 и TestNG такой точки нет.

**Шаг 3.** Запустите сценарии. Если подключение к DoQA не настроено, плагин записывает результаты в
`./results/` в Allure-совместимом формате; загрузить их можно командой `doqactl upload` или
отдельной джобой CI. Чтобы отправлять результаты через API, задайте `url`, `token` и `spaceId`
в `doqa.properties`, переменных окружения `DOQA_*` или системных свойствах `-Ddoqa.*`.

```properties
url=https://demo.doqa.app
token=<project token из настроек пространства>
spaceId=42
```

---

## Настройки

Настройки те же, что у остальных JVM-адаптеров: все ключи `doqa.properties`, переменные `DOQA_*` и
`-Ddoqa.*` с тем же приоритетом, способы отправки `reporting=auto|api|files|off`, режимы прогона
0, 1 и 2, `importRealtime`, лимиты длины. Полная таблица в
[README адаптера TestNG](../doqa-testng/README.md#настройки), подробности разбора в
[README `doqa-client`](../doqa-client/README.md#настройки).

Особенности Cucumber:

- `importRealtime=true` отправляет результаты после каждого сценария: события «фича завершена» в
  Cucumber нет.
- Если DoQA недоступен на старте, результаты всего прогона записываются в `resultsDir`; если DoQA
  отклонил пакет посреди прогона, в файлы записывается только он. Рядом лежит
  `doqa-reporting.properties`, по нему джоба загрузки понимает, что осталось на диске.

---

## Что уходит в DoQA

| Поле автотеста | Значение |
|---|---|
| `name` | имя сценария; у Scenario Outline шаблон с `<параметрами>`. У результата строки Examples имя с подставленными значениями |
| `namespace` | каталог фичи относительно корня classpath: `features/payments` |
| `classname` | имя Feature |
| `runner_method` | имя сценария (шаблон), по нему DoQA строит нативный фильтр |
| `tags` | теги всех уровней (Feature, Rule, сценарий, Examples) без `@`, кроме служебных тегов DoQA |
| `parameters` результата | строка Examples списком «колонка = значение» в порядке колонок |

`name` и `runner_method` обрезаются до 255 символов, заголовок шага до 500: это лимиты сервера.

### Идентификатор автотеста

Сценарий становится автотестом. Scenario Outline даёт один автотест, каждая строка Examples даёт отдельный
результат с параметрами. Идентификатор выбирается по порядку:

1. `Doqa.addExternalId(...)` в коде шага;
2. тег `@doqa.id:<значение>`;
3. тег `@DOQA-<n>` / `@DOQA:<n>` или метка `[DOQA-<n>]` в имени сценария;
4. тег `@allure.id:<n>` (идентификатор `ALLURE-<n>`);
5. вычисляемый `cucumber:<sha1>` от `<путь фичи>#<имя сценария>`, внутри Rule
   `<путь фичи>#<имя Rule>/<имя сценария>`.

Путь фичи берётся относительно корня classpath (`features/payments/transfer.feature`), поэтому
выбор фич через `classpath:` и через файл даёт один идентификатор. Номер строки, случайный
`TestCase.getId()` и отображаемое имя JUnit (стратегия именования) в идентификатор не входят.
Переименование сценария, Rule или файла меняет вычисляемый идентификатор, как переименование метода
в Java; чтобы история сохранилась, закрепите его тегом `@doqa.id:`.

Если в одной фиче есть одноимённые сценарии или `@doqa.id:` стоит на уровне Feature, под одним
идентификатором оказывается несколько сценариев: плагин пишет WARNING о дубле. Строки одного Outline предупреждения не дают.

---

## Теги DoQA

Теги заменяют аннотации `@Doqa*`, которые в `.feature` повесить нельзя.

| Тег | Что делает | Аналог в коде |
|---|---|---|
| `@doqa.id:<значение>` | явный идентификатор, дословно. `{колонка}` подставляется из строки Examples, и строка становится отдельным автотестом | `@DoqaId` |
| `@DOQA-<n>` или `@DOQA:<n>` | идентификатор `DOQA-<n>`. То же даёт `[DOQA-<n>]` в имени сценария | нет |
| `@allure.id:<n>` | идентификатор `ALLURE-<n>` и поле `allure_id`. Числовой `allure_id` привязывает кейс `<n>` и снимает прочие привязки автотеста | `@AllureId` |
| `@doqa.case:<n>` | привязка кейса `<n>`. Несколько кейсов задаются повтором тега; старые связи не снимаются | `@DoqaCaseIds` |

```gherkin
@doqa.case:101
Feature: Перевод между счетами

  @doqa.id:transfer-basic
  Scenario: Простой перевод
    ...

  Scenario Outline: Перевод <amount> на счёт <to>
    ...
    @doqa.id:transfer-{to}
    Examples:
      | amount | to      |
      | 10     | savings |
```

Правила:

- **Приоритет.** Ветки идентификатора идут сверху вниз, как в таблице. Внутри ветки побеждает самый
  узкий уровень: Examples, сценарий, Rule, Feature. Два тега одной ветки на одном уровне дают
  WARNING, берётся первый.
- **Кейсы** из `@doqa.case:` объединяются со всех уровней и с `Doqa.addCaseIds(...)`. Нечисловое
  значение даёт WARNING, тег пропускается.
- **Написание.** Разделитель `:`, при чтении принимается и `=` (`@allure.id=5`, как пишет
  allure-cucumber). Регистр префикса `doqa.` не важен. Список через запятую не поддерживается:
  запятая запрещена в тегах JUnit, повторяйте тег.
- Служебные теги в `tags` автотеста не попадают.
- Из тегов allure-cucumber читается только `@allure.id`. Метки и ссылки (`@severity`, `@issue`,
  `@tmsLink`, `@allure.label.*`, `@allure.link.*`) задаются фасадом `Doqa` в коде шага.

---

## Шаги, хуки, вложения

| В Cucumber | В DoQA |
|---|---|
| Шаг Gherkin, включая Background | шаг «ключевое слово + текст» с ключевым словом из файла (`Дано …`). В определении Outline текст шаблона с `<…>`, в результате подставленный |
| DocString, DataTable | текстовое вложение шага |
| `@Before` / `@After` | шаги setup / teardown с именем `Класс.метод` |
| `@BeforeStep` / `@AfterStep` | отдельными шагами не показываются. Ошибка `@BeforeStep` попадает в сообщение следующего шага (Cucumber пропускает его), ошибка `@AfterStep` меняет исход шага, после которого хук выполнился. Вложения и сообщения хуков попадают в этот шаг |
| `@BeforeAll` / `@AfterAll` | не показываются: у них нет событий сценария |
| `scenario.attach(...)` | вложение текущего шага, вне шага вложение сценария |
| `scenario.log(...)` | сообщение текущего шага, как `Doqa.addMessage` |
| `Doqa.step`, `@Step` в коде шага | вложенный шаг текущего шага Gherkin |

Фасад `Doqa` работает в коде шагов и хуков так же, как в JUnit и TestNG: `Doqa.addLabel`,
`Doqa.addLink`, `Doqa.addCaseIds`, `Doqa.addExternalId` и остальные методы.

---

## Исходы

| Cucumber | DoQA |
|---|---|
| `PASSED` | `passed` |
| `FAILED` | `failed` для `AssertionError` и `AssertionFailedError`, иначе `broken` |
| `SKIPPED` | `skipped`: шаги после упавшего и исключения-предположения (`TestAbortedException`, `AssumptionViolatedException`, `SkipException`) |
| `PENDING` | `skipped` с сообщением, что шаг не реализован |
| `UNDEFINED` | `broken` с сообщением, что для шага нет определения, и текстом шага |
| `AMBIGUOUS` | `broken` с сообщением Cucumber |
| `UNUSED` | `skipped` |

Сценарии, отфильтрованные `cucumber.filter.tags` или `cucumber.filter.name`, в DoQA не уходят:
Cucumber не присылает по ним событий. Повтор сценария (rerun Surefire, rerun-файл) даёт новый результат
того же автотеста.

---

## Выборочный прогон (режим 0)

**JUnit Platform.** Модуль регистрирует свой фильтр discovery, и невыбранные сценарии исчезают из
плана запуска, в том числе отдельные строки Examples. Идентификатор считается из файла фичи и
строк в `UniqueId` узла тем же кодом, что в плагине, поэтому стратегия именования
(`cucumber.junit-platform.naming-strategy`) на отбор не влияет. Если идентификатор посчитать не
удалось, сценарий остаётся в прогоне: лишнее отсекут проверка при отправке и сервер. Вне режима 0
фильтр ничего не делает.

**JUnit 4 и TestNG.** Плагин не может исключить сценарий до запуска: выполняются все сценарии, в
DoQA уходят только выбранные. Лишнее выполнение убирает нативный фильтр (ниже).

Порядок выполнения по плану DoQA не поддерживается: порядком сценариев управляет Cucumber.

### Нативный фильтр

Для Cucumber DoQA строит регулярное выражение по именам сценариев (`^(?:имя1|имя2)$`, каждый `<…>`
шаблона Outline заменяется на `.*`) и передаёт его в пайплайн в переменной `DOQA_NATIVE_FILTER`.
Флаг Cucumber подставляет CI-рецепт:

```yaml
# .gitlab-ci.yml
test:
  script:
    - mvn test ${DOQA_NATIVE_FILTER:+-Dcucumber.filter.name="$DOQA_NATIVE_FILTER"}
```

- JUnit Platform читает только `-Dcucumber.filter.name`: Maven передаёт его в форк Surefire, в
  Gradle нужен `systemProperty` в задаче `test`.
- JUnit 4 и TestNG читают и `-Dcucumber.filter.name`, и переменную `CUCUMBER_FILTER_NAME`.
- Одноимённые сценарии разных фич запустятся оба, лишний отсечёт план.
- Если в выборке и юнит-тесты, и сценарии, фильтр не строится: для смешанного модуля отбор даёт
  только режим 0 на JUnit Platform.

---

## Совместная работа с другими адаптерами

Юнит-тесты и сценарии часто живут в одном модуле. Если в JVM создан `DoqaCucumberPlugin`,
`doqa-junit5`, `doqa-junit4` и `doqa-testng` пропускают сценарии Cucumber, и каждый сценарий
отправляется один раз, а юнит-тесты сохраняют свои идентификаторы (`junit5:…`, `testng:…`). Сессия
и прогон DoQA на JVM одни. Если модуль подключён, а строки `cucumber.plugin` нет, сценарии
отправляет адаптер раннера, как без `doqa-cucumber`.

---

## Ограничения

- **Подключение только строкой `cucumber.plugin`.** У Cucumber нет механизма автоподключения
  плагинов.
- **Переход на `doqa-cucumber` начинает историю заново.** Адаптеры раннеров отправляли сценарии с
  другими идентификаторами (`junit5:…`, `testng:…`). Сохранить историю можно только у сценариев с
  `[DOQA-<n>]` в имени.
- **Нет фикстур уровня класса.** `@BeforeAll` / `@AfterAll` Cucumber выполняются на уровне прогона и
  в результаты не попадают.
- **Нет отбора до запуска на JUnit 4 и TestNG** и порядка по плану (см. [режим 0](#выборочный-прогон-режим-0)).
- **Имена хуков** (`@Before(name = …)` в Cucumber 8) через API плагинов недоступны: шаг хука
  называется `Класс.метод`.
- **DocString и DataTable одновременно** (Cucumber 8): API плагинов отдаёт только один аргумент
  шага.
- **Имя существующего автотеста** в каталоге DoQA не обновляется, если в имени сценария есть `.`,
  `#` или `::` (например, «Перевод 1.5 EUR»): сервер обновляет имя, только когда его последний
  сегмент совпадает с `runner_method`.

---

## Устранение неполадок

| Симптом | Причина и решение |
|---|---|
| Сценарии не приходят, в логе WARNING «Cucumber scenarios are running without the DoQA plugin» | не добавлена строка `cucumber.plugin`. На JUnit Platform проверьте `@ConfigurationParameter` на классе `@Suite`: он перекрывает `junit-platform.properties` |
| Сценарии не приходят, в логе ничего нет (JUnit 4, TestNG) | плагин не указан в `@CucumberOptions(plugin = …)`, `CUCUMBER_PLUGIN` или `-Dcucumber.plugin` |
| Результаты в `results/`, а ожидались в DoQA | не заданы `url`, `token`, `spaceId` (в логе WARNING «no reporting configuration found») или DoQA был недоступен на старте («could not establish the test run») |
| У строк Outline разные автотесты | в `@doqa.id:` есть `{колонка}`, так задумано. Уберите подстановку, чтобы строки стали результатами одного автотеста |
| WARNING о дубле `externalId` | два сценария с одним именем в одной фиче или `@doqa.id:` на Feature. Задайте сценариям разные имена или теги |
| В режиме 0 на JUnit 4 или TestNG выполняются все сценарии | так устроены эти раннеры: передайте нативный фильтр (`-Dcucumber.filter.name`) |
| Выборочный прогон ничего не выполнил, в логе WARNING со списком `externalId` | идентификаторы в DoQA и в фичах разошлись (переименование сценария или файла); отправьте результаты полного прогона |

---

## Сборка из исходников

Модуль находится в монорепозитории `doqa-java`. По умолчанию он собирается и тестируется с
Cucumber 7.0.0; профили `cucumber-7-latest` и `cucumber-8` запускают тесты на Cucumber 7.34 и 8.0
(для 8.0 нужен JDK 17):

```bash
mvn -pl doqa-cucumber -am clean verify
mvn -pl doqa-cucumber -am clean verify -Pcucumber-8
```

## Лицензия

[Apache License 2.0](../LICENSE).
