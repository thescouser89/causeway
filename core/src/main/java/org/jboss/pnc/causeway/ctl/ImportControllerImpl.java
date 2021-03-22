package org.jboss.pnc.causeway.ctl;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.UniformReservoir;
import com.redhat.red.build.koji.model.json.KojiImport;
import lombok.Data;

import org.jboss.pnc.api.causeway.dto.push.Build;
import org.jboss.pnc.api.causeway.dto.push.BuiltArtifact;
import org.jboss.pnc.api.causeway.dto.push.Logfile;
import org.jboss.pnc.api.causeway.dto.untag.TaggedBuild;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.causeway.CausewayException;
import org.jboss.pnc.causeway.CausewayFailure;
import org.jboss.pnc.causeway.brewclient.BrewClient;
import org.jboss.pnc.causeway.brewclient.BuildTranslator;
import org.jboss.pnc.causeway.brewclient.ImportFileGenerator;
import org.jboss.pnc.causeway.config.CausewayConfig;
import org.jboss.pnc.causeway.source.RenamedSources;
import org.jboss.pnc.pncmetrics.MetricsConfiguration;
import org.jboss.pnc.causeway.rest.BrewBuild;
import org.jboss.pnc.causeway.rest.BrewNVR;
import org.jboss.pnc.causeway.rest.model.response.OperationStatus;
import org.jboss.pnc.causeway.rest.model.response.UntagResultRest;
import org.jboss.pnc.causeway.rest.model.response.UntagResultRest.UntagResultRestBuilder;
import org.jboss.pnc.dto.BuildPushResult;
import org.jboss.pnc.enums.BuildPushStatus;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;

import javax.ejb.Asynchronous;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.PrintWriter;
import java.io.StringWriter;
import lombok.extern.slf4j.Slf4j;

import static org.jboss.pnc.causeway.ctl.PncImportControllerImpl.messageMissingTag;
import org.jboss.pnc.constants.MDCKeys;
import org.slf4j.MDC;

