package gov.nist.asbestos.testEngine;

import ca.uhn.fhir.parser.IParser;
import gov.nist.asbestos.client.Base.ProxyBase;
import gov.nist.asbestos.client.client.FhirClient;
import gov.nist.asbestos.client.resolver.Ref;
import gov.nist.asbestos.client.resolver.ResourceCacheMgr;
import gov.nist.asbestos.client.resolver.ResourceWrapper;
import gov.nist.asbestos.simapi.validation.Val;
import gov.nist.asbestos.simapi.validation.ValE;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.TestReport;
import org.hl7.fhir.r4.model.TestScript;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

/**
 * See http://hl7.org/fhir/testing.html
 */
public class TestEngine  {
    private File testDef = null;
    private URI sut = null;
    private TestScript testScript = null;
    private Map<String, FixtureComponent> fixtures = new HashMap<>();
    private Val val;
    private ValE engineVal;
    private FhirClient fhirClientForFixtures;
    private TestReport testReport = new TestReport();
    private List<String> errors;
    private FhirClient fhirClient = null;

    /**
     *
     * @param testDef  directory containing test definition
     * @param sut base address of fhir server under test
     */
    public TestEngine(File testDef, URI sut) {
        Objects.requireNonNull(testDef);
        Objects.requireNonNull(sut);
        this.testDef = testDef;
        this.sut = sut;
        ResourceCacheMgr inTestResources = new ResourceCacheMgr(testDef, new Ref(""));
        fhirClientForFixtures = new FhirClient().setResourceCacheMgr(inTestResources);
    }

    public TestEngine run() {
        Objects.requireNonNull(val);
        engineVal = new ValE(val);
        engineVal.setMsg("TestEngine");
        doWorkflow();
        return this;
    }

    private void doWorkflow() {
        testScript = loadTestScript();
        testReport.setName(testScript.getName());
        testReport.setTestScript(new Reference(testScript.getId()));
        doPreProcessing();
        doLoadFixtures();
        doAutoCreates();
        doSetup();
        doTest();
        doTearDown();
        doPostProcessing();
        errors = doReportResult();
    }

    private List<String> doReportResult() {
        List<String> failingComponents = new ArrayList<>();

        TestReport.TestReportSetupComponent setup = testReport.getSetup();
        for (TestReport.SetupActionComponent action : setup.getAction()) {
            if (action.hasOperation()) {
                TestReport.SetupActionOperationComponent op = action.getOperation();
                TestReport.TestReportActionResult result = op.getResult();
                if (result == TestReport.TestReportActionResult.FAIL || result == TestReport.TestReportActionResult.ERROR)
                    failingComponents.add(op.getMessage());
            }
            if (action.hasAssert()) {
                TestReport.SetupActionAssertComponent as = action.getAssert();
                TestReport.TestReportActionResult result2 = as.getResult();
                if (result2 == TestReport.TestReportActionResult.FAIL || result2 == TestReport.TestReportActionResult.ERROR)
                    failingComponents.add(as.getMessage());
            }
        }
        for (TestReport.TestReportTestComponent test : testReport.getTest()) {
            for (TestReport.TestActionComponent action : test.getAction()) {
                if (action.hasOperation()) {
                    TestReport.SetupActionOperationComponent op = action.getOperation();
                    TestReport.TestReportActionResult result = op.getResult();
                    if (result == TestReport.TestReportActionResult.FAIL || result == TestReport.TestReportActionResult.ERROR)
                        failingComponents.add(op.getMessage());
                }
                if (action.hasAssert()) {
                    TestReport.SetupActionAssertComponent as = action.getAssert();
                    TestReport.TestReportActionResult result2 = as.getResult();
                    if (result2 == TestReport.TestReportActionResult.FAIL || result2 == TestReport.TestReportActionResult.ERROR)
                        failingComponents.add(as.getMessage());
                }
            }
        }
        TestReport.TestReportTeardownComponent teardown = testReport.getTeardown();
        for (TestReport.TeardownActionComponent action : teardown.getAction()) {
            if (action.hasOperation()) {
                TestReport.SetupActionOperationComponent op = action.getOperation();
                TestReport.TestReportActionResult result = op.getResult();
                if (result == TestReport.TestReportActionResult.FAIL || result == TestReport.TestReportActionResult.ERROR)
                    failingComponents.add(op.getMessage());
            }
        }

        testReport.setResult(
                failingComponents.isEmpty()
                        ? TestReport.TestReportResult.PASS
                        : TestReport.TestReportResult.FAIL
        );
        return failingComponents;
    }

