package app.doqa.testkit;

import app.doqa.client.ApiClient;
import app.doqa.client.DoqaConfig;
import app.doqa.client.Transport;
import app.doqa.core.DoqaSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process DoQA Autotest API for adapter tests, plugged in as the session's {@link Transport}. */
public final class FakeBackend implements Transport {

    public final List<String> upserts = new CopyOnWriteArrayList<>();
    public final List<String> results = new CopyOnWriteArrayList<>();

    @Override
    public Response send(Request request) {
        String url = request.url;
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (path.endsWith("/test-runs")) {
            return new Response(200, "{\"runId\":4242}");
        }
        if (path.endsWith("/upsert")) {
            upserts.add(request.jsonBody);
            return new Response(200, "{\"map\":{}}");
        }
        if (path.endsWith("/attachments")) {
            return new Response(200, "{\"mediaFileId\":555}");
        }
        if (path.endsWith("/results")) {
            results.add(request.jsonBody);
            return new Response(200, "{\"accepted\":1,\"elementIds\":[]}");
        }
        return new Response(200, "{}");
    }

    public FakeBackend install(Path resultsDir) {
        install(new DoqaConfig.Builder()
                .url("https://doqa.test").token("TOKEN").spaceId("31")
                .reporting(DoqaConfig.REPORTING_API).resultsDir(resultsDir.toString())
                .build());
        return this;
    }

    public FakeBackend install(DoqaConfig config) {
        DoqaSession.reset();
        DoqaSession.setEnvOverride(Map.of());
        DoqaSession.setConfigOverride(config);
        DoqaSession.setClientFactory(c -> new ApiClient(c, this, 1, 0));
        return this;
    }

    public static void installFiles(Path dir) {
        DoqaSession.reset();
        DoqaSession.setEnvOverride(Map.of());
        DoqaSession.setClientFactory(null);
        DoqaSession.setConfigOverride(new DoqaConfig.Builder()
                .reporting(DoqaConfig.REPORTING_FILES).resultsDir(dir.toString()).build());
    }

    public static void uninstall() {
        DoqaSession.reset();
        DoqaSession.setClientFactory(null);
        DoqaSession.setConfigOverride(null);
        DoqaSession.setEnvOverride(null);
    }
}