/**
 *
 * @author Honza Brázdil &lt;jbrazdil@redhat.com&gt;
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
@Slf4j
public class ImportControllerImpl implements ImportController {

    private static final String METRICS_IMPORT_BASE = "causeway.import.build";
    private static final String METRICS_UNTAG_BASE = "causeway.untag.build";
    private static final String METRICS_TIMER = ".timer";
    private static final String METRICS_METER = ".meter";
    private static final String METRICS_ERRORS = ".errors";

    private static final String METRICS_PUSHED_FILE_TO_BREW_KEY = "pushed-file-to-brew";
    public static final String METRICS_LOGS_NUMBER_KEY = METRICS_PUSHED_FILE_TO_BREW_KEY + ".logs.number";
    public static final String METRICS_LOGS_SIZE_KEY = METRICS_PUSHED_FILE_TO_BREW_KEY + ".logs.size";
    public static final String METRICS_ARTIFACTS_NUMBER_KEY = METRICS_PUSHED_FILE_TO_BREW_KEY + ".artifacts.number";
    public static final String METRICS_ARTIFACTS_SIZE_KEY = METRICS_PUSHED_FILE_TO_BREW_KEY + ".artifacts.size";

    private static final String BUILD_NOT_TAGGED = " but not previously tagged. Tagged now.";
    private static final String BUILD_ALREADY_IMPORTED = "Build was already imported with id ";

    @Inject
    private BrewClient brewClient;
    @Inject
    private BuildTranslator translator;
    @Inject
    private CausewayConfig config;
    private ResteasyClient restClient;

    @Inject
    private MetricsConfiguration metricsConfiguration;

    @Inject
    public ImportControllerImpl() {
        restClient = new ResteasyClientBuilder().connectionPoolSize(4).build();
    }

    @Override
    @Asynchronous
    public void importBuild(Build build, Request callback, String username, boolean reimport) {
        MDC.put(MDCKeys.BUILD_ID_KEY, String.valueOf(build.getExternalBuildID()));
        log.info("Importing external build {} to tag {}.", build.getExternalBuildID(), build.getTagPrefix());

        MetricRegistry registry = metricsConfiguration.getMetricRegistry();
        Meter meter = registry.meter(METRICS_IMPORT_BASE + METRICS_METER);
        meter.mark();

        Timer timer = registry.timer(METRICS_IMPORT_BASE + METRICS_TIMER);
        Timer.Context context = timer.time();

        Meter errors = registry.meter(METRICS_IMPORT_BASE + METRICS_ERRORS);
        BuildPushResult.Builder response = BuildPushResult.builder();
        response.buildId(String.valueOf(build.getExternalBuildID()));
        try {
            BuildResult result = importBuild(build, build.getTagPrefix(), username, reimport);
            response.brewBuildId(result.getBrewID());
            response.brewBuildUrl(result.getBrewURL());
            response.status(BuildPushStatus.SUCCESS);
            response.message(result.getMessage());
        } catch (CausewayFailure ex) {
            log.error("Failed to import build. " + ex.getMessage(), ex);
            response.status(BuildPushStatus.FAILED);
            response.message(getMessageOrStacktrace(ex));
            errors.mark();
        } catch (CausewayException | RuntimeException ex) {
            log.error("Error while importing build. " + ex.getMessage(), ex);
            response.status(BuildPushStatus.SYSTEM_ERROR);
            response.message(getMessageOrStacktrace(ex));
            errors.mark();
        }
        respond(callback, response.build());

        // stop the timer
        context.stop();
    }

    @Override
    @Asynchronous
    public void untagBuild(TaggedBuild build, Request callback) {
        log.info("Untaging build {} from tag {}.", build.getBrewBuildId(), build.getTagPrefix());

        MetricRegistry registry = metricsConfiguration.getMetricRegistry();
        Meter meter = registry.meter(METRICS_UNTAG_BASE + METRICS_METER);
        meter.mark();

        Timer timer = registry.timer(METRICS_UNTAG_BASE + METRICS_TIMER);
        Timer.Context context = timer.time();

        Meter errors = registry.meter(METRICS_UNTAG_BASE + METRICS_ERRORS);

        UntagResultRestBuilder response = UntagResultRest.builder();
        response.brewBuildId(build.getBrewBuildId());
        try {
            untagBuild(build.getBrewBuildId(), build.getTagPrefix());
            response.log("Brew build " + build.getBrewBuildId() + " untaged from tag " + build.getTagPrefix());
            response.status(OperationStatus.SUCCESS);
        } catch (CausewayFailure ex) {
            log.error("Failed to untag build.", ex);
            response.status(OperationStatus.FAILED);
            response.log(getMessageOrStacktrace(ex));
            errors.mark();
        } catch (CausewayException | RuntimeException ex) {
            log.error("Error while untaging build.", ex);
            response.status(OperationStatus.SYSTEM_ERROR);
            response.log(getMessageOrStacktrace(ex));
            errors.mark();
        }
        respond(callback, response.build());

        // stop the timer
        context.stop();
    }

    private BuildResult importBuild(Build build, String tagPrefix, String username, boolean reimport)
            throws CausewayException {
        if (build.getBuiltArtifacts().isEmpty()) {
            throw new CausewayFailure("Build doesn't contain any artifacts");
        }
        if (!brewClient.tagsExists(tagPrefix)) {
            throw new CausewayFailure(messageMissingTag(tagPrefix, config.getKojiURL()));
        }

        BrewNVR nvr = getNVR(build);
        boolean buildImported = false;

        BrewBuild brewBuild = brewClient.findBrewBuildOfNVR(nvr);
        String message;
        if (brewBuild == null) {
            brewBuild = translateAndImport(nvr, build, username);
            buildImported = true;
            message = "Build imported with id " + brewBuild.getId() + ".";
        } else {
            if (reimport) {
                int revision = 1;
                while (brewBuild != null && brewClient.isBuildTagged(tagPrefix, brewBuild)) {
                    nvr = getNVR(build, ++revision);
                    brewBuild = brewClient.findBrewBuildOfNVR(nvr);
                }
                if (brewBuild == null) {
                    brewBuild = translateAndImport(nvr, build, username);
                    message = "Build was previously imported. Reimported again with revision " + revision
                            + " and with id " + brewBuild.getId() + ".";
                    buildImported = true;
                } else {
                    message = BUILD_ALREADY_IMPORTED + brewBuild.getId() + BUILD_NOT_TAGGED;
                }
            } else {
                message = BUILD_ALREADY_IMPORTED + brewBuild.getId();
                if (!brewClient.isBuildTagged(tagPrefix, brewBuild)) {
                    message += BUILD_NOT_TAGGED;
                }
            }
        }

        if (buildImported) {
            long artifactSize = build.getBuiltArtifacts().stream().mapToLong(BuiltArtifact::getSize).sum();
            int artifactNumber = build.getBuiltArtifacts().size();
            long logSize = build.getLogs().stream().mapToLong(Logfile::getSize).sum();
            int logNumber = build.getLogs().size();

            updateHistogram(metricsConfiguration, METRICS_ARTIFACTS_SIZE_KEY, artifactSize);
            updateHistogram(metricsConfiguration, METRICS_ARTIFACTS_NUMBER_KEY, artifactNumber);
            updateHistogram(metricsConfiguration, METRICS_LOGS_SIZE_KEY, logSize);
            updateHistogram(metricsConfiguration, METRICS_LOGS_NUMBER_KEY, logNumber);
        }

        brewClient.tagBuild(tagPrefix, brewBuild);

        return new BuildResult(brewBuild.getId(), brewClient.getBuildUrl(brewBuild.getId()), message);
    }

    private BrewBuild translateAndImport(BrewNVR nvr, Build build, String username) throws CausewayException {
        RenamedSources sources = translator.getSources(build);
        KojiImport kojiImport = translator.translate(nvr, build, sources, username);
        ImportFileGenerator importFiles = translator.getImportFiles(build, sources);
        return brewClient.importBuild(nvr, kojiImport, importFiles);
    }

    private void updateHistogram(MetricsConfiguration metricsConfiguration, String name, long value) {
        Histogram histogram = null;
        if (metricsConfiguration != null) {
            MetricRegistry registry = metricsConfiguration.getMetricRegistry();
            try {
                histogram = registry.register(name, new Histogram(new UniformReservoir()));
            } catch (IllegalArgumentException e) {
                histogram = registry.histogram(name);
            }
        }
        if (histogram != null) {
            histogram.update(value);
        }
    }

    BrewNVR getNVR(Build build) throws CausewayException {
        String buildVersion = build.getBuildVersion();
        if (buildVersion == null) {
            buildVersion = BuildTranslator.guessVersion(build);
        }

        return new BrewNVR(build.getBuildName(), buildVersion, "1");
    }

    BrewNVR getNVR(Build build, int revision) throws CausewayException {
        if (revision <= 0)
            throw new IllegalArgumentException("Revison must be positive, is " + revision);
        String buildVersion = build.getBuildVersion();
        if (buildVersion == null) {
            buildVersion = BuildTranslator.guessVersion(build);
        }

        return new BrewNVR(build.getBuildName(), buildVersion, Integer.toString(revision));
    }

    private <T> void respond(Request callback, T responseEntity) {
        if (callback == null) {
            log.info("Not sending callback.");
            return;
        }
        log.info("Sending callback to {}.", callback.getUri());
        ResteasyWebTarget target = restClient.target(callback.getUri());
        Invocation.Builder request = target.request(MediaType.APPLICATION_JSON);
        callback.getHeaders().forEach(h -> request.header(h.getName(), h.getValue()));
        Response response = request.post(Entity.entity(responseEntity, MediaType.APPLICATION_JSON_TYPE));
        log.debug("Callback response: {} - {}.", response.getStatusInfo(), response.readEntity(String.class));
    }

    private void untagBuild(int brewBuildId, String tagPrefix) throws CausewayException {
        BrewBuild build = brewClient.findBrewBuild(brewBuildId);
        if (build == null) {
            throw new CausewayFailure("Build with given id (" + brewBuildId + ") not found");
        }
        brewClient.untagBuild(tagPrefix, build);
    }

    /**
     * If the exception.getMessage() is null, return the stacktrace instead If exception.getMessage() is not-null, just
     * return the message
     *
     * @param e exception
     * @return reason for exception
     */
    private String getMessageOrStacktrace(Exception e) {

        // get the message
        String message = e.getMessage();

        // it can be null for NullPointerException for example!
        if (message != null) {
            return message;
        }

        // if message is null, return the stacktrace instead
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));

        return sw.toString();
    }

    @Data
    public static class BuildResult {

        private final int brewID;
        private final String brewURL;
        private final String message;
    }
}