    private void doPreProcessing() {

    }


    private void doLoadFixtures() {

        if (testScript.hasFixture()) {
            ValE fVal = new ValE(engineVal).setMsg("Fixtures");

            for (TestScript.TestScriptFixtureComponent comp : testScript.getFixture()) {
                String id = comp.getId();
                Ref ref = new Ref(comp.getResource().getReference());
                Optional<ResourceWrapper> optWrapper = fhirClientForFixtures.readCachedResource(ref);
                if (!optWrapper.isPresent())
                    throw new Error("Static Fixture " + ref + " cannot be loaded");
                ResourceWrapper wrapper = optWrapper.get();
                FixtureComponent fixtureMgr;
                try {
                    fixtureMgr = new FixtureComponent(id).setResource(wrapper).setVal(fVal).load(wrapper);
                } catch (Throwable e) {
                    throw new Error(e);
                }
                if (id != null)
                    fixtures.put(id, fixtureMgr);
            }
        }
    }

    private void doAutoCreates() {

    }

    private void doSetup() {
        if (testScript.hasSetup()) {
            TestScript.TestScriptSetupComponent comp = testScript.getSetup();
            if (comp.hasAction()) {
                ValE fVal = new ValE(engineVal).setMsg("Setup");
                String lastOp = null;
                for (TestScript.SetupActionComponent action : comp.getAction()) {
                    SetupAction setupAction = new SetupAction(fixtures, action)
                            .setVal(fVal)
                            .setLastOp(lastOp)
                            .setFhirClient(fhirClient)
                            .setTestReport(testReport);
                    setupAction.run();
                    lastOp = setupAction.getLastOp();
                }
            }
        }
    }

    private void doTest() {
        if (testScript.hasTest()) {
            ValE fVal = new ValE(engineVal).setMsg("Tests");

            for (TestScript.TestScriptTestComponent comp : testScript.getTest()) {
                String id = comp.getId();
                if (id == null || id.equals("")) {
                    fVal.add(new ValE("Fixture has no id").asError());
                    return;
                }
                fVal.add(new ValE("Test " + id));


            }

        }

    }

    private void doTearDown() {

    }

    private void doPostProcessing() {

    }

    private TestScript loadTestScript() {
        Objects.requireNonNull(testDef);
        File location = new File(testDef, "TestScript.xml");
        if (!location.exists() || !location.canRead() ) {
            location = new File(testDef, "TestScript.json");
            if (!location.exists() || !location.canRead() ) {
                throw new RuntimeException("Cannot load TestScript (.xml or .json) from " + testDef);
            }
        }
        InputStream is;
        try {
            is = new FileInputStream(location);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        IParser parser = (location.toString().endsWith("xml") ? ProxyBase.getFhirContext().newXmlParser() : ProxyBase.getFhirContext().newJsonParser());
        IBaseResource resource = parser.parseResource(is);
        assert resource instanceof TestScript;
        return (TestScript) resource;
    }

    private boolean isFixtureDefined(String id) {
        return fixtures.containsKey(id);
    }

    private TestEngine addFixture(FixtureComponent fixtureMgr) {
        fixtures.put(fixtureMgr.getId(), fixtureMgr);
        return this;
    }

    Map<String, FixtureComponent> getFixtures() {
        return fixtures;
    }

    private boolean fixturesOk() {
        for (FixtureComponent fixtureMgr : fixtures.values()) {
            if (!fixtureMgr.IsOk())
                return false;
        }
        return true;
    }

    TestEngine setVal(Val val) {
        this.val = val;
        return this;
    }

    public TestReport getTestReport() {
        return testReport;
    }

    public List<String> getErrors() {
        return errors;
    }

    public TestScript getTestScript() {
        return testScript;
    }

    public FhirClient getFhirClient() {
        return fhirClient;
    }

    public TestEngine setFhirClient(FhirClient fhirClient) {
        this.fhirClient = fhirClient;
        return this;
    }
}
