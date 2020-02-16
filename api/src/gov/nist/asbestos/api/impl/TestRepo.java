package gov.nist.asbestos.api.impl;

import gov.nist.asbestos.api.Channel;
import gov.nist.asbestos.api.TestResult;
import gov.nist.asbestos.client.Base.ProxyBase;
import gov.nist.asbestos.client.client.Format;
import gov.nist.asbestos.http.operations.HttpGet;
import org.hl7.fhir.r4.model.TestReport;
import org.hl7.fhir.r4.model.TestScript;

import java.net.URISyntaxException;

public class TestRepo {

    public static TestResult runServerTest(Channel channel, String testCollectionName, String testName) throws URISyntaxException {

        // get TestScript
        HttpGet getter = new HttpGet();
        getter.getJson(ApiConfig.getAsbestosBase() + "/engine/collection/" + testCollectionName + "/" + testName);
        if (getter.getStatus() != 200)
            return null;

        TestResultImpl testResult = new TestResultImpl();
        testResult.setChannel(channel);
        testResult.setTestCollection(testCollectionName);
        testResult.setTestName(testName);

        String testScriptJson =  getter.getResponseText();
        TestScript testScript = (TestScript) ProxyBase.parse(testScriptJson, Format.JSON);
        testResult.setTestScript(testScript);

        // run test - returns TestReport
        getter = new HttpGet();
        getter.getJson(ApiConfig.getAsbestosBase()
                + "/engine/testrun/"
                + channel.getTestSession() + "__" + channel.getChannelId() +
                "/" + testCollectionName +
                "/" + testName);
        if (getter.getStatus() !=200)
            return null;

        String testReportJson = getter.getResponseText();
        TestReport testReport = (TestReport) ProxyBase.parse(testReportJson, Format.JSON);
        testResult.setTestReport(testReport);

        return testResult;
    }
}
