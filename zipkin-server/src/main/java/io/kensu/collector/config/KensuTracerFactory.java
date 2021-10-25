package io.kensu.collector.config;


import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class KensuTracerFactory {

    public static void prepareTracer() {
        Properties properties = null;
        try {
            properties = getProperties();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        // Configure Kensu Tracer
        System.setProperty("DAM_OFFLINE", properties.getProperty("kensu.collector.api.offline.switch"));
        System.setProperty("DAM_OFFLINE_FILE_NAME", properties.getProperty("kensu.collector.api.offline.file"));
        System.setProperty("DAM_INGESTION_URL", properties.getProperty("kensu.collector.api.url"));
        System.setProperty("DAM_AUTH_TOKEN", properties.getProperty("kensu.collector.api.token"));
        System.setProperty("DAM_USER_NAME", properties.getProperty("kensu.collector.run.user", System.getenv("USER")));
        System.setProperty("DAM_RUN_ENVIRONMENT", properties.getProperty("kensu.collector.run.env"));
        System.setProperty("DAM_PROJECTS", properties.getProperty("kensu.collector.run.projects",""));
        // FIXME: need different special handling here...
        System.setProperty("DAM_PROCESS_NAME", properties.getProperty("app.artifactId", "unknown-default-process"));
        System.setProperty("DAM_CODEBASE_LOCATION", properties.getProperty("git.remote.origin.url", "unknown-git-repo" + System.currentTimeMillis()));
        System.setProperty("DAM_CODE_VERSION", properties.getProperty("app.version")+"_"+properties.getProperty("git.commit.id.describe-short", "unknown-code-version" + System.currentTimeMillis()));


    }
    private static Properties getProperties() throws IOException {

        Properties properties = new Properties();

        // FIXME: should be different!!!
        Properties kensuTracerProperties = new Properties();
        try (InputStream inputStream = KensuTracerFactory.class.getResourceAsStream("/kensu-tracer.properties")) {
            if (inputStream == null)
                throw new IOException("Can't locate kensu-tracer.properties file to generate app info");

            kensuTracerProperties.load(inputStream);
        }

        properties.putAll(kensuTracerProperties);
        return properties;
    }
}
