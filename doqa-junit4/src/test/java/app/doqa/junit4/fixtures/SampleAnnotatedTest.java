package app.doqa.junit4.fixtures;

import app.doqa.annotations.DoqaCaseIds;
import app.doqa.annotations.DoqaClassName;
import app.doqa.annotations.DoqaDescription;
import app.doqa.annotations.DoqaDisplayName;
import app.doqa.annotations.DoqaId;
import app.doqa.annotations.DoqaLabels;
import app.doqa.annotations.DoqaLink;
import app.doqa.annotations.DoqaLinks;
import app.doqa.annotations.DoqaTags;
import app.doqa.annotations.DoqaTitle;

/**
 * Static contract fixture - a class carrying the full range of {@code @Doqa*} annotations (class +
 * method scope) plus a plain method for the signature-hash fallback path. It declares no
 * {@code @Test} method on purpose: no runner is ever built for it (the contract test reflects over
 * these members directly), and surefire's JUnit 4 detection skips a class without test methods.
 */
@DoqaLabels({"regression"})
@DoqaClassName("SampleAnnotatedTest")
public class SampleAnnotatedTest {

    @DoqaId("DOQA-42")
    @DoqaDisplayName("login happy path")
    @DoqaTitle("Login works")
    @DoqaDescription("verifies the happy login path")
    @DoqaLabels({"smoke"})
    @DoqaTags({"ui"})
    @DoqaLinks({@DoqaLink(url = "http://bug/1", type = "defect", title = "bug")})
    @DoqaCaseIds({101, 102})
    public void loginWorks() {
    }

    public void fallbackTest() {
    }
}
