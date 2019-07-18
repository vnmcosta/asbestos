package gov.nist.asbestos.proxyWar;

import gov.nist.asbestos.client.client.FhirClient;
import gov.nist.asbestos.http.operations.HttpDelete;
import gov.nist.asbestos.http.operations.HttpPost;
import gov.nist.asbestos.sharedObjects.ChannelConfig;
import gov.nist.asbestos.sharedObjects.ChannelConfigFactory;
import gov.nist.asbestos.simapi.validation.Val;
import gov.nist.asbestos.testEngine.TestEngine;
import org.hl7.fhir.r4.model.TestReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class ToProxyIT {
    private static String testSession = "default";
    private static String channelId = "fhirpass";
    private static String fhirPort = ITConfig.getFhirPort();
    private static String proxyPort = ITConfig.getProxyPort();
    private static URI base;

    @Test
    void createPatient() throws URISyntaxException {
        run("/toProxy/createPatient/TestScript.xml");
    }

    @Test
    void patientWithAutoCreate() throws URISyntaxException {
        run("/toProxy/createPatientWithAutoCreate/TestScript.xml");
    }

    @Test
    void patientWithAutoCreateDelete() throws URISyntaxException {
        run("/toProxy/createPatientWithAutoCreateDelete/TestScript.xml");
    }

    void run(String testScriptLocation) throws URISyntaxException {
        Val val = new Val();
        File test1 = Paths.get(getClass().getResource(testScriptLocation).toURI()).getParent().toFile();
        TestEngine testEngine = new TestEngine(test1, base)
                .setVal(val)
                .setFhirClient(new FhirClient())
                .run();
        System.out.println(testEngine.getTestReportAsJson());
        TestReport report = testEngine.getTestReport();
        TestReport.TestReportResult result = report.getResult();
        assertEquals(TestReport.TestReportResult.PASS, result);
    }


    @BeforeAll
    static void beforeAll() throws IOException, URISyntaxException {
        // delete channel
        new HttpDelete().run("http://localhost:" + proxyPort + "/proxy/prox/default__fhirpass");
        // create channel
        base = new URI(createChannel());
    }

    private static String createChannel() throws URISyntaxException, IOException {
        ChannelConfig channelConfig = new ChannelConfig()
                .setTestSession(testSession)
                .setChannelId(channelId)
                .setEnvironment("default")
                .setActorType("fhir")
                .setChannelType("passthrough")
                .setFhirBase("http://localhost:" + fhirPort + "/fhir/fhir");
        String json = ChannelConfigFactory.convert(channelConfig);
        HttpPost poster = new HttpPost();
        poster.postJson(new URI("http://localhost:" + proxyPort + "/proxy/prox"), json);
        int status = poster.getStatus();
        if (!(status == 200 || status == 201))
            fail("200 or 201 required - returned " + status);
        //return "http://localhost:8080/fhir/fhir";
        return "http://localhost:" + proxyPort + "/proxy/prox/" + testSession + "__" + channelId + "/Channel";
    }

}
