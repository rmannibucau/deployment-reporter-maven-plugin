package com.github.rmannibucau.maven.plugin.deployment.reporter;

import static java.util.Collections.list;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.aether.RepositoryEvent;
import org.slf4j.Logger;

import lombok.Data;

@Component(role = EventSpy.class, hint = "deployment")
public class DeploymentReporterMojo extends AbstractEventSpy {

    @Requirement
    private Logger logger;

    private Jsonb jsonb;

    private Deployments deployments;

    private String output;

    @Override
    public void init(final Context context) {
        output = Properties.class.cast(context.getData().get("systemProperties")).getProperty("deployment-reporter.output",
                Properties.class.cast(context.getData().get("userProperties")).getProperty("deployment-reporter.output"));
    }

    @Override
    public void onEvent(final Object event) throws Exception {
        if (ExecutionEvent.class.isInstance(event)) {
            switch (ExecutionEvent.class.cast(event).getType()) {
            case SessionStarted:
                jsonb = JsonbBuilder.create(new JsonbConfig().withFormatting(true));
                deployments = new Deployments();
                deployments.deployments = new ArrayList<>();
                break;
            case SessionEnded:
                if (!deployments.deployments.isEmpty()) {
                    final String json = jsonb.toJson(deployments);
                    if (output == null) {
                        logger.info("Deployments:\n{}", json);
                    } else {
                        try (final Writer writer = new BufferedWriter(new FileWriter(output))) {
                            writer.write(json);
                        }
                    }
                }
                jsonb.close();
                break;
            default:
                // no-op
            }
        } else if (RepositoryEvent.class.isInstance(event)) {
            final RepositoryEvent e = RepositoryEvent.class.cast(event);
            switch (e.getType()) {
            case ARTIFACT_INSTALLED:
                onDeployment(e.getArtifact().toString(), e.getFile());
                break;
            default:
            }
        } else {
            logger.debug("event: {}", event);
        }
    }

    private void onDeployment(final String artifact, final File file) {
        final String name = file.getName();
        final Deployment deployment = new Deployment();
        deployment.artifact = artifact;
        synchronized (deployments) {
            deployments.deployments.add(deployment);
        }
        if (name.endsWith(".jar")) {
            deployment.content = listJarContent(file);
        } else if (name.endsWith(".pom")) {
            // add the actual content to be able to diff it
            try {
                deployment.content = singletonMap("content",
                        Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream().collect(joining("\n")));
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        } else {
            deployment.content = null; // for now
        }
    }

    private Map<String, String> listJarContent(final File file) {
        try (final JarFile jar = new JarFile(file)) {
            return new TreeMap<>(list(jar.entries()).stream().collect(toMap(ZipEntry::getName, e -> Long.toString(e.getSize()))));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Data
    public static class Deployment {

        private String artifact;

        private Map<String, String> content;
    }

    @Data
    public static class Deployments {

        private Collection<Deployment> deployments;
    }
}
