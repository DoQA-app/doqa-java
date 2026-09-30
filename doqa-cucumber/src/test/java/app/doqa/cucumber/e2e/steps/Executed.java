package app.doqa.cucumber.e2e.steps;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Executed {

    public static final List<String> LOG = new CopyOnWriteArrayList<>();

    private Executed() {
    }

    static void add(String entry) {
        LOG.add(entry);
    }
}
