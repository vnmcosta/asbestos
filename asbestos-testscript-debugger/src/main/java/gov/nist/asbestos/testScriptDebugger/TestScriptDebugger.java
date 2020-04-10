package gov.nist.asbestos.testScriptDebugger;

import gov.nist.asbestos.asbestosProxy.requests.Request;
import gov.nist.asbestos.client.client.FhirClient;
import gov.nist.asbestos.client.client.Format;
import gov.nist.asbestos.client.log.SimStore;
import gov.nist.asbestos.serviceproperties.ServiceProperties;
import gov.nist.asbestos.serviceproperties.ServicePropertiesEnum;
import gov.nist.asbestos.sharedObjects.ChannelConfig;
import gov.nist.asbestos.sharedObjects.TestScriptDebugState;
import gov.nist.asbestos.simapi.simCommon.SimId;
import gov.nist.asbestos.simapi.validation.Val;
import gov.nist.asbestos.testEngine.engine.ModularEngine;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.hl7.fhir.r4.model.TestReport;

import javax.websocket.Session;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
// 0 - "debug-testscript"
// 1 - channelName (testSession__channelId)
// 2 - testCollectionId
// 3 - testId
// Returns modular test reports
//   JSON object : test/moduleId => TestReport

public class TestScriptDebugger implements Callable<String> {
    private static Logger log = Logger.getLogger(TestScriptDebugger.class);

    private Request request;
    private TestScriptDebugState state;

    public static boolean isRequest(Request request) {
        return request.uriParts.size() == 4 && request.uriParts.get(0).equals("debug-testscript");
    }

    public TestScriptDebugger(Request request, Session session) {
        this.request = request;
        this.state = new TestScriptDebugState(new AtomicBoolean(), new ConcurrentSkipListSet(), session);
    }

    public TestScriptDebugState getState() {
        return state;
    }

    @Override
    public String call() throws Exception {
        log.info("Run DebugTestScriptRequest");
        String channelId = request.uriParts.get(1);
        String testCollection = request.uriParts.get(2);
        String testName = request.uriParts.get(3);

        ChannelConfig channelConfig = null;
        try {

            SimId simId = SimId.buildFromRawId(channelId);
            SimStore simStore = new SimStore(request.externalCache, simId);
            if (simStore.exists()) {
                simStore.open();
                channelConfig = simStore.getChannelConfig();
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        if (channelConfig == null) {
            throw new RuntimeException("channelConfig is null for " + channelId);
        }

        String testSession = channelConfig.getTestSession();
        String proxyStr = null;
        ServicePropertiesEnum key = ServicePropertiesEnum.FHIR_TOOLKIT_BASE;
        proxyStr = ServiceProperties.getInstance().getPropertyOrStop(key);
        proxyStr += "/proxy/" + channelId;
        URI proxy = null;
        try {
            proxy = new URI(proxyStr);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        File testDir = request.ec.getTest(testCollection, testName);

        File patientCacheDir = request.ec.getTestLogCacheDir(channelId);
        File alternatePatientCacheDir = request.ec.getTestLogCacheDir("default__default");
        patientCacheDir.mkdirs();
        alternatePatientCacheDir.mkdirs();

        FhirClient fhirClient = new FhirClient()
                .setFormat(request.isJson ? Format.JSON : Format.XML)
                .sendGzip(request.isGzip)
                .requestGzip(request.isGzip);
        TestReport report;
        ModularEngine modularEngine;
        try {
            modularEngine = new ModularEngine(testDir, proxy, state).setSaveLogs(true);
            report = modularEngine
                    //.getLastTestEngine()
                    .setTestSession(testSession)
                    .setChannelId(channelId)
                    .setExternalCache(request.externalCache)
                    .setVal(new Val())
                    .setFhirClient(fhirClient)
                    .setTestCollection(testCollection)
                    .addCache(patientCacheDir)
                    .addCache(alternatePatientCacheDir)
                    .runTest()
                    .getTestReport();

        } catch (Throwable t) {
            log.error(ExceptionUtils.getStackTrace(t));
            throw t;
        }

        String json = modularEngine.reportsAsJson();
        return json;
    }
}
