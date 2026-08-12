package app.doqa.testng.fixtures;

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
 * method scope), a parameterized method whose id is a {@code {param}} template, and a plain method
 * for the signature-hash fallback path. It declares no {@code @Test} method on purpose: no TestNG
 * run is ever built for it (the contract test reflects over these members directly).
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

    /** The TestNG shape: argument values are known, so a templated id resolves per invocation. */
    @DoqaId("DOQA-{browser}")
    @DoqaTitle("Login in {browser}")
    public void loginInBrowser(String browser) {
    }

    public void fallbackTest() {
    }
}
