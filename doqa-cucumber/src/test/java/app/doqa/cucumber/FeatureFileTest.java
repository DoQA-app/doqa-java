package app.doqa.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureFileTest {

    private static final String TEXT = String.join("\n",
            "# language: ru",
            "@f1 @f2 # trailing comment",
            "",
            "Функция: Перевод: со счёта",
            "  Описание фичи",
            "",
            "  @r1",
            "  Правило: Лимиты",
            "",
            "    @s1",
            "    # comment between tags",
            "    @s2 @doqa.id:x",
            "    Структура сценария: Вход <user>",
            "      Дано пользователь <user>",
            "",
            "      @e1",
            "      Примеры: первые",
            "        Строки перед таблицей",
            "        | user  | note      |",
            "        # comment in the table",
            "",
            "        |  a\\|b | x\\\\y\\nz |",
            "        | c | \\q |");

    private final FeatureFile file = FeatureFile.of("﻿" + TEXT);

    @Test
    void namesAreTheTextAfterTheFirstColonInAnyLanguage() {
        assertEquals(4, file.featureLine());
        assertEquals("Перевод: со счёта", file.nameAt(4));
        assertEquals("Лимиты", file.nameAt(8));
        assertEquals("Вход <user>", file.nameAt(13));
        assertEquals("первые", file.nameAt(17));
        assertEquals("", file.nameAt(99));
    }

    @Test
    void tagsAreTheTagLinesRightAboveTheElement() {
        assertEquals(Arrays.asList("f1", "f2"), file.tagsAbove(4));
        assertEquals(List.of("r1"), file.tagsAbove(8));
        assertEquals(Arrays.asList("s1", "s2", "doqa.id:x"), file.tagsAbove(13));
        assertEquals(List.of("e1"), file.tagsAbove(17));
        assertEquals(List.of(), file.tagsAbove(14));
    }

    @Test
    void examplesTableSkipsCommentsAndBlankLinesAndUnescapes() {
        assertEquals(Arrays.asList("user", "note"), file.examplesHeader(17));
        assertEquals(Arrays.asList("a|b", "x\\y\nz"), file.rowCells(22));
        assertEquals(Arrays.asList("c", "\\q"), file.rowCells(23));
        assertEquals(List.of(), file.rowCells(20));
    }

    @Test
    void stepTemplateIsTheTextAfterTheKeyword() {
        assertEquals("пользователь <user>", file.stepText(14, "Дано "));
        assertEquals(null, file.stepText(14, "Когда"));
    }

    @Test
    void crlfLineEndingsKeepLineNumbers() {
        FeatureFile crlf = FeatureFile.of(TEXT.replace("\n", "\r\n"));
        assertEquals("Вход <user>", crlf.nameAt(13));
        assertEquals(Arrays.asList("a|b", "x\\y\nz"), crlf.rowCells(22));
    }
}
